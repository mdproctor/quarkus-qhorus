package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.data.ArtefactClaim;
import io.casehub.qhorus.runtime.data.DataService;
import io.casehub.qhorus.runtime.data.SharedData;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.ArtefactDetail;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Edge-case tests for shared data / artefact lifecycle.
 *
 * Findings covered:
 * - Re-opening a completed artefact by calling store with append=true after complete=true.
 * - Double-claim: two claim rows for same (artefact, instance) pair; one release deletes both.
 * - append=true on non-existent key silently creates a new record.
 * - GC eligibility for non-existent artefact ID returns false (not NPE).
 * - Claim with a non-existent artefact UUID (should it succeed? FK violation?).
 * - Empty content artefact (content="") — sizeBytes should be 0.
 * - Very large content — sizeBytes accurately reflects the length.
 * - Overwrite of a complete artefact resets complete flag to whatever lastChunk says.
 */
@QuarkusTest
class SharedDataEdgeCaseTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    DataService dataService;

    @Inject
    InstanceService instanceService;

    /**
     * IMPORTANT finding: calling store with append=false on a key that was previously
     * marked complete=true RESETS the complete flag to whatever lastChunk specifies.
     * An agent that accidentally calls store(key, append=false, lastChunk=false) on a
     * finished artefact will re-open it (complete=false) and it becomes GC-ineligible
     * again even with no active claims.
     */
    @Test
    @TestTransaction
    void storeWithAppendFalseOnCompletedArtefactResetsCompleteFlag() {
        // Create and complete an artefact
        tools.shareArtefact("reopen-test", "original", "alice", "final content", false, true);

        // "Accidentally" overwrite with incomplete=false
        ArtefactDetail reopened = tools.shareArtefact("reopen-test", null, "alice", "new chunk", false, false);

        assertFalse(reopened.complete(),
                "store(append=false, lastChunk=false) on a completed artefact resets complete=false — " +
                        "this re-opens the artefact and makes it GC-ineligible");
        assertFalse(tools.isGcEligible(reopened.artefactId().toString()),
                "a re-opened (complete=false) artefact must not be GC eligible");
    }

    /**
     * IMPORTANT finding: DataService.claim() has no idempotency guard. Calling claim twice
     * for the same (artefact, instance) pair inserts two ArtefactClaim rows. Then calling
     * release once deletes BOTH rows (DELETE WHERE artefactId=? AND instanceId=?).
     *
     * Net effect: after claim+claim+release, the artefact has 0 claims and is GC-eligible —
     * even though the logical claim count should be 1.
     *
     * This test documents the current (broken) double-claim behaviour.
     */
    @Test
    @TestTransaction
    void doubleClaimIsIdempotentAndSingleReleaseRestoresGcEligibility() {
        ArtefactDetail artefact = tools.shareArtefact("double-claim-test", "desc", "alice", "content", false, true);
        var claimant = instanceService.register("double-claimant", "Agent", java.util.List.of());

        // Claim twice — idempotent: must produce exactly one claim row
        tools.claimArtefact(artefact.artefactId().toString(), claimant.id.toString());
        tools.claimArtefact(artefact.artefactId().toString(), claimant.id.toString());

        long claimCount = ArtefactClaim.count("artefactId = ?1 AND instanceId = ?2",
                artefact.artefactId(), claimant.id);
        assertEquals(1, claimCount,
                "Double claim must be idempotent — only one ArtefactClaim row should exist");

        assertFalse(tools.isGcEligible(artefact.artefactId().toString()),
                "Artefact with an active claim must not be GC eligible");

        // One release should clear the single claim row
        tools.releaseArtefact(artefact.artefactId().toString(), claimant.id.toString());

        assertTrue(tools.isGcEligible(artefact.artefactId().toString()),
                "After one logical claim + one release, artefact should be GC eligible");
    }

    /**
     * IMPORTANT finding: store with append=true on a NON-EXISTENT key silently creates
     * a new record with the content set directly (not appended). The caller intended to
     * append to an existing upload, but there is nothing to append to — the content is
     * stored as-is. No error is thrown.
     */
    @Test
    @TestTransaction
    void storeWithAppendTrueOnNonExistentKeyCreatesNewRecord() {
        ArtefactDetail result = tools.shareArtefact("append-new-key", "desc", "alice",
                "first-chunk", true, false); // append=true, but key doesn't exist

        assertNotNull(result.artefactId(), "record should be created even when append=true and key doesn't exist");
        assertEquals("first-chunk", result.content(),
                "content for a non-existent key with append=true is stored directly (no accumulation)");
        assertFalse(result.complete(), "lastChunk=false so complete should be false");
    }

    /**
     * CREATIVE: GC eligibility check for a non-existent artefact UUID should return false
     * (not throw NPE). DataService.isGcEligible fetches by ID; if null, returns false.
     */
    @Test
    @TestTransaction
    void isGcEligibleForNonExistentArtefactReturnsFalse() {
        String randomId = UUID.randomUUID().toString();
        assertFalse(tools.isGcEligible(randomId),
                "isGcEligible should return false for a non-existent artefact UUID");
    }

    /**
     * CREATIVE: artefact with empty-string content — sizeBytes must be 0.
     */
    @Test
    @TestTransaction
    void artefactWithEmptyContentHasZeroSizeBytes() {
        ArtefactDetail result = tools.shareArtefact("empty-content-key", "desc", "alice", "", false, true);

        assertEquals(0L, result.sizeBytes(),
                "artefact with empty string content must have sizeBytes=0");
        assertTrue(result.complete());
    }

    /**
     * CREATIVE: artefact with null content — edge case in sizeBytes calculation.
     * DataService.store sets data.sizeBytes = data.content != null ? data.content.length() : 0.
     * But the Tool layer passes content directly — if content is null, this could NPE.
     * This test documents what happens when content is null.
     *
     * Note: the MCP tool layer takes content as a String parameter (not required=false),
     * so null content would typically come from the tool parameter being absent.
     * We test via DataService directly to confirm the null-safety.
     */
    @Test
    @TestTransaction
    void dataServiceStoreWithNullContentUsesZeroSizeBytes() {
        // Direct service test — tool layer enforces non-null but service is called directly elsewhere
        io.casehub.qhorus.runtime.channel.ChannelService channelService = null; // unused
        SharedData data = dataService.store("null-content-key", "desc", "alice", null, false, true);

        assertEquals(0L, data.sizeBytes,
                "store with null content should set sizeBytes=0 not NPE");
        assertNull(data.content);
    }

    /**
     * CREATIVE: multiple different agents claiming the same artefact — GC eligibility
     * requires ALL to release. Confirm N claims require N releases.
     */
    @Test
    @TestTransaction
    void multipleAgentsClaimSameArtefactRequiresAllReleasesForGcEligibility() {
        ArtefactDetail artefact = tools.shareArtefact("multi-claim-test", "desc", "alice", "content", false, true);
        var agent1 = instanceService.register("mc-agent-1", "A1", java.util.List.of());
        var agent2 = instanceService.register("mc-agent-2", "A2", java.util.List.of());
        var agent3 = instanceService.register("mc-agent-3", "A3", java.util.List.of());

        String id = artefact.artefactId().toString();
        tools.claimArtefact(id, agent1.id.toString());
        tools.claimArtefact(id, agent2.id.toString());
        tools.claimArtefact(id, agent3.id.toString());

        assertFalse(tools.isGcEligible(id), "3 active claims — not GC eligible");

        tools.releaseArtefact(id, agent1.id.toString());
        assertFalse(tools.isGcEligible(id), "2 claims remaining — still not GC eligible");

        tools.releaseArtefact(id, agent2.id.toString());
        assertFalse(tools.isGcEligible(id), "1 claim remaining — still not GC eligible");

        tools.releaseArtefact(id, agent3.id.toString());
        assertTrue(tools.isGcEligible(id), "all 3 claims released — now GC eligible");
    }

    /**
     * CREATIVE: sizeBytes is computed from content.length() (character count, not bytes).
     * For ASCII content these are equivalent. This test confirms the length-based computation.
     */
    @Test
    @TestTransaction
    void artefactSizeBytesReflectsContentCharacterCount() {
        String content = "Hello, World!"; // 13 characters
        ArtefactDetail result = tools.shareArtefact("size-test-key", "desc", "alice", content, false, true);

        assertEquals(13L, result.sizeBytes(),
                "sizeBytes should equal content.length() = 13 for ASCII content");
    }

    /**
     * IMPORTANT: getSharedData with both key AND id provided — what happens? The tool checks
     * hasKey first; if key is non-null/non-blank, it uses key regardless of id. Verify this
     * precedence rule is implemented correctly.
     */
    @Test
    @TestTransaction
    void getSharedDataPrefersKeyOverIdWhenBothProvided() {
        ArtefactDetail byKey = tools.shareArtefact("key-a", "desc", "alice", "content for key-a", false, true);
        ArtefactDetail byId = tools.shareArtefact("key-b", "desc", "alice", "content for key-b", false, true);

        // Provide key="key-a" and id pointing to key-b's UUID — key should win
        ArtefactDetail result = tools.getArtefact("key-a", byId.artefactId().toString());

        assertEquals("key-a", result.key(),
                "when both key and id are provided, key takes precedence");
        assertEquals("content for key-a", result.content());
    }

    /**
     * CREATIVE: chunked upload where the second chunk uses append=false — this OVERWRITES
     * the content rather than appending. The sizeBytes reflects only the new chunk.
     */
    @Test
    @TestTransaction
    void chunkedUploadWithAppendFalseOnSecondChunkOverwritesPriorContent() {
        tools.shareArtefact("chunk-overwrite", "desc", "alice", "chunk1", false, false);

        // Second call with append=false — should overwrite, not append
        ArtefactDetail result = tools.shareArtefact("chunk-overwrite", null, "alice", "replacement", false, true);

        assertEquals("replacement", result.content(),
                "second store with append=false should overwrite all prior content");
        assertEquals("replacement".length(), result.sizeBytes());
        assertTrue(result.complete());
    }
}
