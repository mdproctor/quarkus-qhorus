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
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.MessageSummary;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests for message ordering guarantees.
 *
 * The system guarantees messages are ordered by sequence ID (ASC) in all delivery
 * paths. This is critical for agent coordination: if two agents are alternating writes,
 * every reader must see the same causal ordering.
 *
 * The sequence generator has allocationSize=50 — this pre-allocates blocks of 50 IDs
 * in memory for performance. Under H2 test conditions all IDs come from the same
 * allocation block, so they are monotonically increasing. These tests pin that guarantee.
 */
@QuarkusTest
class MessageOrderingTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    /**
     * IMPORTANT: messages returned by check_messages are always in ascending ID order,
     * regardless of the order agents called send_message.
     *
     * If the ORDER BY id ASC in pollAfter were dropped, agents would see a
     * non-deterministic ordering that changes between polls.
     */
    @Test
    void appendChannelDeliversMessagesInStrictlyAscendingIdOrder() {
        String ch = "ord-append-" + System.nanoTime();
        List<Long> writtenIds = new ArrayList<>();

        QuarkusTransaction.requiringNew().run(() -> {
            channelService.create(ch, "Ordering test", ChannelSemantic.APPEND, null);
            var channel = channelService.findByName(ch).orElseThrow();
            for (int i = 0; i < 10; i++) {
                Message m = messageService.send(channel.id, "sender-" + i, MessageType.STATUS,
                        "msg-" + i, null, null);
                writtenIds.add(m.id);
            }
        });

        try {
            CheckResult result = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 100, null));

            assertEquals(10, result.messages().size());

            // Verify ascending order
            List<Long> deliveredIds = result.messages().stream()
                    .map(MessageSummary::messageId).toList();
            for (int i = 1; i < deliveredIds.size(); i++) {
                assertTrue(deliveredIds.get(i) > deliveredIds.get(i - 1),
                        "Messages must be in strictly ascending ID order. " +
                                "Got id[" + (i - 1) + "]=" + deliveredIds.get(i - 1) +
                                " id[" + i + "]=" + deliveredIds.get(i));
            }

            // The first delivered ID must be the first written ID
            assertEquals(writtenIds.get(0), deliveredIds.get(0),
                    "First delivered message must have the first written ID");
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                channelService.findByName(ch).ifPresent(c -> Message.delete("channelId", c.id));
                Channel.delete("name", ch);
            });
        }
    }

    /**
     * IMPORTANT: COLLECT delivers messages in ascending ID order within the collected set.
     *
     * Agents consuming a COLLECT result may depend on the causal ordering of contributions.
     * If collect delivered messages in an arbitrary order, an agent processing results
     * would see a non-deterministic sequence.
     */
    @Test
    void collectChannelDeliversMessagesInAscendingIdOrder() {
        String ch = "ord-collect-" + System.nanoTime();
        List<Long> writtenIds = new ArrayList<>();

        QuarkusTransaction.requiringNew().run(() -> {
            channelService.create(ch, "COLLECT ordering test", ChannelSemantic.COLLECT, null);
            var channel = channelService.findByName(ch).orElseThrow();
            for (int i = 0; i < 5; i++) {
                Message m = messageService.send(channel.id, "contributor-" + i, MessageType.STATUS,
                        "contrib-" + i, null, null);
                writtenIds.add(m.id);
            }
        });

        try {
            CheckResult result = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 100, null));

            assertEquals(5, result.messages().size());
            for (int i = 1; i < result.messages().size(); i++) {
                long prev = result.messages().get(i - 1).messageId();
                long curr = result.messages().get(i).messageId();
                assertTrue(curr > prev,
                        "COLLECT must deliver messages in ascending ID order. " +
                                "Got id[" + (i - 1) + "]=" + prev + " id[" + i + "]=" + curr);
            }
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                channelService.findByName(ch).ifPresent(c -> Message.delete("channelId", c.id));
                Channel.delete("name", ch);
            });
        }
    }

    /**
     * IMPORTANT: BARRIER delivers messages in ascending ID order after release.
     *
     * After a BARRIER releases, agents process the batch. If the batch ordering were
     * non-deterministic, different consumers would process contributions in different
     * orders — breaking any order-dependent protocols.
     */
    @Test
    void barrierChannelDeliversMessagesInAscendingIdOrderOnRelease() {
        String ch = "ord-barrier-" + System.nanoTime();

        QuarkusTransaction.requiringNew().run(() -> {
            channelService.create(ch, "BARRIER ordering test",
                    ChannelSemantic.BARRIER, "alice,bob,carol");
            var channel = channelService.findByName(ch).orElseThrow();
            messageService.send(channel.id, "alice", MessageType.STATUS, "alice-contrib", null, null);
            messageService.send(channel.id, "bob", MessageType.STATUS, "bob-contrib", null, null);
            messageService.send(channel.id, "carol", MessageType.STATUS, "carol-contrib", null, null);
        });

        try {
            CheckResult result = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 100, null));

            assertNull(result.barrierStatus(), "barrier must have released");
            assertEquals(3, result.messages().size());

            for (int i = 1; i < result.messages().size(); i++) {
                long prev = result.messages().get(i - 1).messageId();
                long curr = result.messages().get(i).messageId();
                assertTrue(curr > prev,
                        "BARRIER released payload must be in ascending ID order");
            }
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                channelService.findByName(ch).ifPresent(c -> Message.delete("channelId", c.id));
                Channel.delete("name", ch);
            });
        }
    }

    /**
     * CREATIVE: cursor-based walking through a channel with many messages — the cursor
     * must advance monotonically, and the combination of cursor + limit must not skip
     * any message ID or repeat any message.
     *
     * This covers the pagination correctness guarantee over the sequence-based ID space.
     */
    @Test
    void cursorBasedWalkingVisitsEveryMessageExactlyOnce() {
        String ch = "ord-walk-" + System.nanoTime();
        int totalMessages = 15;
        List<Long> writtenIds = new ArrayList<>();

        QuarkusTransaction.requiringNew().run(() -> {
            channelService.create(ch, "Cursor walk test", ChannelSemantic.APPEND, null);
            var channel = channelService.findByName(ch).orElseThrow();
            for (int i = 0; i < totalMessages; i++) {
                Message m = messageService.send(channel.id, "agent", MessageType.STATUS,
                        "payload-" + i, null, null);
                writtenIds.add(m.id);
            }
        });

        try {
            // Walk through 5 at a time
            List<Long> visitedIds = new ArrayList<>();
            long cursor = 0L;
            int pageSize = 5;

            for (int page = 0; page < 4; page++) { // enough passes to cover all 15
                final long finalCursor = cursor;
                CheckResult pageResult = QuarkusTransaction.requiringNew().call(
                        () -> tools.checkMessages(ch, finalCursor, pageSize, null));
                if (pageResult.messages().isEmpty()) {
                    break;
                }
                for (var msg : pageResult.messages()) {
                    assertFalse(visitedIds.contains(msg.messageId()),
                            "Message " + msg.messageId() + " visited more than once during cursor walk");
                    visitedIds.add(msg.messageId());
                }
                cursor = pageResult.lastId();
            }

            assertEquals(totalMessages, visitedIds.size(),
                    "cursor-based walk must visit all " + totalMessages +
                            " messages exactly once; visited " + visitedIds.size());

            // Every written ID must have been visited
            for (Long id : writtenIds) {
                assertTrue(visitedIds.contains(id),
                        "Written message ID " + id + " was never visited during cursor walk");
            }
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                channelService.findByName(ch).ifPresent(c -> Message.delete("channelId", c.id));
                Channel.delete("name", ch);
            });
        }
    }

    /**
     * CREATIVE: EPHEMERAL with cursor — messages delivered are exactly those with
     * id > cursor, and they are in ascending ID order.
     */
    @Test
    void ephemeralWithCursorDeliversMessagesAfterCursorInAscendingOrder() {
        String ch = "ord-eph-cursor-" + System.nanoTime();

        QuarkusTransaction.requiringNew().run(() -> {
            channelService.create(ch, "EPHEMERAL cursor ordering", ChannelSemantic.EPHEMERAL, null);
            var channel = channelService.findByName(ch).orElseThrow();
            // Write 5 messages
            for (int i = 0; i < 5; i++) {
                messageService.send(channel.id, "sender", MessageType.STATUS, "msg-" + i, null, null);
            }
        });

        try {
            // First read: get the first 2, recording their IDs
            CheckResult firstBatch = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 2, null));
            assertEquals(2, firstBatch.messages().size(), "first EPHEMERAL read with limit=2");
            // Verify ascending
            assertTrue(firstBatch.messages().get(1).messageId() > firstBatch.messages().get(0).messageId(),
                    "EPHEMERAL first batch must be in ascending order");

            // Second read with cursor from first batch: delivers remaining messages
            final long lastId = firstBatch.lastId();
            CheckResult secondBatch = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, lastId, 10, null));
            assertEquals(3, secondBatch.messages().size(),
                    "EPHEMERAL second read (cursor past first 2) must deliver remaining 3 messages");
            // Verify all IDs are > firstBatch's lastId and in ascending order
            for (var msg : secondBatch.messages()) {
                assertTrue(msg.messageId() > lastId,
                        "EPHEMERAL second batch must only contain messages with id > cursor");
            }
            for (int i = 1; i < secondBatch.messages().size(); i++) {
                assertTrue(secondBatch.messages().get(i).messageId() > secondBatch.messages().get(i - 1).messageId(),
                        "EPHEMERAL second batch must be in ascending order");
            }
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                channelService.findByName(ch).ifPresent(c -> Message.delete("channelId", c.id));
                Channel.delete("name", ch);
            });
        }
    }
}
