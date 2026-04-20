package io.quarkiverse.qhorus.testing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.store.ReactiveMessageStore;
import io.quarkiverse.qhorus.runtime.store.query.MessageQuery;
import io.smallrye.mutiny.Uni;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryReactiveMessageStore implements ReactiveMessageStore {

    private final InMemoryMessageStore delegate = new InMemoryMessageStore();

    @Override
    public Uni<Message> put(Message message) {
        return Uni.createFrom().item(() -> delegate.put(message));
    }

    @Override
    public Uni<Optional<Message>> find(Long id) {
        return Uni.createFrom().item(() -> delegate.find(id));
    }

    @Override
    public Uni<List<Message>> scan(MessageQuery query) {
        return Uni.createFrom().item(() -> delegate.scan(query));
    }

    @Override
    public Uni<Void> deleteAll(UUID channelId) {
        return Uni.createFrom().voidItem().invoke(() -> delegate.deleteAll(channelId));
    }

    @Override
    public Uni<Void> delete(Long id) {
        return Uni.createFrom().voidItem().invoke(() -> delegate.delete(id));
    }

    @Override
    public Uni<Integer> countByChannel(UUID channelId) {
        return Uni.createFrom().item(() -> delegate.countByChannel(channelId));
    }

    public void clear() {
        delegate.clear();
    }
}
