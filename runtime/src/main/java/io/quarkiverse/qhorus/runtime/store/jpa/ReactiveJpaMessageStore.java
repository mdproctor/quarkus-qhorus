package io.quarkiverse.qhorus.runtime.store.jpa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.store.ReactiveMessageStore;
import io.quarkiverse.qhorus.runtime.store.query.MessageQuery;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;

@Alternative
@ApplicationScoped
public class ReactiveJpaMessageStore implements ReactiveMessageStore {

    @Inject
    MessageReactivePanacheRepo repo;

    @Override
    @WithTransaction
    public Uni<Message> put(Message message) {
        return repo.persist(message);
    }

    @Override
    public Uni<Optional<Message>> find(Long id) {
        return repo.findById(id).map(Optional::ofNullable);
    }

    @Override
    public Uni<List<Message>> scan(MessageQuery q) {
        StringBuilder jpql = new StringBuilder("FROM Message WHERE 1=1");
        List<Object> params = new ArrayList<>();
        int idx = 1;

        if (q.channelId() != null) {
            jpql.append(" AND channelId = ?").append(idx++);
            params.add(q.channelId());
        }
        if (q.afterId() != null) {
            jpql.append(" AND id > ?").append(idx++);
            params.add(q.afterId());
        }
        if (q.sender() != null) {
            jpql.append(" AND sender = ?").append(idx++);
            params.add(q.sender());
        }
        if (q.target() != null) {
            jpql.append(" AND target = ?").append(idx++);
            params.add(q.target());
        }
        if (q.inReplyTo() != null) {
            jpql.append(" AND inReplyTo = ?").append(idx++);
            params.add(q.inReplyTo());
        }
        if (q.excludeTypes() != null && !q.excludeTypes().isEmpty()) {
            jpql.append(" AND messageType NOT IN ?").append(idx++);
            params.add(q.excludeTypes());
        }
        if (q.contentPattern() != null) {
            jpql.append(" AND LOWER(content) LIKE ?").append(idx++);
            params.add("%" + q.contentPattern().toLowerCase() + "%");
        }
        jpql.append(" ORDER BY id ASC");

        return repo.list(jpql.toString(), params.toArray())
                .map(results -> q.limit() != null && results.size() > q.limit()
                        ? results.subList(0, q.limit())
                        : results);
    }

    @Override
    @WithTransaction
    public Uni<Void> deleteAll(UUID channelId) {
        return repo.delete("channelId", channelId).replaceWithVoid();
    }

    @Override
    @WithTransaction
    public Uni<Void> delete(Long id) {
        return repo.deleteById(id).replaceWithVoid();
    }

    @Override
    public Uni<Integer> countByChannel(UUID channelId) {
        return repo.count("channelId", channelId).map(Long::intValue);
    }

    @Override
    public Uni<Map<UUID, Long>> countAllByChannel() {
        return repo.getSession()
                .flatMap(session -> session
                        .createQuery(
                                "SELECT m.channelId, COUNT(m) FROM Message m GROUP BY m.channelId",
                                Object[].class)
                        .getResultList())
                .map(rows -> rows.stream()
                        .collect(Collectors.toMap(r -> (UUID) r[0], r -> (Long) r[1])));
    }

    @Override
    public Uni<List<String>> distinctSendersByChannel(UUID channelId, MessageType excludedType) {
        return repo.list("channelId = ?1 AND messageType != ?2", channelId, excludedType)
                .map(msgs -> msgs.stream()
                        .map(m -> m.sender)
                        .filter(s -> s != null && !s.isBlank())
                        .distinct()
                        .sorted()
                        .toList());
    }
}
