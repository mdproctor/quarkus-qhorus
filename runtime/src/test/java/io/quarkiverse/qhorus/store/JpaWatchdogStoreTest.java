package io.quarkiverse.qhorus.store;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.api.WatchdogEnabledProfile;
import io.quarkiverse.qhorus.runtime.store.WatchdogStore;
import io.quarkiverse.qhorus.runtime.store.query.WatchdogQuery;
import io.quarkiverse.qhorus.runtime.watchdog.Watchdog;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(WatchdogEnabledProfile.class)
class JpaWatchdogStoreTest {

    @Inject
    WatchdogStore watchdogStore;

    private Watchdog buildWatchdog(String conditionType) {
        Watchdog w = new Watchdog();
        w.conditionType = conditionType;
        w.targetName = "test-target-" + UUID.randomUUID();
        w.notificationChannel = "alerts";
        w.thresholdSeconds = 300;
        w.createdBy = "test-agent";
        return w;
    }

    @Test
    @TestTransaction
    void put_persistsWatchdogAndAssignsId() {
        Watchdog w = buildWatchdog("CHANNEL_IDLE");

        Watchdog saved = watchdogStore.put(w);

        assertNotNull(saved.id);
        assertEquals("CHANNEL_IDLE", saved.conditionType);
    }

    @Test
    @TestTransaction
    void find_returnsWatchdog_whenExists() {
        Watchdog w = buildWatchdog("BARRIER_STUCK");
        watchdogStore.put(w);

        Optional<Watchdog> found = watchdogStore.find(w.id);

        assertTrue(found.isPresent());
        assertEquals(w.id, found.get().id);
    }

    @Test
    @TestTransaction
    void find_returnsEmpty_whenNotFound() {
        assertTrue(watchdogStore.find(UUID.randomUUID()).isEmpty());
    }

    @Test
    @TestTransaction
    void scan_all_returnsAllWatchdogs() {
        String suffix = UUID.randomUUID().toString();
        Watchdog w1 = buildWatchdog("QUEUE_DEPTH");
        w1.targetName = "target-a-" + suffix;
        watchdogStore.put(w1);

        Watchdog w2 = buildWatchdog("AGENT_STALE");
        w2.targetName = "target-b-" + suffix;
        watchdogStore.put(w2);

        List<Watchdog> results = watchdogStore.scan(WatchdogQuery.all());

        assertTrue(results.stream().anyMatch(wd -> wd.id.equals(w1.id)));
        assertTrue(results.stream().anyMatch(wd -> wd.id.equals(w2.id)));
    }

    @Test
    @TestTransaction
    void scan_byConditionType_returnsMatchingOnly() {
        String suffix = UUID.randomUUID().toString();
        Watchdog idle = buildWatchdog("CHANNEL_IDLE");
        idle.targetName = "idle-target-" + suffix;
        watchdogStore.put(idle);

        Watchdog stale = buildWatchdog("AGENT_STALE");
        stale.targetName = "stale-target-" + suffix;
        watchdogStore.put(stale);

        List<Watchdog> results = watchdogStore.scan(WatchdogQuery.byConditionType("CHANNEL_IDLE"));

        assertTrue(results.stream().anyMatch(wd -> wd.id.equals(idle.id)));
        assertTrue(results.stream().noneMatch(wd -> wd.id.equals(stale.id)));
    }

    @Test
    @TestTransaction
    void delete_removesWatchdog() {
        Watchdog w = buildWatchdog("APPROVAL_PENDING");
        watchdogStore.put(w);

        watchdogStore.delete(w.id);

        assertTrue(watchdogStore.find(w.id).isEmpty());
    }
}
