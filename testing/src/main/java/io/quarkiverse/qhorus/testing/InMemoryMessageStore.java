package io.quarkiverse.qhorus.testing;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.message.MessageType;
import io.quarkiverse.qhorus.runtime.store.MessageStore;
import io.quarkiverse.qhorus.runtime.store.query.MessageQuery;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryMessageStore implements MessageStore {

    private final Map<Long, Message> store = new LinkedHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(1);

    @Override
    public Message put(Message message) {
        if (message.id == null) {
            message.id = idCounter.getAndIncrement();
        }
        if (message.createdAt == null) {
            message.createdAt = Instant.now();
        }
        store.put(message.id, message);
        return message;
    }

    @Override
    public Optional<Message> find(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Message> scan(MessageQuery query) {
        Stream<Message> stream = store.values().stream()
                .filter(query::matches);

        List<Message> results = stream.toList();

        if (query.limit() != null && results.size() > query.limit()) {
            return results.subList(0, query.limit());
        }
        return results;
    }

    @Override
    public void deleteAll(UUID channelId) {
        store.values().removeIf(m -> channelId.equals(m.channelId));
    }

    @Override
    public void delete(Long id) {
        store.remove(id);
    }

    @Override
    public int countByChannel(UUID channelId) {
        return (int) store.values().stream()
                .filter(m -> channelId.equals(m.channelId))
                .count();
    }

    @Override
    public Map<UUID, Long> countAllByChannel() {
        return store.values().stream()
                .collect(Collectors.groupingBy(m -> m.channelId, Collectors.counting()));
    }

    @Override
    public List<String> distinctSendersByChannel(UUID channelId, MessageType excludedType) {
        return store.values().stream()
                .filter(m -> channelId.equals(m.channelId) && m.messageType != excludedType)
                .map(m -> m.sender)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    /** Call in @BeforeEach for test isolation. */
    public void clear() {
        store.clear();
        idCounter.set(1);
    }
}
