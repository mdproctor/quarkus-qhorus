package io.quarkiverse.qhorus.store.reactive;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.data.ArtefactClaim;
import io.quarkiverse.qhorus.runtime.data.SharedData;
import io.quarkiverse.qhorus.runtime.store.ReactiveDataStore;
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
class ReactiveJpaDataStoreTest {

    @Inject
    ReactiveDataStore store;

    @Test
    @RunOnVertxContext
    void put_assignsIdAndReturns(UniAsserter asserter) {
        SharedData data = sharedData("rx-key-" + UUID.randomUUID());
        asserter.assertThat(
                () -> Panache.withTransaction(() -> store.put(data)),
                saved -> assertNotNull(saved.id));
    }

    @Test
    @RunOnVertxContext
    void findByKey_returnsData_whenExists(UniAsserter asserter) {
        SharedData data = sharedData("rx-lookup-" + UUID.randomUUID());
        asserter
                .execute(() -> Panache.withTransaction(() -> store.put(data)))
                .assertThat(
                        () -> store.findByKey(data.key),
                        opt -> assertTrue(opt.isPresent()));
    }

    @Test
    @RunOnVertxContext
    void putClaim_andCountClaims(UniAsserter asserter) {
        SharedData data = sharedData("rx-claim-" + UUID.randomUUID());
        UUID instanceId = UUID.randomUUID();
        asserter
                .execute(() -> Panache.withTransaction(() -> store.put(data)))
                .execute(() -> {
                    ArtefactClaim claim = new ArtefactClaim();
                    claim.artefactId = data.id;
                    claim.instanceId = instanceId;
                    return Panache.withTransaction(() -> store.putClaim(claim));
                })
                .assertThat(
                        () -> store.countClaims(data.id),
                        count -> assertEquals(1, count));
    }

    private SharedData sharedData(String key) {
        SharedData d = new SharedData();
        d.key = key;
        d.createdBy = "test-rx";
        return d;
    }
}
