package io.casehub.qhorus.store.query;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.instance.Instance;
import io.casehub.qhorus.runtime.store.query.InstanceQuery;

class InstanceQueryTest {

    private Instance instance(String status, Instant lastSeen) {
        Instance inst = new Instance();
        inst.status = status;
        inst.lastSeen = lastSeen;
        return inst;
    }

    @Test
    void all_matchesAnyInstance() {
        Instance inst = instance("online", Instant.now());
        assertTrue(InstanceQuery.all().matches(inst));
    }

    @Test
    void all_matchesOfflineInstance() {
        Instance inst = instance("offline", Instant.now());
        assertTrue(InstanceQuery.all().matches(inst));
    }

    @Test
    void online_matchesOnlineInstance() {
        Instance inst = instance("online", Instant.now());
        assertTrue(InstanceQuery.online().matches(inst));
    }

    @Test
    void online_doesNotMatchOfflineInstance() {
        Instance inst = instance("offline", Instant.now());
        assertFalse(InstanceQuery.online().matches(inst));
    }

    @Test
    void staleOlderThan_matchesStaleInstance() {
        Instant threshold = Instant.now().minusSeconds(300);
        Instance inst = instance("stale", Instant.now().minusSeconds(600));

        assertTrue(InstanceQuery.staleOlderThan(threshold).matches(inst));
    }

    @Test
    void staleOlderThan_doesNotMatchRecentInstance() {
        Instant threshold = Instant.now().minusSeconds(300);
        Instance inst = instance("online", Instant.now().minusSeconds(60));

        assertFalse(InstanceQuery.staleOlderThan(threshold).matches(inst));
    }

    @Test
    void staleOlderThan_doesNotMatchNullLastSeen() {
        Instant threshold = Instant.now().minusSeconds(300);
        Instance inst = instance("online", null);

        assertFalse(InstanceQuery.staleOlderThan(threshold).matches(inst));
    }

    @Test
    void builder_combinesStatusAndStale() {
        Instant threshold = Instant.now().minusSeconds(300);
        Instance staleOnline = instance("online", Instant.now().minusSeconds(600));
        Instance freshOnline = instance("online", Instant.now().minusSeconds(60));
        Instance staleOffline = instance("offline", Instant.now().minusSeconds(600));

        InstanceQuery q = InstanceQuery.builder().status("online").staleOlderThan(threshold).build();

        assertTrue(q.matches(staleOnline));
        assertFalse(q.matches(freshOnline));
        assertFalse(q.matches(staleOffline));
    }

    @Test
    void byCapability_exposesCapabilityFilter() {
        // capability() is stored for the store to apply via join — matches() ignores it
        InstanceQuery q = InstanceQuery.byCapability("code-review");
        assertEquals("code-review", q.capability());
    }

    @Test
    void toBuilder_roundTrips() {
        InstanceQuery original = InstanceQuery.builder().status("stale").build();
        InstanceQuery copy = original.toBuilder().build();

        Instance inst = instance("stale", Instant.now());
        assertTrue(original.matches(inst));
        assertTrue(copy.matches(inst));
    }
}
