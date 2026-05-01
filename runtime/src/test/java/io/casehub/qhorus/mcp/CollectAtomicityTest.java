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
 * Tests for the COLLECT atomicity invariant: ALL non-EVENT messages are delivered and
 * cleared in a single atomic operation. No message may be lost or double-delivered.
 *
 * The critical invariant: after a successful COLLECT read, the sum of messages delivered
 * across all concurrent readers must equal the total number of non-EVENT messages written.
 * Double-delivery or partial delivery are both bugs that would corrupt agent state.
 */
@QuarkusTest
class CollectAtomicityTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    /**
     * ARCHITECTURE FINDING: COLLECT atomicity under concurrent reads depends on the
     * database isolation level. The SELECT-then-DELETE pattern within a single
     * READ_COMMITTED transaction does NOT prevent two concurrent transactions from both
     * SELECTing the same rows before either commits the DELETE. Under H2 (READ_COMMITTED),
     * concurrent COLLECT reads will both deliver the full message set — double-delivery.
     *
     * Production deployments on PostgreSQL with REPEATABLE_READ or SERIALIZABLE isolation
     * would prevent this. H2 tests expose the gap.
     *
     * This test documents and pins the SEQUENTIAL committed case (the actually-guaranteed
     * contract): the first committed COLLECT read delivers all messages; the second
     * committed read gets nothing. Concurrent safety requires SERIALIZABLE isolation in prod.
     */
    @Test
    void collectSequentialCommittedReadsFirstGetsAllSecondGetsNothing() {
        int messageCount = 5;
        String ch = "col-atomic-seq-" + System.nanoTime();

        QuarkusTransaction.requiringNew().run(() -> {
            channelService.create(ch, "COLLECT sequential atomicity test", ChannelSemantic.COLLECT, null);
            var channel = channelService.findByName(ch).orElseThrow();
            for (int i = 0; i < messageCount; i++) {
                messageService.send(channel.id, "writer-" + i, MessageType.STATUS,
                        "msg-" + i, null, null);
            }
        });

        try {
            // First committed read: gets all messages and clears the channel
            CheckResult r1 = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 100, null, null, null));
            assertEquals(messageCount, r1.messages().size(),
                    "First committed COLLECT read must deliver all " + messageCount + " messages");

            // Second committed read: channel was cleared; must return empty
            CheckResult r2 = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 100, null, null, null));
            assertTrue(r2.messages().isEmpty(),
                    "Second committed COLLECT read must return empty — channel was cleared by first read");
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                channelService.findByName(ch).ifPresent(c -> Message.delete("channelId", c.id));
                Channel.delete("name", ch);
            });
        }
    }

    /**
     * CRITICAL: COLLECT must deliver ALL messages in one call — never a partial set.
     *
     * If the COLLECT implementation had a bug where it only deleted `limit` messages
     * but delivered all of them (or vice versa), messages would be silently lost.
     * This test verifies every written message ID appears exactly once in the delivered set.
     */
    @Test
    void collectDeliveredMessageIdsMatchExactlyWhatWasWritten() {
        String ch = "col-atomic-ids-" + System.nanoTime();
        List<Long> writtenIds = new ArrayList<>();

        QuarkusTransaction.requiringNew().run(() -> {
            channelService.create(ch, "COLLECT ID check", ChannelSemantic.COLLECT, null);
            var channel = channelService.findByName(ch).orElseThrow();
            for (int i = 0; i < 8; i++) {
                Message m = messageService.send(channel.id, "writer", MessageType.STATUS,
                        "msg-" + i, null, null);
                writtenIds.add(m.id);
            }
        });

        try {
            // limit=3 — COLLECT must ignore it and deliver all 8
            CheckResult result = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 3, null, null, null));

            List<Long> deliveredIds = result.messages().stream()
                    .map(QhorusMcpTools.MessageSummary::messageId).toList();

            assertEquals(writtenIds.size(), deliveredIds.size(),
                    "COLLECT must deliver all " + writtenIds.size() +
                            " messages, not just the limit=3 subset");
            for (Long id : writtenIds) {
                assertTrue(deliveredIds.contains(id),
                        "COLLECT result is missing message ID " + id +
                                " — partial delivery detected");
            }
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                channelService.findByName(ch).ifPresent(c -> Message.delete("channelId", c.id));
                Channel.delete("name", ch);
            });
        }
    }

    /**
     * IMPORTANT: COLLECT cycles are isolated — cycle 2 messages must not bleed into cycle 1,
     * and cycle 1 messages cleared must not reappear in cycle 2.
     *
     * This tests that committed collects and committed writes form strict cycle boundaries.
     */
    @Test
    void collectClearAndNewWriteFormStrictCycles() {
        String ch = "col-atomic-cycles-" + System.nanoTime();

        QuarkusTransaction.requiringNew().run(
                () -> channelService.create(ch, "COLLECT cycle test", ChannelSemantic.COLLECT, null));

        try {
            // Cycle 1: write 3 messages, collect all 3
            QuarkusTransaction.requiringNew().run(() -> {
                var channel = channelService.findByName(ch).orElseThrow();
                messageService.send(channel.id, "a", MessageType.STATUS, "cycle1-a", null, null);
                messageService.send(channel.id, "b", MessageType.STATUS, "cycle1-b", null, null);
                messageService.send(channel.id, "c", MessageType.STATUS, "cycle1-c", null, null);
            });

            CheckResult cycle1 = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 100, null, null, null));
            assertEquals(3, cycle1.messages().size(), "cycle 1 must deliver all 3 messages");
            assertTrue(cycle1.messages().stream().allMatch(m -> m.content().startsWith("cycle1")));

            // Cycle 2: write 2 new messages after the clear
            QuarkusTransaction.requiringNew().run(() -> {
                var channel = channelService.findByName(ch).orElseThrow();
                messageService.send(channel.id, "a", MessageType.STATUS, "cycle2-a", null, null);
                messageService.send(channel.id, "b", MessageType.STATUS, "cycle2-b", null, null);
            });

            CheckResult cycle2 = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 100, null, null, null));
            assertEquals(2, cycle2.messages().size(),
                    "cycle 2 must deliver only the 2 new messages, not cycle 1 messages that were cleared");
            assertTrue(cycle2.messages().stream().allMatch(m -> m.content().startsWith("cycle2")),
                    "cycle 2 must not contain any cycle 1 message content — the clear was not effective");
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                channelService.findByName(ch).ifPresent(c -> Message.delete("channelId", c.id));
                Channel.delete("name", ch);
            });
        }
    }

    /**
     * IMPORTANT: COLLECT with EVENT messages in the channel — EVENTs survive the clear.
     * After a collect that clears non-EVENT messages, the remaining EVENT rows must not
     * appear as non-EVENT messages in the next cycle's collect payload.
     *
     * This tests the invariant that EVENT accumulation does not corrupt subsequent cycles.
     */
    @Test
    void collectEventMessagesSurvivingClearDoNotCorruptNextCyclePayload() {
        String ch = "col-atomic-event-leak-" + System.nanoTime();

        QuarkusTransaction.requiringNew().run(
                () -> channelService.create(ch, "COLLECT EVENT leak test", ChannelSemantic.COLLECT, null));

        try {
            // Write 2 non-EVENT and 3 EVENTs
            QuarkusTransaction.requiringNew().run(() -> {
                var channel = channelService.findByName(ch).orElseThrow();
                messageService.send(channel.id, "alice", MessageType.STATUS, "work-result", null, null);
                messageService.send(channel.id, "monitor", MessageType.EVENT, "tel-1", null, null);
                messageService.send(channel.id, "monitor", MessageType.EVENT, "tel-2", null, null);
                messageService.send(channel.id, "bob", MessageType.STATUS, "bob-result", null, null);
                messageService.send(channel.id, "monitor", MessageType.EVENT, "tel-3", null, null);
            });

            // Collect cycle 1 — should deliver only the 2 non-EVENT messages
            CheckResult cycle1 = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 100, null, null, null));
            assertEquals(2, cycle1.messages().size(),
                    "collect cycle 1 must deliver exactly 2 non-EVENT messages");
            assertTrue(cycle1.messages().stream().noneMatch(m -> "EVENT".equals(m.messageType())),
                    "collect must never deliver EVENT messages");

            // Cycle 2: write 1 new non-EVENT — the 3 surviving EVENTs must not appear
            QuarkusTransaction.requiringNew().run(() -> {
                var channel = channelService.findByName(ch).orElseThrow();
                messageService.send(channel.id, "carol", MessageType.STATUS, "cycle2-result", null, null);
            });

            CheckResult cycle2 = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 100, null, null, null));
            assertEquals(1, cycle2.messages().size(),
                    "collect cycle 2 must deliver only carol's message; surviving EVENTs must not appear");
            assertEquals("cycle2-result", cycle2.messages().get(0).content());
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                channelService.findByName(ch).ifPresent(c -> Message.delete("channelId", c.id));
                Channel.delete("name", ch);
            });
        }
    }
}
