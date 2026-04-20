package io.quarkiverse.qhorus.testing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.store.ReactiveChannelStore;
import io.quarkiverse.qhorus.runtime.store.query.ChannelQuery;
import io.smallrye.mutiny.Uni;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryReactiveChannelStore implements ReactiveChannelStore {

    private final InMemoryChannelStore delegate = new InMemoryChannelStore();

    @Override
    public Uni<Channel> put(Channel channel) {
        return Uni.createFrom().item(() -> delegate.put(channel));
    }

    @Override
    public Uni<Optional<Channel>> find(UUID id) {
        return Uni.createFrom().item(() -> delegate.find(id));
    }

    @Override
    public Uni<Optional<Channel>> findByName(String name) {
        return Uni.createFrom().item(() -> delegate.findByName(name));
    }

    @Override
    public Uni<List<Channel>> scan(ChannelQuery query) {
        return Uni.createFrom().item(() -> delegate.scan(query));
    }

    @Override
    public Uni<Void> delete(UUID id) {
        return Uni.createFrom().voidItem().invoke(() -> delegate.delete(id));
    }

    public void clear() {
        delegate.clear();
    }
}
