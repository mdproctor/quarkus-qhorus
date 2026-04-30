package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.ToolCallException;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.CheckResult;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.MessageResult;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Edge-case tests for LAST_WRITE semantics.
 *
 * Findings covered:
 * - LAST_WRITE overwrite path hardcodes parentReplyCount=0 (never queries it).
 * - LAST_WRITE overwrite does NOT call messageService.send(), bypassing inReplyTo
 * parent reply-count increment on the new target.
 * - LAST_WRITE with inReplyTo changes between overwrites leaves inconsistent replyCount.
 * - LAST_WRITE with empty channel then different sender check.
 * - LAST_WRITE channel read via checkMessages uses APPEND path (correct).
 */
@QuarkusTest
class LastWriteEdgeCaseTest {

    @Inject
    QhorusMcpTools tools;

    /**
     * IMPORTANT finding: the LAST_WRITE overwrite path returns parentReplyCount=0 always.
     * The normal send path looks up the parent's current replyCount; the overwrite path
     * hardcodes 0. This means if the LAST_WRITE message is itself a reply (inReplyTo != null),
     * the returned parentReplyCount is misleading.
     *
     * This test documents the current (hardcoded 0) behaviour. If the intent is to return the
     * real count, the implementation needs to query the parent's replyCount on overwrite.
     */
    @Test
    @TestTransaction
    void lastWriteOverwriteAlwaysReturnsParentReplyCountZero() {
        tools.createChannel("lw-edge-1", "APPEND", null, null); // request channel
        tools.createChannel("lw-edge-2", "LAST_WRITE state", "LAST_WRITE", null);

        // Send a request in the APPEND channel
        MessageResult request = tools.sendMessage("lw-edge-1", "alice", "query", "Q?", null, null);

        // First LAST_WRITE write with inReplyTo pointing to the request
        tools.sendMessage("lw-edge-2", "alice", "status", "v1", null, request.messageId());

        // Overwrite — same sender, new content. Returns MessageResult with parentReplyCount.
        MessageResult overwrite = tools.sendMessage("lw-edge-2", "alice", "status", "v2", null, request.messageId());

        // parentReplyCount is hardcoded to 0 in the overwrite path — document this.
        assertEquals(0, overwrite.parentReplyCount(),
                "LAST_WRITE overwrite path always returns parentReplyCount=0 (hardcoded, not queried)");
    }

    /**
     * IMPORTANT finding: the LAST_WRITE overwrite path updates the existing Message row directly
     * and does NOT call messageService.send(), where parent replyCount increment lives.
     *
     * Scenario: first write has inReplyTo=null, second write (overwrite) changes inReplyTo to parentId.
     * get_replies(parentId) DOES find the LAST_WRITE message (because inReplyTo FK is updated by
     * Hibernate dirty tracking). However, the parentMsg.replyCount field is NOT incremented by the
     * overwrite path — only the initial write to a non-existent last message calls messageService.send().
     *
     * This is an inconsistency: get_replies returns 1 but the replyCount field on the parent stays 0.
     */
    @Test
    @TestTransaction
    void lastWriteOverwriteChangingInReplyToLinksMessageButDoesNotIncrementParentReplyCountField() {
        tools.createChannel("lw-edge-3-parent", "APPEND", null, null);
        tools.createChannel("lw-edge-3", "LAST_WRITE state", "LAST_WRITE", null);

        MessageResult parentMsg = tools.sendMessage("lw-edge-3-parent", "orchestrator", "command",
                "do task", null, null);

        // First LAST_WRITE write — no inReplyTo (goes through messageService.send())
        tools.sendMessage("lw-edge-3", "alice", "status", "v1 no parent", null, null);

        // Overwrite — now links to parentMsg; goes through the overwrite path, NOT messageService.send()
        tools.sendMessage("lw-edge-3", "alice", "status", "v2 with parent", null, parentMsg.messageId());

        // get_replies DOES find the LAST_WRITE message (inReplyTo is set by dirty tracking)
        var replies = tools.getReplies(parentMsg.messageId());
        assertEquals(1, replies.size(),
                "get_replies finds the LAST_WRITE message whose inReplyTo was set by the overwrite path");

        // The returned parentReplyCount from the overwrite call is hardcoded 0 (not queried).
        // The parent message's replyCount field was not explicitly incremented by the overwrite path.
        // This inconsistency means callers cannot rely on parentReplyCount from LAST_WRITE overwrites.
        MessageResult secondWrite = tools.sendMessage("lw-edge-3", "alice", "status", "v3", null, parentMsg.messageId());
        assertEquals(0, secondWrite.parentReplyCount(),
                "LAST_WRITE overwrite path hardcodes parentReplyCount=0 regardless of actual reply count");
    }

    /**
     * CREATIVE: LAST_WRITE overwrite preserves the message ID. After multiple overwrites,
     * checkMessages should return a single message whose ID equals the first write's ID.
     * This verifies the "overwrite in place" contract from the consumer's perspective.
     */
    @Test
    @TestTransaction
    void lastWriteOverwriteMessageIdIsStableAcrossMultipleWrites() {
        tools.createChannel("lw-edge-4", "LAST_WRITE", "LAST_WRITE", null);

        MessageResult first = tools.sendMessage("lw-edge-4", "alice", "status", "v1", null, null);
        tools.sendMessage("lw-edge-4", "alice", "status", "v2", null, null);
        MessageResult third = tools.sendMessage("lw-edge-4", "alice", "status", "v3", null, null);

        // All three writes should produce the same message ID
        assertEquals(first.messageId(), third.messageId(),
                "LAST_WRITE overwrite must return the same message ID across all same-sender writes");

        // Confirm exactly one row in the channel
        CheckResult check = tools.checkMessages("lw-edge-4", 0L, 10, null);
        assertEquals(1, check.messages().size());
        assertEquals(first.messageId(), check.messages().get(0).messageId());
        assertEquals("v3", check.messages().get(0).content());
    }

    /**
     * CREATIVE: LAST_WRITE channel with event message from the owner — the event is excluded
     * from the read (pollAfter excludes EVENT), so the channel "appears" empty to a reader
     * even though a write happened. The next same-sender non-EVENT write would then be the
     * "first" message as far as the LAST_WRITE check is concerned.
     *
     * Specifically: if the only existing message is from alice and is an EVENT, then a second
     * sender "bob" sending to the channel would see an existing message FROM alice — and be
     * rejected. The LAST_WRITE check does NOT filter by messageType.
     */
    @Test
    @TestTransaction
    void lastWriteEventMessageFromFirstSenderBlocksSecondSender() {
        tools.createChannel("lw-edge-5", "LAST_WRITE", "LAST_WRITE", null);

        // alice sends an EVENT message — this is the only message in the channel
        tools.sendMessage("lw-edge-5", "alice", "event", "alice telemetry", null, null);

        // bob tries to send — the LAST_WRITE check finds alice's EVENT as the "last" message
        // and rejects bob because last.sender ("alice") != "bob"
        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("lw-edge-5", "bob", "status", "bob wants in", null, null),
                "LAST_WRITE should reject bob even when alice's only message is an EVENT type");
    }

    /**
     * CREATIVE: LAST_WRITE channel read via checkMessages uses checkMessagesAppend (the default
     * path). Verify that the EVENT exclusion filter in the append path applies correctly.
     * Alice writes a STATUS (visible) and an EVENT — only the STATUS should be returned.
     *
     * This also confirms: after alice overwrites STATUS with EVENT, the checkMessages returns
     * the EVENT-replaced row... wait, no. Alice writes STATUS first, then EVENT. But LAST_WRITE
     * only has ONE row — the overwrite replaces STATUS with EVENT. So checkMessages returns
     * nothing (EVENT is excluded). This is a subtle trap.
     */
    @Test
    @TestTransaction
    void lastWriteChannelAfterOwnerOverwritesWithEventTypeAppearsEmpty() {
        tools.createChannel("lw-edge-6", "LAST_WRITE", "LAST_WRITE", null);

        // Write STATUS first (visible)
        tools.sendMessage("lw-edge-6", "alice", "status", "visible state", null, null);
        CheckResult beforeOverwrite = tools.checkMessages("lw-edge-6", 0L, 10, null);
        assertEquals(1, beforeOverwrite.messages().size());

        // Overwrite with EVENT — the single row is now an EVENT, invisible to pollAfter
        tools.sendMessage("lw-edge-6", "alice", "event", "now just telemetry", null, null);
        CheckResult afterOverwrite = tools.checkMessages("lw-edge-6", 0L, 10, null);

        assertTrue(afterOverwrite.messages().isEmpty(),
                "After LAST_WRITE overwrites the channel message with an EVENT type, " +
                        "checkMessages returns empty (EVENT is excluded from agent context)");
    }

    /**
     * CREATIVE: LAST_WRITE where alice's write has a correlationId, and an overwrite
     * clears it (sets correlationId=null). Confirm the stored row reflects the null.
     */
    @Test
    @TestTransaction
    void lastWriteOverwriteClearsCorrelationIdWhenNewWriteHasNone() {
        tools.createChannel("lw-edge-7", "LAST_WRITE", "LAST_WRITE", null);
        tools.sendMessage("lw-edge-7", "alice", "status", "v1", "initial-corr", null);

        // Overwrite with no correlationId — for non-REQUEST type, corrId stays null
        tools.sendMessage("lw-edge-7", "alice", "status", "v2", null, null);

        CheckResult check = tools.checkMessages("lw-edge-7", 0L, 10, null);
        assertEquals(1, check.messages().size());
        assertNull(check.messages().get(0).correlationId(),
                "LAST_WRITE overwrite with null correlationId should clear the stored correlationId");
    }
}
