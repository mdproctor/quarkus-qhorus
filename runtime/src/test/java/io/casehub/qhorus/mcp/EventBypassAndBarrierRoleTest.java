package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Issue #31 — EVENT message target bypass and BARRIER+role broadcast semantics.
 *
 * <h2>EVENT bypass</h2>
 * <p>
 * {@code type:event} messages are observer telemetry (routing decisions, queue depth, system
 * signals). They must bypass the address filter regardless of their {@code target} field — events
 * are always broadcast. The test vector is {@code get_replies}, which unlike {@code check_messages}
 * does not pre-filter by message type and therefore passes event messages through
 * {@code isVisibleToReader}.
 *
 * <h2>BARRIER+role semantics</h2>
 * <p>
 * A BARRIER channel releases when all declared contributors (by name) have each written at
 * least one message. Targeting is a read-side filter that controls who can see a message; it does
 * NOT affect who is counted as a contributor.
 *
 * <p>
 * Design rule: a {@code role:reviewer} broadcast from Alice does NOT auto-contribute on behalf
 * of all role members. Alice's message counts only Alice as a contributor. Bob must independently
 * call {@code send_message} to count as his contribution.
 *
 * <p>
 * This rule keeps BARRIER semantics simple and avoids server-side fan-out. It is non-obvious
 * and documented here to prevent future regressions.
 *
 * <p>
 * Known architecture note: COLLECT+role broadcast has the same double-delivery risk as
 * documented in {@code CollectAtomicityTest} — COLLECT clears the channel atomically but
 * concurrent readers may still race. Sequential agent delivery (realistic) is safe.
 *
 * <p>
 * Refs #31, Epic #27.
 */
@QuarkusTest
class EventBypassAndBarrierRoleTest {

    @Inject
    QhorusMcpTools tools;

    // =========================================================================
    // EVENT bypass — target filter skipped for type:event messages
    // =========================================================================

    @Test
    @TestTransaction
    void eventReplyWithInstanceTargetIsVisibleToAllReadersViaGetReplies() {
        // get_replies does not pre-filter by messageType, so event messages pass through
        // isVisibleToReader. The EVENT bypass ensures that a targeted event is still visible
        // to all readers, not just the targeted instance.
        tools.createChannel("evt-bp-1", "Test", "APPEND", null);
        tools.register("bob", "Bob the observer", List.of(), null);

        QhorusMcpTools.MessageResult parent = tools.sendMessage("evt-bp-1", "alice", "command", "work item", null, null, null,
                null);

        // System emits an event targeted at alice — telemetry, not work
        tools.sendMessage("evt-bp-1", "system", "event", "telemetry for alice",
                null, parent.messageId(), null, "instance:alice");

        // Bob reads replies — event must bypass the instance:alice filter and be visible
        List<QhorusMcpTools.MessageSummary> bobReplies = tools.getReplies(parent.messageId(), "bob");
        assertEquals(1, bobReplies.size(),
                "event message should bypass target filtering and be visible to all readers");
        assertEquals("EVENT", bobReplies.get(0).messageType());
    }

    @Test
    @TestTransaction
    void eventReplyWithCapabilityTargetIsVisibleToAllReadersViaGetReplies() {
        tools.createChannel("evt-bp-2", "Test", "APPEND", null);
        // bob has no capability:code-review — but the event should still be visible
        tools.register("bob", "Bob", List.of("capability:python"), null);

        QhorusMcpTools.MessageResult parent = tools.sendMessage("evt-bp-2", "alice", "command", "work item", null, null, null,
                null);

        tools.sendMessage("evt-bp-2", "system", "event", "routing telemetry",
                null, parent.messageId(), null, "capability:code-review");

        List<QhorusMcpTools.MessageSummary> bobReplies = tools.getReplies(parent.messageId(), "bob");
        assertEquals(1, bobReplies.size(),
                "event with capability target should bypass filter and be visible even to readers without that capability");
    }

    @Test
    @TestTransaction
    void nonEventTargetedReplyRemainsFilteredForWrongReader() {
        // Sanity check: the event bypass only applies to type:event, not other types
        tools.createChannel("evt-bp-3", "Test", "APPEND", null);
        tools.register("bob", "Bob", List.of(), null);

        QhorusMcpTools.MessageResult parent = tools.sendMessage("evt-bp-3", "alice", "query", "question", null, null, null,
                null);

        // A regular response targeted at alice
        tools.sendMessage("evt-bp-3", "system", "response", "answer for alice",
                null, parent.messageId(), null, "instance:alice");

        // Bob should NOT see it — regular message, targeted at alice, not event
        List<QhorusMcpTools.MessageSummary> bobReplies = tools.getReplies(parent.messageId(), "bob");
        assertTrue(bobReplies.isEmpty(),
                "non-event targeted message must still be filtered for wrong reader");
    }

    // =========================================================================
    // BARRIER+role semantics
    // =========================================================================

    @Test
    @TestTransaction
    void barrierRoleBroadcastCountsOnlyAsSenderContribution() {
        // BARRIER has contributors "alice" and "bob".
        // Alice sends a role:reviewer broadcast — visible to both.
        // Only alice is counted as having contributed; bob must send his own message.
        tools.createChannel("barr-role-1", "Test", "BARRIER", "alice,bob");
        tools.register("alice", "Alice reviewer", List.of("role:reviewer"), null);
        tools.register("bob", "Bob reviewer", List.of("role:reviewer"), null);

        // Alice sends with role:reviewer target — broadcast, both alice and bob can read it
        tools.sendMessage("barr-role-1", "alice", "response", "alice's contribution",
                null, null, null, "role:reviewer");

        // BARRIER should still be waiting — alice's broadcast does not count for bob
        QhorusMcpTools.CheckResult pendingResult = tools.checkMessages("barr-role-1", 0L, 10, null, null);
        assertNotNull(pendingResult.barrierStatus(),
                "BARRIER should still be pending after only alice wrote");
        assertTrue(pendingResult.barrierStatus().contains("bob"),
                "BARRIER should list bob as a pending contributor");

        // Bob sends his own contribution
        tools.sendMessage("barr-role-1", "bob", "response", "bob's contribution",
                null, null, null, null);

        // Now BARRIER should release
        QhorusMcpTools.CheckResult releasedResult = tools.checkMessages("barr-role-1", 0L, 10, null, null);
        assertNull(releasedResult.barrierStatus(),
                "BARRIER should release after all declared contributors have individually written");
        assertEquals(2, releasedResult.messages().size(),
                "BARRIER should deliver both messages on release");
    }

    @Test
    @TestTransaction
    void barrierReleasesAfterEachContributorWritesRegardlessOfTarget() {
        // Verify that targeting on messages does not affect BARRIER contribution tracking.
        // Contributions are counted by sender, not by who the message is addressed to.
        tools.createChannel("barr-role-2", "Test", "BARRIER", "alice,bob");

        // Alice sends to bob specifically
        tools.sendMessage("barr-role-2", "alice", "response", "to bob", null, null, null, "instance:bob");
        // Bob sends to alice specifically
        tools.sendMessage("barr-role-2", "bob", "response", "to alice", null, null, null, "instance:alice");

        // Both have written — BARRIER must release regardless of targets
        QhorusMcpTools.CheckResult result = tools.checkMessages("barr-role-2", 0L, 10, null, null);
        assertNull(result.barrierStatus(),
                "BARRIER must release when all contributors have written, regardless of message targets");
        assertEquals(2, result.messages().size());
    }

    @Test
    @TestTransaction
    void barrierStillBlocksWhenOnlyOneOfTwoRoleMembersHasWritten() {
        // Sanity: BARRIER with two contributors requires both to write independently.
        tools.createChannel("barr-role-3", "Test", "BARRIER", "alice,bob");

        // Only alice writes
        tools.sendMessage("barr-role-3", "alice", "response", "alice done", null, null, null, null);

        QhorusMcpTools.CheckResult result = tools.checkMessages("barr-role-3", 0L, 10, null, null);
        assertNotNull(result.barrierStatus());
        assertTrue(result.messages().isEmpty(), "BARRIER should not release until bob also writes");
    }
}
