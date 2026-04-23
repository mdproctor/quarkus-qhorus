package io.quarkiverse.qhorus.testing;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.store.ChannelStore;
import io.quarkiverse.qhorus.runtime.store.query.ChannelQuery;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryChannelStore implements ChannelStore {

    private final Map<UUID, Channel> store = new LinkedHashMap<>();

    @Override
    public Channel put(Channel channel) {
        Instant now = Instant.now();
        if (channel.id == null) {
            channel.id = UUID.randomUUID();
        }
        if (channel.createdAt == null) {
            channel.createdAt = now;
        }
        if (channel.lastActivityAt == null) {
            channel.lastActivityAt = now;
        }
        store.put(channel.id, channel);
        return channel;
    }

    @Override
    public Optional<Channel> find(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<Channel> findByName(String name) {
        return store.values().stream()
                .filter(c -> name.equals(c.name))
                .findFirst();
    }

    @Override
    public List<Channel> scan(ChannelQuery query) {
        return store.values().stream()
                .filter(query::matches)
                .toList();
    }

    @Override
    public void delete(UUID id) {
        store.remove(id);
    }

    /** Call in @BeforeEach for test isolation. */
    public void clear() {
        store.clear();
    }
}
