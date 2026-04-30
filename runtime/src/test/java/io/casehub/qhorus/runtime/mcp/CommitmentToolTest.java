package io.casehub.qhorus.runtime.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.CommitmentDetail;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class CommitmentToolTest {

    @Inject
    QhorusMcpTools tools;

    @Test
    @TestTransaction
    void listMyCommitments_asObligor_showsCommandToMe() {
        String ch = "ct-ob-" + UUID.randomUUID();
        tools.createChannel(ch, "APPEND", null, null);
        var sent = tools.sendMessage(ch, "orchestrator", "command",
                "do the task", null, null, null, "role:worker", null);

        List<CommitmentDetail> open = tools.listMyCommitments(ch, "role:worker", "obligor");
        assertEquals(1, open.size());
        assertEquals(sent.correlationId(), open.get(0).correlationId());
        assertEquals("OPEN", open.get(0).state());
        assertEquals("role:worker", open.get(0).obligor());
    }

    @Test
    @TestTransaction
    void listMyCommitments_asRequester_showsPendingCommand() {
        String ch = "ct-rq-" + UUID.randomUUID();
        tools.createChannel(ch, "APPEND", null, null);
        tools.sendMessage(ch, "orchestrator", "command",
                "do the task", null, null, null, "role:worker", null);

        List<CommitmentDetail> open = tools.listMyCommitments(ch, "orchestrator", "requester");
        assertEquals(1, open.size());
        assertEquals("OPEN", open.get(0).state());
    }

    @Test
    @TestTransaction
    void listMyCommitments_fulfilledExcluded() {
        String ch = "ct-ful-" + UUID.randomUUID();
        tools.createChannel(ch, "APPEND", null, null);
        var sent = tools.sendMessage(ch, "req", "command", "task", null, null, null, "role:obl", null);
        tools.sendMessage(ch, "obl", "done", "done", sent.correlationId(), null, null, null, null);

        assertTrue(tools.listMyCommitments(ch, "role:obl", "obligor").isEmpty());
    }

    @Test
    @TestTransaction
    void getCommitment_returnsCurrentState() {
        String ch = "ct-get-" + UUID.randomUUID();
        tools.createChannel(ch, "APPEND", null, null);
        var sent = tools.sendMessage(ch, "req", "query",
                "what is the count?", null, null, null, null, null);

        CommitmentDetail detail = tools.getCommitment(sent.correlationId());
        assertEquals(sent.correlationId(), detail.correlationId());
        assertEquals("QUERY", detail.messageType());
        assertEquals("OPEN", detail.state());
        assertEquals("req", detail.requester());
    }

    @Test
    @TestTransaction
    void getCommitment_afterDone_showsFulfilled() {
        String ch = "ct-done-" + UUID.randomUUID();
        tools.createChannel(ch, "APPEND", null, null);
        var sent = tools.sendMessage(ch, "req", "command",
                "run the report", null, null, null, null, null);
        tools.sendMessage(ch, "obl", "done",
                "report complete", sent.correlationId(), null, null, null, null);

        CommitmentDetail detail = tools.getCommitment(sent.correlationId());
        assertEquals("FULFILLED", detail.state());
        assertNotNull(detail.resolvedAt());
    }
}
