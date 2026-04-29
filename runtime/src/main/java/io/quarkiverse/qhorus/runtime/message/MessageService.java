package io.quarkiverse.qhorus.runtime.message;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.qhorus.runtime.channel.ChannelService;
import io.quarkiverse.qhorus.runtime.store.MessageStore;
import io.quarkiverse.qhorus.runtime.store.query.MessageQuery;

@ApplicationScoped
public class MessageService {

    @Inject
    ChannelService channelService;

    @Inject
    MessageStore messageStore;

    @Inject
    CommitmentService commitmentService;

    @Inject
    MessageTypePolicy messageTypePolicy;

    @Transactional
    public Message send(UUID channelId, String sender, MessageType type, String content,
            String correlationId, Long inReplyTo) {
        return send(channelId, sender, type, content, correlationId, inReplyTo, null, null);
    }

    @Transactional
    public Message send(UUID channelId, String sender, MessageType type, String content,
            String correlationId, Long inReplyTo, String artefactRefs) {
        return send(channelId, sender, type, content, correlationId, inReplyTo, artefactRefs, null);
    }

    @Transactional
    public Message send(UUID channelId, String sender, MessageType type, String content,
            String correlationId, Long inReplyTo, String artefactRefs, String target) {
        channelService.findById(channelId)
                .ifPresent(ch -> messageTypePolicy.validate(ch, type));
        Message message = new Message();
        message.channelId = channelId;
        message.sender = sender;
        message.messageType = type;
        message.content = content;
        message.correlationId = correlationId;
        message.inReplyTo = inReplyTo;
        message.artefactRefs = artefactRefs;
        message.target = target;
        messageStore.put(message);

        // Trigger commitment state machine for obligation tracking
        if (message.correlationId != null) {
            switch (message.messageType) {
                case QUERY, COMMAND -> commitmentService.open(
                        message.commitmentId != null ? message.commitmentId : java.util.UUID.randomUUID(),
                        message.correlationId, message.channelId, message.messageType,
                        message.sender, message.target, message.deadline);
                case STATUS -> commitmentService.acknowledge(message.correlationId);
                case RESPONSE, DONE -> commitmentService.fulfill(message.correlationId);
                case DECLINE -> commitmentService.decline(message.correlationId);
                case FAILURE -> commitmentService.fail(message.correlationId);
                case HANDOFF -> commitmentService.delegate(message.correlationId, message.target);
                case EVENT -> {
                    /* no commitment effect */ }
            }
        }

        if (inReplyTo != null) {
            messageStore.find(inReplyTo).ifPresent(parent -> parent.replyCount++);
        }

        channelService.updateLastActivity(channelId);

        return message;
    }

    public Optional<Message> findById(Long id) {
        return messageStore.find(id);
    }

    /**
     * Returns messages in channel posted after {@code afterId}, excluding EVENT type
     * (observer-only — not delivered to agent context).
     */
    public List<Message> pollAfter(UUID channelId, Long afterId, int limit) {
        return pollAfter(channelId, afterId, limit, false);
    }

    /**
     * Returns messages in channel posted after {@code afterId}. If {@code includeEvents}
     * is true, EVENT messages are included (for read-only observer instances).
     */
    public List<Message> pollAfter(UUID channelId, Long afterId, int limit, boolean includeEvents) {
        MessageQuery.Builder builder = MessageQuery.builder()
                .channelId(channelId)
                .afterId(afterId)
                .limit(limit);
        if (!includeEvents) {
            builder.excludeTypes(List.of(MessageType.EVENT));
        }
        return messageStore.scan(builder.build());
    }

    /**
     * Like {@link #pollAfter} but filters by sender in the query — avoids the
     * post-limit filtering bug where messages are lost when limit < total results.
     */
    public List<Message> pollAfterBySender(UUID channelId, Long afterId, int limit, String sender) {
        return pollAfterBySender(channelId, afterId, limit, sender, false);
    }

    /**
     * Like {@link #pollAfter(UUID, Long, int, boolean)} but also filters by sender.
     */
    public List<Message> pollAfterBySender(UUID channelId, Long afterId, int limit, String sender,
            boolean includeEvents) {
        MessageQuery.Builder builder = MessageQuery.builder()
                .channelId(channelId)
                .afterId(afterId)
                .limit(limit)
                .sender(sender);
        if (!includeEvents) {
            builder.excludeTypes(List.of(MessageType.EVENT));
        }
        return messageStore.scan(builder.build());
    }

    public Optional<Message> findByCorrelationId(String correlationId) {
        return Message.find("correlationId", correlationId).firstResultOptional();
    }

    /** Returns all messages with the given correlation ID ordered by id ascending. */
    public List<Message> findAllByCorrelationId(String correlationId) {
        return Message.<Message> find("correlationId = ?1 ORDER BY id ASC", correlationId).list();
    }

    /**
     * Find a RESPONSE message in the given channel with the given correlation ID.
     * Used by wait_for_reply to detect when a matching response has arrived.
     */
    @Transactional
    public Optional<Message> findResponseByCorrelationId(UUID channelId, String correlationId) {
        return Message.find(
                "channelId = ?1 AND messageType = ?2 AND correlationId = ?3",
                channelId, MessageType.RESPONSE, correlationId)
                .firstResultOptional();
    }

    /**
     * Find a DONE message in the given channel with the given correlation ID.
     * Used by wait_for_reply to detect when a COMMAND obligation has been discharged.
     */
    @Transactional
    public Optional<Message> findDoneByCorrelationId(UUID channelId, String correlationId) {
        return Message.find(
                "channelId = ?1 AND messageType = ?2 AND correlationId = ?3",
                channelId, MessageType.DONE, correlationId)
                .firstResultOptional();
    }

}
