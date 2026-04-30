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

class InMemoryDataStoreTest extends DataStoreContractTest {
    private final InMemoryDataStore store = new InMemoryDataStore();

    @Override
    protected SharedData put(SharedData d) {
        return store.put(d);
    }

    @Override
    protected Optional<SharedData> find(UUID id) {
        return store.find(id);
    }

    @Override
    protected Optional<SharedData> findByKey(String key) {
        return store.findByKey(key);
    }

    @Override
    protected List<SharedData> scan(DataQuery q) {
        return store.scan(q);
    }

    @Override
    protected ArtefactClaim putClaim(ArtefactClaim c) {
        return store.putClaim(c);
    }

    @Override
    protected void deleteClaim(UUID artefactId, UUID instanceId) {
        store.deleteClaim(artefactId, instanceId);
    }

    @Override
    protected int countClaims(UUID artefactId) {
        return store.countClaims(artefactId);
    }

    @Override
    protected void reset() {
        store.clear();
    }

    @Test
    void scan_completeOnly_returnsOnlyComplete() {
        store.put(data("k1-" + UUID.randomUUID()));
        SharedData incomplete = data("k2-" + UUID.randomUUID());
        incomplete.complete = false;
        store.put(incomplete);

        List<SharedData> results = store.scan(DataQuery.completeOnly());
        assertTrue(results.stream().allMatch(d -> d.complete));
        assertTrue(results.stream().noneMatch(d -> d.key.equals(incomplete.key)));
    }

    @Test
    void deleteClaim_doesNotRemoveOtherArtefactsClaims() {
        SharedData d1 = store.put(data("da-" + UUID.randomUUID()));
        SharedData d2 = store.put(data("db-" + UUID.randomUUID()));
        UUID instanceId = UUID.randomUUID();

        ArtefactClaim c1 = new ArtefactClaim();
        c1.artefactId = d1.id;
        c1.instanceId = instanceId;
        store.putClaim(c1);

        ArtefactClaim c2 = new ArtefactClaim();
        c2.artefactId = d2.id;
        c2.instanceId = instanceId;
        store.putClaim(c2);

        store.deleteClaim(d1.id, instanceId);

        assertEquals(0, store.countClaims(d1.id));
        assertEquals(1, store.countClaims(d2.id));
    }

    @Test
    void delete_removesDataAndItsArtefactClaims() {
        SharedData d = store.put(data("del-" + UUID.randomUUID()));

        ArtefactClaim c = new ArtefactClaim();
        c.artefactId = d.id;
        c.instanceId = UUID.randomUUID();
        store.putClaim(c);

        store.delete(d.id);

        assertTrue(store.find(d.id).isEmpty());
        assertEquals(0, store.countClaims(d.id));
    }
}
