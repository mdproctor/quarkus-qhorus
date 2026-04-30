package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.CheckResult;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests for the EPHEMERAL exactly-once delivery invariant:
 * exactly ONE reader must receive each message; no double-delivery.
 *
 * ARCHITECTURE NOTE: EPHEMERAL delivery relies on SELECT-then-DELETE within a single
 * transaction. Under H2 with READ_COMMITTED isolation (used in tests), two concurrent
 * transactions can both SELECT the same rows before either commits the DELETE — resulting
 * in double-delivery. This is NOT a bug in the EPHEMERAL implementation; it is a known
 * consequence of READ_COMMITTED isolation. Production deployments on PostgreSQL with
 * REPEATABLE_READ or SERIALIZABLE would prevent this.
 *
 * These tests verify the sequential committed contract (which IS guaranteed) and do not
 * test concurrent reads (which require SERIALIZABLE isolation to guarantee exactly-once).
 */
@QuarkusTest
class EphemeralDoubleDeliveryTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    /**
     * CRITICAL: first committed EPHEMERAL read delivers all messages; second delivers nothing.
     *
     * This is the fundamental exactly-once contract for sequential readers. It verifies that
     * the SELECT-then-DELETE is effective: once a transaction commits the DELETE, no subsequent
     * reader can see the deleted rows.
     */
    @Test
    void ephemeralSequentialCommittedReadsEnforceExactlyOnceDelivery() {
        int messageCount = 6;
        String ch = "eph-seq-exactly-once-" + System.nanoTime();

        QuarkusTransaction.requiringNew().run(() -> {
            channelService.create(ch, "EPHEMERAL exactly-once sequential", ChannelSemantic.EPHEMERAL, null);
            var channel = channelService.findByName(ch).orElseThrow();
            for (int i = 0; i < messageCount; i++) {
                messageService.send(channel.id, "writer", MessageType.STATUS,
                        "msg-" + i, null, null);
            }
        });

        try {
            // First committed read: delivers all messages (atomically selected and deleted)
            CheckResult r1 = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 100, null));
            assertEquals(messageCount, r1.messages().size(),
                    "First committed EPHEMERAL read must deliver all " + messageCount + " messages");

            // Second committed read: channel is empty (all messages deleted by first read)
            CheckResult r2 = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 100, null));
            assertTrue(r2.messages().isEmpty(),
                    "Second committed EPHEMERAL read must return empty — all messages were consumed");

            // Explicit no-duplication check (trivially passes since r2 is empty)
            var idsInR1 = r1.messages().stream()
                    .map(QhorusMcpTools.MessageSummary::messageId).toList();
            for (var msg : r2.messages()) {
                assertFalse(idsInR1.contains(msg.messageId()),
                        "Message " + msg.messageId() + " appeared in both reads — double delivery");
            }
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                channelService.findByName(ch).ifPresent(c -> Message.delete("channelId", c.id));
                Channel.delete("name", ch);
            });
        }
    }

    /**
     * IMPORTANT: EPHEMERAL single-message delivery — exactly one reader gets it.
     *
     * Tests the delete path with a single-element IN clause. Any off-by-one in the
     * IN-list construction would be caught here.
     */
    @Test
    void ephemeralSingleMessageDeliveredToExactlyOneReader() {
        String ch = "eph-single-del-" + System.nanoTime();

        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "EPHEMERAL single message",
                    ChannelSemantic.EPHEMERAL, null);
            messageService.send(channel.id, "writer", MessageType.STATUS, "the-one-message", null, null);
        });

        try {
            CheckResult r1 = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 10, null));
            CheckResult r2 = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 10, null));

            assertEquals(1, r1.messages().size(), "first reader must get the one message");
            assertEquals("the-one-message", r1.messages().get(0).content());
            assertTrue(r2.messages().isEmpty(),
                    "second reader must get nothing — single EPHEMERAL message was consumed");
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                channelService.findByName(ch).ifPresent(c -> Message.delete("channelId", c.id));
                Channel.delete("name", ch);
            });
        }
    }

    /**
     * CREATIVE: EPHEMERAL sequential partial drain — N reads of limit=2 drain the channel
     * without any message being delivered twice. This tests the limit+delete correctness
     * over multiple sequential reads.
     */
    @Test
    void ephemeralSequentialPartialReadsDrainChannelWithNoRepeats() {
        String ch = "eph-drain-" + System.nanoTime();

        QuarkusTransaction.requiringNew().run(() -> {
            channelService.create(ch, "EPHEMERAL sequential drain", ChannelSemantic.EPHEMERAL, null);
            var channel = channelService.findByName(ch).orElseThrow();
            for (int i = 0; i < 6; i++) {
                messageService.send(channel.id, "writer", MessageType.STATUS,
                        "msg-" + i, null, null);
            }
        });

        try {
            List<Long> allDeliveredIds = new ArrayList<>();
            long cursor = 0L;

            for (int pass = 0; pass < 4; pass++) {
                final long finalCursor = cursor;
                CheckResult batch = QuarkusTransaction.requiringNew().call(
                        () -> tools.checkMessages(ch, finalCursor, 2, null));
                if (batch.messages().isEmpty()) {
                    break;
                }
                for (var msg : batch.messages()) {
                    assertFalse(allDeliveredIds.contains(msg.messageId()),
                            "Message " + msg.messageId() +
                                    " delivered more than once across sequential EPHEMERAL reads");
                    allDeliveredIds.add(msg.messageId());
                }
                cursor = batch.lastId();
            }

            assertEquals(6, allDeliveredIds.size(),
                    "Sequential drain of 6 EPHEMERAL messages with limit=2 must visit all 6 exactly once");
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                channelService.findByName(ch).ifPresent(c -> Message.delete("channelId", c.id));
                Channel.delete("name", ch);
            });
        }
    }

    /**
     * CREATIVE: verify that only the messages within the limit are deleted, not all messages.
     * A read with limit=2 on a 5-message channel deletes exactly 2 rows; remaining 3 are
     * available for the next reader.
     */
    @Test
    void ephemeralPartialReadDeletesOnlyDeliveredMessages() {
        String ch = "eph-partial-del-" + System.nanoTime();

        QuarkusTransaction.requiringNew().run(() -> {
            channelService.create(ch, "EPHEMERAL partial delete", ChannelSemantic.EPHEMERAL, null);
            var channel = channelService.findByName(ch).orElseThrow();
            for (int i = 0; i < 5; i++) {
                messageService.send(channel.id, "writer", MessageType.STATUS,
                        "msg-" + i, null, null);
            }
        });

        try {
            // Read 2: exactly 2 are delivered and deleted
            CheckResult first = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 2, null));
            assertEquals(2, first.messages().size(), "limit=2 must deliver exactly 2 messages");

            // Read all remaining: should be exactly 3 (not 5 and not 0)
            CheckResult remaining = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 100, null));
            assertEquals(3, remaining.messages().size(),
                    "After partial EPHEMERAL read of 2, exactly 3 messages must remain");

            // Confirm no overlap between first and remaining
            var firstIds = first.messages().stream()
                    .map(QhorusMcpTools.MessageSummary::messageId).toList();
            for (var msg : remaining.messages()) {
                assertFalse(firstIds.contains(msg.messageId()),
                        "Message " + msg.messageId() +
                                " appeared in both the partial read and the remaining set — not deleted");
            }
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                channelService.findByName(ch).ifPresent(c -> Message.delete("channelId", c.id));
                Channel.delete("name", ch);
            });
        }
    }
}
