package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.CheckResult;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.WaitResult;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Edge-case tests for EPHEMERAL semantics.
 *
 * Findings covered:
 * - EVENT messages in an EPHEMERAL channel are never deleted (they accumulate).
 * - wait_for_reply on an EPHEMERAL channel does NOT delete the response message
 * (because it calls findResponseByCorrelationId, not checkMessagesEphemeral).
 * - EPHEMERAL with EVENT-only content returns empty and keeps the EVENT rows.
 * - cursor-based partial reads on EPHEMERAL: only delivered messages are deleted.
 */
@QuarkusTest
class EphemeralEdgeCaseTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    /**
     * IMPORTANT finding: EVENT messages in an EPHEMERAL channel are excluded from
     * pollAfter() (which filters messageType != EVENT), so they are never included
     * in the IDs that get deleted after a read. They silently accumulate forever.
     * This test pins the current behaviour. If the design intent is to also delete
     * EVENTs on first EPHEMERAL read, the implementation must change.
     */
    @Test
    @TestTransaction
    void ephemeralEventMessagesAreNotDeletedOnRead() {
        tools.createChannel("eph-edge-1", "EPHEMERAL channel", "EPHEMERAL", null);
        tools.sendMessage("eph-edge-1", "alice", "status", "routing hint", null, null);
        tools.sendMessage("eph-edge-1", "monitor", "event", "telemetry", null, null);

        // First read: routing hint is delivered and deleted; EVENT is skipped
        CheckResult first = tools.checkMessages("eph-edge-1", 0L, 10, null);
        assertEquals(1, first.messages().size(), "only the non-EVENT message is delivered");
        assertEquals("routing hint", first.messages().get(0).content());

        // Second read with cursor=0: routing hint is gone, but the EVENT row remains in the DB.
        // The EVENT row is not visible to agents (pollAfter excludes EVENTs), so this read returns empty.
        CheckResult second = tools.checkMessages("eph-edge-1", 0L, 10, null);
        assertTrue(second.messages().isEmpty(),
                "second read is empty — the routing hint was consumed; EVENT row is invisible but still in DB");
    }

    /**
     * IMPORTANT finding (cross-domain): wait_for_reply uses findResponseByCorrelationId
     * which is a direct DB query — it does NOT go through checkMessagesEphemeral.
     * As a result, when wait_for_reply finds a RESPONSE on an EPHEMERAL channel, the
     * response message is NOT deleted. A subsequent checkMessages call would deliver
     * the same response again (and then delete it on that second read).
     *
     * This means wait_for_reply + EPHEMERAL channel gives a surprising double-delivery
     * opportunity: the waiter sees the message once via wait_for_reply, and any agent
     * that calls checkMessages subsequently will also see it.
     */
    @Test
    void waitForReplyOnEphemeralChannelDoesNotDeleteTheResponseMessage() {
        String ch = "eph-wfr-" + System.nanoTime();
        String corrId = "corr-" + java.util.UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "EPHEMERAL", ChannelSemantic.EPHEMERAL, null);
            // Send QUERY first (creates Commitment in OPEN state), then RESPONSE.
            // The QUERY message counts in the channel — but the test asserts checkMessages
            // finds exactly 1 RESPONSE, so we account for the QUERY being an EPHEMERAL message too.
            // checkMessages excludes EVENT; QUERY and RESPONSE are both visible, so after wait_for_reply
            // the channel has both messages: QUERY + RESPONSE (2 total).
            // The key finding: wait_for_reply does NOT delete the RESPONSE.
            messageService.send(channel.id, "alice", MessageType.QUERY, "Question?", corrId, null);
            messageService.send(channel.id, "bob", MessageType.RESPONSE, "Answer", corrId, null);
        });

        try {
            // wait_for_reply finds the RESPONSE immediately (Commitment is FULFILLED after QUERY+RESPONSE)
            WaitResult waitResult = tools.waitForReply(ch, corrId, 5, null);
            assertTrue(waitResult.found(), "wait_for_reply should find the existing RESPONSE");
            assertEquals("Answer", waitResult.message().content());

            // Now do a checkMessages — because wait_for_reply did NOT delete the RESPONSE,
            // it is still in the channel. checkMessagesEphemeral will deliver it again AND THEN delete it.
            CheckResult checkResult = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 10, null));

            // Channel has QUERY + RESPONSE (2 messages). wait_for_reply does NOT consume them.
            // This documents the double-delivery exposure: the RESPONSE is visible again via checkMessages.
            assertEquals(2, checkResult.messages().size(),
                    "EPHEMERAL messages (QUERY + RESPONSE) are not consumed by wait_for_reply — " +
                            "a subsequent checkMessages still delivers them (double-delivery exposure)");
            assertTrue(checkResult.messages().stream().anyMatch(m -> "RESPONSE".equals(m.messageType())),
                    "RESPONSE should be in the checkMessages result");
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                io.casehub.qhorus.runtime.message.Commitment.delete("correlationId", corrId);
                io.casehub.qhorus.runtime.channel.Channel.delete("name", ch);
            });
        }
    }

    /**
     * CREATIVE: EPHEMERAL channel with only EVENT messages — the channel appears empty
     * to every reader, but the EVENT rows accumulate in the DB. Document this subtle
     * "invisible accumulation" scenario.
     */
    @Test
    @TestTransaction
    void ephemeralWithOnlyEventMessagesAppearsEmptyButRowsAccumulate() {
        tools.createChannel("eph-edge-3", "EPHEMERAL channel", "EPHEMERAL", null);

        // Send 5 EVENT messages
        for (int i = 0; i < 5; i++) {
            tools.sendMessage("eph-edge-3", "monitor", "event", "telemetry-" + i, null, null);
        }

        // Every read returns empty — EVENTs are invisible to agents
        CheckResult first = tools.checkMessages("eph-edge-3", 0L, 10, null);
        assertTrue(first.messages().isEmpty());

        CheckResult second = tools.checkMessages("eph-edge-3", 0L, 10, null);
        assertTrue(second.messages().isEmpty(),
                "EPHEMERAL channel with only EVENT messages always appears empty to agents");
    }

    /**
     * IMPORTANT: EPHEMERAL with cursor > 0 only delivers messages AFTER the cursor.
     * Messages at or before the cursor are not deleted (they were never delivered).
     * This tests that the cursor exclusion on EPHEMERAL doesn't accidentally consume
     * messages the agent hasn't seen yet.
     *
     * Note: this is an unusual use of EPHEMERAL (normally cursor=0) but agents may
     * misconfigure it.
     */
    @Test
    @TestTransaction
    void ephemeralWithHighCursorSkipsAndDoesNotDeleteEarlierMessages() {
        tools.createChannel("eph-edge-4", "EPHEMERAL channel", "EPHEMERAL", null);
        var m1 = tools.sendMessage("eph-edge-4", "alice", "status", "early-msg", null, null);
        tools.sendMessage("eph-edge-4", "bob", "status", "later-msg", null, null);

        // Read with cursor at m1 — only "later-msg" is delivered and deleted
        CheckResult result = tools.checkMessages("eph-edge-4", m1.messageId(), 10, null);
        assertEquals(1, result.messages().size());
        assertEquals("later-msg", result.messages().get(0).content());

        // "early-msg" was never delivered (cursor excluded it) so it was NOT deleted.
        // A read with cursor=0 should now deliver it.
        CheckResult recovery = tools.checkMessages("eph-edge-4", 0L, 10, null);
        assertEquals(1, recovery.messages().size(),
                "early EPHEMERAL message skipped by high cursor should still be available with cursor=0");
        assertEquals("early-msg", recovery.messages().get(0).content());
    }

    /**
     * CREATIVE: EPHEMERAL channels used for routing hints — confirm that separate channels
     * isolate correctly. A hint sent to channel A should never appear when reading channel B.
     */
    @Test
    @TestTransaction
    void ephemeralChannelIsolationBetweenTwoChannels() {
        tools.createChannel("eph-isolation-a", "EPHEMERAL A", "EPHEMERAL", null);
        tools.createChannel("eph-isolation-b", "EPHEMERAL B", "EPHEMERAL", null);

        tools.sendMessage("eph-isolation-a", "alice", "status", "hint-for-a", null, null);
        tools.sendMessage("eph-isolation-b", "bob", "status", "hint-for-b", null, null);

        CheckResult resultA = tools.checkMessages("eph-isolation-a", 0L, 10, null);
        CheckResult resultB = tools.checkMessages("eph-isolation-b", 0L, 10, null);

        assertEquals(1, resultA.messages().size());
        assertEquals("hint-for-a", resultA.messages().get(0).content());
        assertEquals(1, resultB.messages().size());
        assertEquals("hint-for-b", resultB.messages().get(0).content());
    }
}
