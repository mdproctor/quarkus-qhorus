package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
 * Tests for BARRIER semantics under concurrent conditions that go beyond single-threaded
 * sequential tests.
 *
 * Critical invariants tested here:
 * - BARRIER must not release prematurely when contributors write concurrently.
 * - BARRIER must release correctly when all contributors finish concurrently.
 * - A contributor writing EVENT then non-EVENT satisfies the barrier (non-EVENT counts).
 * - Empty string in the contributor list (double-comma "alice,,bob") does not create
 * a phantom contributor that blocks the barrier forever.
 */
@QuarkusTest
class BarrierConcurrentWriteTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    /**
     * IMPORTANT: a contributor who first sends an EVENT and then sends a non-EVENT
     * in the same barrier cycle must satisfy their contribution requirement.
     *
     * The BARRIER check queries DISTINCT senders WHERE messageType != EVENT. If alice
     * sends EVENT then STATUS, the STATUS write appears in the DISTINCT query and she
     * satisfies the barrier. This is a non-obvious multi-write scenario.
     */
    @Test
    void barrierContributorSendingEventThenNonEventSatisfiesBarrier() {
        String ch = "bar-event-then-status-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(
                () -> channelService.create(ch, "BARRIER contributor test",
                        ChannelSemantic.BARRIER, "alice,bob"));

        try {
            // alice sends EVENT first (should NOT satisfy her contribution)
            QuarkusTransaction.requiringNew().run(() -> {
                var channel = channelService.findByName(ch).orElseThrow();
                messageService.send(channel.id, "alice", MessageType.EVENT, "telemetry", null, null);
            });

            // Barrier should still be blocked — alice's EVENT doesn't count
            CheckResult afterEvent = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 10, null, null, null));
            assertTrue(afterEvent.messages().isEmpty(),
                    "Barrier must not release after alice sends only an EVENT");
            assertNotNull(afterEvent.barrierStatus());
            assertTrue(afterEvent.barrierStatus().contains("alice"),
                    "alice must still be listed as pending after sending only EVENT");

            // alice now sends a non-EVENT — she satisfies her contribution
            QuarkusTransaction.requiringNew().run(() -> {
                var channel = channelService.findByName(ch).orElseThrow();
                messageService.send(channel.id, "alice", MessageType.STATUS, "ready", null, null);
            });

            // bob also writes — now both contributors have written non-EVENT
            QuarkusTransaction.requiringNew().run(() -> {
                var channel = channelService.findByName(ch).orElseThrow();
                messageService.send(channel.id, "bob", MessageType.STATUS, "ready", null, null);
            });

            CheckResult afterBothNonEvent = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 10, null, null, null));
            assertNull(afterBothNonEvent.barrierStatus(),
                    "BARRIER must release once alice sent a non-EVENT and bob has written");
            // The payload includes alice's STATUS and bob's STATUS (not alice's EVENT)
            assertEquals(2, afterBothNonEvent.messages().size(),
                    "Released barrier payload must contain alice's STATUS and bob's STATUS");
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                channelService.findByName(ch).ifPresent(c -> Message.delete("channelId", c.id));
                Channel.delete("name", ch);
            });
        }
    }

    /**
     * IMPORTANT: concurrent writes from all contributors — barrier must release correctly
     * even when all contributors write in overlapping transactions.
     *
     * This tests that the BARRIER check query (DISTINCT senders) correctly sees all
     * committed writes even when they arrived nearly simultaneously.
     */
    @Test
    void barrierReleasesCorrectlyWhenAllContributorsWriteConcurrently() throws Exception {
        String ch = "bar-concurrent-write-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(
                () -> channelService.create(ch, "BARRIER concurrent write test",
                        ChannelSemantic.BARRIER, "alice,bob,carol"));

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch go = new CountDownLatch(1);

        try {
            Future<?> fa = pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    return;
                }
                QuarkusTransaction.requiringNew().run(() -> {
                    var channel = channelService.findByName(ch).orElseThrow();
                    messageService.send(channel.id, "alice", MessageType.STATUS, "alice-done", null, null);
                });
            });
            Future<?> fb = pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    return;
                }
                QuarkusTransaction.requiringNew().run(() -> {
                    var channel = channelService.findByName(ch).orElseThrow();
                    messageService.send(channel.id, "bob", MessageType.STATUS, "bob-done", null, null);
                });
            });
            Future<?> fc = pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    return;
                }
                QuarkusTransaction.requiringNew().run(() -> {
                    var channel = channelService.findByName(ch).orElseThrow();
                    messageService.send(channel.id, "carol", MessageType.STATUS, "carol-done", null, null);
                });
            });

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            fa.get(10, TimeUnit.SECONDS);
            fb.get(10, TimeUnit.SECONDS);
            fc.get(10, TimeUnit.SECONDS);

            // All three committed — barrier must release
            CheckResult result = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 10, null, null, null));

            assertNull(result.barrierStatus(),
                    "BARRIER must release after all 3 contributors have written, even concurrently");
            assertEquals(3, result.messages().size(),
                    "Released BARRIER payload must include all 3 contributor messages");
        } finally {
            pool.shutdownNow();
            QuarkusTransaction.requiringNew().run(() -> {
                channelService.findByName(ch).ifPresent(c -> Message.delete("channelId", c.id));
                Channel.delete("name", ch);
            });
        }
    }

    /**
     * IMPORTANT: BARRIER channel with an empty contributor name from double-comma in
     * the barrierContributors string (e.g., "alice,,bob") — the empty string after
     * split/trim/filter must be eliminated, or it creates a phantom contributor that
     * blocks the barrier permanently.
     *
     * The implementation uses `.filter(s -> !s.isBlank())` after trim — this test pins
     * that empty-string contributors from double commas are correctly ignored.
     */
    @Test
    void barrierWithDoubleCommaInContributorListIgnoresEmptyEntry() {
        String ch = "bar-double-comma-" + System.nanoTime();

        QuarkusTransaction.requiringNew().run(() -> {
            channelService.create(ch, "BARRIER double comma", ChannelSemantic.BARRIER, "alice,,bob");
        });

        try {
            // Both legitimate contributors write — the phantom "" contributor must not block release
            QuarkusTransaction.requiringNew().run(() -> {
                var channel = channelService.findByName(ch).orElseThrow();
                messageService.send(channel.id, "alice", MessageType.STATUS, "ready", null, null);
                messageService.send(channel.id, "bob", MessageType.STATUS, "ready", null, null);
            });

            CheckResult result = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 10, null, null, null));

            assertNull(result.barrierStatus(),
                    "BARRIER with 'alice,,bob' should release when alice and bob have written; " +
                            "the empty string between commas must not create a phantom contributor");
            assertEquals(2, result.messages().size());
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                channelService.findByName(ch).ifPresent(c -> Message.delete("channelId", c.id));
                Channel.delete("name", ch);
            });
        }
    }

    /**
     * CREATIVE: BARRIER channel where a check_messages call arrives exactly between
     * two contributors' writes — it must stay blocked (not prematurely release).
     *
     * Sequence: alice writes -> check (must stay blocked) -> bob writes -> check (must release).
     * This is the fundamental partial-progress safety property.
     */
    @Test
    void barrierInterleavedCheckBetweenContributorWritesNeverReleasesPrematurely() {
        String ch = "bar-interleaved-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(
                () -> channelService.create(ch, "BARRIER interleaved check",
                        ChannelSemantic.BARRIER, "alice,bob"));

        try {
            // alice writes
            QuarkusTransaction.requiringNew().run(() -> {
                var channel = channelService.findByName(ch).orElseThrow();
                messageService.send(channel.id, "alice", MessageType.STATUS, "alice-ready", null, null);
            });

            // Check BETWEEN alice and bob — must stay blocked
            CheckResult midCheck = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 10, null, null, null));
            assertTrue(midCheck.messages().isEmpty(),
                    "BARRIER must not release after only alice writes; bob is still pending");
            assertNotNull(midCheck.barrierStatus());
            assertTrue(midCheck.barrierStatus().contains("bob"),
                    "mid-check barrierStatus must list bob as pending");

            // CRITICAL: alice's message must still be in the channel (mid-check didn't consume it)
            // Bob writes
            QuarkusTransaction.requiringNew().run(() -> {
                var channel = channelService.findByName(ch).orElseThrow();
                messageService.send(channel.id, "bob", MessageType.STATUS, "bob-ready", null, null);
            });

            // Now both have written — must release with BOTH messages
            CheckResult releaseCheck = QuarkusTransaction.requiringNew().call(
                    () -> tools.checkMessages(ch, 0L, 10, null, null, null));
            assertNull(releaseCheck.barrierStatus(),
                    "BARRIER must release once both alice and bob have written");
            assertEquals(2, releaseCheck.messages().size(),
                    "Released BARRIER must include alice's message (not consumed by mid-check) " +
                            "plus bob's message");
            assertTrue(releaseCheck.messages().stream().anyMatch(m -> "alice-ready".equals(m.content())),
                    "alice's message must be in the released payload — mid-check must not consume it");
            assertTrue(releaseCheck.messages().stream().anyMatch(m -> "bob-ready".equals(m.content())));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                channelService.findByName(ch).ifPresent(c -> Message.delete("channelId", c.id));
                Channel.delete("name", ch);
            });
        }
    }
}
