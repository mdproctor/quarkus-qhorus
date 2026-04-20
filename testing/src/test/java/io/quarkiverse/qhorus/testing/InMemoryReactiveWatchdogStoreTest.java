package io.quarkiverse.qhorus.testing;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.store.query.WatchdogQuery;
import io.quarkiverse.qhorus.runtime.watchdog.Watchdog;

class InMemoryReactiveWatchdogStoreTest {

    private final InMemoryReactiveWatchdogStore store = new InMemoryReactiveWatchdogStore();

    @BeforeEach
    void reset() {
        store.clear();
    }

    @Test
    void put_assignsIdAndReturns() {
        Watchdog w = watchdog("threshold");
        Watchdog saved = store.put(w).await().indefinitely();
        assertThat(saved.id).isNotNull();
    }

    @Test
    void find_returnsEmpty_whenNotFound() {
        assertThat(store.find(UUID.randomUUID()).await().indefinitely()).isEmpty();
    }

    @Test
    void scan_byConditionType_returnsMatching() {
        store.put(watchdog("threshold")).await().indefinitely();
        store.put(watchdog("pattern")).await().indefinitely();

        List<Watchdog> results = store.scan(WatchdogQuery.byConditionType("threshold")).await().indefinitely();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).conditionType).isEqualTo("threshold");
    }

    @Test
    void delete_removesWatchdog() {
        Watchdog w = watchdog("del-type");
        store.put(w).await().indefinitely();

        store.delete(w.id).await().indefinitely();

        assertThat(store.find(w.id).await().indefinitely()).isEmpty();
    }

    private Watchdog watchdog(String conditionType) {
        Watchdog w = new Watchdog();
        w.conditionType = conditionType;
        return w;
    }
}
