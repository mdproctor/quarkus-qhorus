package io.casehub.qhorus.runtime.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.runtime.store.CommitmentStore;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * E2E tests verifying the full commitment lifecycle is tracked as MCP tool calls are made.
 * Simulates realistic agent interactions using the public MCP tool API.
 */
@QuarkusTest
class CommitmentLifecycleTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    CommitmentStore commitmentStore;

    @Test
    @TestTransaction
    void commandLifecycle_openStatusDone_tracksCorrectly() {
        String ch = "e2e-cmd-" + UUID.randomUUID();
        tools.createChannel(ch, "APPEND", null, null);

        // Orchestrator sends COMMAND — creates OPEN commitment
        var cmd = tools.sendMessage(ch, "orchestrator", "command",
                "review the auth module", null, null, null, "role:reviewer", null);
        String corrId = cmd.correlationId();
        assertNotNull(corrId);

        var commitment = commitmentStore.findByCorrelationId(corrId);
        assertTrue(commitment.isPresent());
        assertEquals(CommitmentState.OPEN, commitment.get().state);
        assertEquals("orchestrator", commitment.get().requester);
        assertEquals("role:reviewer", commitment.get().obligor);

        // Reviewer sends STATUS — transitions to ACKNOWLEDGED
        tools.sendMessage(ch, "reviewer", "status",
                "reviewing now", corrId, null, null, null, null);
        assertEquals(CommitmentState.ACKNOWLEDGED,
                commitmentStore.findByCorrelationId(corrId).get().state);
        assertNotNull(commitmentStore.findByCorrelationId(corrId).get().acknowledgedAt);

        // Reviewer sends DONE — transitions to FULFILLED
        tools.sendMessage(ch, "reviewer", "done",
                "review complete — no issues found", corrId, null, null, null, null);
        var fulfilled = commitmentStore.findByCorrelationId(corrId).get();
        assertEquals(CommitmentState.FULFILLED, fulfilled.state);
        assertNotNull(fulfilled.resolvedAt);
    }

    @Test
    @TestTransaction
    void queryLifecycle_queryResponse_tracksCorrectly() {
        String ch = "e2e-qry-" + UUID.randomUUID();
        tools.createChannel(ch, "APPEND", null, null);

        var q = tools.sendMessage(ch, "agent-a", "query",
                "what is the current row count?", null, null, null, null, null);
        assertEquals(CommitmentState.OPEN,
                commitmentStore.findByCorrelationId(q.correlationId()).get().state);

        tools.sendMessage(ch, "agent-b", "response",
                "current count: 42", q.correlationId(), null, null, null, null);
        assertEquals(CommitmentState.FULFILLED,
                commitmentStore.findByCorrelationId(q.correlationId()).get().state);
    }

    @Test
    @TestTransaction
    void declineLifecycle_commandDecline_tracksCorrectly() {
        String ch = "e2e-dcl-" + UUID.randomUUID();
        tools.createChannel(ch, "APPEND", null, null);

        var cmd = tools.sendMessage(ch, "orchestrator", "command",
                "perform a financial audit", null, null, null, "role:code-reviewer", null);

        tools.sendMessage(ch, "code-reviewer", "decline",
                "outside my capabilities — I am a code reviewer, not an auditor",
                cmd.correlationId(), null, null, null, null);

        var declined = commitmentStore.findByCorrelationId(cmd.correlationId()).get();
        assertEquals(CommitmentState.DECLINED, declined.state);
        assertNotNull(declined.resolvedAt);
    }

    @Test
    @TestTransaction
    void handoffLifecycle_createsDelegationChain() {
        String ch = "e2e-hof-" + UUID.randomUUID();
        tools.createChannel(ch, "APPEND", null, null);

        var cmd = tools.sendMessage(ch, "orchestrator", "command",
                "run compliance check", null, null, null, "role:agent-a", null);
        String corrId = cmd.correlationId();
        UUID parentId = commitmentStore.findByCorrelationId(corrId).get().id;

        // agent-a handoffs to compliance-specialist
        tools.sendMessage(ch, "agent-a", "handoff",
                "routing to compliance specialist", corrId, null, null,
                "role:compliance-specialist", null);

        // Original commitment is DELEGATED
        var parent = commitmentStore.findById(parentId).get();
        assertEquals(CommitmentState.DELEGATED, parent.state);
        assertEquals("role:compliance-specialist", parent.delegatedTo);
        assertNotNull(parent.resolvedAt);

        // Child commitment is OPEN for compliance-specialist
        var children = commitmentStore.findOpenByObligor("role:compliance-specialist", parent.channelId);
        assertEquals(1, children.size());
        assertEquals(corrId, children.get(0).correlationId);
        assertEquals(parentId, children.get(0).parentCommitmentId);
    }
}
