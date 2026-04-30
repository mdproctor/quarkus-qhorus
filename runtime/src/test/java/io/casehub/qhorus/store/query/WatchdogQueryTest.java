package io.casehub.qhorus.store.query;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.store.query.WatchdogQuery;
import io.casehub.qhorus.runtime.watchdog.Watchdog;

class WatchdogQueryTest {

    private Watchdog watchdog(String conditionType) {
        Watchdog w = new Watchdog();
        w.conditionType = conditionType;
        w.targetName = "test-channel";
        w.notificationChannel = "alerts";
        return w;
    }

    @Test
    void all_matchesAnyWatchdog() {
        Watchdog w = watchdog("BARRIER_STUCK");
        assertTrue(WatchdogQuery.all().matches(w));
    }

    @Test
    void byConditionType_matchesCorrectType() {
        Watchdog w = watchdog("AGENT_STALE");
        assertTrue(WatchdogQuery.byConditionType("AGENT_STALE").matches(w));
    }

    @Test
    void byConditionType_doesNotMatchDifferentType() {
        Watchdog w = watchdog("BARRIER_STUCK");
        assertFalse(WatchdogQuery.byConditionType("AGENT_STALE").matches(w));
    }

    @Test
    void byConditionType_doesNotMatchNullConditionType() {
        Watchdog w = watchdog(null);
        assertFalse(WatchdogQuery.byConditionType("QUEUE_DEPTH").matches(w));
    }

    @Test
    void builder_filtersOnConditionType() {
        Watchdog stuck = watchdog("BARRIER_STUCK");
        Watchdog stale = watchdog("AGENT_STALE");

        WatchdogQuery q = WatchdogQuery.builder().conditionType("BARRIER_STUCK").build();

        assertTrue(q.matches(stuck));
        assertFalse(q.matches(stale));
    }

    @Test
    void toBuilder_roundTrips() {
        WatchdogQuery original = WatchdogQuery.builder().conditionType("CHANNEL_IDLE").build();
        WatchdogQuery copy = original.toBuilder().build();

        Watchdog w = watchdog("CHANNEL_IDLE");
        assertTrue(original.matches(w));
        assertTrue(copy.matches(w));
    }

    @Test
    void conditionType_accessor_returnsValue() {
        WatchdogQuery q = WatchdogQuery.byConditionType("APPROVAL_PENDING");
        assertEquals("APPROVAL_PENDING", q.conditionType());
    }
}
