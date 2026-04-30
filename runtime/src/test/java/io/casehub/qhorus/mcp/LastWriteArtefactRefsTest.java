package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.ToolCallException;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.ArtefactDetail;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.CheckResult;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.MessageResult;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests for artefact_refs behaviour on LAST_WRITE channels.
 *
 * The LAST_WRITE overwrite path directly sets {@code last.artefactRefs = refsStr}
 * rather than calling messageService.send(). This means artefact refs are replaced
 * (not accumulated) on each overwrite. These tests pin that replacement semantics
 * and verify that dangling refs cannot be attached even on the overwrite path.
 */
@QuarkusTest
class LastWriteArtefactRefsTest {

    @Inject
    QhorusMcpTools tools;

    /**
     * IMPORTANT: LAST_WRITE overwrite with a different artefact ref set replaces
     * the stored refs, not accumulates them.
     *
     * If the overwrite path had a bug where it appended to the existing comma-separated
     * string instead of replacing it, old refs would accumulate with every write.
     * This test verifies that only the latest ref set is stored.
     */
    @Test
    @TestTransaction
    void lastWriteOverwriteReplacesArtefactRefsNotAccumulatesThem() {
        tools.createChannel("lw-refs-replace", "LAST_WRITE artefact refs", "LAST_WRITE", null);
        ArtefactDetail ref1 = tools.shareArtefact("lw-ref-data-1", "d", "alice", "content1", false, true);
        ArtefactDetail ref2 = tools.shareArtefact("lw-ref-data-2", "d", "alice", "content2", false, true);
        ArtefactDetail ref3 = tools.shareArtefact("lw-ref-data-3", "d", "alice", "content3", false, true);

        // First write: attach ref1 and ref2
        tools.sendMessage("lw-refs-replace", "alice", "status", "v1",
                null, null, List.of(ref1.artefactId().toString(), ref2.artefactId().toString()));

        // Overwrite: attach only ref3 — ref1 and ref2 must be GONE
        MessageResult overwrite = tools.sendMessage("lw-refs-replace", "alice", "status", "v2",
                null, null, List.of(ref3.artefactId().toString()));

        // The returned MessageResult should reflect only ref3
        assertEquals(1, overwrite.artefactRefs().size(),
                "LAST_WRITE overwrite must replace artefact refs, not accumulate; " +
                        "expected 1 ref (ref3) but got " + overwrite.artefactRefs().size());
        assertTrue(overwrite.artefactRefs().contains(ref3.artefactId().toString()),
                "overwrite must contain ref3");
        assertFalse(overwrite.artefactRefs().contains(ref1.artefactId().toString()),
                "ref1 from first write must be gone after overwrite");
        assertFalse(overwrite.artefactRefs().contains(ref2.artefactId().toString()),
                "ref2 from first write must be gone after overwrite");

        // Confirm via checkMessages that the stored row has only ref3
        CheckResult check = tools.checkMessages("lw-refs-replace", 0L, 10, null);
        assertEquals(1, check.messages().size());
        assertEquals(1, check.messages().get(0).artefactRefs().size(),
                "checkMessages must show only 1 artefact ref after LAST_WRITE overwrite");
        assertEquals(ref3.artefactId().toString(),
                check.messages().get(0).artefactRefs().get(0));
    }

    /**
     * IMPORTANT: LAST_WRITE overwrite with null refs clears the stored artefact refs.
     *
     * If the first write attaches refs and the second write passes null refs, the
     * stored row must have no refs. The overwrite path sets `last.artefactRefs = refsStr`
     * where refsStr is null when artefactRefs is null — so the stored value becomes null.
     */
    @Test
    @TestTransaction
    void lastWriteOverwriteWithNullRefsClearsStoredRefs() {
        tools.createChannel("lw-refs-clear", "LAST_WRITE clear refs", "LAST_WRITE", null);
        ArtefactDetail ref = tools.shareArtefact("lw-ref-clear-data", "d", "alice", "content", false, true);

        // First write: attach ref
        tools.sendMessage("lw-refs-clear", "alice", "status", "v1",
                null, null, List.of(ref.artefactId().toString()));

        // Overwrite with null refs — stored refs must be cleared
        MessageResult overwrite = tools.sendMessage("lw-refs-clear", "alice", "status", "v2",
                null, null, null);

        assertTrue(overwrite.artefactRefs().isEmpty(),
                "LAST_WRITE overwrite with null refs must clear the stored artefact refs");

        CheckResult check = tools.checkMessages("lw-refs-clear", 0L, 10, null);
        assertTrue(check.messages().get(0).artefactRefs().isEmpty(),
                "checkMessages must show empty artefact refs after LAST_WRITE overwrite with null refs");
    }

    /**
     * CRITICAL: LAST_WRITE overwrite with an unknown artefact UUID must be rejected,
     * even though the overwrite path goes through a different code path than the initial
     * send. The artefact validation runs before the LAST_WRITE check, so it applies to
     * both initial writes and overwrites.
     *
     * This is a critical invariant: dangling artefact refs must never be stored,
     * regardless of whether this is a new message or a LAST_WRITE overwrite.
     */
    @Test
    @TestTransaction
    void lastWriteOverwriteWithUnknownArtefactRefIsRejected() {
        tools.createChannel("lw-refs-bad-overwrite", "LAST_WRITE bad ref test", "LAST_WRITE", null);

        // First write succeeds (no refs)
        tools.sendMessage("lw-refs-bad-overwrite", "alice", "status", "v1", null, null);

        // Overwrite with a dangling (non-existent) artefact UUID — must be rejected
        String fakeUuid = java.util.UUID.randomUUID().toString();
        ToolCallException ex = assertThrows(ToolCallException.class,
                () -> tools.sendMessage("lw-refs-bad-overwrite", "alice", "status", "v2",
                        null, null, List.of(fakeUuid)),
                "LAST_WRITE overwrite with an unknown artefact UUID must be rejected before the write");

        assertTrue(ex.getMessage().contains(fakeUuid),
                "rejection message must identify the unknown artefact UUID");

        // The existing message must be unchanged (v1, no refs)
        CheckResult check = tools.checkMessages("lw-refs-bad-overwrite", 0L, 10, null);
        assertEquals(1, check.messages().size());
        assertEquals("v1", check.messages().get(0).content(),
                "failed overwrite must not modify the existing LAST_WRITE message");
        assertTrue(check.messages().get(0).artefactRefs().isEmpty());
    }

    /**
     * CREATIVE: LAST_WRITE channel with artefact refs on the initial write — verify that
     * a same-sender overwrite with an EMPTY list (not null) also clears the stored refs.
     *
     * Empty list vs. null list: both result in refsStr=null in the implementation,
     * so both should clear. This tests the empty-list path specifically.
     */
    @Test
    @TestTransaction
    void lastWriteOverwriteWithEmptyRefsListClearsStoredRefs() {
        tools.createChannel("lw-refs-empty-list", "LAST_WRITE empty list", "LAST_WRITE", null);
        ArtefactDetail ref = tools.shareArtefact("lw-ref-empty-data", "d", "alice", "content", false, true);

        // First write: attach ref
        tools.sendMessage("lw-refs-empty-list", "alice", "status", "v1",
                null, null, List.of(ref.artefactId().toString()));

        // Overwrite with empty list — stored refs must be cleared
        MessageResult overwrite = tools.sendMessage("lw-refs-empty-list", "alice", "status", "v2",
                null, null, List.of());

        assertTrue(overwrite.artefactRefs().isEmpty(),
                "LAST_WRITE overwrite with empty list must clear the stored artefact refs, " +
                        "not keep the previous ref set");
    }
}
