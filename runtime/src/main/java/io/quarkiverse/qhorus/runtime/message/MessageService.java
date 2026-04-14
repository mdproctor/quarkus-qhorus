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
        Message message = new Message();
        message.channelId = channelId;
        message.sender = sender;
        message.messageType = type;
        message.content = content;
        message.correlationId = correlationId;
        message.inReplyTo = inReplyTo;
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

    public Optional<Message> findByCorrelationId(String correlationId) {
        return Message.find("correlationId", correlationId).firstResultOptional();
    }
}
