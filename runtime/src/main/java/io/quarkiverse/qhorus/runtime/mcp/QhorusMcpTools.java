package io.quarkiverse.qhorus.runtime.mcp;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.channel.ChannelService;
import io.quarkiverse.qhorus.runtime.channel.RateLimiter;
import io.quarkiverse.qhorus.runtime.instance.Capability;
import io.quarkiverse.qhorus.runtime.instance.Instance;
import io.quarkiverse.qhorus.runtime.instance.InstanceService;
import io.quarkiverse.qhorus.runtime.ledger.AgentMessageLedgerEntry;
import io.quarkiverse.qhorus.runtime.ledger.LedgerWriteService;
import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.message.MessageService;
import io.quarkiverse.qhorus.runtime.message.MessageType;
import io.quarkiverse.qhorus.runtime.store.MessageStore;
import io.quarkiverse.qhorus.runtime.store.query.MessageQuery;
import io.quarkus.arc.properties.UnlessBuildProperty;

/**
 * All business logic exceptions ({@link IllegalArgumentException} and
 * {@link IllegalStateException}) thrown from any {@code @Tool} method are
 * automatically wrapped in {@link io.quarkiverse.mcp.server.ToolCallException}
 * by the quarkus-mcp-server interceptor, producing an {@code isError: true}
 * tool response with the exception message as text content. This gives Claude
 * readable errors without changing the happy-path return types of the 37
 * structured-return tools. See ADR-0001.
 */
@UnlessBuildProperty(name = "quarkus.qhorus.reactive.enabled", stringValue = "true", enableIfMissing = true)
@WrapBusinessError({ IllegalArgumentException.class, IllegalStateException.class })
@ApplicationScoped
public class QhorusMcpTools extends QhorusMcpToolsBase {

    private static final Logger LOG = Logger.getLogger(QhorusMcpTools.class);

    @Inject
    InstanceService instanceService;

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    @Inject
    io.quarkiverse.qhorus.runtime.data.DataService dataService;

    @Inject
    io.quarkiverse.qhorus.runtime.config.QhorusConfig qhorusConfig;

    @Inject
    RateLimiter rateLimiter;

    @Inject
    io.quarkiverse.qhorus.runtime.instance.ObserverRegistry observerRegistry;

    @Inject
    LedgerWriteService ledgerWriteService;

    @Inject
    io.quarkiverse.qhorus.runtime.ledger.AgentMessageLedgerEntryRepository ledgerRepo;

    @Inject
    MessageStore messageStore;

    // ---------------------------------------------------------------------------
    // Instance management tools
    // ---------------------------------------------------------------------------

    @Tool(name = "register", description = "Register an agent instance with capability tags. "
            + "Returns active channels and online instances as immediate context.")
    @Transactional
    public RegisterResponse register(
            @ToolArg(name = "instance_id", description = "Unique human-readable identifier for this agent") String instanceId,
            @ToolArg(name = "description", description = "Description of this agent's role") String description,
            @ToolArg(name = "capabilities", description = "Capability tags for peer discovery", required = false) List<String> capabilities,
            @ToolArg(name = "claudony_session_id", description = "Optional Claudony session ID for managed workers", required = false) String claudonySessionId) {
        List<String> caps = capabilities != null ? capabilities : List.of();
        Instance instance = instanceService.register(instanceId, description, caps, claudonySessionId);

        List<ChannelSummary> channels = channelService.listAll().stream()
                .map(ch -> new ChannelSummary(ch.name, ch.description, ch.semantic.name()))
                .toList();

        List<InstanceInfo> onlineInstances = buildInstanceInfoList(instanceService.listAll());

        return new RegisterResponse(instance.instanceId, channels, onlineInstances);
    }

    /**
     * Convenience overload used by ledger-package tests that need a per-channel registration style.
     * In Qhorus, instance registration is global — the {@code channelName} parameter is accepted
     * for API symmetry but not used for scoping.
     */
    @Transactional
    public RegisterResponse registerInstance(
            String channelName,
            String instanceId,
            String description,
            List<String> capabilities,
            String claudonySessionId,
            String role,
            String extra) {
        List<String> caps = capabilities != null ? capabilities : List.of();
        Instance instance = instanceService.register(instanceId,
                description != null ? description : instanceId, caps, claudonySessionId);

        List<ChannelSummary> channels = channelService.listAll().stream()
                .map(ch -> new ChannelSummary(ch.name, ch.description, ch.semantic.name()))
                .toList();

        List<InstanceInfo> onlineInstances = buildInstanceInfoList(instanceService.listAll());
        return new RegisterResponse(instance.instanceId, channels, onlineInstances);
    }

    @Tool(name = "list_instances", description = "List registered agent instances. "
            + "Optionally filter by capability tag.")
    public List<InstanceInfo> listInstances(
            @ToolArg(name = "capability", description = "Filter by capability tag (optional)", required = false) String capability) {
        List<Instance> instances = (capability != null && !capability.isBlank())
                ? instanceService.findByCapability(capability)
                : instanceService.listAll();
        return buildInstanceInfoList(instances);
    }

    // ---------------------------------------------------------------------------
    // Observer tools
    // ---------------------------------------------------------------------------

    @Tool(name = "register_observer", description = "Subscribe a read-only observer to event messages on one or more channels. "
            + "Observers receive EVENT messages without joining the instance registry — "
            + "they are invisible to agents and cannot send messages. "
            + "Observer registrations are in-memory and reset on restart.")
    public ObserverRegistration registerObserver(
            @ToolArg(name = "observer_id", description = "Unique observer identifier (use a distinct namespace from instance IDs, e.g. 'dashboard', 'monitor-1')") String observerId,
            @ToolArg(name = "channel_names", description = "List of channel names to subscribe to") List<String> channelNames) {
        observerRegistry.register(observerId, channelNames);
        return new ObserverRegistration(observerId, observerRegistry.getSubscriptions(observerId));
    }

    @Tool(name = "deregister_observer", description = "Remove an observer subscription. "
            + "After deregistration the ID is no longer treated as an observer.")
    public DeregisterObserverResult deregisterObserver(
            @ToolArg(name = "observer_id", description = "Observer ID to remove") String observerId) {
        boolean removed = observerRegistry.deregister(observerId);
        return new DeregisterObserverResult(observerId, removed,
                removed ? "Observer '" + observerId + "' deregistered."
                        : "Observer '" + observerId + "' was not registered.");
    }

    @Tool(name = "read_observer_events", description = "Read EVENT messages from a subscribed channel as a registered observer. "
            + "Only returns EVENT-type messages. Supports cursor-based pagination via after_id.")
    @Transactional
    public CheckResult readObserverEvents(
            @ToolArg(name = "observer_id", description = "Registered observer ID") String observerId,
            @ToolArg(name = "channel_name", description = "Channel name to read from (must be in observer's subscription list)") String channelName,
            @ToolArg(name = "after_id", description = "Return messages with ID greater than this value (0 for all)", required = false) Long afterId,
            @ToolArg(name = "limit", description = "Max messages to return", required = false) Integer limit) {
        if (!observerRegistry.isObserver(observerId)) {
            throw new IllegalArgumentException(
                    "Observer '" + observerId + "' is not registered. Call register_observer first.");
        }
        if (!observerRegistry.isSubscribedTo(observerId, channelName)) {
            throw new IllegalArgumentException(
                    "Observer '" + observerId + "' is not subscribed to channel '" + channelName
                            + "'. Re-register with the channel included.");
        }
        Channel ch = channelService.findByName(channelName)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));

        long cursor = afterId != null ? afterId : 0L;
        int pageSize = (limit != null && limit > 0) ? limit : 50;

        List<Message> events = Message.<Message> find(
                "channelId = ?1 AND messageType = ?2 AND id > ?3 ORDER BY id ASC",
                ch.id, MessageType.EVENT, cursor)
                .page(0, pageSize)
                .list();

        Long lastId = events.isEmpty() ? cursor : events.get(events.size() - 1).id;
        List<MessageSummary> summaries = events.stream().map(this::toMessageSummary).toList();
        return new CheckResult(summaries, lastId, null);
    }

    // ---------------------------------------------------------------------------
    // Channel management tools
    // ---------------------------------------------------------------------------

    /** Convenience overload — no ACL or rate limits. Used by tests and internal callers. */
    public ChannelDetail createChannel(String name, String description, String semantic, String barrierContributors) {
        return createChannel(name, description, semantic, barrierContributors, null, null, null, null);
    }

    /** Convenience overload — allowed_writers but no admin_instances or rate limits. */
    public ChannelDetail createChannel(String name, String description, String semantic, String barrierContributors,
            String allowedWriters) {
        return createChannel(name, description, semantic, barrierContributors, allowedWriters, null, null, null);
    }

    /** Convenience overload — allowed_writers and admin_instances but no rate limits. */
    public ChannelDetail createChannel(String name, String description, String semantic, String barrierContributors,
            String allowedWriters, String adminInstances) {
        return createChannel(name, description, semantic, barrierContributors, allowedWriters, adminInstances, null,
                null);
    }

    @Tool(name = "create_channel", description = "Create a named channel with declared semantic. "
            + "Semantic defaults to APPEND if not specified.")
    @Transactional
    public ChannelDetail createChannel(
            @ToolArg(name = "name", description = "Unique channel name") String name,
            @ToolArg(name = "description", description = "Channel purpose description") String description,
            @ToolArg(name = "semantic", description = "Channel semantic: APPEND (default), COLLECT, BARRIER, EPHEMERAL, LAST_WRITE", required = false) String semantic,
            @ToolArg(name = "barrier_contributors", description = "Comma-separated contributor names (BARRIER channels only)", required = false) String barrierContributors,
            @ToolArg(name = "allowed_writers", description = "Comma-separated allowed writers: bare instance IDs and/or capability:tag / role:name patterns. Null = open to all.", required = false) String allowedWriters,
            @ToolArg(name = "admin_instances", description = "Comma-separated instance IDs permitted to manage this channel (pause/resume/force_release/clear). Null = open to any caller.", required = false) String adminInstances,
            @ToolArg(name = "rate_limit_per_channel", description = "Max messages per minute across all senders. Null = unlimited.", required = false) Integer rateLimitPerChannel,
            @ToolArg(name = "rate_limit_per_instance", description = "Max messages per minute from a single sender. Null = unlimited.", required = false) Integer rateLimitPerInstance) {
        ChannelSemantic sem;
        if (semantic == null || semantic.isBlank()) {
            sem = ChannelSemantic.APPEND;
        } else {
            try {
                sem = ChannelSemantic.valueOf(semantic.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid semantic '" + semantic + "'. Valid values: APPEND, COLLECT, BARRIER, EPHEMERAL, LAST_WRITE");
            }
        }
        Channel ch = channelService.create(name, description, sem, barrierContributors, allowedWriters, adminInstances,
                rateLimitPerChannel, rateLimitPerInstance);
        return toChannelDetail(ch, 0L);
    }

    @Tool(name = "set_channel_rate_limits", description = "Update the rate limits on an existing channel. "
            + "Pass null to remove a limit (restores unrestricted behaviour). "
            + "Limits are enforced via an in-memory sliding 60-second window that resets on restart.")
    @Transactional
    public ChannelDetail setChannelRateLimits(
            @ToolArg(name = "channel_name", description = "Name of the channel to update") String channelName,
            @ToolArg(name = "rate_limit_per_channel", description = "Max messages per minute across all senders. Null = unlimited.", required = false) Integer rateLimitPerChannel,
            @ToolArg(name = "rate_limit_per_instance", description = "Max messages per minute from a single sender. Null = unlimited.", required = false) Integer rateLimitPerInstance) {
        Channel ch = channelService.setRateLimits(channelName, rateLimitPerChannel, rateLimitPerInstance);
        return toChannelDetail(ch, Message.<Message> count("channelId", ch.id));
    }

    @Tool(name = "set_channel_writers", description = "Update the write ACL on an existing channel. "
            + "Pass null or blank to open the channel to all writers.")
    @Transactional
    public ChannelDetail setChannelWriters(
            @ToolArg(name = "channel_name", description = "Name of the channel to update") String channelName,
            @ToolArg(name = "allowed_writers", description = "Comma-separated allowed writers (instance IDs and/or capability:tag / role:name). Null = open to all.", required = false) String allowedWriters) {
        Channel ch = channelService.setAllowedWriters(channelName, allowedWriters);
        return toChannelDetail(ch, Message.<Message> count("channelId", ch.id));
    }

    @Tool(name = "set_channel_admins", description = "Update the admin instance list on an existing channel. "
            + "Admins may invoke pause_channel, resume_channel, force_release_channel, and clear_channel. "
            + "Pass null or blank to open management to any caller.")
    @Transactional
    public ChannelDetail setChannelAdmins(
            @ToolArg(name = "channel_name", description = "Name of the channel to update") String channelName,
            @ToolArg(name = "admin_instances", description = "Comma-separated instance IDs permitted to manage this channel. Null = open to any caller.", required = false) String adminInstances) {
        Channel ch = channelService.setAdminInstances(channelName, adminInstances);
        return toChannelDetail(ch, Message.<Message> count("channelId", ch.id));
    }

    @Tool(name = "list_channels", description = "List all channels with message count and last activity.")
    public List<ChannelDetail> listChannels() {
        List<Channel> channels = channelService.listAll();
        if (channels.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long> countByChannel = messageStore.countAllByChannel();
        return channels.stream()
                .map(ch -> toChannelDetail(ch, countByChannel.getOrDefault(ch.id, 0L)))
                .toList();
    }

    @Tool(name = "find_channel", description = "Search channels by keyword in name or description.")
    public List<ChannelDetail> findChannel(
            @ToolArg(name = "keyword", description = "Search term (case-insensitive)") String keyword) {
        String pattern = "%" + keyword.toLowerCase() + "%";
        List<Channel> matches = Channel.<Channel> find(
                "LOWER(name) LIKE ?1 OR LOWER(description) LIKE ?1", pattern).list();
        return matches.stream()
                .map(ch -> toChannelDetail(ch, Message.<Message> count("channelId", ch.id)))
                .toList();
    }

    // ---------------------------------------------------------------------------
    // Human-in-the-loop — channel flow control
    // ---------------------------------------------------------------------------

    /** Convenience overload — no caller identity (open governance assumed). */
    public ChannelDetail pauseChannel(String channelName) {
        return pauseChannel(channelName, null);
    }

    @Tool(name = "pause_channel", description = "Pause a channel — blocks send_message and returns empty on check_messages. "
            + "Idempotent. Use to stop agent work flowing through a channel for human review.")
    @Transactional
    public ChannelDetail pauseChannel(
            @ToolArg(name = "channel_name", description = "Name of the channel to pause") String channelName,
            @ToolArg(name = "caller_instance_id", description = "Instance ID of the caller. Required when the channel has an admin_instances list.", required = false) String callerInstanceId) {
        Channel ch = channelService.findByName(channelName)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));
        checkAdminAccess(ch, callerInstanceId, "pause_channel");
        ch = channelService.pause(channelName);
        return toChannelDetail(ch, Message.<Message> count("channelId", ch.id));
    }

    /** Convenience overload — no caller identity (open governance assumed). */
    public ChannelDetail resumeChannel(String channelName) {
        return resumeChannel(channelName, null);
    }

    @Tool(name = "resume_channel", description = "Resume a paused channel — re-enables send_message and check_messages. "
            + "Idempotent.")
    @Transactional
    public ChannelDetail resumeChannel(
            @ToolArg(name = "channel_name", description = "Name of the channel to resume") String channelName,
            @ToolArg(name = "caller_instance_id", description = "Instance ID of the caller. Required when the channel has an admin_instances list.", required = false) String callerInstanceId) {
        Channel ch = channelService.findByName(channelName)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));
        checkAdminAccess(ch, callerInstanceId, "resume_channel");
        ch = channelService.resume(channelName);
        return toChannelDetail(ch, Message.<Message> count("channelId", ch.id));
    }

    /** Convenience overload — no caller identity (open governance assumed). */
    public ForceReleaseResult forceReleaseChannel(String channelName, String reason) {
        return forceReleaseChannel(channelName, reason, null);
    }

    @Tool(name = "force_release_channel", description = "Force-deliver all accumulated messages and clear a BARRIER or COLLECT channel, "
            + "bypassing normal release conditions. Use when a BARRIER is stuck (missing contributors) "
            + "or to collect early from a COLLECT channel. Posts an audit event. "
            + "Only valid for BARRIER and COLLECT semantics.")
    @Transactional
    public ForceReleaseResult forceReleaseChannel(
            @ToolArg(name = "channel_name", description = "Name of the BARRIER or COLLECT channel to force-release") String channelName,
            @ToolArg(name = "reason", description = "Reason for the force-release (recorded in audit event)", required = false) String reason,
            @ToolArg(name = "caller_instance_id", description = "Instance ID of the caller. Required when the channel has an admin_instances list.", required = false) String callerInstanceId) {
        Channel ch = channelService.findByName(channelName)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));
        checkAdminAccess(ch, callerInstanceId, "force_release_channel");

        if (ch.semantic != ChannelSemantic.BARRIER && ch.semantic != ChannelSemantic.COLLECT) {
            throw new IllegalArgumentException(
                    "force_release_channel only applies to BARRIER and COLLECT channels, not "
                            + ch.semantic.name());
        }

        // Deliver all non-event messages
        List<Message> messages = Message.<Message> find(
                "channelId = ?1 AND messageType != ?2 ORDER BY id ASC",
                ch.id, MessageType.EVENT).list();
        Message.delete("channelId = ?1 AND messageType != ?2", ch.id, MessageType.EVENT);

        // Post audit event
        String auditContent = "force_release" + (reason != null && !reason.isBlank() ? ": " + reason : "");
        messageService.send(ch.id, "system", MessageType.EVENT, auditContent, null, null, null, null);

        channelService.updateLastActivity(ch.id);

        List<MessageSummary> summaries = messages.stream().map(this::toMessageSummary).toList();
        return new ForceReleaseResult(ch.name, ch.semantic.name(), messages.size(), summaries);
    }

    // ---------------------------------------------------------------------------
    // Messaging tools
    // ---------------------------------------------------------------------------

    /** Convenience overload — no artefact refs. Used by tests and internal callers. */
    public MessageResult sendMessage(String channelName, String sender, String type,
            String content, String correlationId, Long inReplyTo) {
        return sendMessage(channelName, sender, type, content, correlationId, inReplyTo, null, null);
    }

    /**
     * Convenience overload — artefact refs but no target. Maintains backward compatibility
     * for test callers that pre-date the target field. The non-{@code @Tool} annotation here
     * is intentional — only the 8-arg method is exposed to MCP.
     */
    public MessageResult sendMessage(String channelName, String sender, String type,
            String content, String correlationId, Long inReplyTo, List<String> artefactRefs) {
        return sendMessage(channelName, sender, type, content, correlationId, inReplyTo, artefactRefs, null);
    }

    @Tool(name = "send_message", description = "Post a typed message to a channel. "
            + "For 'request' type, correlation_id is auto-generated if not supplied.")
    @Transactional
    public MessageResult sendMessage(
            @ToolArg(name = "channel_name", description = "Target channel name") String channelName,
            @ToolArg(name = "sender", description = "Sender identifier") String sender,
            @ToolArg(name = "type", description = "Message type: request, response, status, handoff, done, event") String type,
            @ToolArg(name = "content", description = "Message content") String content,
            @ToolArg(name = "correlation_id", description = "Correlation ID (auto-generated for request if omitted)", required = false) String correlationId,
            @ToolArg(name = "in_reply_to", description = "ID of the message being replied to", required = false) Long inReplyTo,
            @ToolArg(name = "artefact_refs", description = "UUIDs of shared data artefacts to attach (from share_data)", required = false) List<String> artefactRefs,
            @ToolArg(name = "target", description = "Addressing target: instance:<id>, capability:<tag>, or role:<name>. Null/omitted = broadcast to all.", required = false) String target) {
        Channel ch = channelService.findByName(channelName)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));

        if (ch.paused) {
            throw new IllegalStateException(
                    "Channel '" + channelName + "' is paused — send_message blocked. Use resume_channel to re-enable.");
        }

        // Observer check — registered observers are read-only and cannot send any messages
        if (observerRegistry.isObserver(sender)) {
            throw new IllegalStateException(
                    "Observer '" + sender + "' is read-only and cannot send messages. "
                            + "Use read_observer_events to receive EVENT messages.");
        }

        MessageType msgType = MessageType.valueOf(type.toUpperCase());

        // ACL check — EVENT messages bypass (telemetry always flows)
        if (msgType != MessageType.EVENT && !isAllowedWriter(sender, ch.allowedWriters,
                () -> instanceService.findCapabilityTagsForInstance(sender))) {
            throw new IllegalStateException(
                    "Sender '" + sender + "' is not permitted to write to channel '" + channelName
                            + "'. Channel has an allowed_writers ACL. Use set_channel_writers to update it.");
        }

        // Rate limit check — EVENT messages bypass
        if (msgType != MessageType.EVENT) {
            String rateLimitError = rateLimiter.check(ch.id, channelName, sender, ch.rateLimitPerChannel,
                    ch.rateLimitPerInstance);
            if (rateLimitError != null) {
                throw new IllegalStateException(rateLimitError);
            }
        }
        String corrId = correlationId;
        if (corrId == null && msgType == MessageType.REQUEST) {
            corrId = java.util.UUID.randomUUID().toString();
        }

        // Validate artefact refs — batch query to avoid N+1
        if (artefactRefs != null && !artefactRefs.isEmpty()) {
            List<java.util.UUID> refUuids = artefactRefs.stream()
                    .map(java.util.UUID::fromString)
                    .toList();
            List<java.util.UUID> found = io.quarkiverse.qhorus.runtime.data.SharedData.<io.quarkiverse.qhorus.runtime.data.SharedData> find(
                    "id IN ?1", refUuids)
                    .list()
                    .stream()
                    .map(sd -> sd.id)
                    .toList();
            List<String> unknown = artefactRefs.stream()
                    .filter(r -> !found.contains(java.util.UUID.fromString(r)))
                    .toList();
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException(
                        "Unknown artefact ref(s): " + String.join(", ", unknown));
            }
        }

        String refsStr = (artefactRefs != null && !artefactRefs.isEmpty())
                ? String.join(",", artefactRefs)
                : null;

        // Validate and normalise target — null/blank → no addressing (broadcast)
        String normalisedTarget = (target == null || target.isBlank()) ? null : target.strip();
        if (normalisedTarget != null) {
            if (!normalisedTarget.startsWith("instance:") &&
                    !normalisedTarget.startsWith("capability:") &&
                    !normalisedTarget.startsWith("role:")) {
                throw new IllegalArgumentException(
                        "Invalid target format: '" + normalisedTarget
                                + "'. Must be instance:<id>, capability:<tag>, or role:<name>.");
            }
            String valuePart = normalisedTarget.substring(normalisedTarget.indexOf(':') + 1);
            if (valuePart.isBlank()) {
                throw new IllegalArgumentException(
                        "Invalid target format: '" + normalisedTarget
                                + "'. Value after prefix cannot be empty.");
            }
        }

        // LAST_WRITE enforcement: one authoritative writer per channel
        if (ch.semantic == ChannelSemantic.LAST_WRITE) {
            List<Message> existing = Message.<Message> find(
                    "channelId = ?1 ORDER BY id DESC", ch.id).page(0, 1).list();
            if (!existing.isEmpty()) {
                Message last = existing.get(0);
                if (last.sender.equals(sender)) {
                    // Same sender — overwrite in place, do not insert a new row.
                    // Replace the full message payload so the persisted record reflects the new write.
                    last.content = content;
                    last.messageType = msgType;
                    last.correlationId = corrId;
                    last.inReplyTo = inReplyTo;
                    last.artefactRefs = refsStr;
                    last.target = normalisedTarget;
                    last.createdAt = Instant.now();
                    channelService.updateLastActivity(ch.id);
                    rateLimiter.recordSend(ch.id, sender, ch.rateLimitPerChannel, ch.rateLimitPerInstance);
                    List<String> storedRefs = refsStr != null ? List.of(refsStr.split(",")) : List.of();
                    return new MessageResult(last.id, ch.name, last.sender,
                            last.messageType.name(), last.correlationId, last.inReplyTo, 0, storedRefs,
                            last.target);
                } else {
                    throw new IllegalStateException(
                            "LAST_WRITE channel '" + ch.name + "' already has a message from '"
                                    + last.sender + "'. Only the current writer may update this channel.");
                }
            }
        }

        Message msg = messageService.send(ch.id, sender, msgType, content, corrId, inReplyTo, refsStr,
                normalisedTarget);

        // Record structured ledger entry for EVENT messages
        if (msgType == MessageType.EVENT) {
            try {
                ledgerWriteService.recordEvent(ch, msg);
            } catch (Exception e) {
                LOG.warnf("Ledger write failed for EVENT message %d in channel '%s': %s",
                        msg.id, ch.name, e.getMessage());
            }
        }

        // Record rate window entry after successful persist (not on rejected or EVENT messages)
        if (msgType != MessageType.EVENT) {
            rateLimiter.recordSend(ch.id, sender, ch.rateLimitPerChannel, ch.rateLimitPerInstance);
        }

        int parentReplyCount = 0;
        if (inReplyTo != null) {
            parentReplyCount = messageService.findById(inReplyTo)
                    .map(m -> m.replyCount).orElse(0);
        }

        List<String> storedRefs = (msg.artefactRefs != null && !msg.artefactRefs.isBlank())
                ? List.of(msg.artefactRefs.split(","))
                : List.of();
        return new MessageResult(msg.id, ch.name, msg.sender, msg.messageType.name(),
                msg.correlationId, msg.inReplyTo, parentReplyCount, storedRefs, msg.target);
    }

    /** Backward-compat overload — no reader_instance_id filter. */
    public CheckResult checkMessages(String channelName, Long afterId, Integer limit, String sender) {
        return checkMessages(channelName, afterId, limit, sender, null);
    }

    @Tool(name = "check_messages", description = "Poll for messages on a channel after a given cursor ID. "
            + "Excludes EVENT type. Returns messages and last_id for subsequent polling. "
            + "Behaviour varies by channel semantic: EPHEMERAL deletes on read, "
            + "COLLECT delivers all and clears, BARRIER blocks until all contributors have written.")
    @Transactional
    public CheckResult checkMessages(
            @ToolArg(name = "channel_name", description = "Channel to poll") String channelName,
            @ToolArg(name = "after_id", description = "Return messages with ID > after_id (use 0 for all)", required = false) Long afterId,
            @ToolArg(name = "limit", description = "Maximum messages to return (default 20)", required = false) Integer limit,
            @ToolArg(name = "sender", description = "Filter by sender (optional)", required = false) String sender,
            @ToolArg(name = "reader_instance_id", description = "Calling agent's instance ID for target filtering. "
                    + "When provided, only broadcast (null target) and instance:<reader> messages are returned.", required = false) String readerInstanceId) {
        Channel ch = channelService.findByName(channelName)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));

        if (ch.paused) {
            return new CheckResult(List.of(), afterId != null ? afterId : 0L, "Channel is paused");
        }

        long cursor = afterId != null ? afterId : 0L;
        int pageSize = limit != null ? limit : 20;

        return switch (ch.semantic) {
            case EPHEMERAL -> checkMessagesEphemeral(ch, cursor, pageSize, readerInstanceId);
            case COLLECT -> checkMessagesCollect(ch, readerInstanceId);
            case BARRIER -> checkMessagesBarrier(ch, readerInstanceId);
            default -> checkMessagesAppend(ch, cursor, pageSize, sender, readerInstanceId);
        };
    }

    /** EPHEMERAL: deliver messages visible to this reader then delete only those. */
    private CheckResult checkMessagesEphemeral(Channel ch, long cursor, int pageSize, String readerInstanceId) {
        List<Message> fetched = messageService.pollAfter(ch.id, cursor, pageSize);
        // Filter BEFORE deleting — must not consume messages targeted at other readers.
        List<Message> visible = fetched.stream()
                .filter(m -> isVisibleToReader(m, readerInstanceId,
                        () -> instanceService.findCapabilityTagsForInstance(readerInstanceId)))
                .toList();
        if (!visible.isEmpty()) {
            List<Long> ids = visible.stream().map(m -> m.id).toList();
            Message.delete("channelId = ?1 AND id IN ?2", ch.id, ids);
        }
        List<MessageSummary> summaries = visible.stream().map(this::toMessageSummary).toList();
        Long lastId = summaries.isEmpty() ? cursor : summaries.getLast().messageId();
        return new CheckResult(summaries, lastId, null);
    }

    /** COLLECT: deliver ALL accumulated messages atomically and clear the channel; filter returned view. */
    private CheckResult checkMessagesCollect(Channel ch, String readerInstanceId) {
        List<Message> messages = Message.<Message> find(
                "channelId = ?1 AND messageType != ?2 ORDER BY id ASC",
                ch.id, MessageType.EVENT).list();
        if (!messages.isEmpty()) {
            Message.delete("channelId = ?1 AND messageType != ?2", ch.id, MessageType.EVENT);
        }
        List<MessageSummary> summaries = messages.stream()
                .filter(m -> isVisibleToReader(m, readerInstanceId,
                        () -> instanceService.findCapabilityTagsForInstance(readerInstanceId)))
                .map(this::toMessageSummary).toList();
        Long lastId = summaries.isEmpty() ? 0L : summaries.getLast().messageId();
        return new CheckResult(summaries, lastId, null);
    }

    /** BARRIER: block until all declared contributors have written; then deliver and reset. */
    private CheckResult checkMessagesBarrier(Channel ch, String readerInstanceId) {
        Set<String> required = Arrays.stream(
                ch.barrierContributors != null ? ch.barrierContributors.split(",") : new String[0])
                .map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toSet());

        // A BARRIER channel with no declared contributors is a configuration error.
        // Return a permanent-blocking status rather than silently releasing on every poll.
        if (required.isEmpty()) {
            return new CheckResult(List.of(), 0L, "Waiting for: (no contributors declared — check channel configuration)");
        }

        // Which contributors have written (non-EVENT messages only)
        List<String> written = messageStore.distinctSendersByChannel(ch.id, MessageType.EVENT);

        Set<String> pending = required.stream()
                .filter(r -> !written.contains(r))
                .collect(Collectors.toSet());

        if (!pending.isEmpty()) {
            String status = "Waiting for: " + String.join(", ", pending.stream().sorted().toList());
            return new CheckResult(List.of(), 0L, status);
        }

        // All contributors have written — deliver and clear
        List<Message> messages = Message.<Message> find(
                "channelId = ?1 AND messageType != ?2 ORDER BY id ASC",
                ch.id, MessageType.EVENT).list();
        Message.delete("channelId = ?1 AND messageType != ?2", ch.id, MessageType.EVENT);
        List<MessageSummary> summaries = messages.stream()
                .filter(m -> isVisibleToReader(m, readerInstanceId,
                        () -> instanceService.findCapabilityTagsForInstance(readerInstanceId)))
                .map(this::toMessageSummary).toList();
        Long lastId = summaries.isEmpty() ? 0L : summaries.getLast().messageId();
        return new CheckResult(summaries, lastId, null);
    }

    /** APPEND / LAST_WRITE: standard cursor-based polling with optional target filter. */
    private CheckResult checkMessagesAppend(Channel ch, long cursor, int pageSize, String sender,
            String readerInstanceId) {
        List<Message> messages = (sender != null && !sender.isBlank())
                ? messageService.pollAfterBySender(ch.id, cursor, pageSize, sender)
                : messageService.pollAfter(ch.id, cursor, pageSize);
        List<MessageSummary> summaries = messages.stream()
                .filter(m -> isVisibleToReader(m, readerInstanceId,
                        () -> instanceService.findCapabilityTagsForInstance(readerInstanceId)))
                .map(this::toMessageSummary).toList();
        Long lastId = summaries.isEmpty() ? cursor : summaries.getLast().messageId();
        return new CheckResult(summaries, lastId, null);
    }

    /** Backward-compat overload — no reader_instance_id filter. */
    public List<MessageSummary> getReplies(Long messageId) {
        return getReplies(messageId, null);
    }

    @Tool(name = "get_replies", description = "Retrieve all direct replies to a specific message.")
    public List<MessageSummary> getReplies(
            @ToolArg(name = "message_id", description = "ID of the parent message") Long messageId,
            @ToolArg(name = "reader_instance_id", description = "Calling agent's instance ID for target filtering (optional)", required = false) String readerInstanceId) {
        return Message.<Message> find("inReplyTo = ?1 ORDER BY id ASC", messageId)
                .list()
                .stream()
                .filter(m -> isVisibleToReader(m, readerInstanceId,
                        () -> instanceService.findCapabilityTagsForInstance(readerInstanceId)))
                .map(this::toMessageSummary)
                .toList();
    }

    /** Backward-compat overload — no reader_instance_id filter. */
    public List<MessageSummary> searchMessages(String query, String channelName, Integer limit) {
        return searchMessages(query, channelName, limit, null);
    }

    @Tool(name = "search_messages", description = "Full-text keyword search across messages. Excludes EVENT type.")
    public List<MessageSummary> searchMessages(
            @ToolArg(name = "query", description = "Keyword to search for (case-insensitive)") String query,
            @ToolArg(name = "channel_name", description = "Restrict search to a specific channel (optional)", required = false) String channelName,
            @ToolArg(name = "limit", description = "Maximum results (default 20)", required = false) Integer limit,
            @ToolArg(name = "reader_instance_id", description = "Calling agent's instance ID for target filtering (optional)", required = false) String readerInstanceId) {
        String pattern = "%" + query.toLowerCase() + "%";
        int pageSize = limit != null ? limit : 20;

        List<Message> results;
        if (channelName != null && !channelName.isBlank()) {
            Channel ch = channelService.findByName(channelName)
                    .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));
            results = Message.<Message> find(
                    "channelId = ?1 AND LOWER(content) LIKE ?2 AND messageType != ?3 ORDER BY id ASC",
                    ch.id, pattern, MessageType.EVENT).page(0, pageSize).list();
        } else {
            results = Message.<Message> find(
                    "LOWER(content) LIKE ?1 AND messageType != ?2 ORDER BY id ASC",
                    pattern, MessageType.EVENT).page(0, pageSize).list();
        }

        return results.stream()
                .filter(m -> isVisibleToReader(m, readerInstanceId,
                        () -> instanceService.findCapabilityTagsForInstance(readerInstanceId)))
                .map(this::toMessageSummary).toList();
    }

    // ---------------------------------------------------------------------------
    // Correlation / wait_for_reply
    // ---------------------------------------------------------------------------

    @Tool(name = "wait_for_reply", description = "Block until a RESPONSE message with the given correlation_id "
            + "arrives on the channel, or until timeout_s seconds elapse. "
            + "Returns immediately if a matching response already exists.")
    public WaitResult waitForReply(
            @ToolArg(name = "channel_name", description = "Channel to watch for the response") String channelName,
            @ToolArg(name = "correlation_id", description = "UUID matching the correlation_id on the expected RESPONSE") String correlationId,
            @ToolArg(name = "timeout_s", description = "Seconds to wait before timing out (default 90)", required = false) Integer timeoutS,
            @ToolArg(name = "instance_id", description = "Waiting agent's instance ID for tracking (optional)", required = false) String instanceId) {
        Channel ch = channelService.findByName(channelName)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));

        int timeout = timeoutS != null ? timeoutS : 90;
        java.time.Instant expiresAt = java.time.Instant.now().plusSeconds(timeout);
        // instance_id is the human-readable agent name (e.g. "alice"), not a UUID.
        // Look up its UUID; fall back to null if not registered — instance_id is metadata only.
        UUID instanceUuid = (instanceId != null && !instanceId.isBlank())
                ? instanceService.findByInstanceId(instanceId).map(i -> i.id).orElse(null)
                : null;

        // Register the pending reply (upserts if already present)
        messageService.registerPendingReply(correlationId, ch.id, instanceUuid, expiresAt);

        // Poll loop — each check is its own short transaction so we don't hold a connection
        long pollMs = 100;
        while (java.time.Instant.now().isBefore(expiresAt)) {
            // Cancellation check — if PendingReply was deleted by cancel_wait, return immediately
            if (!messageService.pendingReplyExists(correlationId)) {
                return new WaitResult(false, false, correlationId, null, "cancelled");
            }
            Message response = messageService.findResponseByCorrelationId(ch.id, correlationId)
                    .orElse(null);
            if (response != null) {
                messageService.deletePendingReply(correlationId);
                return new WaitResult(true, false, correlationId, toMessageSummary(response),
                        "Response received for correlation_id=" + correlationId);
            }
            try {
                Thread.sleep(pollMs);
                pollMs = Math.min(pollMs * 2, 500); // backoff up to 500ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Timeout
        messageService.deletePendingReply(correlationId);
        return new WaitResult(false, true, correlationId, null,
                "Timed out after " + timeout + "s waiting for response to correlation_id=" + correlationId);
    }

    // ---------------------------------------------------------------------------
    // Human-in-the-loop — approval gate
    // ---------------------------------------------------------------------------

    @Tool(name = "request_approval", description = "Send an approval request to a channel and block until a human responds. "
            + "Returns the human's response or a timeout result. "
            + "Pair with list_pending_approvals (for human to discover) and respond_to_approval (for human to answer).")
    public WaitResult requestApproval(
            @ToolArg(name = "channel_name", description = "Channel to post the approval request on") String channelName,
            @ToolArg(name = "content", description = "The approval request content shown to the human") String content,
            @ToolArg(name = "timeout_s", description = "Seconds to wait for human response (default 300)", required = false) Integer timeoutS) {
        String correlationId = UUID.randomUUID().toString();
        return requestApproval(channelName, content, correlationId, timeoutS);
    }

    /**
     * Testability overload — accepts a pre-supplied correlationId so tests can pre-seed the response.
     * Not exposed as an MCP tool.
     */
    public WaitResult requestApproval(String channelName, String content, String correlationId,
            Integer timeoutS) {
        int timeout = timeoutS != null ? timeoutS : 300;
        sendMessage(channelName, "agent", "request", content, correlationId, null, null, null);
        return waitForReply(channelName, correlationId, timeout, null);
    }

    @Tool(name = "respond_to_approval", description = "Human-callable: send a response to a pending approval request. "
            + "Use correlation_id from list_pending_approvals to identify which request to answer.")
    @Transactional
    public MessageResult respondToApproval(
            @ToolArg(name = "correlation_id", description = "Correlation ID of the approval request (from list_pending_approvals)") String correlationId,
            @ToolArg(name = "response_text", description = "The approval decision or message to send back") String responseText,
            @ToolArg(name = "channel_name", description = "Channel the approval request was posted on") String channelName) {
        return sendMessage(channelName, "human", "response", responseText, correlationId, null, null, null);
    }

    // ---------------------------------------------------------------------------
    // Human-in-the-loop — wait management
    // ---------------------------------------------------------------------------

    @Tool(name = "cancel_wait", description = "Cancel a pending wait_for_reply by its correlation_id. "
            + "The waiting agent receives status='cancelled' instead of timing out. "
            + "Use list_pending_waits to discover what is blocked.")
    @Transactional
    public CancelWaitResult cancelWait(
            @ToolArg(name = "correlation_id", description = "Correlation ID of the pending wait to cancel") String correlationId) {
        long deleted = io.quarkiverse.qhorus.runtime.message.PendingReply
                .delete("correlationId", correlationId);
        if (deleted > 0) {
            return new CancelWaitResult(correlationId, true,
                    "Cancelled pending wait for correlation_id=" + correlationId);
        } else {
            return new CancelWaitResult(correlationId, false,
                    "No pending wait found for correlation_id=" + correlationId);
        }
    }

    @Tool(name = "list_pending_waits", description = "List all agents currently blocked in wait_for_reply. "
            + "Returns oldest first. Use cancel_wait to unblock a specific wait.")
    @Transactional
    public List<PendingWaitSummary> listPendingWaits() {
        java.time.Instant now = java.time.Instant.now();
        return io.quarkiverse.qhorus.runtime.message.PendingReply.<io.quarkiverse.qhorus.runtime.message.PendingReply> findAll(
                io.quarkus.panache.common.Sort.ascending("expiresAt"))
                .list()
                .stream()
                .map(pr -> {
                    String channelName = pr.channelId != null
                            ? channelService.findById(pr.channelId)
                                    .map(ch -> ch.name)
                                    .orElse("unknown")
                            : "unknown";
                    long remaining = pr.expiresAt != null
                            ? Math.max(0, pr.expiresAt.getEpochSecond() - now.getEpochSecond())
                            : 0;
                    return new PendingWaitSummary(
                            pr.correlationId,
                            channelName,
                            pr.expiresAt != null ? pr.expiresAt.toString() : null,
                            remaining);
                })
                .toList();
    }

    @Tool(name = "list_pending_approvals", description = "List all outstanding approval requests waiting for a human response. "
            + "Returns oldest first. Use correlation_id with respond_to_approval to answer.")
    @Transactional
    public List<ApprovalSummary> listPendingApprovals() {
        java.time.Instant now = java.time.Instant.now();
        return io.quarkiverse.qhorus.runtime.message.PendingReply.<io.quarkiverse.qhorus.runtime.message.PendingReply> findAll(
                io.quarkus.panache.common.Sort.ascending("expiresAt"))
                .list()
                .stream()
                .map(pr -> {
                    String channelName = pr.channelId != null
                            ? channelService.findById(pr.channelId)
                                    .map(ch -> ch.name)
                                    .orElse("unknown")
                            : "unknown";
                    long remaining = pr.expiresAt != null
                            ? Math.max(0, pr.expiresAt.getEpochSecond() - now.getEpochSecond())
                            : 0;
                    return new ApprovalSummary(
                            pr.correlationId,
                            channelName,
                            pr.expiresAt != null ? pr.expiresAt.toString() : null,
                            remaining);
                })
                .toList();
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private List<InstanceInfo> buildInstanceInfoList(List<Instance> instances) {
        if (instances.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = instances.stream().map(i -> i.id).toList();
        Map<UUID, List<String>> capsByInstanceId = Capability
                .<Capability> find("instanceId IN ?1", ids)
                .list()
                .stream()
                .collect(Collectors.groupingBy(
                        c -> c.instanceId,
                        Collectors.mapping(c -> c.tag, Collectors.toList())));

        return instances.stream()
                .map(i -> new InstanceInfo(
                        i.instanceId,
                        i.description,
                        i.status,
                        capsByInstanceId.getOrDefault(i.id, List.of()),
                        i.lastSeen.toString()))
                .toList();
    }

    // ---------------------------------------------------------------------------
    // Shared data tools
    // ---------------------------------------------------------------------------

    @Tool(name = "share_data", description = "Store a large artefact by key. "
            + "Supports chunked upload via append=true; last_chunk=true marks the artefact complete. "
            + "Returns the artefact UUID for use in message artefact_refs.")
    @Transactional
    public ArtefactDetail shareData(
            @ToolArg(name = "key", description = "Unique key for this artefact") String key,
            @ToolArg(name = "description", description = "Human-readable description", required = false) String description,
            @ToolArg(name = "created_by", description = "Owner instance identifier") String createdBy,
            @ToolArg(name = "content", description = "Content to store or append") String content,
            @ToolArg(name = "append", description = "Append to existing content (default false)", required = false) Boolean append,
            @ToolArg(name = "last_chunk", description = "Mark artefact complete (default true)", required = false) Boolean lastChunk) {
        boolean doAppend = append != null && append;
        boolean isLastChunk = lastChunk == null || lastChunk;
        var data = dataService.store(key, description, createdBy, content, doAppend, isLastChunk);
        return toArtefactDetail(data);
    }

    @Tool(name = "get_shared_data", description = "Retrieve a shared artefact by key or UUID. Exactly one of key or id must be provided.")
    public ArtefactDetail getSharedData(
            @ToolArg(name = "key", description = "Artefact key", required = false) String key,
            @ToolArg(name = "id", description = "Artefact UUID", required = false) String id) {
        boolean hasKey = key != null && !key.isBlank();
        boolean hasId = id != null && !id.isBlank();
        if (!hasKey && !hasId) {
            throw new IllegalArgumentException("Either 'key' or 'id' must be provided");
        }
        var data = hasKey
                ? dataService.getByKey(key)
                        .orElseThrow(() -> new IllegalArgumentException("Artefact not found: key=" + key))
                : dataService.getByUuid(java.util.UUID.fromString(id))
                        .orElseThrow(() -> new IllegalArgumentException("Artefact not found: id=" + id));
        return toArtefactDetail(data);
    }

    @Tool(name = "list_shared_data", description = "List all artefacts with metadata.")
    public List<ArtefactDetail> listSharedData() {
        return dataService.listAll().stream().map(this::toArtefactDetail).toList();
    }

    @Tool(name = "claim_artefact", description = "Declare this instance holds a reference to an artefact. Prevents GC.")
    @Transactional
    public String claimArtefact(
            @ToolArg(name = "artefact_id", description = "Artefact UUID") String artefactId,
            @ToolArg(name = "instance_id", description = "Claiming instance UUID") String instanceId) {
        try {
            dataService.claim(java.util.UUID.fromString(artefactId), java.util.UUID.fromString(instanceId));
            return "claimed";
        } catch (final IllegalArgumentException | IllegalStateException e) {
            return toolError(e);
        }
    }

    @Tool(name = "release_artefact", description = "Release a reference to an artefact. GC-eligible when all claims released.")
    @Transactional
    public String releaseArtefact(
            @ToolArg(name = "artefact_id", description = "Artefact UUID") String artefactId,
            @ToolArg(name = "instance_id", description = "Releasing instance UUID") String instanceId) {
        try {
            dataService.release(java.util.UUID.fromString(artefactId), java.util.UUID.fromString(instanceId));
            return "released";
        } catch (final IllegalArgumentException | IllegalStateException e) {
            return toolError(e);
        }
    }

    /** Not a @Tool — helper for tests and internal GC logic. */
    public boolean isGcEligible(String artefactId) {
        return dataService.isGcEligible(java.util.UUID.fromString(artefactId));
    }

    @Tool(name = "revoke_artefact", description = "Force-delete a shared artefact and release all its claims. "
            + "Use for data breaches, PII removal, or invalid data. "
            + "get_shared_data will fail after revocation. Does not cascade to messages that reference this artefact.")
    @Transactional
    public RevokeResult revokeArtefact(
            @ToolArg(name = "artefact_id", description = "UUID of the artefact to revoke") String artefactId) {
        java.util.UUID uuid = java.util.UUID.fromString(artefactId);
        io.quarkiverse.qhorus.runtime.data.SharedData data = io.quarkiverse.qhorus.runtime.data.SharedData.findById(uuid);
        if (data == null) {
            return new RevokeResult(artefactId, null, null, 0, 0, false,
                    "Artefact not found: " + artefactId);
        }
        String key = data.key;
        String createdBy = data.createdBy;
        long sizeBytes = data.sizeBytes;

        // Delete claims first (FK constraint)
        int claimsReleased = (int) io.quarkiverse.qhorus.runtime.data.ArtefactClaim
                .delete("artefactId", uuid);
        // Delete the artefact
        data.delete();

        return new RevokeResult(artefactId, key, createdBy, sizeBytes, claimsReleased, true,
                "Artefact '" + key + "' revoked — " + claimsReleased + " claim(s) released");
    }

    // ---------------------------------------------------------------------------
    // Human-in-the-loop — message and instance management
    // ---------------------------------------------------------------------------

    @Tool(name = "delete_message", description = "Delete a single message by its sequence ID. "
            + "Use for PII removal, bad data, or agent mistakes. Does not cascade to replies.")
    @Transactional
    public DeleteMessageResult deleteMessage(
            @ToolArg(name = "message_id", description = "Sequence ID of the message to delete") Long messageId) {
        Message msg = Message.findById(messageId);
        if (msg == null) {
            return new DeleteMessageResult(messageId, false, null, null, null,
                    "Message not found: " + messageId);
        }
        String sender = msg.sender;
        String type = msg.messageType.name();
        String preview = msg.content != null
                ? (msg.content.length() > 80 ? msg.content.substring(0, 80) + "…" : msg.content)
                : null;
        // Orphan replies (null out in_reply_to) before deleting — replies survive, FK satisfied
        Message.update("inReplyTo = null WHERE inReplyTo = ?1", messageId);
        // Post audit event to the channel
        messageService.send(msg.channelId, "system", MessageType.EVENT,
                "delete_message: id=" + messageId + " sender=" + sender, null, null, null, null);
        msg.delete();
        return new DeleteMessageResult(messageId, true, sender, type, preview,
                "Message " + messageId + " deleted");
    }

    /** Convenience overload — no caller identity (open governance assumed). */
    public ClearChannelResult clearChannel(String channelName) {
        return clearChannel(channelName, null);
    }

    @Tool(name = "clear_channel", description = "Delete ALL non-event messages from a channel. "
            + "Does not delete the channel itself or event messages. "
            + "Returns count of messages deleted.")
    @Transactional
    public ClearChannelResult clearChannel(
            @ToolArg(name = "channel_name", description = "Name of the channel to clear") String channelName,
            @ToolArg(name = "caller_instance_id", description = "Instance ID of the caller. Required when the channel has an admin_instances list.", required = false) String callerInstanceId) {
        Channel ch = channelService.findByName(channelName)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));
        checkAdminAccess(ch, callerInstanceId, "clear_channel");
        long deleted = Message.delete("channelId = ?1 AND messageType != ?2",
                ch.id, MessageType.EVENT);
        // Post audit event (survives the clear)
        messageService.send(ch.id, "system", MessageType.EVENT,
                "clear_channel: " + deleted + " message(s) deleted", null, null, null, null);
        channelService.updateLastActivity(ch.id);
        return new ClearChannelResult(channelName, (int) deleted, true);
    }

    @Tool(name = "deregister_instance", description = "Force-remove an agent instance and its capability tags from the registry. "
            + "Use for misbehaving agents that won't self-deregister. Does not delete past messages.")
    @Transactional
    public DeregisterResult deregisterInstance(
            @ToolArg(name = "instance_id", description = "Human-readable instance ID of the agent to remove") String instanceId) {
        io.quarkiverse.qhorus.runtime.instance.Instance instance = io.quarkiverse.qhorus.runtime.instance.Instance.<io.quarkiverse.qhorus.runtime.instance.Instance> find(
                "instanceId", instanceId)
                .firstResult();
        if (instance == null) {
            return new DeregisterResult(instanceId, false,
                    "Instance not found: " + instanceId);
        }
        // Delete capabilities first (no FK from capability to instance that would block)
        io.quarkiverse.qhorus.runtime.instance.Capability.delete("instanceId", instance.id);
        instance.delete();
        return new DeregisterResult(instanceId, true,
                "Instance '" + instanceId + "' deregistered");
    }

    @Tool(name = "channel_digest", description = "Return a structured human-readable summary of a channel's recent activity. "
            + "Useful for human dashboards to understand state before intervening. "
            + "Includes message count, sender/type breakdowns, artefact refs, recent messages (truncated), and timestamps.")
    @Transactional
    public ChannelDigest channelDigest(
            @ToolArg(name = "channel_name", description = "Name of the channel to summarise") String channelName,
            @ToolArg(name = "limit", description = "Max recent messages to include (default 10)", required = false) Integer limit) {
        Channel ch = channelService.findByName(channelName)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));

        int pageSize = limit != null ? limit : 10;
        List<Message> allMessages = Message.<Message> find(
                "channelId = ?1 AND messageType != ?2 ORDER BY id ASC",
                ch.id, MessageType.EVENT).list();

        if (allMessages.isEmpty()) {
            return new ChannelDigest(ch.name, ch.semantic.name(), ch.paused,
                    0L, Map.of(), Map.of(), 0, List.of(), List.of(), null, null);
        }

        // Sender and type breakdowns
        Map<String, Integer> senderBreakdown = new java.util.LinkedHashMap<>();
        Map<String, Integer> typeBreakdown = new java.util.LinkedHashMap<>();
        java.util.Set<String> artefactUuids = new java.util.LinkedHashSet<>();

        for (Message m : allMessages) {
            senderBreakdown.merge(m.sender, 1, Integer::sum);
            typeBreakdown.merge(m.messageType.name(), 1, Integer::sum);
            if (m.artefactRefs != null && !m.artefactRefs.isBlank()) {
                for (String ref : m.artefactRefs.split(",")) {
                    if (!ref.isBlank())
                        artefactUuids.add(ref.strip());
                }
            }
        }

        // Active agents — sent a non-event message in the last 5 minutes
        java.time.Instant cutoff = java.time.Instant.now().minusSeconds(300);
        List<String> activeAgents = allMessages.stream()
                .filter(m -> m.createdAt != null && m.createdAt.isAfter(cutoff))
                .map(m -> m.sender)
                .distinct()
                .toList();

        // Recent messages — last `pageSize`, newest first, content truncated
        List<MessagePreview> recent = allMessages.stream()
                .skip(Math.max(0, allMessages.size() - pageSize))
                .map(m -> {
                    String content = m.content != null ? m.content : "";
                    String preview = content.length() > 120
                            ? content.substring(0, 120) + "…"
                            : content;
                    return new MessagePreview(m.id, m.sender, m.messageType.name(),
                            preview, m.createdAt != null ? m.createdAt.toString() : null);
                })
                .toList();

        String oldest = allMessages.get(0).createdAt != null
                ? allMessages.get(0).createdAt.toString()
                : null;
        String newest = allMessages.get(allMessages.size() - 1).createdAt != null
                ? allMessages.get(allMessages.size() - 1).createdAt.toString()
                : null;

        return new ChannelDigest(ch.name, ch.semantic.name(), ch.paused,
                allMessages.size(), senderBreakdown, typeBreakdown,
                artefactUuids.size(), activeAgents, recent, oldest, newest);
    }

    // ---------------------------------------------------------------------------
    // Ledger event audit trail tools
    // ---------------------------------------------------------------------------

    @Tool(name = "list_events", description = "Query the structured event audit trail for a channel. "
            + "Returns EVENT-type ledger entries in chronological order. "
            + "Supports optional filters for agent_id, since (ISO-8601 timestamp), and cursor-based "
            + "pagination via after_id (sequence_number cursor).")
    @Transactional
    public List<Map<String, Object>> listEvents(
            @ToolArg(name = "channel_name", description = "Name of the channel to query") String channelName,
            @ToolArg(name = "after_id", description = "Return entries with sequence_number > after_id (cursor pagination)", required = false) Long afterId,
            @ToolArg(name = "limit", description = "Maximum entries to return (default 20, max 100)", required = false) Integer limit,
            @ToolArg(name = "agent_id", description = "Filter by agent (actor_id) — returns only entries from this agent", required = false) String agentId,
            @ToolArg(name = "since", description = "ISO-8601 timestamp — return only entries at or after this time", required = false) String since) {

        Channel ch = channelService.findByName(channelName)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 100) : 20;

        java.time.Instant sinceInstant = null;
        if (since != null && !since.isBlank()) {
            try {
                sinceInstant = java.time.Instant.parse(since);
            } catch (final java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "Invalid 'since' timestamp '" + since
                                + "' — use ISO-8601 format, e.g. 2026-04-15T10:00:00Z");
            }
        }

        List<AgentMessageLedgerEntry> entries = ledgerRepo.listEventEntries(
                ch.id, afterId, agentId, sinceInstant, effectiveLimit);

        return entries.stream().map(this::toEventMap).toList();
    }

    @Tool(name = "get_channel_timeline", description = "Return all messages for a channel in chronological order, "
            + "interleaving regular messages and EVENT telemetry entries. "
            + "Each entry has a 'type' discriminator: 'MESSAGE' or 'EVENT'. "
            + "Supports cursor-based pagination via after_id (message.id cursor).")
    @Transactional
    public List<Map<String, Object>> getChannelTimeline(
            @ToolArg(name = "channel_name", description = "Name of the channel to query") String channelName,
            @ToolArg(name = "after_id", description = "Return messages with id > after_id (cursor pagination)", required = false) Long afterId,
            @ToolArg(name = "limit", description = "Maximum messages to return (default 50, max 200)", required = false) Integer limit) {

        Channel ch = channelService.findByName(channelName)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 200) : 50;

        List<Message> messages = messageStore.scan(
                MessageQuery.poll(ch.id, afterId, effectiveLimit));

        return messages.stream().map(this::toTimelineEntry).toList();
    }

    // ---------------------------------------------------------------------------
    // Human-in-the-loop — watchdogs and alerts (optional module)
    // ---------------------------------------------------------------------------

    private void requireWatchdogEnabled() {
        if (!qhorusConfig.watchdog().enabled()) {
            throw new IllegalStateException(
                    "Watchdog module is disabled. Set quarkus.qhorus.watchdog.enabled=true to activate.");
        }
    }

    @Tool(name = "register_watchdog", description = "Register a condition-based watchdog that posts an alert to a notification channel "
            + "when the condition is met. Condition types: BARRIER_STUCK, APPROVAL_PENDING, AGENT_STALE, CHANNEL_IDLE, QUEUE_DEPTH. "
            + "Requires quarkus.qhorus.watchdog.enabled=true.")
    @Transactional
    public WatchdogSummary registerWatchdog(
            @ToolArg(name = "condition_type", description = "BARRIER_STUCK | APPROVAL_PENDING | AGENT_STALE | CHANNEL_IDLE | QUEUE_DEPTH") String conditionType,
            @ToolArg(name = "target_name", description = "Channel name, instance_id, or '*' for all") String targetName,
            @ToolArg(name = "threshold_seconds", description = "Time threshold in seconds (for time-based conditions)", required = false) Integer thresholdSeconds,
            @ToolArg(name = "threshold_count", description = "Count threshold (for QUEUE_DEPTH)", required = false) Integer thresholdCount,
            @ToolArg(name = "notification_channel", description = "Channel to post alert events to") String notificationChannel,
            @ToolArg(name = "created_by", description = "Who is registering this watchdog") String createdBy) {
        requireWatchdogEnabled();
        io.quarkiverse.qhorus.runtime.watchdog.Watchdog w = new io.quarkiverse.qhorus.runtime.watchdog.Watchdog();
        w.conditionType = conditionType;
        w.targetName = targetName;
        w.thresholdSeconds = thresholdSeconds;
        w.thresholdCount = thresholdCount;
        w.notificationChannel = notificationChannel;
        w.createdBy = createdBy;
        w.persist();
        return toWatchdogSummary(w);
    }

    @Tool(name = "list_watchdogs", description = "List all registered watchdog conditions. "
            + "Requires quarkus.qhorus.watchdog.enabled=true.")
    @Transactional
    public List<WatchdogSummary> listWatchdogs() {
        requireWatchdogEnabled();
        return io.quarkiverse.qhorus.runtime.watchdog.Watchdog.<io.quarkiverse.qhorus.runtime.watchdog.Watchdog> listAll()
                .stream()
                .map(this::toWatchdogSummary)
                .toList();
    }

    @Tool(name = "delete_watchdog", description = "Remove a registered watchdog by its ID. "
            + "Requires quarkus.qhorus.watchdog.enabled=true.")
    @Transactional
    public DeleteWatchdogResult deleteWatchdog(
            @ToolArg(name = "watchdog_id", description = "UUID of the watchdog to delete") String watchdogId) {
        requireWatchdogEnabled();
        final UUID watchdogUuid;
        try {
            watchdogUuid = UUID.fromString(watchdogId);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid watchdog_id '" + watchdogId + "' — must be a UUID (e.g. 550e8400-e29b-41d4-a716-446655440000)");
        }
        long deleted = io.quarkiverse.qhorus.runtime.watchdog.Watchdog
                .delete("id", watchdogUuid);
        if (deleted > 0) {
            return new DeleteWatchdogResult(watchdogId, true, "Watchdog " + watchdogId + " deleted");
        }
        return new DeleteWatchdogResult(watchdogId, false,
                "Watchdog not found: " + watchdogId);
    }

}
