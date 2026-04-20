package io.quarkiverse.qhorus.testing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkiverse.qhorus.runtime.instance.Instance;
import io.quarkiverse.qhorus.runtime.store.ReactiveInstanceStore;
import io.quarkiverse.qhorus.runtime.store.query.InstanceQuery;
import io.smallrye.mutiny.Uni;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryReactiveInstanceStore implements ReactiveInstanceStore {

    private final InMemoryInstanceStore delegate = new InMemoryInstanceStore();

    @Override
    public Uni<Instance> put(Instance instance) {
        return Uni.createFrom().item(() -> delegate.put(instance));
    }

    @Override
    public Uni<Optional<Instance>> find(UUID id) {
        return Uni.createFrom().item(() -> delegate.find(id));
    }

    @Override
    public Uni<Optional<Instance>> findByInstanceId(String instanceId) {
        return Uni.createFrom().item(() -> delegate.findByInstanceId(instanceId));
    }

    @Override
    public Uni<List<Instance>> scan(InstanceQuery query) {
        return Uni.createFrom().item(() -> delegate.scan(query));
    }

    @Override
    public Uni<Void> putCapabilities(UUID instanceId, List<String> tags) {
        return Uni.createFrom().voidItem().invoke(() -> delegate.putCapabilities(instanceId, tags));
    }

    @Override
    public Uni<Void> deleteCapabilities(UUID instanceId) {
        return Uni.createFrom().voidItem().invoke(() -> delegate.deleteCapabilities(instanceId));
    }

    @Override
    public Uni<List<String>> findCapabilities(UUID instanceId) {
        return Uni.createFrom().item(() -> delegate.findCapabilities(instanceId));
    }

    @Override
    public Uni<Void> delete(UUID id) {
        return Uni.createFrom().voidItem().invoke(() -> delegate.delete(id));
    }

    public void clear() {
        delegate.clear();
    }
}
