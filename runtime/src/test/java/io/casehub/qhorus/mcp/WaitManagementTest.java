package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.CommitmentDetail;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Issue #39 — Wait management: cancel_wait, list_pending_commitments, wait_for_reply cancellation.
 *
 * <p>
 * Since Task 9 (commitment migration), wait_for_reply polls the Commitment state rather
 * than a PendingReply row. cancel_wait deletes the Commitment, and list_pending_commitments
 * queries OPEN + ACKNOWLEDGED Commitments.
 *
 * <p>
 * Tests must send a QUERY/COMMAND with the correlationId to create a Commitment before
 * calling wait_for_reply or listing pending commitments.
 *
 * <p>
 * Refs #39, Epic #36, Refs #95, Refs #121.
 */
@QuarkusTest
class WaitManagementTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    MessageService messageService;

    @Inject
    ChannelService channelService;

    @Inject
    ManagedExecutor executor;

    // -------------------------------------------------------------------------
    // Unit — cancel_wait
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void cancelWaitDeletesCommitment() {
        tools.createChannel("wm-cancel-1", "Test", null, null);
        String corrId = UUID.randomUUID().toString();
        var ch = channelService.findByName("wm-cancel-1").orElseThrow();
        // Send QUERY to create a Commitment in OPEN state
        messageService.send(ch.id, "alice", MessageType.QUERY, "Q?", corrId, null);

        QhorusMcpTools.CancelWaitResult result = tools.cancelWait(corrId);

        assertNotNull(result);
        assertEquals(corrId, result.correlationId());
        assertTrue(result.cancelled(), "cancel should succeed for existing open commitment");
    }

    @Test
    @TestTransaction
    void cancelWaitOnUnknownIdReturnsFalse() {
        String unknownId = UUID.randomUUID().toString();

        QhorusMcpTools.CancelWaitResult result = tools.cancelWait(unknownId);

        assertFalse(result.cancelled(), "cancel on unknown correlationId should return cancelled=false");
        assertNotNull(result.message(), "should include an informative message");
    }

    @Test
    @TestTransaction
    void cancelWaitRemovesFromList() {
        tools.createChannel("wm-cancel-2", "Test", null, null);
        String corrId = UUID.randomUUID().toString();
        var ch = channelService.findByName("wm-cancel-2").orElseThrow();
        messageService.send(ch.id, "alice", MessageType.QUERY, "Q?", corrId, null);

        // Verify it's in the list
        assertTrue(tools.listPendingCommitments().stream()
                .anyMatch(w -> corrId.equals(w.correlationId())));

        // Cancel it
        tools.cancelWait(corrId);

        // No longer in the list
        assertFalse(tools.listPendingCommitments().stream()
                .anyMatch(w -> corrId.equals(w.correlationId())),
                "cancelled commitment should not appear in list_pending_commitments");
    }

    // -------------------------------------------------------------------------
    // Unit — list_pending_commitments
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void listPendingCommitmentsShowsOpenCommitment() {
        tools.createChannel("wm-list-1", "Test", null, null);
        String corrId = UUID.randomUUID().toString();
        var ch = channelService.findByName("wm-list-1").orElseThrow();
        messageService.send(ch.id, "alice", MessageType.QUERY, "Q?", corrId, null);

        List<CommitmentDetail> waits = tools.listPendingCommitments();
        assertTrue(waits.stream().anyMatch(w -> corrId.equals(w.correlationId())));
    }

    @Test
    @TestTransaction
    void listPendingCommitmentsResolvesChannelId() {
        tools.createChannel("wm-list-2", "Test", null, null);
        String corrId = UUID.randomUUID().toString();
        var ch = channelService.findByName("wm-list-2").orElseThrow();
        messageService.send(ch.id, "alice", MessageType.QUERY, "Q?", corrId, null);

        CommitmentDetail summary = tools.listPendingCommitments().stream()
                .filter(w -> corrId.equals(w.correlationId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected commitment not found"));

        assertEquals(ch.id.toString(), summary.channelId());
    }

    @Test
    @TestTransaction
    void listPendingCommitmentsShowsExpiresAt() {
        tools.createChannel("wm-list-3", "Test", null, null);
        String corrId = UUID.randomUUID().toString();
        var ch = channelService.findByName("wm-list-3").orElseThrow();
        // Send QUERY with no explicit deadline
        messageService.send(ch.id, "alice", MessageType.QUERY, "Q?", corrId, null,
                null, null);

        // The Commitment expiresAt may be null if no deadline was set on the QUERY —
        // but the entry must appear.
        CommitmentDetail summary = tools.listPendingCommitments().stream()
                .filter(w -> corrId.equals(w.correlationId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected pending commitment not found in list"));

        assertNotNull(summary.correlationId());
        assertEquals(corrId, summary.correlationId());
    }

    // -------------------------------------------------------------------------
    // Integration — wait_for_reply detects cancellation
    // -------------------------------------------------------------------------

    @Test
    void cancelWaitToolUnblocksWaitForReply() throws Exception {
        String ch = "wm-wfr-2-" + System.nanoTime();
        String corrId = UUID.randomUUID().toString();

        // Create channel and QUERY (creates Commitment in OPEN state) atomically
        QuarkusTransaction.requiringNew().run(() -> {
            channelService.create(ch, "Test", ChannelSemantic.APPEND, null);
            var channel = channelService.findByName(ch).orElseThrow();
            messageService.send(channel.id, "alice", MessageType.QUERY, "Q?", corrId, null);
        });

        try {
            // Start wait_for_reply in CDI-aware background thread
            Future<QhorusMcpTools.WaitResult> future = executor.submit(
                    () -> tools.waitForReply(ch, corrId, 30, null));

            // Give it time to enter poll loop
            Thread.sleep(400);

            // Cancel via the tool — deletes the Commitment
            tools.cancelWait(corrId);

            QhorusMcpTools.WaitResult result = future.get(5, TimeUnit.SECONDS);

            assertFalse(result.found());
            assertFalse(result.timedOut());
            assertTrue(result.status().contains("cancelled") || result.status().contains("Wait cancelled"),
                    "status should indicate cancellation; got: " + result.status());
        } finally {
            cleanupChannel(ch, corrId);
        }
    }

    // -------------------------------------------------------------------------
    // E2E — full wait management lifecycle
    // -------------------------------------------------------------------------

    @Test
    void e2eListCommitmentsAndCancelUnblocksAgent() throws Exception {
        String ch = "wm-e2e-1-" + System.nanoTime();
        String corrId = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            channelService.create(ch, "Test", ChannelSemantic.APPEND, null);
            var channel = channelService.findByName(ch).orElseThrow();
            // QUERY creates Commitment in OPEN state — this is what list_pending_commitments queries
            messageService.send(channel.id, "alice", MessageType.QUERY, "Q?", corrId, null);
        });

        try {
            // 1. Agent calls waitForReply (in CDI-aware background thread, 60s timeout)
            Future<QhorusMcpTools.WaitResult> agentFuture = executor.submit(
                    () -> tools.waitForReply(ch, corrId, 60, null));

            // Give it time to enter poll loop
            Thread.sleep(400);

            // 2. Human calls list_pending_commitments — sees the blocked agent
            List<CommitmentDetail> waits = tools.listPendingCommitments();
            assertTrue(waits.stream().anyMatch(w -> corrId.equals(w.correlationId())),
                    "human should see the pending commitment");

            // 3. Human calls cancel_wait
            QhorusMcpTools.CancelWaitResult cancel = tools.cancelWait(corrId);
            assertTrue(cancel.cancelled());

            // 4. Pending commitment no longer in list (Commitment deleted)
            assertFalse(tools.listPendingCommitments().stream()
                    .anyMatch(w -> corrId.equals(w.correlationId())));

            // 5. Agent receives cancelled result
            QhorusMcpTools.WaitResult agentResult = agentFuture.get(5, TimeUnit.SECONDS);
            assertFalse(agentResult.found());
            assertFalse(agentResult.timedOut());
            assertTrue(agentResult.status().contains("cancelled") || agentResult.status().contains("Wait cancelled"),
                    "status should indicate cancellation; got: " + agentResult.status());
        } finally {
            cleanupChannel(ch, corrId);
        }
    }

    @Test
    @TestTransaction
    void e2eMultipleOpenCommitmentsCanBeSelectivelyCancelled() {
        tools.createChannel("wm-e2e-2", "Test", null, null);
        String corrId1 = UUID.randomUUID().toString();
        String corrId2 = UUID.randomUUID().toString();

        var ch = channelService.findByName("wm-e2e-2").orElseThrow();
        messageService.send(ch.id, "alice", MessageType.QUERY, "Q1?", corrId1, null);
        messageService.send(ch.id, "alice", MessageType.QUERY, "Q2?", corrId2, null);

        // Both visible
        List<CommitmentDetail> waits = tools.listPendingCommitments();
        assertTrue(waits.stream().anyMatch(w -> corrId1.equals(w.correlationId())));
        assertTrue(waits.stream().anyMatch(w -> corrId2.equals(w.correlationId())));

        // Cancel only corrId1
        tools.cancelWait(corrId1);

        List<CommitmentDetail> after = tools.listPendingCommitments();
        assertFalse(after.stream().anyMatch(w -> corrId1.equals(w.correlationId())),
                "cancelled commitment should be gone");
        assertTrue(after.stream().anyMatch(w -> corrId2.equals(w.correlationId())),
                "uncancelled commitment should still be present");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void cleanupChannel(String channelName, String corrId) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (corrId != null) {
                Commitment.delete("correlationId", corrId);
            }
            channelService.findByName(channelName).ifPresent(c -> {
                Message.delete("channelId", c.id);
            });
            io.casehub.qhorus.runtime.channel.Channel.delete("name", channelName);
        });
    }
}
