package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.CheckResult;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Edge-case tests for BARRIER semantics that go beyond the happy-path suite.
 *
 * Findings covered:
 * - EVENT messages from non-contributors accumulate across cycles (no cleanup).
 * - BARRIER with a contributor whose name has leading/trailing spaces fails to match senders.
 * - BARRIER parameters afterId and sender are silently ignored; confirmed correct.
 * - Multiple cycles: EVENT messages from a prior cycle do not ghost into a new cycle.
 */
@QuarkusTest
class BarrierEdgeCaseTest {

    @Inject
    QhorusMcpTools tools;

    /**
     * IMPORTANT finding: EVENT messages in a BARRIER channel are never deleted — not during
     * the barrier release clear, not during subsequent polls. They accumulate silently.
     * This test documents and pins that behaviour. If the design intent is that EVENT
     * messages should be wiped on barrier release, this test will catch a regression.
     */
    @Test
    @TestTransaction
    void barrierEventMessagesAccumulateAcrossCycles() {
        tools.createChannel("bar-edge-1", "BARRIER channel", "BARRIER", "alice,bob", null, null, null, null, null);

        // Both contributors write non-EVENT + a telemetry EVENT from observer
        tools.sendMessage("bar-edge-1", "alice", "status", "ready", null, null, null, null, null);
        tools.sendMessage("bar-edge-1", "bob", "status", "ready", null, null, null, null, null);
        tools.sendMessage("bar-edge-1", "monitor", "event", "cycle-1 telemetry", null, null, null, null, null);

        // Release cycle 1 — EVENT message should NOT be cleared
        CheckResult cycle1 = tools.checkMessages("bar-edge-1", 0L, 10, null, null, null);
        assertNull(cycle1.barrierStatus(), "barrier should have released");
        assertEquals(2, cycle1.messages().size(),
                "released payload should contain the 2 non-EVENT messages only");

        // After release, the EVENT message from "monitor" still exists in the channel.
        // In cycle 2, only alice writes non-EVENT. The EVENT from cycle 1 does NOT count
        // toward bob's contribution — it is from a different sender.
        tools.sendMessage("bar-edge-1", "alice", "status", "cycle-2 ready", null, null, null, null, null);

        CheckResult cycle2 = tools.checkMessages("bar-edge-1", 0L, 10, null, null, null);
        assertTrue(cycle2.messages().isEmpty(),
                "BARRIER cycle 2 should still block — bob has not yet written a non-EVENT message");
        assertNotNull(cycle2.barrierStatus());
        assertTrue(cycle2.barrierStatus().contains("bob"));
    }

    /**
     * IMPORTANT finding: if a BARRIER contributor is declared with surrounding whitespace in the
     * stored barrierContributors string but the trim() is NOT applied — the match fails.
     * The current implementation DOES trim. This test pins the trim behaviour so a refactor
     * that drops the trim() will be caught immediately.
     */
    @Test
    @TestTransaction
    void barrierContributorDeclarationWithWhitespaceIsNormalized() {
        // Deliberately include leading/trailing spaces around contributor names
        tools.createChannel("bar-edge-2", "BARRIER channel", "BARRIER", " alice , bob ", null, null, null, null, null);

        tools.sendMessage("bar-edge-2", "alice", "status", "ready", null, null, null, null, null);
        tools.sendMessage("bar-edge-2", "bob", "status", "ready", null, null, null, null, null);

        CheckResult result = tools.checkMessages("bar-edge-2", 0L, 10, null, null, null);

        // If trim() is working, "alice" and " alice " are normalized to the same key
        assertNull(result.barrierStatus(),
                "BARRIER should release: contributor names with surrounding whitespace must be trimmed before matching");
        assertEquals(2, result.messages().size());
    }

    /**
     * IMPORTANT finding: a sender with leading/trailing whitespace in their name will NEVER
     * satisfy a contributor requirement for the trimmed name. This exposes the asymmetry:
     * contributor names are trimmed, sender names are not.
     */
    @Test
    @TestTransaction
    void barrierSenderWithTrailingSpaceDoesNotMatchTrimmedContributor() {
        // Contributor declared as "alice" (no spaces)
        tools.createChannel("bar-edge-3", "BARRIER channel", "BARRIER", "alice,bob", null, null, null, null, null);

        // Sender writes with a trailing space — "alice " != "alice"
        tools.sendMessage("bar-edge-3", "alice ", "status", "ready with space", null, null, null, null, null);
        tools.sendMessage("bar-edge-3", "bob", "status", "ready", null, null, null, null, null);

        CheckResult result = tools.checkMessages("bar-edge-3", 0L, 10, null, null, null);

        // "alice " does not appear in the contributor set {"alice", "bob"} so alice is still pending
        assertTrue(result.messages().isEmpty(),
                "Sender name 'alice ' (trailing space) must not satisfy contributor requirement for 'alice'");
        assertNotNull(result.barrierStatus());
        assertTrue(result.barrierStatus().contains("alice"),
                "alice should still be listed as pending because her sender name has a trailing space");
    }

    /**
     * IMPORTANT finding: afterId and sender parameters are silently ignored for BARRIER channels.
     * The method signature accepts them, but checkMessagesBarrier() drops both. This test pins
     * the documented behaviour so accidental future changes are caught.
     */
    @Test
    @TestTransaction
    void barrierIgnoresAfterIdAndSenderParameters() {
        tools.createChannel("bar-edge-4", "BARRIER channel", "BARRIER", "alice,bob", null, null, null, null, null);
        tools.sendMessage("bar-edge-4", "alice", "status", "alice ready", null, null, null, null, null);
        tools.sendMessage("bar-edge-4", "bob", "status", "bob ready", null, null, null, null, null);

        // Pass a huge afterId and a sender filter — both should be ignored
        CheckResult result = tools.checkMessages("bar-edge-4", Long.MAX_VALUE - 1, 2, "alice", null, null);

        // BARRIER ignores cursor and sender; since all contributors wrote, it should release
        assertNull(result.barrierStatus(),
                "BARRIER should release regardless of afterId and sender filter parameters");
        assertEquals(2, result.messages().size(),
                "BARRIER release payload should include all non-EVENT messages, ignoring sender filter");
    }

    /**
     * Creative finding: a single contributor writing MULTIPLE non-EVENT messages should
     * still count as only one satisfied contributor — not give extra "votes" toward release
     * when not all other contributors have written.
     */
    @Test
    @TestTransaction
    void barrierContributorWritingMultipleTimesCountsOnce() {
        tools.createChannel("bar-edge-5", "BARRIER channel", "BARRIER", "alice,bob", null, null, null, null, null);

        // alice writes 5 times — should still count as 1
        for (int i = 0; i < 5; i++) {
            tools.sendMessage("bar-edge-5", "alice", "status", "alice msg " + i, null, null, null, null, null);
        }

        CheckResult result = tools.checkMessages("bar-edge-5", 0L, 10, null, null, null);

        assertTrue(result.messages().isEmpty(),
                "BARRIER should not release when only alice has written (even multiple times) and bob has not");
        assertTrue(result.barrierStatus().contains("bob"));
    }

    /**
     * Creative finding: BARRIER channel with exactly one contributor — edge case in split/trim logic.
     */
    @Test
    @TestTransaction
    void barrierWithSingleContributor() {
        tools.createChannel("bar-edge-6", "BARRIER channel", "BARRIER", "alice", null, null, null, null, null);

        CheckResult beforeWrite = tools.checkMessages("bar-edge-6", 0L, 10, null, null, null);
        assertTrue(beforeWrite.messages().isEmpty());
        assertNotNull(beforeWrite.barrierStatus());
        assertTrue(beforeWrite.barrierStatus().contains("alice"));

        tools.sendMessage("bar-edge-6", "alice", "status", "solo ready", null, null, null, null, null);
        CheckResult afterWrite = tools.checkMessages("bar-edge-6", 0L, 10, null, null, null);

        assertNull(afterWrite.barrierStatus(), "single-contributor BARRIER should release when that contributor writes");
        assertEquals(1, afterWrite.messages().size());
    }

    /**
     * Creative finding: BARRIER release clears non-EVENT messages but then second read is clean.
     * Verify that EVENT messages left over from a previous cycle don't interfere with the DISTINCT
     * sender query in a subsequent cycle — specifically, a "monitor" who only ever sends EVENTs
     * is correctly excluded from the "written" set.
     */
    @Test
    @TestTransaction
    void barrierLeftoverEventMessagesFromMonitorDontPoisonSubsequentCycles() {
        tools.createChannel("bar-edge-7", "BARRIER channel", "BARRIER", "alice,bob", null, null, null, null, null);

        // Cycle 1 — monitor sends EVENTs only, alice and bob satisfy the barrier
        tools.sendMessage("bar-edge-7", "alice", "status", "c1", null, null, null, null, null);
        tools.sendMessage("bar-edge-7", "bob", "status", "c1", null, null, null, null, null);
        tools.sendMessage("bar-edge-7", "monitor", "event", "telemetry", null, null, null, null, null);
        tools.checkMessages("bar-edge-7", 0L, 10, null, null, null); // release cycle 1

        // Cycle 2 — only bob writes
        tools.sendMessage("bar-edge-7", "bob", "status", "c2", null, null, null, null, null);
        CheckResult cycle2 = tools.checkMessages("bar-edge-7", 0L, 10, null, null, null);

        // The leftover EVENT from "monitor" should NOT be treated as alice's non-EVENT write
        assertTrue(cycle2.messages().isEmpty(),
                "Leftover EVENT from 'monitor' must not count as alice's contribution in cycle 2");
        assertNotNull(cycle2.barrierStatus());
        assertTrue(cycle2.barrierStatus().contains("alice"),
                "alice must still be listed as pending after cycle 1 release");
    }
}
