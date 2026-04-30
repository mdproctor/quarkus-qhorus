package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.ArtefactDetail;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Issue #121-D — 3-step chunked upload API: begin_artefact, append_chunk, finalize_artefact.
 *
 * <p>
 * Tests the full chunked upload lifecycle:
 * <ul>
 * <li>begin_artefact creates an incomplete artefact with the first chunk</li>
 * <li>append_chunk appends content without finalizing</li>
 * <li>finalize_artefact marks the artefact complete</li>
 * </ul>
 *
 * Refs #121.
 */
@QuarkusTest
class ChunkedUploadTest {

    @Inject
    QhorusMcpTools tools;

    @Test
    @TestTransaction
    void beginArtefactCreatesIncompleteArtefact() {
        ArtefactDetail result = tools.beginArtefact("chunk-begin-1", "Test artefact", "agent-a", "first chunk");

        assertNotNull(result);
        assertEquals("chunk-begin-1", result.key());
        assertEquals("Test artefact", result.description());
        assertEquals("agent-a", result.createdBy());
        assertEquals("first chunk", result.content());
        assertFalse(result.complete(), "begin_artefact should leave artefact incomplete");
        assertEquals(11, result.sizeBytes());
    }

    @Test
    @TestTransaction
    void appendChunkAppendsContentWithoutFinalizing() {
        tools.beginArtefact("chunk-append-1", "Chunked doc", "agent-b", "part1-");
        ArtefactDetail result = tools.appendChunk("chunk-append-1", "part2-");

        assertEquals("chunk-append-1", result.key());
        assertEquals("part1-part2-", result.content());
        assertFalse(result.complete(), "append_chunk should leave artefact incomplete");
        assertEquals(12, result.sizeBytes());
    }

    @Test
    @TestTransaction
    void finalizeArtefactMarksComplete() {
        tools.beginArtefact("chunk-final-1", "Finalized doc", "agent-c", "hello ");
        tools.appendChunk("chunk-final-1", "world ");
        ArtefactDetail result = tools.finalizeArtefact("chunk-final-1", "end");

        assertEquals("chunk-final-1", result.key());
        assertEquals("hello world end", result.content());
        assertTrue(result.complete(), "finalize_artefact should mark artefact complete");
        assertEquals(15, result.sizeBytes());
        assertNotNull(result.artefactId(), "finalized artefact should have a UUID");
    }

    @Test
    @TestTransaction
    void finalizeWithNoContentStillCompletes() {
        tools.beginArtefact("chunk-final-2", "No final content", "agent-d", "only-chunk");
        ArtefactDetail result = tools.finalizeArtefact("chunk-final-2", null);

        assertEquals("only-chunk", result.content());
        assertTrue(result.complete(), "finalize with null content should still complete");
    }

    @Test
    @TestTransaction
    void getArtefactShowsCorrectStateAtEachStep() {
        // Begin
        tools.beginArtefact("chunk-lifecycle-1", "Lifecycle test", "agent-e", "step1-");
        ArtefactDetail afterBegin = tools.getArtefact("chunk-lifecycle-1", null);
        assertFalse(afterBegin.complete());
        assertEquals("step1-", afterBegin.content());

        // Append
        tools.appendChunk("chunk-lifecycle-1", "step2-");
        ArtefactDetail afterAppend = tools.getArtefact("chunk-lifecycle-1", null);
        assertFalse(afterAppend.complete());
        assertEquals("step1-step2-", afterAppend.content());

        // Finalize
        tools.finalizeArtefact("chunk-lifecycle-1", "step3");
        ArtefactDetail afterFinalize = tools.getArtefact("chunk-lifecycle-1", null);
        assertTrue(afterFinalize.complete());
        assertEquals("step1-step2-step3", afterFinalize.content());
        assertEquals(17, afterFinalize.sizeBytes());
    }

    @Test
    @TestTransaction
    void multipleAppendChunksAccumulate() {
        tools.beginArtefact("chunk-multi-1", "Multi-chunk", "agent-f", "A");
        tools.appendChunk("chunk-multi-1", "B");
        tools.appendChunk("chunk-multi-1", "C");
        tools.appendChunk("chunk-multi-1", "D");
        ArtefactDetail result = tools.finalizeArtefact("chunk-multi-1", "E");

        assertEquals("ABCDE", result.content());
        assertTrue(result.complete());
        assertEquals(5, result.sizeBytes());
    }
}
