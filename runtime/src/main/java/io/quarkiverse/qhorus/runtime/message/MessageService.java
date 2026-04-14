package io.quarkiverse.qhorus.runtime.message;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.qhorus.runtime.channel.ChannelService;

@ApplicationScoped
public class MessageService {

    @Inject
    ChannelService channelService;

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
        Message message = new Message();
        message.channelId = channelId;
        message.sender = sender;
        message.messageType = type;
        message.content = content;
        message.correlationId = correlationId;
        message.inReplyTo = inReplyTo;
        message.artefactRefs = artefactRefs;
        message.target = target;
        message.persist();

        if (inReplyTo != null) {
            Message parent = Message.findById(inReplyTo);
            if (parent != null) {
                parent.replyCount++;
            }
        }

        channelService.updateLastActivity(channelId);

        return message;
    }

    public Optional<Message> findById(Long id) {
        return Message.findByIdOptional(id);
    }

    /**
     * Returns messages in channel posted after {@code afterId}, excluding EVENT type
     * (observer-only — not delivered to agent context).
     */
    public List<Message> pollAfter(UUID channelId, Long afterId, int limit) {
        return Message.find(
                "channelId = ?1 AND id > ?2 AND messageType != ?3 ORDER BY id ASC",
                channelId, afterId, MessageType.EVENT)
                .page(0, limit)
                .list();
    }

    /**
     * Like {@link #pollAfter} but filters by sender in the query — avoids the
     * post-limit filtering bug where messages are lost when limit < total results.
     */
    public List<Message> pollAfterBySender(UUID channelId, Long afterId, int limit, String sender) {
        return Message.find(
                "channelId = ?1 AND id > ?2 AND messageType != ?3 AND sender = ?4 ORDER BY id ASC",
                channelId, afterId, MessageType.EVENT, sender)
                .page(0, limit)
                .list();
    }

    public Optional<Message> findByCorrelationId(String correlationId) {
        return Message.find("correlationId", correlationId).firstResultOptional();
    }

    /** Returns all messages with the given correlation ID ordered by id ascending. */
    public List<Message> findAllByCorrelationId(String correlationId) {
        return Message.<Message> find("correlationId = ?1 ORDER BY id ASC", correlationId).list();
    }

    /**
     * Register or update a PendingReply row for the given correlation ID.
     * Upserts — if a row already exists, updates expiresAt rather than inserting a duplicate.
     */
    @Transactional
    public void registerPendingReply(String correlationId, UUID channelId, UUID instanceId,
            java.time.Instant expiresAt) {
        PendingReply existing = PendingReply.<PendingReply> find("correlationId", correlationId)
                .firstResult();
        if (existing != null) {
            existing.expiresAt = expiresAt;
        } else {
            PendingReply pr = new PendingReply();
            pr.correlationId = correlationId;
            pr.channelId = channelId;
            pr.instanceId = instanceId;
            pr.expiresAt = expiresAt;
            pr.persist();
        }
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

    /** Delete the PendingReply row for the given correlation ID (cleanup on match or timeout). */
    @Transactional
    public void deletePendingReply(String correlationId) {
        PendingReply.delete("correlationId", correlationId);
    }
}
