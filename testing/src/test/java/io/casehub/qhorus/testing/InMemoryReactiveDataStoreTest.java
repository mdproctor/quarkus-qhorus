package io.casehub.qhorus.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.data.ArtefactClaim;
import io.casehub.qhorus.runtime.data.SharedData;
import io.casehub.qhorus.runtime.store.query.DataQuery;
import io.casehub.qhorus.testing.contract.DataStoreContractTest;

class InMemoryReactiveDataStoreTest extends DataStoreContractTest {
    private final InMemoryReactiveDataStore store = new InMemoryReactiveDataStore();

    @Override
    protected SharedData put(SharedData d) {
        return store.put(d).await().indefinitely();
    }

    @Override
    protected Optional<SharedData> find(UUID id) {
        return store.find(id).await().indefinitely();
    }

    @Override
    protected Optional<SharedData> findByKey(String key) {
        return store.findByKey(key).await().indefinitely();
    }

    @Override
    protected List<SharedData> scan(DataQuery q) {
        return store.scan(q).await().indefinitely();
    }

    @Override
    protected ArtefactClaim putClaim(ArtefactClaim c) {
        return store.putClaim(c).await().indefinitely();
    }

    @Override
    protected void deleteClaim(UUID artefactId, UUID instanceId) {
        store.deleteClaim(artefactId, instanceId).await().indefinitely();
    }

    @Override
    protected int countClaims(UUID artefactId) {
        return store.countClaims(artefactId).await().indefinitely();
    }

    @Override
    protected void reset() {
        store.clear();
    }

    @Test
    void scan_completeOnly_returnsOnlyComplete() {
        store.put(data("k1-" + UUID.randomUUID())).await().indefinitely();
        SharedData incomplete = data("k2-" + UUID.randomUUID());
        incomplete.complete = false;
        store.put(incomplete).await().indefinitely();

        List<SharedData> results = store.scan(DataQuery.completeOnly()).await().indefinitely();
        assertTrue(results.stream().allMatch(d -> d.complete));
        assertTrue(results.stream().noneMatch(d -> d.key.equals(incomplete.key)));
    }

    @Test
    void deleteClaim_doesNotRemoveOtherArtefactsClaims() {
        SharedData d1 = store.put(data("rx-da-" + UUID.randomUUID())).await().indefinitely();
        SharedData d2 = store.put(data("rx-db-" + UUID.randomUUID())).await().indefinitely();
        UUID instanceId = UUID.randomUUID();

        ArtefactClaim c1 = new ArtefactClaim();
        c1.artefactId = d1.id;
        c1.instanceId = instanceId;
        store.putClaim(c1).await().indefinitely();

        ArtefactClaim c2 = new ArtefactClaim();
        c2.artefactId = d2.id;
        c2.instanceId = instanceId;
        store.putClaim(c2).await().indefinitely();

        store.deleteClaim(d1.id, instanceId).await().indefinitely();

        assertEquals(0, store.countClaims(d1.id).await().indefinitely());
        assertEquals(1, store.countClaims(d2.id).await().indefinitely());
    }

    @Test
    void delete_removesDataAndItsArtefactClaims() {
        SharedData d = store.put(data("rx-del-" + UUID.randomUUID())).await().indefinitely();

        ArtefactClaim c = new ArtefactClaim();
        c.artefactId = d.id;
        c.instanceId = UUID.randomUUID();
        store.putClaim(c).await().indefinitely();

        store.delete(d.id).await().indefinitely();

        assertTrue(store.find(d.id).await().indefinitely().isEmpty());
        assertEquals(0, store.countClaims(d.id).await().indefinitely());
    }
}
