package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.ToolCallException;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ArtefactRefValidationTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    InstanceService instanceService;

    @Test
    @TestTransaction
    void sendMessageWithValidArtefactRefSucceeds() {
        tools.createChannel("arv-ch-1", "Test", null, null, null, null, null, null, null);
        QhorusMcpTools.ArtefactDetail artefact = tools.shareArtefact(
                "arv-data-1", "desc", "alice", "content", false, true);

        assertDoesNotThrow(() -> tools.sendMessage("arv-ch-1", "alice", "status", "with valid ref", null, null, List.of(artefact.artefactId().toString()), null, null));
    }

    @Test
    @TestTransaction
    void sendMessageWithUnknownArtefactRefThrowsIllegalArgument() {
        tools.createChannel("arv-ch-2", "Test", null, null, null, null, null, null, null);
        String fakeUuid = UUID.randomUUID().toString();

        ToolCallException ex = assertThrows(ToolCallException.class,
                () -> tools.sendMessage("arv-ch-2", "alice", "status", "with bad ref", null, null, List.of(fakeUuid), null, null));

        assertTrue(ex.getMessage().contains(fakeUuid),
                "Error message should identify the unknown artefact UUID");
    }

    @Test
    @TestTransaction
    void sendMessageWithMixedValidAndInvalidRefsThrows() {
        tools.createChannel("arv-ch-3", "Test", null, null, null, null, null, null, null);
        QhorusMcpTools.ArtefactDetail good = tools.shareArtefact(
                "arv-data-3", "desc", "alice", "content", false, true);
        String badUuid = UUID.randomUUID().toString();

        ToolCallException ex = assertThrows(ToolCallException.class,
                () -> tools.sendMessage("arv-ch-3", "alice", "status", "mixed refs", null, null, List.of(good.artefactId().toString(), badUuid), null, null));

        assertTrue(ex.getMessage().contains(badUuid));
    }

    @Test
    @TestTransaction
    void sendMessageWithNullArtefactRefsSkipsValidation() {
        tools.createChannel("arv-ch-4", "Test", null, null, null, null, null, null, null);

        // null refs should always succeed — no validation needed
        assertDoesNotThrow(() -> tools.sendMessage("arv-ch-4", "alice", "status", "no refs", null, null, null, null, null));
    }

    @Test
    @TestTransaction
    void sendMessageWithEmptyArtefactRefsListSkipsValidation() {
        tools.createChannel("arv-ch-5", "Test", null, null, null, null, null, null, null);

        assertDoesNotThrow(() -> tools.sendMessage("arv-ch-5", "alice", "status", "empty refs", null, null, List.of(), null, null));
    }

    @Test
    @TestTransaction
    void sendMessageWithIncompleteArtefactRefIsAllowed() {
        // An artefact mid-chunked-upload (complete=false) can still be referenced —
        // the receiver checks completeness via get_artefact before consuming
        tools.createChannel("arv-ch-6", "Test", null, null, null, null, null, null, null);
        QhorusMcpTools.ArtefactDetail incomplete = tools.shareArtefact(
                "arv-data-6", "desc", "alice", "chunk1", false, false);

        assertDoesNotThrow(() -> tools.sendMessage("arv-ch-6", "alice", "status", "ref to incomplete", null, null, List.of(incomplete.artefactId().toString()), null, null),
                "References to incomplete artefacts should be allowed");
    }
}
