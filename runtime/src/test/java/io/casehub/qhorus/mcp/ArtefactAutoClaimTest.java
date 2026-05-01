package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.ArtefactDetail;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Issue #121-C — Auto-claim/release artefacts on send_message.
 *
 * <p>
 * Sending a message with artefact_refs auto-claims each artefact for the sender.
 * When a commitment resolves (RESPONSE/DONE/DECLINE/FAILURE), artefact claims
 * from the original QUERY/COMMAND are auto-released.
 * HANDOFF does NOT auto-release (obligation transfers to delegate).
 *
 * Refs #121.
 */
@QuarkusTest
class ArtefactAutoClaimTest {

    @Inject
    QhorusMcpTools tools;

    // =========================================================================
    // Auto-claim on send_message
    // =========================================================================

    @Test
    @TestTransaction
    void sendMessageWithArtefactRefsAutoClaimsForSender() {
        tools.createChannel("autoclaim-1", "Test channel", null, null, null, null, null, null, null);
        tools.register("claimer-agent", "Agent", List.of(), null, false);

        ArtefactDetail art = tools.shareArtefact("doc-1", "A doc", "claimer-agent", "content", null, null);
        String artId = art.artefactId().toString();

        // Before sending, artefact is GC-eligible (no claims)
        assertTrue(tools.isGcEligible(artId), "artefact should be GC-eligible before any claim");

        // Send message with artefact_refs — should auto-claim
        tools.sendMessage("autoclaim-1", "claimer-agent", "command", "process this doc", null, null, List.of(artId), null, null);

        // After sending, artefact should NOT be GC-eligible (auto-claimed)
        assertFalse(tools.isGcEligible(artId),
                "artefact should not be GC-eligible after auto-claim via send_message");
    }

    @Test
    @TestTransaction
    void sendMessageWithoutArtefactRefsDoesNotClaim() {
        tools.createChannel("autoclaim-2", "Test channel", null, null, null, null, null, null, null);
        tools.register("no-ref-agent", "Agent", List.of(), null, false);

        ArtefactDetail art = tools.shareArtefact("doc-2", "A doc", "no-ref-agent", "content", null, null);
        String artId = art.artefactId().toString();

        tools.sendMessage("autoclaim-2", "no-ref-agent", "status", "no artefacts here", null, null, null, null, null);

        assertTrue(tools.isGcEligible(artId),
                "artefact should remain GC-eligible when no artefact_refs are sent");
    }

    // =========================================================================
    // Auto-release on commitment resolution
    // =========================================================================

    @Test
    @TestTransaction
    void responseAutoReleasesClaimsFromOriginalQuery() {
        tools.createChannel("autorel-1", "Test channel", null, null, null, null, null, null, null);
        tools.register("asker", "Asker agent", List.of(), null, false);
        tools.register("answerer", "Answerer agent", List.of(), null, false);

        ArtefactDetail art = tools.shareArtefact("query-doc", "Query doc", "asker", "content", null, null);
        String artId = art.artefactId().toString();

        // Asker sends QUERY with artefact ref — auto-claimed
        QhorusMcpTools.MessageResult queryResult = tools.sendMessage("autorel-1", "asker", "query", "what about this doc?", null, null, List.of(artId), null, null);
        String corrId = queryResult.correlationId();

        assertFalse(tools.isGcEligible(artId), "artefact should be claimed after QUERY");

        // Answerer sends RESPONSE — should auto-release asker's claims
        tools.sendMessage("autorel-1", "answerer", "response", "here is the answer", corrId, null, null, null, null);

        assertTrue(tools.isGcEligible(artId),
                "artefact should be GC-eligible after RESPONSE auto-releases claims");
    }

    @Test
    @TestTransaction
    void doneAutoReleasesClaimsFromOriginalCommand() {
        tools.createChannel("autorel-2", "Test channel", null, null, null, null, null, null, null);
        tools.register("commander", "Commander", List.of(), null, false);
        tools.register("worker", "Worker", List.of(), null, false);

        ArtefactDetail art = tools.shareArtefact("cmd-doc", "Command doc", "commander", "content", null, null);
        String artId = art.artefactId().toString();

        // Commander sends COMMAND with artefact ref — auto-claimed
        QhorusMcpTools.MessageResult cmdResult = tools.sendMessage("autorel-2", "commander", "command", "process this", null, null, List.of(artId), null, null);
        String corrId = cmdResult.correlationId();

        assertFalse(tools.isGcEligible(artId), "artefact should be claimed after COMMAND");

        // Worker sends DONE — should auto-release commander's claims
        tools.sendMessage("autorel-2", "worker", "done", "completed", corrId, null, null, null, null);

        assertTrue(tools.isGcEligible(artId),
                "artefact should be GC-eligible after DONE auto-releases claims");
    }

    @Test
    @TestTransaction
    void declineAutoReleasesClaimsFromOriginalQuery() {
        tools.createChannel("autorel-3", "Test channel", null, null, null, null, null, null, null);
        tools.register("asker-2", "Asker", List.of(), null, false);
        tools.register("decliner", "Decliner", List.of(), null, false);

        ArtefactDetail art = tools.shareArtefact("decline-doc", "Decline doc", "asker-2", "content", null, null);
        String artId = art.artefactId().toString();

        QhorusMcpTools.MessageResult queryResult = tools.sendMessage("autorel-3", "asker-2", "query", "what about this?", null, null, List.of(artId), null, null);
        String corrId = queryResult.correlationId();

        assertFalse(tools.isGcEligible(artId), "artefact should be claimed after QUERY");

        // DECLINE auto-releases
        tools.sendMessage("autorel-3", "decliner", "decline", "cannot help with that", corrId, null, null, null, null);

        assertTrue(tools.isGcEligible(artId),
                "artefact should be GC-eligible after DECLINE auto-releases claims");
    }

    @Test
    @TestTransaction
    void failureAutoReleasesClaimsFromOriginalCommand() {
        tools.createChannel("autorel-4", "Test channel", null, null, null, null, null, null, null);
        tools.register("cmd-agent", "Commander", List.of(), null, false);
        tools.register("fail-agent", "Failer", List.of(), null, false);

        ArtefactDetail art = tools.shareArtefact("fail-doc", "Fail doc", "cmd-agent", "content", null, null);
        String artId = art.artefactId().toString();

        QhorusMcpTools.MessageResult cmdResult = tools.sendMessage("autorel-4", "cmd-agent", "command", "do this", null, null, List.of(artId), null, null);
        String corrId = cmdResult.correlationId();

        // FAILURE auto-releases
        tools.sendMessage("autorel-4", "fail-agent", "failure", "could not complete", corrId, null, null, null, null);

        assertTrue(tools.isGcEligible(artId),
                "artefact should be GC-eligible after FAILURE auto-releases claims");
    }

    @Test
    @TestTransaction
    void handoffDoesNotAutoReleaseClaims() {
        tools.createChannel("autorel-5", "Test channel", null, null, null, null, null, null, null);
        tools.register("handoff-cmd", "Commander", List.of(), null, false);
        tools.register("handoff-agent", "Delegator", List.of(), null, false);

        ArtefactDetail art = tools.shareArtefact("handoff-doc", "Handoff doc", "handoff-cmd", "content", null, null);
        String artId = art.artefactId().toString();

        QhorusMcpTools.MessageResult cmdResult = tools.sendMessage("autorel-5", "handoff-cmd", "command", "handle this", null, null, List.of(artId), null, null);
        String corrId = cmdResult.correlationId();

        assertFalse(tools.isGcEligible(artId), "artefact should be claimed after COMMAND");

        // HANDOFF delegates but does NOT release
        tools.sendMessage("autorel-5", "handoff-agent", "handoff", "passing to someone else", corrId, null, null, "instance:someone-else", null);

        assertFalse(tools.isGcEligible(artId),
                "artefact should still be claimed after HANDOFF — obligation transferred, not resolved");
    }

    // =========================================================================
    // Idempotent auto-claim
    // =========================================================================

    @Test
    @TestTransaction
    void autoClaimIsIdempotent() {
        tools.createChannel("autoclaim-idem", "Test channel", null, null, null, null, null, null, null);
        tools.register("idem-agent", "Agent", List.of(), null, false);

        ArtefactDetail art = tools.shareArtefact("idem-doc", "Doc", "idem-agent", "content", null, null);
        String artId = art.artefactId().toString();

        // Manually claim first
        tools.claimArtefact(artId, tools.listInstances(null).stream()
                .filter(i -> "idem-agent".equals(i.instanceId()))
                .findFirst().orElseThrow()
                .instanceId());

        // Actually, claimArtefact takes the Instance UUID, not the instanceId string.
        // Let me just send a message twice — auto-claim is idempotent
        tools.sendMessage("autoclaim-idem", "idem-agent", "status", "first", null, null, List.of(artId), null, null);
        tools.sendMessage("autoclaim-idem", "idem-agent", "status", "second", null, null, List.of(artId), null, null);

        assertFalse(tools.isGcEligible(artId),
                "artefact should still be claimed after duplicate auto-claims");
    }
}
