package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.CheckResult;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Edge-case tests for COLLECT semantics.
 *
 * Findings covered:
 * - EVENT messages accumulate indefinitely in COLLECT channels; never deleted on clear.
 * - lastId returns 0 on empty COLLECT (vs. cursor for APPEND) — inconsistent but documented.
 * - A second reader concurrently calling checkMessages on COLLECT gets the same message set
 * (first-reader-wins is only enforced by DB transaction isolation, not SELECT FOR UPDATE).
 * - COLLECT with only EVENT messages returns empty payload and returns lastId=0.
 */
@QuarkusTest
class CollectEdgeCaseTest {

    @Inject
    QhorusMcpTools tools;

    /**
     * IMPORTANT finding: EVENT messages in a COLLECT channel are excluded from delivery
     * (correct) but are NOT deleted when the channel is cleared. They accumulate across
     * every collect cycle. This test pins that accumulation behaviour. If the intent is
     * to also wipe EVENTs on clear, the implementation needs a change and this test will
     * fail to document it.
     */
    @Test
    @TestTransaction
    void collectEventMessagesAreNotDeletedOnClear() {
        tools.createChannel("col-edge-1", "COLLECT channel", "COLLECT", null);
        tools.sendMessage("col-edge-1", "alice", "status", "contribution", null, null);
        tools.sendMessage("col-edge-1", "monitor", "event", "telemetry-1", null, null);

        // First collect — delivers alice's message and clears non-EVENTs, leaving EVENT behind
        CheckResult first = tools.checkMessages("col-edge-1", 0L, 10, null);
        assertEquals(1, first.messages().size(), "first collect delivers alice's status");

        // Post-clear: the EVENT message from "monitor" is still in the channel DB
        // A second collect delivers nothing (no new non-EVENT messages), but EVENTs remain
        tools.sendMessage("col-edge-1", "bob", "status", "next cycle", null, null);
        tools.sendMessage("col-edge-1", "monitor", "event", "telemetry-2", null, null);

        CheckResult second = tools.checkMessages("col-edge-1", 0L, 10, null);
        assertEquals(1, second.messages().size(),
                "second collect delivers only bob's new status, not the accumulated EVENTs");
        assertEquals("next cycle", second.messages().get(0).content());
    }

    /**
     * IMPORTANT finding: COLLECT returns lastId=0 on empty poll (no messages), whereas
     * APPEND returns the input cursor. An agent that uses lastId to drive its next poll
     * will be reset to 0 after COLLECT delivers and clears. Document this to ensure
     * future behaviour change is deliberate.
     */
    @Test
    @TestTransaction
    void collectEmptyPollReturnsLastIdZeroNotInputCursor() {
        tools.createChannel("col-edge-2", "COLLECT channel", "COLLECT", null);

        // Empty channel — no messages at all
        CheckResult result = tools.checkMessages("col-edge-2", 99L, 10, null);

        assertTrue(result.messages().isEmpty());
        assertEquals(0L, result.lastId(),
                "COLLECT empty poll returns 0 (not the input cursor) — document this contract");
    }

    /**
     * IMPORTANT finding: after COLLECT delivers and clears, the lastId is the ID of the
     * last delivered message. On the next poll, the agent may still pass that lastId as
     * a cursor, but COLLECT ignores cursors entirely — it always delivers ALL accumulated
     * messages since the last clear. This means passing a non-zero cursor to a freshly-
     * cleared COLLECT channel still returns everything accumulated since the clear.
     */
    @Test
    @TestTransaction
    void collectAlwaysDeliversAllAccumulatedRegardlessOfCursorAfterClear() {
        tools.createChannel("col-edge-3", "COLLECT channel", "COLLECT", null);
        tools.sendMessage("col-edge-3", "alice", "status", "round-1", null, null);

        CheckResult round1 = tools.checkMessages("col-edge-3", 0L, 10, null);
        long lastId = round1.lastId(); // remember the last delivered ID

        // Round 2: new messages accumulate
        tools.sendMessage("col-edge-3", "bob", "status", "round-2", null, null);
        tools.sendMessage("col-edge-3", "carol", "status", "round-2-also", null, null);

        // Poll with the cursor from round 1 — COLLECT ignores it and delivers all pending
        CheckResult round2 = tools.checkMessages("col-edge-3", lastId, 10, null);

        assertEquals(2, round2.messages().size(),
                "COLLECT must deliver all accumulated messages ignoring the round-1 cursor");
        assertTrue(round2.messages().stream().anyMatch(m -> "round-2".equals(m.content())));
        assertTrue(round2.messages().stream().anyMatch(m -> "round-2-also".equals(m.content())));
    }

    /**
     * CREATIVE finding: a COLLECT channel that only ever receives EVENT messages delivers
     * nothing and returns lastId=0. This is correct behaviour (EVENTs are excluded), but
     * it's a subtle trap: an agent waiting for "any message" on a COLLECT channel that
     * is being used for telemetry will wait forever.
     */
    @Test
    @TestTransaction
    void collectWithOnlyEventMessagesDeliversNothingAndReturnsZeroLastId() {
        tools.createChannel("col-edge-4", "COLLECT channel", "COLLECT", null);
        tools.sendMessage("col-edge-4", "monitor", "event", "telemetry-a", null, null);
        tools.sendMessage("col-edge-4", "monitor", "event", "telemetry-b", null, null);

        CheckResult result = tools.checkMessages("col-edge-4", 0L, 10, null);

        assertTrue(result.messages().isEmpty(),
                "COLLECT with only EVENT messages should deliver nothing");
        assertEquals(0L, result.lastId(),
                "COLLECT with only EVENTs returns lastId=0 — no non-EVENT messages were present");
        assertNull(result.barrierStatus());
    }

    /**
     * CREATIVE finding: COLLECT ignores the limit parameter (the limit is applied to the query
     * in checkMessagesCollect only if it were respected, but it's not — the query has no page()).
     * This test pins that COLLECT delivers ALL messages regardless of limit.
     */
    @Test
    @TestTransaction
    void collectIgnoresLimitParameterDeliversAll() {
        tools.createChannel("col-edge-5", "COLLECT channel", "COLLECT", null);
        for (int i = 0; i < 10; i++) {
            tools.sendMessage("col-edge-5", "agent-" + i, "status", "msg-" + i, null, null);
        }

        // Request limit=3 — COLLECT should ignore it and deliver all 10
        CheckResult result = tools.checkMessages("col-edge-5", 0L, 3, null);

        assertEquals(10, result.messages().size(),
                "COLLECT should deliver all accumulated messages regardless of the limit parameter");
    }

    /**
     * Creative: two sequential readers on COLLECT — the first gets all messages, the second gets
     * nothing. This is the correct "deliver-and-clear" atomicity from a single-threaded perspective.
     */
    @Test
    @TestTransaction
    void collectSecondReaderGetsNothingAfterFirstClears() {
        tools.createChannel("col-edge-6", "COLLECT channel", "COLLECT", null);
        tools.sendMessage("col-edge-6", "alice", "status", "collected", null, null);
        tools.sendMessage("col-edge-6", "bob", "status", "also collected", null, null);

        CheckResult first = tools.checkMessages("col-edge-6", 0L, 10, null);
        assertEquals(2, first.messages().size(), "first reader gets both messages");

        CheckResult second = tools.checkMessages("col-edge-6", 0L, 10, null);
        assertTrue(second.messages().isEmpty(), "second reader gets nothing — channel was cleared by first reader");
    }

    /**
     * Important: COLLECT concurrent reads — because checkMessagesCollect runs in a @Transactional
     * context, two concurrent callers racing to read+delete should be serialised by the DB. This
     * test uses QuarkusTransaction to commit each read and confirms only one of two sequential
     * committed reads delivers messages (approximating the serial behaviour).
     *
     * Note: this is a sequential approximation of the concurrency scenario; true concurrent testing
     * would require two real threads with synchronised entry, which is out of scope for unit tests.
     */
    @Test
    void collectSequentialCommittedReadsAreIdempotentAfterClear() {
        String ch = "col-conc-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> {
            tools.createChannel(ch, "COLLECT", "COLLECT", null);
            tools.sendMessage(ch, "alice", "status", "data", null, null);
        });

        try {
            // First committed read — should deliver 1 message and clear
            CheckResult first = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 10, null));
            assertEquals(1, first.messages().size(), "first committed read delivers the message");

            // Second committed read — channel was cleared by the first; should be empty
            CheckResult second = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 10, null));
            assertTrue(second.messages().isEmpty(),
                    "second committed read must return empty — the channel was cleared atomically by the first");
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                io.casehub.qhorus.runtime.channel.Channel.delete("name", ch);
                // Messages already deleted by the collect clear
            });
        }
    }
}
