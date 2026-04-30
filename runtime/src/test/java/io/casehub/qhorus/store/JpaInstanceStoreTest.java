package io.casehub.qhorus.store;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.instance.Instance;
import io.casehub.qhorus.runtime.store.InstanceStore;
import io.casehub.qhorus.runtime.store.query.InstanceQuery;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class JpaInstanceStoreTest {

    @Inject
    InstanceStore instanceStore;

    private Instance buildInstance(String status) {
        Instance inst = new Instance();
        inst.instanceId = "agent-" + UUID.randomUUID();
        inst.status = status;
        inst.lastSeen = Instant.now();
        return inst;
    }

    @Test
    @TestTransaction
    void put_persistsInstanceAndAssignsId() {
        Instance inst = buildInstance("online");

        Instance saved = instanceStore.put(inst);

        assertNotNull(saved.id);
        assertEquals(inst.instanceId, saved.instanceId);
    }

    @Test
    @TestTransaction
    void find_returnsInstance_whenExists() {
        Instance inst = buildInstance("online");
        instanceStore.put(inst);

        Optional<Instance> found = instanceStore.find(inst.id);

        assertTrue(found.isPresent());
        assertEquals(inst.id, found.get().id);
    }

    @Test
    @TestTransaction
    void find_returnsEmpty_whenNotFound() {
        assertTrue(instanceStore.find(UUID.randomUUID()).isEmpty());
    }

    @Test
    @TestTransaction
    void findByInstanceId_returnsInstance_whenExists() {
        Instance inst = buildInstance("online");
        instanceStore.put(inst);

        Optional<Instance> found = instanceStore.findByInstanceId(inst.instanceId);

        assertTrue(found.isPresent());
        assertEquals(inst.instanceId, found.get().instanceId);
    }

    @Test
    @TestTransaction
    void findByInstanceId_returnsEmpty_whenNotFound() {
        assertTrue(instanceStore.findByInstanceId("no-such-agent-" + UUID.randomUUID()).isEmpty());
    }

    @Test
    @TestTransaction
    void scan_online_returnsOnlyOnlineInstances() {
        String suffix = UUID.randomUUID().toString();
        Instance online = buildInstance("online");
        online.instanceId = "online-" + suffix;
        instanceStore.put(online);

        Instance stale = buildInstance("stale");
        stale.instanceId = "stale-" + suffix;
        instanceStore.put(stale);

        List<Instance> results = instanceStore.scan(InstanceQuery.online());

        assertTrue(results.stream().anyMatch(i -> i.instanceId.equals(online.instanceId)));
        assertTrue(results.stream().noneMatch(i -> i.instanceId.equals(stale.instanceId)));
    }

    @Test
    @TestTransaction
    void scan_staleOlderThan_returnsOnlyOldInstances() {
        String suffix = UUID.randomUUID().toString();

        Instance recent = buildInstance("online");
        recent.instanceId = "recent-" + suffix;
        recent.lastSeen = Instant.now();
        instanceStore.put(recent);

        Instance old = buildInstance("online");
        old.instanceId = "old-" + suffix;
        old.lastSeen = Instant.now().minusSeconds(3600);
        instanceStore.put(old);

        Instant threshold = Instant.now().minusSeconds(1800);
        List<Instance> results = instanceStore.scan(InstanceQuery.staleOlderThan(threshold));

        assertTrue(results.stream().anyMatch(i -> i.instanceId.equals(old.instanceId)));
        assertTrue(results.stream().noneMatch(i -> i.instanceId.equals(recent.instanceId)));
    }

    @Test
    @TestTransaction
    void putCapabilities_andFindCapabilities_roundTrip() {
        Instance inst = buildInstance("online");
        instanceStore.put(inst);

        instanceStore.putCapabilities(inst.id, List.of("code-review", "summarise"));
        List<String> caps = instanceStore.findCapabilities(inst.id);

        assertEquals(2, caps.size());
        assertTrue(caps.contains("code-review"));
        assertTrue(caps.contains("summarise"));
    }

    @Test
    @TestTransaction
    void putCapabilities_replacesExisting() {
        Instance inst = buildInstance("online");
        instanceStore.put(inst);

        instanceStore.putCapabilities(inst.id, List.of("old-cap"));
        instanceStore.putCapabilities(inst.id, List.of("new-cap-a", "new-cap-b"));

        List<String> caps = instanceStore.findCapabilities(inst.id);

        assertEquals(2, caps.size());
        assertFalse(caps.contains("old-cap"));
        assertTrue(caps.contains("new-cap-a"));
        assertTrue(caps.contains("new-cap-b"));
    }

    @Test
    @TestTransaction
    void scan_byCapability_returnsMatchingInstances() {
        String suffix = UUID.randomUUID().toString();
        String tag = "unique-cap-" + suffix;

        Instance withCap = buildInstance("online");
        withCap.instanceId = "with-cap-" + suffix;
        instanceStore.put(withCap);
        instanceStore.putCapabilities(withCap.id, List.of(tag));

        Instance noCap = buildInstance("online");
        noCap.instanceId = "no-cap-" + suffix;
        instanceStore.put(noCap);

        List<Instance> results = instanceStore.scan(InstanceQuery.byCapability(tag));

        assertTrue(results.stream().anyMatch(i -> i.id.equals(withCap.id)));
        assertTrue(results.stream().noneMatch(i -> i.id.equals(noCap.id)));
    }

    @Test
    @TestTransaction
    void delete_removesInstance() {
        Instance inst = buildInstance("online");
        instanceStore.put(inst);
        instanceStore.putCapabilities(inst.id, List.of("some-cap"));

        instanceStore.delete(inst.id);

        assertTrue(instanceStore.find(inst.id).isEmpty());
        assertTrue(instanceStore.findCapabilities(inst.id).isEmpty());
    }
}
