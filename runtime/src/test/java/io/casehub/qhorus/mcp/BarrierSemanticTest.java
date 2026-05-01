package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.CheckResult;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class BarrierSemanticTest {

    @Inject
    QhorusMcpTools tools;

    @Test
    @TestTransaction
    void barrierBlocksWhenNoContributorsHaveWritten() {
        tools.createChannel("bar-1", "BARRIER channel", "BARRIER", "alice,bob", null, null, null, null, null);

        CheckResult result = tools.checkMessages("bar-1", 0L, 10, null, null, null);

        assertTrue(result.messages().isEmpty(),
                "BARRIER should return empty when no contributors have written");
        assertNotNull(result.barrierStatus(),
                "BARRIER should include a status describing pending contributors");
        assertTrue(result.barrierStatus().contains("alice"),
                "barrierStatus should name all pending contributors — alice missing");
        assertTrue(result.barrierStatus().contains("bob"),
                "barrierStatus should name all pending contributors — bob missing");
    }

    @Test
    @TestTransaction
    void barrierBlocksWhenOnlySomeContributorsHaveWritten() {
        tools.createChannel("bar-2", "BARRIER channel", "BARRIER", "alice,bob", null, null, null, null, null);
        tools.sendMessage("bar-2", "alice", "status", "alice ready", null, null, null, null, null);

        CheckResult result = tools.checkMessages("bar-2", 0L, 10, null, null, null);

        assertTrue(result.messages().isEmpty(),
                "BARRIER should stay blocked when not all contributors have written");
        assertTrue(result.barrierStatus().contains("bob"),
                "barrier status should name the pending contributor");
    }

    @Test
    @TestTransaction
    void barrierReleasesWhenAllContributorsHaveWritten() {
        tools.createChannel("bar-3", "BARRIER channel", "BARRIER", "alice,bob", null, null, null, null, null);
        tools.sendMessage("bar-3", "alice", "status", "alice ready", null, null, null, null, null);
        tools.sendMessage("bar-3", "bob", "status", "bob ready", null, null, null, null, null);

        CheckResult result = tools.checkMessages("bar-3", 0L, 10, null, null, null);

        assertEquals(2, result.messages().size(),
                "BARRIER should release and deliver all messages when all contributors have written");
        assertNull(result.barrierStatus(),
                "barrierStatus should be null when barrier releases");
    }

    @Test
    @TestTransaction
    void barrierClearsAndResetsAfterRelease() {
        tools.createChannel("bar-4", "BARRIER channel", "BARRIER", "alice,bob", null, null, null, null, null);
        tools.sendMessage("bar-4", "alice", "status", "ready", null, null, null, null, null);
        tools.sendMessage("bar-4", "bob", "status", "ready", null, null, null, null, null);
        tools.checkMessages("bar-4", 0L, 10, null, null, null); // Release

        // Barrier has reset — blocked again until both write in the new cycle
        CheckResult second = tools.checkMessages("bar-4", 0L, 10, null, null, null);
        assertTrue(second.messages().isEmpty(),
                "BARRIER should be blocked again after release and reset");
    }

    @Test
    @TestTransaction
    void barrierNonContributorWriteDoesNotTriggerRelease() {
        tools.createChannel("bar-5", "BARRIER channel", "BARRIER", "alice,bob", null, null, null, null, null);
        tools.sendMessage("bar-5", "alice", "status", "alice ready", null, null, null, null, null);
        tools.sendMessage("bar-5", "carol", "status", "observer writes", null, null, null, null, null); // not a contributor

        CheckResult result = tools.checkMessages("bar-5", 0L, 10, null, null, null);

        assertTrue(result.messages().isEmpty(),
                "Non-contributor writes should not count toward the barrier release condition");
        assertTrue(result.barrierStatus().contains("bob"),
                "bob should still be listed as a pending contributor");
    }

    @Test
    @TestTransaction
    void barrierNewCycleRequiresAllContributorsAgain() {
        tools.createChannel("bar-6", "BARRIER channel", "BARRIER", "alice,bob", null, null, null, null, null);

        // Cycle 1: both write, releases
        tools.sendMessage("bar-6", "alice", "status", "cycle-1", null, null, null, null, null);
        tools.sendMessage("bar-6", "bob", "status", "cycle-1", null, null, null, null, null);
        tools.checkMessages("bar-6", 0L, 10, null, null, null); // Release and reset

        // Cycle 2: only alice writes — bob still pending
        tools.sendMessage("bar-6", "alice", "status", "cycle-2", null, null, null, null, null);
        CheckResult result = tools.checkMessages("bar-6", 0L, 10, null, null, null);

        assertTrue(result.messages().isEmpty(),
                "BARRIER cycle 2 should block until bob also writes");
        assertTrue(result.barrierStatus().contains("bob"));
    }

    @Test
    @TestTransaction
    void barrierEventMessageFromContributorDoesNotCountTowardRelease() {
        tools.createChannel("bar-7", "BARRIER channel", "BARRIER", "alice,bob", null, null, null, null, null);
        // alice sends an EVENT message — should NOT satisfy her contribution requirement
        tools.sendMessage("bar-7", "alice", "event", "alice telemetry", null, null, null, null, null);
        tools.sendMessage("bar-7", "bob", "status", "bob ready", null, null, null, null, null);

        CheckResult result = tools.checkMessages("bar-7", 0L, 10, null, null, null);

        assertTrue(result.messages().isEmpty(),
                "EVENT message from a contributor should not count toward the BARRIER release condition");
        assertNotNull(result.barrierStatus());
        assertTrue(result.barrierStatus().contains("alice"),
                "alice should still be listed as pending because her only write was an EVENT");
    }

    @Test
    @TestTransaction
    void barrierWithNullContributorsBlocksPermanently() {
        // A BARRIER channel created without contributors is a configuration error.
        // It must not silently release — it should block with a diagnostic status.
        tools.createChannel("bar-8", "BARRIER channel", "BARRIER", null, null, null, null, null, null);
        tools.sendMessage("bar-8", "alice", "status", "ready", null, null, null, null, null);

        CheckResult result = tools.checkMessages("bar-8", 0L, 10, null, null, null);

        assertTrue(result.messages().isEmpty(),
                "BARRIER with no contributors declared should not release");
        assertNotNull(result.barrierStatus(),
                "BARRIER with no contributors should return a diagnostic barrierStatus, not null");
    }

    @Test
    @TestTransaction
    void barrierReleaseIncludesNonContributorMessages() {
        // Non-contributor writes accumulate; they should be included in the release payload
        // so the consumer sees the full channel state.
        tools.createChannel("bar-9", "BARRIER channel", "BARRIER", "alice,bob", null, null, null, null, null);
        tools.sendMessage("bar-9", "alice", "status", "alice ready", null, null, null, null, null);
        tools.sendMessage("bar-9", "carol", "status", "observer note", null, null, null, null, null); // not a contributor
        tools.sendMessage("bar-9", "bob", "status", "bob ready", null, null, null, null, null);

        CheckResult result = tools.checkMessages("bar-9", 0L, 10, null, null, null);

        // Barrier releases because alice and bob have both written
        assertNull(result.barrierStatus(), "barrier should have released");
        assertEquals(3, result.messages().size(),
                "Release payload should include all non-EVENT messages, including non-contributor writes");
    }

    @Test
    @TestTransaction
    void appendChannelUnaffectedByBarrierLogic() {
        tools.createChannel("append-bar", "APPEND channel", "APPEND", "alice,bob", null, null, null, null, null);

        // Even if barrierContributors is set (should be ignored for APPEND)
        tools.sendMessage("append-bar", "alice", "status", "message", null, null, null, null, null);
        CheckResult result = tools.checkMessages("append-bar", 0L, 10, null, null, null);

        assertEquals(1, result.messages().size(),
                "APPEND channel should not apply BARRIER logic");
        assertNull(result.barrierStatus());
    }
}
