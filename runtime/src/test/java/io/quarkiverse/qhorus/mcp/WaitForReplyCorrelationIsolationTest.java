package io.quarkiverse.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.api.channel.ChannelSemantic;
import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.channel.ChannelService;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpToolsBase.WaitResult;
import io.quarkiverse.qhorus.runtime.message.Commitment;
import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.message.MessageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests for wait_for_reply correlation ID isolation — the property that a waiter
 * NEVER receives a response intended for a different waiter, even when:
 * - The same channel has many competing responses.
 * - The same correlation ID exists on a different channel.
 * - Two waiters run concurrently on the same channel.
 *
 * <p>
 * Since Task 9 (commitment migration), wait_for_reply polls the Commitment state
 * rather than a PendingReply row. Each test must send QUERY/COMMAND with the
 * correlationId to create a Commitment before calling wait_for_reply.
 *
 * <p>
 * Note: the unique constraint on {@code commitment.correlation_id} means the same
 * correlationId cannot be used for two independent QUERY/COMMAND messages across
 * different channels simultaneously. Tests that need isolation across channels use
 * separate correlationIds.
 *
 * This is a safety-critical property: a multi-agent system where one agent receives
 * another's response would produce silent data corruption.
 */
@QuarkusTest
class WaitForReplyCorrelationIsolationTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    /**
     * CRITICAL: two sequential waiters on the same channel with different correlation IDs.
     * Each must receive only its own matching response, even when both responses exist
     * in the channel at the time of waiting.
     *
     * This is the core isolation invariant: waiter A must not receive answer-for-B, and
     * vice versa. If correlationId matching matched any RESPONSE in the channel (not
     * filtered by corrId), waiter A would steal waiter B's response.
     */
    @Test
    void sequentialWaitersOnSameChannelEachReceiveOnlyTheirOwnResponse() {
        String ch = "wfr-iso-sequential-" + System.nanoTime();
        String corrIdA = "corr-A-" + UUID.randomUUID();
        String corrIdB = "corr-B-" + UUID.randomUUID();

        // Pre-commit BOTH queries then BOTH responses
        QuarkusTransaction.requiringNew().run(() -> {
            channelService.create(ch, "Sequential waiters channel", ChannelSemantic.APPEND, null);
            var channel = channelService.findByName(ch).orElseThrow();
            messageService.send(channel.id, "alice", MessageType.QUERY, "QA", corrIdA, null);
            messageService.send(channel.id, "alice", MessageType.QUERY, "QB", corrIdB, null);
            messageService.send(channel.id, "responder", MessageType.RESPONSE,
                    "answer-for-A", corrIdA, null);
            messageService.send(channel.id, "responder", MessageType.RESPONSE,
                    "answer-for-B", corrIdB, null);
        });

        try {
            // Waiter A — channel has BOTH responses; must receive only answer-for-A
            WaitResult ra = tools.waitForReply(ch, corrIdA, 5, null);

            assertTrue(ra.found(), "waiter A must find its response");
            assertEquals("answer-for-A", ra.message().content(),
                    "waiter A must receive answer-for-A, not answer-for-B");
            assertEquals(corrIdA, ra.correlationId(),
                    "waiter A's returned correlationId must be corrIdA");

            // Waiter B — channel still has answer-for-B (not consumed by waiter A)
            WaitResult rb = tools.waitForReply(ch, corrIdB, 5, null);

            assertTrue(rb.found(), "waiter B must find its response");
            assertEquals("answer-for-B", rb.message().content(),
                    "waiter B must receive answer-for-B, not answer-for-A");
            assertEquals(corrIdB, rb.correlationId(),
                    "waiter B's returned correlationId must be corrIdB");
        } finally {
            cleanupChannel(ch, corrIdA, corrIdB);
        }
    }

    /**
     * IMPORTANT: wait_for_reply must not match a response that was committed to the
     * channel BEFORE the request was sent, if it has a completely different correlationId.
     *
     * This tests the scenario where the channel already has a RESPONSE from a prior
     * interaction (a "stale" response from a previous request-reply cycle) with a
     * different corrId. The new waiter must not match it.
     */
    @Test
    void waitForReplyDoesNotMatchStaleResponseWithDifferentCorrId() {
        String ch = "wfr-iso-stale-" + System.nanoTime();
        String staleCorrId = "corr-stale-" + UUID.randomUUID();
        String freshCorrId = "corr-fresh-" + UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "Stale response channel",
                    ChannelSemantic.APPEND, null);
            // Stale QUERY + RESPONSE from a prior cycle
            messageService.send(channel.id, "old-requester", MessageType.QUERY,
                    "old question", staleCorrId, null);
            messageService.send(channel.id, "old-responder", MessageType.RESPONSE,
                    "old answer", staleCorrId, null);
            // Fresh QUERY creates a Commitment in OPEN state for the waiter's corrId
            messageService.send(channel.id, "alice", MessageType.QUERY,
                    "new question", freshCorrId, null);
        });

        try {
            // Waiter for a FRESH corrId — must not pick up the stale response
            WaitResult result = tools.waitForReply(ch, freshCorrId, 1, null);

            assertFalse(result.found(),
                    "wait_for_reply must not match a stale RESPONSE with a different corrId");
            assertTrue(result.timedOut());
        } finally {
            cleanupChannel(ch, staleCorrId, freshCorrId);
        }
    }

    /**
     * CREATIVE: wait_for_reply with a UUID-format correlationId that happens to be
     * a prefix substring of another UUID — verifies that matching is exact, not prefix-based.
     *
     * The SQL query uses = (exact match), not LIKE, so this should be safe. This test
     * documents and proves that property.
     */
    @Test
    void waitForReplyMatchesExactCorrIdNotSubstringOfAnother() {
        String ch = "wfr-iso-exact-" + System.nanoTime();
        // corrIdShort is a known-format string
        String corrIdShort = "corr-abc-" + System.nanoTime();
        // corrIdLong contains corrIdShort as a prefix
        String corrIdLong = corrIdShort + "-extended-suffix-" + UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "Exact match test",
                    ChannelSemantic.APPEND, null);
            // QUERY + RESPONSE for corrIdLong (the longer one) — creates a FULFILLED Commitment
            messageService.send(channel.id, "alice", MessageType.QUERY, "QL", corrIdLong, null);
            messageService.send(channel.id, "responder", MessageType.RESPONSE,
                    "answer-for-long", corrIdLong, null);
            // Only a QUERY for corrIdShort — Commitment in OPEN state (no response yet)
            messageService.send(channel.id, "alice", MessageType.QUERY, "QS", corrIdShort, null);
        });

        try {
            // Wait for corrIdShort — must NOT match corrIdLong's FULFILLED state
            WaitResult result = tools.waitForReply(ch, corrIdShort, 1, null);

            assertFalse(result.found(),
                    "wait_for_reply must match corrId exactly; corrIdLong shares prefix with corrIdShort " +
                            "but must not be matched");
            assertTrue(result.timedOut());
        } finally {
            cleanupChannel(ch, corrIdShort, corrIdLong);
        }
    }

    /**
     * IMPORTANT: cancel_wait deletes the Commitment. A subsequent deletePendingReply-equivalent
     * operation on an already-absent Commitment must be a no-op without throwing.
     *
     * This is the Commitment-based analogue of the former PendingReply cleanup-race test.
     * Because wait_for_reply no longer deletes the Commitment on timeout, there is no
     * race to test — but we verify that calling cancel_wait twice on the same correlationId
     * is idempotent.
     */
    @Test
    void cancelWaitIsIdempotentWhenCalledTwice() {
        String ch = "wfr-iso-cancel-race-" + System.nanoTime();
        String corrId = "corr-cancel-race-" + UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "Cancel race test", ChannelSemantic.APPEND, null);
            messageService.send(channel.id, "alice", MessageType.QUERY, "Q?", corrId, null);
        });

        try {
            // First cancel_wait — should succeed
            var result1 = tools.cancelWait(corrId);
            assertTrue(result1.cancelled(), "first cancel should succeed");

            // Second cancel_wait — Commitment already gone, must not throw
            assertDoesNotThrow(
                    () -> {
                        var result2 = tools.cancelWait(corrId);
                        assertFalse(result2.cancelled(),
                                "second cancel on already-deleted Commitment should report not cancelled");
                    },
                    "cancelWait for an already-deleted Commitment must not throw");
        } finally {
            cleanupChannel(ch, corrId);
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private void cleanupChannel(String channelName, String... corrIds) {
        QuarkusTransaction.requiringNew().run(() -> {
            for (String corrId : corrIds) {
                if (corrId != null) {
                    Commitment.delete("correlationId", corrId);
                }
            }
            channelService.findByName(channelName).ifPresent(c -> {
                Message.delete("channelId", c.id);
            });
            Channel.delete("name", channelName);
        });
    }
}
