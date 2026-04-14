package io.quarkiverse.qhorus.instance;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.instance.Instance;
import io.quarkiverse.qhorus.runtime.instance.InstanceService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class InstanceServiceTest {

    @Inject
    InstanceService instanceService;

    @Test
    @TestTransaction
    void registerCreatesNewInstance() {
        Instance inst = instanceService.register("alice", "Code review agent",
                List.of("code-review", "java"));

        assertNotNull(inst.id);
        assertEquals("alice", inst.instanceId);
        assertEquals("Code review agent", inst.description);
        assertEquals("online", inst.status);
        assertNotNull(inst.lastSeen);
        assertNotNull(inst.registeredAt);
    }

    @Test
    @TestTransaction
    void registerUpsertsByInstanceId() {
        instanceService.register("bob", "First description", List.of("python"));
        Instance updated = instanceService.register("bob", "Updated description", List.of("python", "ml"));

        assertEquals("bob", updated.instanceId);
        assertEquals("Updated description", updated.description);
        assertEquals("online", updated.status);

        // Should still be one instance, not two
        List<Instance> all = instanceService.listAll();
        assertEquals(1, all.stream().filter(i -> "bob".equals(i.instanceId)).count());
    }

    @Test
    @TestTransaction
    void registerReplacesCapabilityTagsOnUpsert() {
        // First registration: "python" only
        instanceService.register("cap-agent", "Agent", List.of("python"));

        // Re-register with different tags — "python" should be gone, "ml" should be present
        instanceService.register("cap-agent", "Agent", List.of("ml"));

        List<Instance> byPython = instanceService.findByCapability("python");
        List<Instance> byMl = instanceService.findByCapability("ml");

        assertTrue(byPython.isEmpty(),
                "stale 'python' tag should be removed when re-registering with new tags");
        assertEquals(1, byMl.size(),
                "'ml' tag should be present after re-registration");
        assertEquals("cap-agent", byMl.get(0).instanceId);
    }

    @Test
    @TestTransaction
    void registerStoresCapabilityTags() {
        instanceService.register("carol", "Multi-skill agent", List.of("code-review", "test-writing", "docs"));

        List<Instance> byCodeReview = instanceService.findByCapability("code-review");
        List<Instance> byDocs = instanceService.findByCapability("docs");
        List<Instance> byUnknown = instanceService.findByCapability("no-such-capability");

        assertEquals(1, byCodeReview.size());
        assertEquals("carol", byCodeReview.get(0).instanceId);
        assertEquals(1, byDocs.size());
        assertTrue(byUnknown.isEmpty());
    }

    @Test
    @TestTransaction
    void heartbeatUpdatesLastSeen() throws InterruptedException {
        Instance inst = instanceService.register("dave", "Agent", List.of());
        var before = inst.lastSeen;

        Thread.sleep(5);
        instanceService.heartbeat("dave");

        Instance refreshed = instanceService.findByInstanceId("dave").orElseThrow();
        assertTrue(refreshed.lastSeen.isAfter(before),
                "lastSeen should advance after heartbeat");
    }

    @Test
    @TestTransaction
    void findByInstanceIdReturnsEmptyWhenNotFound() {
        assertTrue(instanceService.findByInstanceId("ghost").isEmpty());
    }

    @Test
    @TestTransaction
    void listAllReturnsAllRegisteredInstances() {
        instanceService.register("e1", "Agent E1", List.of());
        instanceService.register("e2", "Agent E2", List.of());

        List<Instance> all = instanceService.listAll();

        assertTrue(all.stream().anyMatch(i -> "e1".equals(i.instanceId)));
        assertTrue(all.stream().anyMatch(i -> "e2".equals(i.instanceId)));
    }

    @Test
    @TestTransaction
    void markStaleChangesStatusForOldInstances() throws InterruptedException {
        instanceService.register("stale-agent", "Will go stale", List.of());

        // Wait longer than the 1-second threshold
        Thread.sleep(1100);
        instanceService.markStaleOlderThan(1);

        // Bulk JPQL update bypasses Hibernate's first-level cache — clear it to see DB state
        Instance.getEntityManager().clear();

        Instance inst = instanceService.findByInstanceId("stale-agent").orElseThrow();
        assertEquals("stale", inst.status,
                "instance not seen for > 1s should be marked stale");
    }

    @Test
    @TestTransaction
    void markStaleDoesNotAffectRecentInstances() throws InterruptedException {
        instanceService.register("fresh-agent", "Recently active", List.of());

        // threshold = 60s, agent was just registered — should NOT be marked stale
        instanceService.markStaleOlderThan(60);
        Instance.getEntityManager().clear();

        Instance inst = instanceService.findByInstanceId("fresh-agent").orElseThrow();
        assertEquals("online", inst.status,
                "recently registered instance should remain online");
    }
}
