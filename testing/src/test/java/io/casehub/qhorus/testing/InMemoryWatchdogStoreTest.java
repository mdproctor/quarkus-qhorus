package io.casehub.qhorus.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.store.query.WatchdogQuery;
import io.casehub.qhorus.runtime.watchdog.Watchdog;
import io.casehub.qhorus.testing.contract.WatchdogStoreContractTest;

class InMemoryWatchdogStoreTest extends WatchdogStoreContractTest {
    private final InMemoryWatchdogStore store = new InMemoryWatchdogStore();

    @Override
    protected Watchdog put(Watchdog w) {
        return store.put(w);
    }

    @Override
    protected Optional<Watchdog> find(UUID id) {
        return store.find(id);
    }

    @Override
    protected List<Watchdog> scan(WatchdogQuery q) {
        return store.scan(q);
    }

    @Override
    protected void delete(UUID id) {
        store.delete(id);
    }

    @Override
    protected void reset() {
        store.clear();
    }

    @Test
    void scan_byConditionType_returnsOnlyMatching() {
        store.put(watchdog("BARRIER_STUCK", "alerts"));
        store.put(watchdog("BARRIER_STUCK", "alerts"));
        store.put(watchdog("AGENT_STALE", "ops"));

        List<Watchdog> results = store.scan(WatchdogQuery.byConditionType("BARRIER_STUCK"));
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(w -> "BARRIER_STUCK".equals(w.conditionType)));
    }

    @Test
    void put_updatesExistingEntry_whenSameId() {
        Watchdog w = watchdog("BARRIER_STUCK", "alerts");
        store.put(w);

        w.notificationChannel = "updated-alerts";
        store.put(w);

        assertEquals("updated-alerts", store.find(w.id).get().notificationChannel);
        assertEquals(1, store.scan(WatchdogQuery.all()).size());
    }
}
