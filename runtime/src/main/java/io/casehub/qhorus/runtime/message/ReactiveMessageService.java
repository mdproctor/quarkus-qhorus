package io.casehub.qhorus.runtime.message;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.store.ReactiveChannelStore;
import io.casehub.qhorus.runtime.store.ReactiveMessageStore;
import io.casehub.qhorus.runtime.store.query.MessageQuery;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

@Alternative
@ApplicationScoped
public class ReactiveMessageService {

    @Inject
    ReactiveMessageStore messageStore;

    @Inject
    ReactiveChannelStore channelStore;

    @Inject
    CommitmentService commitmentService;

    public Uni<Message> send(UUID channelId, String sender, MessageType type, String content,
            String correlationId, Long inReplyTo, String artefactRefs, String target) {
        return Panache.withTransaction(() -> {
            Message message = new Message();
            message.channelId = channelId;
            message.sender = sender;
            message.messageType = type;
            message.content = content;
            message.correlationId = correlationId;
            message.inReplyTo = inReplyTo;
            message.artefactRefs = artefactRefs;
            message.target = target;

            return messageStore.put(message)
                    .invoke(m -> {
                        // Trigger commitment state machine for obligation tracking
                        if (m.correlationId != null) {
                            switch (m.messageType) {
                                case QUERY, COMMAND -> commitmentService.open(
                                        m.commitmentId != null ? m.commitmentId : java.util.UUID.randomUUID(),
                                        m.correlationId, m.channelId, m.messageType,
                                        m.sender, m.target, m.deadline);
                                case STATUS -> commitmentService.acknowledge(m.correlationId);
                                case RESPONSE, DONE -> commitmentService.fulfill(m.correlationId);
                                case DECLINE -> commitmentService.decline(m.correlationId);
                                case FAILURE -> commitmentService.fail(m.correlationId);
                                case HANDOFF -> commitmentService.delegate(m.correlationId, m.target);
                                case EVENT -> {
                                    /* no commitment effect */ }
                            }
                        }
                    })
                    .flatMap(m -> inReplyTo != null
                            ? messageStore.find(inReplyTo)
                                    .invoke(opt -> opt.ifPresent(parent -> parent.replyCount++))
                                    .map(ignored -> m)
                            : Uni.createFrom().item(m))
                    .flatMap(m -> channelStore.find(channelId)
                            .invoke(opt -> opt.ifPresent(ch -> ch.lastActivityAt = Instant.now()))
                            .map(ignored -> m));
        });
    }

    public Uni<Optional<Message>> findById(Long id) {
        return messageStore.find(id);
    }

    /**
     * Returns messages in channel posted after {@code afterId}, excluding EVENT type.
     */
    public Uni<List<Message>> pollAfter(UUID channelId, Long afterId, int limit) {
        return messageStore.scan(
                MessageQuery.builder()
                        .channelId(channelId)
                        .afterId(afterId)
                        .limit(limit)
                        .excludeTypes(List.of(MessageType.EVENT))
                        .build());
    }

    /**
     * Like {@link #pollAfter} but filters by sender in the query.
     */
    public Uni<List<Message>> pollAfterBySender(UUID channelId, Long afterId, int limit, String sender) {
        return messageStore.scan(
                MessageQuery.builder()
                        .channelId(channelId)
                        .afterId(afterId)
                        .limit(limit)
                        .excludeTypes(List.of(MessageType.EVENT))
                        .sender(sender)
                        .build());
    }
}
