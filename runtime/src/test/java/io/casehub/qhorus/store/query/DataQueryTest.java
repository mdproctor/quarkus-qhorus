package io.casehub.qhorus.store.query;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.data.SharedData;
import io.casehub.qhorus.runtime.store.query.DataQuery;

class DataQueryTest {

    private SharedData sharedData(String createdBy, boolean complete) {
        SharedData d = new SharedData();
        d.createdBy = createdBy;
        d.complete = complete;
        return d;
    }

    @Test
    void all_matchesAnyData() {
        SharedData d = sharedData("agent-1", false);
        assertTrue(DataQuery.all().matches(d));
    }

    @Test
    void completeOnly_matchesCompleteData() {
        SharedData d = sharedData("agent-1", true);
        assertTrue(DataQuery.completeOnly().matches(d));
    }

    @Test
    void completeOnly_doesNotMatchIncompleteData() {
        SharedData d = sharedData("agent-1", false);
        assertFalse(DataQuery.completeOnly().matches(d));
    }

    @Test
    void byCreator_matchesCorrectCreator() {
        SharedData d = sharedData("agent-1", true);
        assertTrue(DataQuery.byCreator("agent-1").matches(d));
        assertFalse(DataQuery.byCreator("agent-2").matches(d));
    }

    @Test
    void byCreator_doesNotMatchNullCreator() {
        SharedData d = sharedData(null, true);
        assertFalse(DataQuery.byCreator("agent-1").matches(d));
    }

    @Test
    void builder_combinesPredicates() {
        SharedData complete = sharedData("agent-1", true);
        SharedData incomplete = sharedData("agent-1", false);
        SharedData otherAgent = sharedData("agent-2", true);

        DataQuery q = DataQuery.builder().createdBy("agent-1").complete(true).build();

        assertTrue(q.matches(complete));
        assertFalse(q.matches(incomplete));
        assertFalse(q.matches(otherAgent));
    }

    @Test
    void toBuilder_roundTrips() {
        DataQuery original = DataQuery.builder().createdBy("agent-x").complete(false).build();
        DataQuery copy = original.toBuilder().build();

        SharedData d = sharedData("agent-x", false);
        assertTrue(original.matches(d));
        assertTrue(copy.matches(d));
    }
}
