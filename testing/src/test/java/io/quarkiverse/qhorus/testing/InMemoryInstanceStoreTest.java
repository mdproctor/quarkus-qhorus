package io.quarkiverse.qhorus.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.instance.Instance;
import io.quarkiverse.qhorus.runtime.store.query.InstanceQuery;
import io.quarkiverse.qhorus.testing.contract.InstanceStoreContractTest;

class InMemoryInstanceStoreTest extends InstanceStoreContractTest {
    private final InMemoryInstanceStore store = new InMemoryInstanceStore();

    @Override
    protected Instance put(Instance i) {
        return store.put(i);
    }

    @Override
    protected Optional<Instance> find(UUID id) {
        return store.find(id);
    }

    @Override
    protected Optional<Instance> findByInstanceId(String instanceId) {
        return store.findByInstanceId(instanceId);
    }

    @Override
    protected List<Instance> scan(InstanceQuery q) {
        return store.scan(q);
    }

    @Override
    protected void reset() {
        store.clear();
    }

    @Test
    void scan_byCapability_returnsMatchingInstances() {
        Instance i1 = instance("cap-a-" + UUID.randomUUID());
        store.put(i1);
        store.putCapabilities(i1.id, List.of("summarize", "translate"));

        Instance i2 = instance("cap-b-" + UUID.randomUUID());
        store.put(i2);
        store.putCapabilities(i2.id, List.of("translate"));

        List<Instance> results = store.scan(InstanceQuery.byCapability("summarize"));
        assertEquals(1, results.size());
        assertEquals(i1.instanceId, results.get(0).instanceId);
    }

    @Test
    void scan_staleOlderThan_returnsOnlyStale() {
        Instance fresh = instance("fresh-" + UUID.randomUUID());
        fresh.lastSeen = Instant.now();
        store.put(fresh);

        Instance stale = instance("stale-" + UUID.randomUUID());
        stale.lastSeen = Instant.now().minusSeconds(3600);
        store.put(stale);

        Instant threshold = Instant.now().minusSeconds(1800);
        List<Instance> results = store.scan(InstanceQuery.staleOlderThan(threshold));
        assertEquals(1, results.size());
        assertEquals(stale.instanceId, results.get(0).instanceId);
    }

    @Test
    void putCapabilities_replacesExistingList() {
        Instance i = instance("cap-replace-" + UUID.randomUUID());
        store.put(i);

        store.putCapabilities(i.id, List.of("a", "b"));
        store.putCapabilities(i.id, List.of("c"));

        assertEquals(List.of("c"), store.findCapabilities(i.id));
    }

    @Test
    void deleteCapabilities_removesAll() {
        Instance i = instance("cap-del-" + UUID.randomUUID());
        store.put(i);
        store.putCapabilities(i.id, List.of("a", "b"));
        store.deleteCapabilities(i.id);
        assertTrue(store.findCapabilities(i.id).isEmpty());
    }
}
