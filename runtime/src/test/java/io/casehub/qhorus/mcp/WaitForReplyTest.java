package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.WaitResult;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests for wait_for_reply. No @TestTransaction — the tool manages its own
 * short per-poll transactions internally and would not see uncommitted
 *
 * @TestTransaction data. Each test manages setup/teardown with QuarkusTransaction.
 *
 *                  <p>
 *                  Since Task 9 (commitment migration), wait_for_reply polls the Commitment state
 *                  rather than a PendingReply row. A Commitment is created by CommitmentService.open()
 *                  when a QUERY or COMMAND is sent. Tests must send a QUERY or COMMAND with the
 *                  correlationId before calling wait_for_reply.
 */
@QuarkusTest
class WaitForReplyTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    // -----------------------------------------------------------------------
    // Happy path — response already exists when wait registers
    // -----------------------------------------------------------------------

    @Test
    void waitForReplyReturnsImmediatelyWhenResponseAlreadyExists() {
        String ch = "wfr-exists-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "Test", ChannelSemantic.APPEND, null);
            messageService.send(channel.id, "alice", MessageType.QUERY, "Question", corrId, null);
            messageService.send(channel.id, "bob", MessageType.RESPONSE, "Answer!", corrId, null);
        });

        try {
            WaitResult result = tools.waitForReply(ch, corrId, 5, null);

            assertTrue(result.found());
            assertFalse(result.timedOut());
            assertEquals(corrId, result.correlationId());
            assertNotNull(result.message());
            assertEquals("Answer!", result.message().content());
            assertEquals("RESPONSE", result.message().messageType());
        } finally {
            cleanupChannel(ch);
        }
    }

    // -----------------------------------------------------------------------
    // Timeout path
    // -----------------------------------------------------------------------

    @Test
    void waitForReplyTimesOutWhenNoResponseArrives() {
        String ch = "wfr-timeout-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "Test", ChannelSemantic.APPEND, null);
            // Send QUERY to create a Commitment — wait_for_reply polls Commitment state
            messageService.send(channel.id, "alice", MessageType.QUERY, "Question?", corrId, null);
        });

        try {
            WaitResult result = tools.waitForReply(ch, corrId, 1, null); // 1s timeout

            assertFalse(result.found());
            assertTrue(result.timedOut());
            assertEquals(corrId, result.correlationId());
            assertNull(result.message());
            assertNotNull(result.status());
        } finally {
            cleanupChannel(ch);
        }
    }

    // -----------------------------------------------------------------------
    // Correlation ID matching precision
    // -----------------------------------------------------------------------

    @Test
    void waitForReplyIgnoresResponseWithDifferentCorrelationId() {
        String ch = "wfr-diff-corr-" + System.nanoTime();
        String waitCorrId = "corr-wait-" + UUID.randomUUID();
        String otherCorrId = "corr-other-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "Test", ChannelSemantic.APPEND, null);
            messageService.send(channel.id, "alice", MessageType.QUERY, "Q", waitCorrId, null);
            messageService.send(channel.id, "alice", MessageType.QUERY, "Q2", otherCorrId, null);
            // Response for a DIFFERENT correlation ID — should not wake the wait
            messageService.send(channel.id, "bob", MessageType.RESPONSE, "Wrong answer", otherCorrId, null);
        });

        try {
            WaitResult result = tools.waitForReply(ch, waitCorrId, 1, null);

            assertFalse(result.found(), "should not match a response with a different correlationId");
            assertTrue(result.timedOut());
        } finally {
            cleanupChannel(ch);
        }
    }

    @Test
    void waitForReplyFindsResponseMessageType() {
        String ch = "wfr-type-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "Test", ChannelSemantic.APPEND, null);
            messageService.send(channel.id, "alice", MessageType.QUERY, "Q", corrId, null);
            // STATUS then RESPONSE — RESPONSE should satisfy wait_for_reply
            messageService.send(channel.id, "alice", MessageType.STATUS, "working...", corrId, null);
            messageService.send(channel.id, "bob", MessageType.RESPONSE, "final answer", corrId, null);
        });

        try {
            WaitResult result = tools.waitForReply(ch, corrId, 5, null);

            assertTrue(result.found(), "RESPONSE should satisfy wait_for_reply");
            assertEquals("final answer", result.message().content());
        } finally {
            cleanupChannel(ch);
        }
    }

    @Test
    void waitForReplyFindsDoneMessageTypeForCommand() {
        String ch = "wfr-done-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "Test", ChannelSemantic.APPEND, null);
            messageService.send(channel.id, "alice", MessageType.COMMAND, "Do it", corrId, null);
            messageService.send(channel.id, "bob", MessageType.DONE, "completed", corrId, null);
        });

        try {
            WaitResult result = tools.waitForReply(ch, corrId, 5, null);

            assertTrue(result.found(), "DONE should satisfy wait_for_reply for a COMMAND commitment");
            assertEquals("completed", result.message().content());
            assertEquals("DONE", result.message().messageType());
        } finally {
            cleanupChannel(ch);
        }
    }

    // -----------------------------------------------------------------------
    // Commitment lifecycle — replaces PendingReply tests
    // -----------------------------------------------------------------------

    @Test
    void waitForReplyCommitmentRemainsAfterSuccess() {
        // Commitment is kept for audit trail — not deleted on successful match
        String ch = "wfr-commitment-ok-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "Test", ChannelSemantic.APPEND, null);
            messageService.send(channel.id, "alice", MessageType.QUERY, "Q", corrId, null);
            messageService.send(channel.id, "bob", MessageType.RESPONSE, "Answer", corrId, null);
        });

        try {
            tools.waitForReply(ch, corrId, 5, null);

            long remaining = QuarkusTransaction.requiringNew()
                    .call(() -> Commitment.count("correlationId", corrId));
            assertEquals(1, remaining, "Commitment should be kept as audit trail after successful match");
        } finally {
            cleanupChannel(ch);
        }
    }

    @Test
    void waitForReplyCommitmentRemainsAfterTimeout() {
        String ch = "wfr-commitment-timeout-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "Test", ChannelSemantic.APPEND, null);
            messageService.send(channel.id, "alice", MessageType.QUERY, "Q?", corrId, null);
        });

        try {
            tools.waitForReply(ch, corrId, 1, null);

            long remaining = QuarkusTransaction.requiringNew()
                    .call(() -> Commitment.count("correlationId", corrId));
            assertEquals(1, remaining, "Commitment should be kept as audit trail after timeout");
        } finally {
            cleanupChannel(ch);
        }
    }

    @Test
    void waitForReplyWorksWhenCalledTwiceWithSameCorrId() {
        // Two sequential wait_for_reply calls with the same correlationId — must not throw
        String ch = "wfr-double-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "Test", ChannelSemantic.APPEND, null);
            messageService.send(channel.id, "alice", MessageType.QUERY, "Q?", corrId, null);
        });

        try {
            // First wait times out (OPEN commitment stays)
            tools.waitForReply(ch, corrId, 1, null);
            // Second wait with same correlationId must not throw
            assertDoesNotThrow(() -> tools.waitForReply(ch, corrId, 1, null),
                    "second wait_for_reply with same correlationId should not throw");
        } finally {
            cleanupChannel(ch);
        }
    }

    // -----------------------------------------------------------------------
    // Error paths
    // -----------------------------------------------------------------------

    @Test
    void waitForReplyThrowsForUnknownChannel() {
        assertThrows(Exception.class, () -> tools.waitForReply("no-such-channel-xyz", "corr-abc", 1, null));
    }

    // -----------------------------------------------------------------------
    // Polling — response arrives AFTER the wait starts
    // -----------------------------------------------------------------------

    @Test
    void waitForReplyPollingCatchesResponseThatArrivesDuringWait() throws InterruptedException {
        String ch = "wfr-poll-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "Test", ChannelSemantic.APPEND, null);
            // QUERY creates the Commitment — wait_for_reply polls it
            messageService.send(channel.id, "alice", MessageType.QUERY, "Q?", corrId, null);
        });

        // Inject the response on a background thread after 300ms
        Thread responder = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                return;
            }
            QuarkusTransaction.requiringNew().run(() -> {
                var channel = channelService.findByName(ch).orElseThrow();
                messageService.send(channel.id, "bob", MessageType.RESPONSE,
                        "late response", corrId, null);
            });
        });
        responder.start();

        try {
            WaitResult result = tools.waitForReply(ch, corrId, 3, null);
            responder.join(2000);

            assertTrue(result.found(), "should find the response that arrived during polling");
            assertEquals("late response", result.message().content());
        } finally {
            cleanupChannel(ch);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void cleanupChannel(String channelName) {
        QuarkusTransaction.requiringNew().run(() -> {
            channelService.findByName(channelName).ifPresent(ch -> {
                Commitment.delete("channelId", ch.id);
                Message.delete("channelId", ch.id);
            });
            io.casehub.qhorus.runtime.channel.Channel.delete("name", channelName);
        });
    }
}
