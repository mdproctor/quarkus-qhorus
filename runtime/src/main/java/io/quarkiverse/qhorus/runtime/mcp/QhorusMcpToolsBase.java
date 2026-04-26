package io.quarkiverse.qhorus.runtime.mcp;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.data.SharedData;
import io.quarkiverse.qhorus.runtime.ledger.MessageLedgerEntry;
import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.message.MessageType;
import io.quarkiverse.qhorus.runtime.watchdog.Watchdog;

public abstract class QhorusMcpToolsBase {

    public record RegisterResponse(
            String instanceId,
            List<ChannelSummary> activeChannels,
            List<InstanceInfo> onlineInstances) {
    }

    public record ChannelSummary(String name, String description, String semantic) {
    }

    public record InstanceInfo(
            String instanceId,
            String description,
            String status,
            List<String> capabilities,
            String lastSeen) {
    }

    public record ChannelDetail(
            UUID channelId,
            String name,
            String description,
            String semantic,
            String barrierContributors,
            long messageCount,
            String lastActivityAt,
            boolean paused,
            /** Comma-separated allowed-writer entries, or null if the channel is open to all writers. */
            String allowedWriters,
            /** Comma-separated admin instance IDs, or null if management is open to any caller. */
            String adminInstances,
            /** Max messages per minute across all senders. Null = unlimited. */
            Integer rateLimitPerChannel,
            /** Max messages per minute from a single sender. Null = unlimited. */
            Integer rateLimitPerInstance) {
    }

    public record MessageResult(
            Long messageId,
            String channelName,
            String sender,
            String messageType,
            String correlationId,
            Long inReplyTo,
            int parentReplyCount,
            List<String> artefactRefs,
            /** Addressing target: null (broadcast), instance:<id>, capability:<tag>, or role:<name>. */
            String target) {
    }

    public record MessageSummary(
            Long messageId,
            String sender,
            String messageType,
            String content,
            String correlationId,
            Long inReplyTo,
            String createdAt,
            List<String> artefactRefs,
            /** Addressing target: null (broadcast), instance:<id>, capability:<tag>, or role:<name>. */
            String target) {
    }

    public record CheckResult(
            List<MessageSummary> messages,
            Long lastId,
            /** Non-null on BARRIER channels that have not yet released — lists pending contributors. */
            String barrierStatus) {
    }

    public record ArtefactDetail(
            UUID artefactId,
            String key,
            String description,
            String createdBy,
            String content,
            boolean complete,
            long sizeBytes,
            String updatedAt) {
    }

    public record WaitResult(
            boolean found,
            boolean timedOut,
            String correlationId,
            /** The matching response message, or null on timeout. */
            MessageSummary message,
            String status) {
    }

    public record ApprovalSummary(
            String correlationId,
            String channelName,
            String expiresAt,
            long timeRemainingSeconds) {
    }

    public record PendingWaitSummary(
            String correlationId,
            String channelName,
            String expiresAt,
            long timeRemainingSeconds) {
    }

    public record CancelWaitResult(
            String correlationId,
            boolean cancelled,
            String message) {
    }

    public record CommitmentDetail(
            String commitmentId,
            String correlationId,
            String channelId,
            String messageType,
            String requester,
            String obligor,
            String state,
            String expiresAt,
            String acknowledgedAt,
            String resolvedAt,
            String delegatedTo,
            String parentCommitmentId,
            String createdAt) {

        public static CommitmentDetail from(io.quarkiverse.qhorus.runtime.message.Commitment c) {
            return new CommitmentDetail(
                    c.id != null ? c.id.toString() : null,
                    c.correlationId,
                    c.channelId != null ? c.channelId.toString() : null,
                    c.messageType != null ? c.messageType.name() : null,
                    c.requester,
                    c.obligor,
                    c.state != null ? c.state.name() : null,
                    c.expiresAt != null ? c.expiresAt.toString() : null,
                    c.acknowledgedAt != null ? c.acknowledgedAt.toString() : null,
                    c.resolvedAt != null ? c.resolvedAt.toString() : null,
                    c.delegatedTo,
                    c.parentCommitmentId != null ? c.parentCommitmentId.toString() : null,
                    c.createdAt != null ? c.createdAt.toString() : null);
        }
    }

    public record ForceReleaseResult(
            String channelName,
            String semantic,
            int messageCount,
            List<MessageSummary> messages) {
    }

    public record RevokeResult(
            String artefactId,
            String key,
            String createdBy,
            long sizeBytes,
            int claimsReleased,
            boolean revoked,
            String message) {
    }

    public record DeleteMessageResult(
            Long messageId,
            boolean deleted,
            String sender,
            String messageType,
            String contentPreview,
            String message) {
    }

    public record ClearChannelResult(
            String channelName,
            int messagesDeleted,
            boolean cleared) {
    }

    public record DeregisterResult(
            String instanceId,
            boolean deregistered,
            String message) {
    }

    public record MessagePreview(
            Long messageId,
            String sender,
            String messageType,
            String contentPreview,
            String createdAt) {
    }

    public record WatchdogSummary(
            String id,
            String conditionType,
            String targetName,
            Integer thresholdSeconds,
            Integer thresholdCount,
            String notificationChannel,
            String createdBy,
            String createdAt,
            String lastFiredAt) {
    }

    public record ObserverRegistration(
            String observerId,
            Set<String> channelNames) {
    }

    public record DeregisterObserverResult(
            String observerId,
            boolean deregistered,
            String message) {
    }

    public record DeleteWatchdogResult(
            String watchdogId,
            boolean deleted,
            String message) {
    }

    public record ChannelDigest(
            String channelName,
            String semantic,
            boolean paused,
            long messageCount,
            Map<String, Integer> senderBreakdown,
            Map<String, Integer> typeBreakdown,
            int artefactRefCount,
            List<String> activeAgents,
            List<MessagePreview> recentMessages,
            String oldestMessageAt,
            String newestMessageAt) {
    }

    /**
     * Throws {@link IllegalStateException} if the channel has an {@code admin_instances} list
     * and {@code callerInstanceId} is not in it (or is null).
     */
    protected static void checkAdminAccess(Channel ch, String callerInstanceId, String toolName) {
        if (ch.adminInstances == null || ch.adminInstances.isBlank()) {
            return;
        }
        if (callerInstanceId == null || callerInstanceId.isBlank()) {
            throw new IllegalStateException(
                    "Channel '" + ch.name + "' requires a caller_instance_id for " + toolName
                            + " — it has an admin_instances list.");
        }
        for (String raw : ch.adminInstances.split(",")) {
            if (raw.strip().equals(callerInstanceId)) {
                return;
            }
        }
        throw new IllegalStateException(
                "Caller '" + callerInstanceId + "' is not permitted to invoke " + toolName
                        + " on channel '" + ch.name + "'. Not in admin_instances list.");
    }

    /**
     * Returns true if {@code sender} is permitted to write to a channel with the given
     * {@code allowedWriters} ACL string. Null or blank ACL = open to all.
     * {@code senderTagsSupplier} is invoked lazily — only if a capability/role entry is present.
     */
    protected static boolean isAllowedWriter(String sender, String allowedWriters,
            Supplier<List<String>> senderTagsSupplier) {
        if (allowedWriters == null || allowedWriters.isBlank()) {
            return true;
        }
        List<String> senderTags = null;
        for (String raw : allowedWriters.split(",")) {
            String entry = raw.strip();
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.startsWith("capability:") || entry.startsWith("role:")) {
                if (senderTags == null) {
                    senderTags = senderTagsSupplier.get();
                }
                if (senderTags.contains(entry)) {
                    return true;
                }
            } else {
                if (entry.equals(sender)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns true if the message is visible to the given reader.
     * {@code readerTagsSupplier} is invoked lazily — only if the target is a capability/role prefix.
     */
    protected static boolean isVisibleToReader(Message m, String readerInstanceId,
            Supplier<List<String>> readerTagsSupplier) {
        if (readerInstanceId == null || readerInstanceId.isBlank()) {
            return true;
        }
        if (m.messageType == MessageType.EVENT) {
            return true;
        }
        if (m.target == null) {
            return true;
        }
        if (m.target.equals("instance:" + readerInstanceId)) {
            return true;
        }
        if (m.target.startsWith("capability:") || m.target.startsWith("role:")) {
            return readerTagsSupplier.get().contains(m.target);
        }
        return false;
    }

    protected String toolError(Exception e) {
        return "Error: " + e.getMessage();
    }

    protected ArtefactDetail toArtefactDetail(SharedData d) {
        return new ArtefactDetail(d.id, d.key, d.description, d.createdBy,
                d.content, d.complete, d.sizeBytes, d.updatedAt.toString());
    }

    protected MessageSummary toMessageSummary(Message m) {
        List<String> refs = (m.artefactRefs != null && !m.artefactRefs.isBlank())
                ? List.of(m.artefactRefs.split(","))
                : List.of();
        return new MessageSummary(m.id, m.sender, m.messageType.name(), m.content,
                m.correlationId, m.inReplyTo, m.createdAt.toString(), refs, m.target);
    }

    protected ChannelDetail toChannelDetail(Channel ch, long messageCount) {
        return new ChannelDetail(
                ch.id,
                ch.name,
                ch.description,
                ch.semantic.name(),
                ch.barrierContributors,
                messageCount,
                ch.lastActivityAt.toString(),
                ch.paused,
                ch.allowedWriters,
                ch.adminInstances,
                ch.rateLimitPerChannel,
                ch.rateLimitPerInstance);
    }

    protected WatchdogSummary toWatchdogSummary(Watchdog w) {
        return new WatchdogSummary(
                w.id.toString(),
                w.conditionType,
                w.targetName,
                w.thresholdSeconds,
                w.thresholdCount,
                w.notificationChannel,
                w.createdBy,
                w.createdAt != null ? w.createdAt.toString() : null,
                w.lastFiredAt != null ? w.lastFiredAt.toString() : null);
    }

    protected Map<String, Object> toLedgerEntryMap(final MessageLedgerEntry e) {
        final Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("sequence_number", (long) e.sequenceNumber);
        m.put("message_type", e.messageType);
        m.put("entry_type", e.entryType != null ? e.entryType.name() : null);
        m.put("actor_id", e.actorId);
        m.put("target", e.target);
        m.put("content", e.content);
        m.put("correlation_id", e.correlationId);
        m.put("commitment_id", e.commitmentId != null ? e.commitmentId.toString() : null);
        m.put("caused_by_entry_id", e.causedByEntryId != null ? e.causedByEntryId.toString() : null);
        m.put("occurred_at", e.occurredAt != null ? e.occurredAt.toString() : null);
        m.put("message_id", e.messageId);
        // Telemetry — only include keys when values are present (EVENT-only fields)
        if (e.toolName != null) {
            m.put("tool_name", e.toolName);
        }
        if (e.durationMs != null) {
            m.put("duration_ms", e.durationMs);
        }
        if (e.tokenCount != null) {
            m.put("token_count", e.tokenCount);
        }
        if (e.contextRefs != null) {
            m.put("context_refs", e.contextRefs);
        }
        if (e.sourceEntity != null) {
            m.put("source_entity", e.sourceEntity);
        }
        return m;
    }

    protected Map<String, Object> toTimelineEntry(Message m) {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("id", m.id);
        if (m.messageType == MessageType.EVENT) {
            entry.put("type", "EVENT");
            entry.put("created_at", m.createdAt != null ? m.createdAt.toString() : null);
            entry.put("occurred_at", m.createdAt != null ? m.createdAt.toString() : null);
            entry.put("agent_id", m.sender);
            entry.put("message_type", null);
            String toolName = null;
            if (m.content != null) {
                try {
                    com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(m.content);
                    com.fasterxml.jackson.databind.JsonNode tn = node.get("tool_name");
                    if (tn != null && tn.isTextual()) {
                        toolName = tn.asText();
                    }
                } catch (Exception ignored) {
                }
            }
            entry.put("tool_name", toolName);
        } else {
            entry.put("type", "MESSAGE");
            entry.put("created_at", m.createdAt != null ? m.createdAt.toString() : null);
            entry.put("sender", m.sender);
            entry.put("message_type", m.messageType != null ? m.messageType.name().toLowerCase() : null);
            entry.put("content", m.content);
            entry.put("correlation_id", m.correlationId);
            entry.put("tool_name", null);
        }
        return entry;
    }
}
