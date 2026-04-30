package io.casehub.qhorus.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.instance.Instance;
import io.casehub.qhorus.runtime.store.query.InstanceQuery;
import io.casehub.qhorus.testing.contract.InstanceStoreContractTest;

class InMemoryReactiveInstanceStoreTest extends InstanceStoreContractTest {
    private final InMemoryReactiveInstanceStore store = new InMemoryReactiveInstanceStore();

    @Override
    protected Instance put(Instance i) {
        return store.put(i).await().indefinitely();
    }

    @Override
    protected Optional<Instance> find(UUID id) {
        return store.find(id).await().indefinitely();
    }

    @Override
    protected Optional<Instance> findByInstanceId(String instanceId) {
        return store.findByInstanceId(instanceId).await().indefinitely();
    }

    @Override
    protected List<Instance> scan(InstanceQuery q) {
        return store.scan(q).await().indefinitely();
    }

    @Override
    protected void reset() {
        store.clear();
    }

    @Test
    void scan_byCapability_returnsMatchingInstances() {
        Instance i1 = instance("rx-cap-a-" + UUID.randomUUID());
        store.put(i1).await().indefinitely();
        store.putCapabilities(i1.id, List.of("summarize", "translate")).await().indefinitely();

        Instance i2 = instance("rx-cap-b-" + UUID.randomUUID());
        store.put(i2).await().indefinitely();
        store.putCapabilities(i2.id, List.of("translate")).await().indefinitely();

        List<Instance> results = store.scan(InstanceQuery.byCapability("summarize")).await().indefinitely();
        assertEquals(1, results.size());
        assertEquals(i1.instanceId, results.get(0).instanceId);
    }

    @Test
    void scan_staleOlderThan_returnsOnlyStale() {
        Instance fresh = instance("rx-fresh-" + UUID.randomUUID());
        fresh.lastSeen = Instant.now();
        store.put(fresh).await().indefinitely();

        Instance stale = instance("rx-stale-" + UUID.randomUUID());
        stale.lastSeen = Instant.now().minusSeconds(3600);
        store.put(stale).await().indefinitely();

        Instant threshold = Instant.now().minusSeconds(1800);
        List<Instance> results = store.scan(InstanceQuery.staleOlderThan(threshold)).await().indefinitely();
        assertEquals(1, results.size());
        assertEquals(stale.instanceId, results.get(0).instanceId);
    }

    @Test
    void putCapabilities_replacesExistingList() {
        Instance i = instance("rx-cap-replace-" + UUID.randomUUID());
        store.put(i).await().indefinitely();

        store.putCapabilities(i.id, List.of("a", "b")).await().indefinitely();
        store.putCapabilities(i.id, List.of("c")).await().indefinitely();

        assertEquals(List.of("c"), store.findCapabilities(i.id).await().indefinitely());
    }

    @Test
    void deleteCapabilities_removesAll() {
        Instance i = instance("rx-cap-del-" + UUID.randomUUID());
        store.put(i).await().indefinitely();
        store.putCapabilities(i.id, List.of("a", "b")).await().indefinitely();
        store.deleteCapabilities(i.id).await().indefinitely();
        assertTrue(store.findCapabilities(i.id).await().indefinitely().isEmpty());
    }
}
