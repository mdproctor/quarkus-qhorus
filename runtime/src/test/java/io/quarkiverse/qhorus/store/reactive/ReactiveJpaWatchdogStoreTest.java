package io.quarkiverse.qhorus.store.reactive;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.store.ReactiveWatchdogStore;
import io.quarkiverse.qhorus.runtime.store.query.WatchdogQuery;
import io.quarkiverse.qhorus.runtime.watchdog.Watchdog;
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
class ReactiveJpaWatchdogStoreTest {

    @Inject
    ReactiveWatchdogStore store;

    @Test
    @RunOnVertxContext
    void put_assignsIdAndReturns(UniAsserter asserter) {
        Watchdog w = watchdog("threshold");
        asserter.assertThat(
                () -> Panache.withTransaction(() -> store.put(w)),
                saved -> assertNotNull(saved.id));
    }

    @Test
    @RunOnVertxContext
    void find_returnsEmpty_whenNotFound(UniAsserter asserter) {
        asserter.assertThat(
                () -> store.find(UUID.randomUUID()),
                opt -> assertTrue(opt.isEmpty()));
    }

    @Test
    @RunOnVertxContext
    void scan_byConditionType_returnsMatching(UniAsserter asserter) {
        Watchdog w1 = watchdog("threshold");
        Watchdog w2 = watchdog("pattern");
        asserter
                .execute(() -> Panache.withTransaction(() -> store.put(w1)))
                .execute(() -> Panache.withTransaction(() -> store.put(w2)))
                .assertThat(
                        () -> store.scan(WatchdogQuery.byConditionType("threshold")),
                        results -> {
                            assertEquals(1, results.size());
                            assertEquals("threshold", results.get(0).conditionType);
                        });
    }

    @Test
    @RunOnVertxContext
    void delete_removesWatchdog(UniAsserter asserter) {
        Watchdog w = watchdog("del-type");
        asserter
                .execute(() -> Panache.withTransaction(() -> store.put(w)))
                .execute(() -> store.delete(w.id))
                .assertThat(
                        () -> store.find(w.id),
                        opt -> assertTrue(opt.isEmpty()));
    }

    private Watchdog watchdog(String conditionType) {
        Watchdog w = new Watchdog();
        w.conditionType = conditionType;
        w.targetName = "test-target";
        w.notificationChannel = "test-alerts";
        return w;
    }
}
