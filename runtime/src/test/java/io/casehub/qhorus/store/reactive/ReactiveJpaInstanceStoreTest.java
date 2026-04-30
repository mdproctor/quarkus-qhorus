package io.casehub.qhorus.store.reactive;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.instance.Instance;
import io.casehub.qhorus.runtime.store.ReactiveInstanceStore;
import io.casehub.qhorus.runtime.store.query.InstanceQuery;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;

// H2 has no native reactive driver; Quarkus reactive pool requires a native reactive client
// extension (pg, mysql, etc.) or Dev Services (Docker). Enable when a reactive datasource
// is available in the test environment.
@Disabled("Requires reactive datasource — H2 has no reactive driver; run with Dev Services/PostgreSQL")
@QuarkusTest
@TestProfile(ReactiveStoreTestProfile.class)
class ReactiveJpaInstanceStoreTest {

    @Inject
    ReactiveInstanceStore store;

    @Test
    @RunOnVertxContext
    void put_assignsIdAndReturns(UniAsserter asserter) {
        Instance inst = instance("rx-agent-" + UUID.randomUUID());
        asserter.assertThat(
                () -> Panache.withTransaction(() -> store.put(inst)),
                saved -> assertNotNull(saved.id));
    }

    @Test
    @RunOnVertxContext
    void putCapabilities_andFindCapabilities(UniAsserter asserter) {
        Instance inst = instance("cap-rx-" + UUID.randomUUID());
        asserter
                .execute(() -> Panache.withTransaction(() -> store.put(inst)))
                .execute(() -> store.putCapabilities(inst.id, List.of("search", "plan")))
                .assertThat(
                        () -> store.findCapabilities(inst.id),
                        caps -> {
                            assertTrue(caps.contains("search"));
                            assertTrue(caps.contains("plan"));
                        });
    }

    @Test
    @RunOnVertxContext
    void scan_byCapability_returnsMatchingInstances(UniAsserter asserter) {
        Instance a = instance("rx-cap-a-" + UUID.randomUUID());
        Instance b = instance("rx-cap-b-" + UUID.randomUUID());
        asserter
                .execute(() -> Panache.withTransaction(() -> store.put(a)))
                .execute(() -> Panache.withTransaction(() -> store.put(b)))
                .execute(() -> store.putCapabilities(a.id, List.of("review")))
                .assertThat(
                        () -> store.scan(InstanceQuery.byCapability("review")),
                        results -> {
                            assertEquals(1, results.size());
                            assertEquals(a.instanceId, results.get(0).instanceId);
                        });
    }

    private Instance instance(String instanceId) {
        Instance i = new Instance();
        i.instanceId = instanceId;
        i.status = "online";
        return i;
    }
}
