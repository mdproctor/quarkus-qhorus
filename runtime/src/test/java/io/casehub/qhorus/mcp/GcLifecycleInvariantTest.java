package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.data.ArtefactClaim;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.ArtefactDetail;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests for the GC eligibility invariant:
 *
 * isGcEligible = complete AND claimCount == 0
 *
 * Both conditions must be independently necessary. Violating either one is catastrophic:
 * - GC'ing an incomplete artefact loses data mid-upload.
 * - GC'ing a claimed artefact loses data an agent is actively using.
 *
 * This test class focuses on corner cases not covered by existing tests:
 * - Release by a different instance (no-op — must not reduce another instance's claim).
 * - Claim followed by complete=false overwrite (must make ineligible on both axes).
 * - Multiple instances each holding independent claims.
 * - Release of non-existent claim (no-op, must not make eligible prematurely).
 */
@QuarkusTest
class GcLifecycleInvariantTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    InstanceService instanceService;

    /**
     * CRITICAL: releasing a claim by the WRONG instance must not affect another
     * instance's claim. If agent A claims an artefact and agent B releases it,
     * agent A's claim must remain active.
     *
     * The implementation uses DELETE WHERE artefactId=? AND instanceId=?, so a
     * release by the wrong instanceId is a no-op. This test verifies it.
     */
    @Test
    @TestTransaction
    void releaseByWrongInstanceDoesNotReduceRealClaimantsCount() {
        ArtefactDetail artefact = tools.shareArtefact("gc-wrong-release", "d", "alice", "content", false, true);
        var claimant = instanceService.register("gc-claimant", "Agent", List.of());
        var nonClaimant = instanceService.register("gc-non-claimant", "Other Agent", List.of());

        // claimant claims the artefact
        tools.claimArtefact(artefact.artefactId().toString(), claimant.id.toString());
        assertFalse(tools.isGcEligible(artefact.artefactId().toString()),
                "artefact with 1 claim must not be GC eligible");

        // nonClaimant releases (a claim that doesn't exist) — must be a no-op
        tools.releaseArtefact(artefact.artefactId().toString(), nonClaimant.id.toString());

        // claimant's claim must still exist
        long claimCount = ArtefactClaim.count("artefactId = ?1 AND instanceId = ?2",
                artefact.artefactId(), claimant.id);
        assertEquals(1, claimCount,
                "releasing by a different instance must not remove the original claimant's claim");
        assertFalse(tools.isGcEligible(artefact.artefactId().toString()),
                "artefact must remain non-GC-eligible after wrong-instance release");
    }

    /**
     * CRITICAL: an artefact that is complete and has zero claims becomes GC eligible.
     * Then it receives a new claim — it must immediately become non-eligible again.
     *
     * This tests the temporal correctness of the eligibility check: it reflects the
     * current state, not a cached value.
     */
    @Test
    @TestTransaction
    void gcEligibilityChangesCorrectlyWhenClaimStateChanges() {
        ArtefactDetail artefact = tools.shareArtefact("gc-temporal", "d", "alice", "content", false, true);
        var agent1 = instanceService.register("gc-temporal-agent1", "A1", List.of());
        var agent2 = instanceService.register("gc-temporal-agent2", "A2", List.of());

        // No claims — GC eligible
        assertTrue(tools.isGcEligible(artefact.artefactId().toString()),
                "complete artefact with no claims must be GC eligible");

        // agent1 claims — no longer eligible
        tools.claimArtefact(artefact.artefactId().toString(), agent1.id.toString());
        assertFalse(tools.isGcEligible(artefact.artefactId().toString()),
                "must not be GC eligible after agent1 claims");

        // agent2 claims — still not eligible
        tools.claimArtefact(artefact.artefactId().toString(), agent2.id.toString());
        assertFalse(tools.isGcEligible(artefact.artefactId().toString()),
                "must not be GC eligible when both agent1 and agent2 have claims");

        // agent1 releases — still not eligible (agent2 still holds)
        tools.releaseArtefact(artefact.artefactId().toString(), agent1.id.toString());
        assertFalse(tools.isGcEligible(artefact.artefactId().toString()),
                "must not be GC eligible when agent2 still holds a claim");

        // agent2 releases — now eligible again
        tools.releaseArtefact(artefact.artefactId().toString(), agent2.id.toString());
        assertTrue(tools.isGcEligible(artefact.artefactId().toString()),
                "must be GC eligible once all claims are released");
    }

    /**
     * CRITICAL: artefact that is claimed AND then made incomplete by a store(append=false,
     * lastChunk=false) overwrite — must not be GC eligible on EITHER axis (incomplete AND claimed).
     */
    @Test
    @TestTransaction
    void artefactThatIsClaimedAndThenReopenedIsNeverGcEligible() {
        ArtefactDetail artefact = tools.shareArtefact("gc-claimed-reopened", "d", "alice", "v1", false, true);
        var agent = instanceService.register("gc-reopened-agent", "Agent", List.of());

        // agent claims the complete artefact
        tools.claimArtefact(artefact.artefactId().toString(), agent.id.toString());
        assertFalse(tools.isGcEligible(artefact.artefactId().toString()));

        // Reopen the artefact (complete=false)
        tools.shareArtefact("gc-claimed-reopened", null, "alice", "chunk2", false, false);

        // Now it's both incomplete AND claimed — definitely not eligible
        assertFalse(tools.isGcEligible(artefact.artefactId().toString()),
                "artefact that is incomplete AND claimed must not be GC eligible");

        // Release the claim — still not eligible because incomplete
        tools.releaseArtefact(artefact.artefactId().toString(), agent.id.toString());
        assertFalse(tools.isGcEligible(artefact.artefactId().toString()),
                "artefact that is incomplete (even with no claims) must not be GC eligible");

        // Complete the artefact — now eligible
        tools.shareArtefact("gc-claimed-reopened", null, "alice", " done", true, true);
        assertTrue(tools.isGcEligible(artefact.artefactId().toString()),
                "artefact that is now complete and has no claims must be GC eligible");
    }

    /**
     * IMPORTANT: releasing a claim for a non-existent artefact ID must not throw.
     * The DELETE WHERE query returns 0 rows — that is a valid outcome.
     */
    @Test
    @TestTransaction
    void releaseOfNonExistentClaimIsNoOpNotException() {
        var agent = instanceService.register("gc-ghost-release", "Agent", List.of());
        String phantomArtefactId = java.util.UUID.randomUUID().toString();

        // The artefact doesn't exist; neither does any claim — release must be a no-op
        assertDoesNotThrow(
                () -> tools.releaseArtefact(phantomArtefactId, agent.id.toString()),
                "releasing a non-existent claim must be a no-op, not an exception");
    }

    /**
     * CREATIVE: claim is idempotent but the N-instance requirement still holds.
     *
     * Three separate agents claim the same artefact. One agent claims twice (idempotent).
     * Total unique claims = 3. All three must release before GC eligibility is restored.
     */
    @Test
    @TestTransaction
    void gcEligibilityRequiresAllDistinctInstancesRelease() {
        ArtefactDetail artefact = tools.shareArtefact("gc-distinct-release", "d", "alice", "data", false, true);
        var a1 = instanceService.register("gc-distinct-a1", "A1", List.of());
        var a2 = instanceService.register("gc-distinct-a2", "A2", List.of());
        var a3 = instanceService.register("gc-distinct-a3", "A3", List.of());

        String id = artefact.artefactId().toString();
        tools.claimArtefact(id, a1.id.toString());
        tools.claimArtefact(id, a1.id.toString()); // idempotent — still 1 claim from a1
        tools.claimArtefact(id, a2.id.toString());
        tools.claimArtefact(id, a3.id.toString());

        // 3 distinct claims (a1 double-claim is idempotent)
        long claimCount = ArtefactClaim.count("artefactId", artefact.artefactId());
        assertEquals(3, claimCount,
                "double-claim by a1 must be idempotent — exactly 3 distinct claims");

        assertFalse(tools.isGcEligible(id));
        tools.releaseArtefact(id, a1.id.toString());
        assertFalse(tools.isGcEligible(id), "a2 and a3 still hold claims");
        tools.releaseArtefact(id, a2.id.toString());
        assertFalse(tools.isGcEligible(id), "a3 still holds claim");
        tools.releaseArtefact(id, a3.id.toString());
        assertTrue(tools.isGcEligible(id), "all 3 distinct instances released — GC eligible");
    }
}
