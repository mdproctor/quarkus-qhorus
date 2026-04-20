package io.quarkiverse.qhorus.testing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkiverse.qhorus.runtime.data.ArtefactClaim;
import io.quarkiverse.qhorus.runtime.data.SharedData;
import io.quarkiverse.qhorus.runtime.store.ReactiveDataStore;
import io.quarkiverse.qhorus.runtime.store.query.DataQuery;
import io.smallrye.mutiny.Uni;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryReactiveDataStore implements ReactiveDataStore {

    private final InMemoryDataStore delegate = new InMemoryDataStore();

    @Override
    public Uni<SharedData> put(SharedData data) {
        return Uni.createFrom().item(() -> delegate.put(data));
    }

    @Override
    public Uni<Optional<SharedData>> find(UUID id) {
        return Uni.createFrom().item(() -> delegate.find(id));
    }

    @Override
    public Uni<Optional<SharedData>> findByKey(String key) {
        return Uni.createFrom().item(() -> delegate.findByKey(key));
    }

    @Override
    public Uni<List<SharedData>> scan(DataQuery query) {
        return Uni.createFrom().item(() -> delegate.scan(query));
    }

    @Override
    public Uni<ArtefactClaim> putClaim(ArtefactClaim claim) {
        return Uni.createFrom().item(() -> delegate.putClaim(claim));
    }

    @Override
    public Uni<Void> deleteClaim(UUID artefactId, UUID instanceId) {
        return Uni.createFrom().voidItem().invoke(() -> delegate.deleteClaim(artefactId, instanceId));
    }

    @Override
    public Uni<Integer> countClaims(UUID artefactId) {
        return Uni.createFrom().item(() -> delegate.countClaims(artefactId));
    }

    @Override
    public Uni<Void> delete(UUID id) {
        return Uni.createFrom().voidItem().invoke(() -> delegate.delete(id));
    }

    public void clear() {
        delegate.clear();
    }
}
