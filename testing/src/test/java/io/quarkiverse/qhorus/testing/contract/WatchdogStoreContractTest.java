package io.quarkiverse.qhorus.testing.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.store.query.WatchdogQuery;
import io.quarkiverse.qhorus.runtime.watchdog.Watchdog;

public abstract class WatchdogStoreContractTest {

    protected abstract Watchdog put(Watchdog watchdog);

    protected abstract Optional<Watchdog> find(UUID id);

    protected abstract List<Watchdog> scan(WatchdogQuery query);

    protected abstract void delete(UUID id);

    protected abstract void reset();

    @BeforeEach
    void beforeEach() {
        reset();
    }

    @Test
    void put_assignsId_whenNull() {
        assertNotNull(put(watchdog("CHANNEL_IDLE", "alert")).id);
    }

    @Test
    void find_returnsWatchdog_whenPresent() {
        Watchdog saved = put(watchdog("BARRIER_STUCK", "notif"));
        assertTrue(find(saved.id).isPresent());
    }

    @Test
    void find_returnsEmpty_whenAbsent() {
        assertTrue(find(UUID.randomUUID()).isEmpty());
    }

    @Test
    void scan_all_returnsAll() {
        put(watchdog("CHANNEL_IDLE", "ch-a"));
        put(watchdog("QUEUE_DEPTH", "ch-b"));
        assertTrue(scan(WatchdogQuery.all()).size() >= 2);
    }

    @Test
    void delete_removesWatchdog() {
        Watchdog w = put(watchdog("AGENT_STALE", "notif"));
        delete(w.id);
        assertTrue(find(w.id).isEmpty());
    }

    protected Watchdog watchdog(String conditionType, String notificationChannel) {
        Watchdog w = new Watchdog();
        w.conditionType = conditionType;
        w.targetName = "*";
        w.notificationChannel = notificationChannel;
        w.createdBy = "test";
        return w;
    }
}
