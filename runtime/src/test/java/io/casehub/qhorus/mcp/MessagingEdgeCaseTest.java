package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.ToolCallException;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.CheckResult;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.MessageResult;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.MessageSummary;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Edge-case tests for send_message, check_messages, search_messages, and get_replies
 * that are not covered by the existing MessagingToolTest.
 *
 * Findings covered:
 * - Invalid message type string produces IllegalArgumentException (not NPE).
 * - search_messages with empty/blank query matches all (pattern "%%").
 * - check_messages on empty channel with afterId=0 returns empty and lastId=0.
 * - send_message with empty content (content="") — should persist without error.
 * - get_replies for a non-existent parent message ID returns empty (not throw).
 * - search_messages with limit=0 — edge case in pagination.
 * - replyCount increments correctly for multiple replies to the same parent.
 * - search_messages across a channel that also has EVENT messages.
 * - check_messages sender filter on APPEND when all messages match the filter.
 * - Correlation ID on non-REQUEST types: explicit value is preserved.
 */
@QuarkusTest
class MessagingEdgeCaseTest {

    @Inject
    QhorusMcpTools tools;

    /**
     * IMPORTANT finding: MessageType.valueOf(type.toUpperCase()) throws
     * IllegalArgumentException with a JDK-generated message that doesn't list valid types.
     * The tool should surface a human-readable error. This test pins the current behaviour.
     */
    @Test
    @TestTransaction
    void sendMessageWithInvalidTypeThrowsIllegalArgumentException() {
        tools.createChannel("msg-edge-type", "Test", null, null, null, null, null, null, null);

        ToolCallException ex = assertThrows(ToolCallException.class,
                () -> tools.sendMessage("msg-edge-type", "alice", "bogus_type", "content", null, null, null, null, null),
                "invalid message type should throw IllegalArgumentException");

        // The error message is from valueOf() — it doesn't list valid values.
        // This is a usability gap: agents get a cryptic error.
        // Document the current message so a future improvement is visible.
        assertNotNull(ex.getMessage(),
                "exception message should not be null");
    }

    /**
     * CREATIVE finding: searchMessages with a blank query expands to pattern "%%"
     * which matches every row. This means an agent sending an empty search returns
     * all messages (up to the limit). This may be intentional but is undocumented.
     */
    @Test
    @TestTransaction
    void searchMessagesWithEmptyQueryMatchesAllMessages() {
        tools.createChannel("msg-edge-search", "Test", null, null, null, null, null, null, null);
        tools.sendMessage("msg-edge-search", "alice", "status", "hello world", null, null, null, null, null);
        tools.sendMessage("msg-edge-search", "bob", "status", "goodbye world", null, null, null, null, null);

        List<MessageSummary> results = tools.searchMessages("", "msg-edge-search", 10, null);

        assertEquals(2, results.size(),
                "empty query matches all messages (pattern becomes '%%') — document this behaviour");
    }

    /**
     * CREATIVE: searchMessages with only whitespace — same pattern issue.
     */
    @Test
    @TestTransaction
    void searchMessagesWithWhitespaceOnlyQueryMatchesAllMessages() {
        tools.createChannel("msg-edge-ws", "Test", null, null, null, null, null, null, null);
        tools.sendMessage("msg-edge-ws", "alice", "status", "some message", null, null, null, null, null);

        List<MessageSummary> results = tools.searchMessages("   ", "msg-edge-ws", 10, null);

        // "%" + "   ".toLowerCase() + "%" = "%   %" — matches strings containing 3 spaces
        // "some message" does not contain 3 consecutive spaces — so result is empty
        assertTrue(results.isEmpty(),
                "whitespace-only query should match only messages containing those whitespace chars");
    }

    /**
     * CREATIVE: checkMessages on a completely empty channel (no messages at all) with
     * afterId=0 should return empty messages list and lastId=cursor (0).
     */
    @Test
    @TestTransaction
    void checkMessagesOnTotallyEmptyChannelReturnsEmptyAndZeroLastId() {
        tools.createChannel("msg-edge-empty", "Test", null, null, null, null, null, null, null);

        CheckResult result = tools.checkMessages("msg-edge-empty", 0L, 10, null, null, null);

        assertTrue(result.messages().isEmpty());
        assertEquals(0L, result.lastId(),
                "empty channel with afterId=0 should return lastId=0");
        assertNull(result.barrierStatus());
    }

    /**
     * CREATIVE: send_message with empty string content — should persist without error.
     * Empty content is valid (an agent might send a marker/signal message).
     */
    @Test
    @TestTransaction
    void sendMessageWithEmptyContentPersistsSuccessfully() {
        tools.createChannel("msg-edge-empty-content", "Test", null, null, null, null, null, null, null);

        MessageResult result = assertDoesNotThrow(
                () -> tools.sendMessage("msg-edge-empty-content", "alice", "status", "", null, null, null, null, null),
                "empty content should be allowed");

        assertNotNull(result.messageId());

        CheckResult check = tools.checkMessages("msg-edge-empty-content", 0L, 10, null, null, null);
        assertEquals(1, check.messages().size());
        assertEquals("", check.messages().get(0).content());
    }

    /**
     * CREATIVE: get_replies for a non-existent parent message ID. The query is
     * `inReplyTo = ?1` on a non-existent ID — should return empty list, not throw.
     */
    @Test
    @TestTransaction
    void getRepliesForNonExistentParentMessageReturnsEmpty() {
        List<MessageSummary> replies = tools.getReplies(Long.MAX_VALUE - 1, null, null, null);
        assertTrue(replies.isEmpty(),
                "get_replies for a non-existent message ID should return empty list");
    }

    /**
     * IMPORTANT: replyCount on the parent message is incremented once per reply.
     * Multiple replies to the same parent must each increment the counter.
     */
    @Test
    @TestTransaction
    void replyCountIncrementsCorrectlyForMultipleReplies() {
        tools.createChannel("msg-edge-replies", "Test", null, null, null, null, null, null, null);
        MessageResult request = tools.sendMessage("msg-edge-replies", "alice", "query", "Q?", null, null, null, null, null);

        // First reply
        MessageResult r1 = tools.sendMessage("msg-edge-replies", "bob", "response", "A1", null, request.messageId(), null, null, null);
        assertEquals(1, r1.parentReplyCount(), "parentReplyCount should be 1 after first reply");

        // Second reply
        MessageResult r2 = tools.sendMessage("msg-edge-replies", "carol", "response", "A2", null, request.messageId(), null, null, null);
        assertEquals(2, r2.parentReplyCount(), "parentReplyCount should be 2 after second reply");

        // Third reply
        MessageResult r3 = tools.sendMessage("msg-edge-replies", "dave", "response", "A3", null, request.messageId(), null, null, null);
        assertEquals(3, r3.parentReplyCount(), "parentReplyCount should be 3 after third reply");
    }

    /**
     * IMPORTANT: sender filter on checkMessages for APPEND channel with limit boundary.
     * If there are 20 total messages and only 3 from "alice", a limit=5 with sender="alice"
     * should return 3 (all of alice's). The old buggy path would apply limit BEFORE sender
     * filter; pollAfterBySender applies filter in the query (correct).
     */
    @Test
    @TestTransaction
    void checkMessagesSenderFilterIsAppliedInQueryNotPostLimit() {
        tools.createChannel("msg-edge-sender-limit", "Test", null, null, null, null, null, null, null);

        // Post 18 messages from "bob" then 3 from "alice"
        for (int i = 0; i < 18; i++) {
            tools.sendMessage("msg-edge-sender-limit", "bob", "status", "bob msg " + i, null, null, null, null, null);
        }
        tools.sendMessage("msg-edge-sender-limit", "alice", "status", "alice-1", null, null, null, null, null);
        tools.sendMessage("msg-edge-sender-limit", "alice", "status", "alice-2", null, null, null, null, null);
        tools.sendMessage("msg-edge-sender-limit", "alice", "status", "alice-3", null, null, null, null, null);

        // Limit=5, filter by alice — should return all 3 of alice's messages
        CheckResult result = tools.checkMessages("msg-edge-sender-limit", 0L, 5, "alice", null, null);

        assertEquals(3, result.messages().size(),
                "sender filter must be applied IN the query (not post-limit) to avoid losing messages");
        assertTrue(result.messages().stream().allMatch(m -> "alice".equals(m.sender())));
    }

    /**
     * CREATIVE: check_messages with limit=1 on a channel with many messages.
     * lastId should be the ID of the returned message, and subsequent poll with
     * that lastId should return the next message.
     */
    @Test
    @TestTransaction
    void checkMessagesWithLimit1CanWalkThroughChannelIncrementally() {
        tools.createChannel("msg-edge-walk", "Test", null, null, null, null, null, null, null);
        MessageResult m1 = tools.sendMessage("msg-edge-walk", "alice", "status", "first", null, null, null, null, null);
        MessageResult m2 = tools.sendMessage("msg-edge-walk", "bob", "status", "second", null, null, null, null, null);
        MessageResult m3 = tools.sendMessage("msg-edge-walk", "carol", "status", "third", null, null, null, null, null);

        // Walk through one at a time
        CheckResult page1 = tools.checkMessages("msg-edge-walk", 0L, 1, null, null, null);
        assertEquals(1, page1.messages().size());
        assertEquals("first", page1.messages().get(0).content());
        assertEquals(m1.messageId(), page1.lastId());

        CheckResult page2 = tools.checkMessages("msg-edge-walk", page1.lastId(), 1, null, null, null);
        assertEquals(1, page2.messages().size());
        assertEquals("second", page2.messages().get(0).content());
        assertEquals(m2.messageId(), page2.lastId());

        CheckResult page3 = tools.checkMessages("msg-edge-walk", page2.lastId(), 1, null, null, null);
        assertEquals(1, page3.messages().size());
        assertEquals("third", page3.messages().get(0).content());
        assertEquals(m3.messageId(), page3.lastId());

        CheckResult page4 = tools.checkMessages("msg-edge-walk", page3.lastId(), 1, null, null, null);
        assertTrue(page4.messages().isEmpty(), "no more messages after the last");
        assertEquals(m3.messageId(), page4.lastId(),
                "lastId should remain at the last seen ID when poll returns empty");
    }

    /**
     * CREATIVE: HANDOFF and DONE message types are agent-visible.
     * Confirm they appear in check_messages and search_messages.
     */
    @Test
    @TestTransaction
    void handoffAndDoneMessageTypesAreVisibleInCheckAndSearch() {
        tools.createChannel("msg-edge-types", "Test", null, null, null, null, null, null, null);
        tools.sendMessage("msg-edge-types", "alice", "handoff", "passing baton to bob", null, null, null, "instance:bob", null);
        tools.sendMessage("msg-edge-types", "bob", "done", "task complete", null, null, null, null, null);

        CheckResult check = tools.checkMessages("msg-edge-types", 0L, 10, null, null, null);
        assertEquals(2, check.messages().size());
        assertTrue(check.messages().stream().anyMatch(m -> "HANDOFF".equals(m.messageType())));
        assertTrue(check.messages().stream().anyMatch(m -> "DONE".equals(m.messageType())));

        List<MessageSummary> searched = tools.searchMessages("baton", "msg-edge-types", 10, null);
        assertEquals(1, searched.size());
        assertEquals("HANDOFF", searched.get(0).messageType());
    }

    /**
     * CREATIVE: search_messages with a channel-scoped search for a non-existent channel
     * throws IllegalArgumentException (not null/empty result).
     */
    @Test
    @TestTransaction
    void searchMessagesWithUnknownChannelThrowsIllegalArgument() {
        assertThrows(ToolCallException.class,
                () -> tools.searchMessages("anything", "no-such-channel-xyz", 10, null),
                "channel-scoped search with unknown channel should throw IllegalArgumentException");
    }

    /**
     * IMPORTANT: check_messages on unknown channel throws (not returns empty).
     */
    @Test
    @TestTransaction
    void checkMessagesOnUnknownChannelThrows() {
        assertThrows(Exception.class,
                () -> tools.checkMessages("no-such-channel-xyz", 0L, 10, null, null, null));
    }

    /**
     * CREATIVE: multiple senders, polling with afterId in middle of sequence.
     * Ensures the cursor excludes exact-ID match (strictly greater than cursor).
     */
    @Test
    @TestTransaction
    void checkMessagesAfterIdIsStrictlyGreaterThan() {
        tools.createChannel("msg-edge-cursor", "Test", null, null, null, null, null, null, null);
        MessageResult m1 = tools.sendMessage("msg-edge-cursor", "alice", "status", "msg1", null, null, null, null, null);
        MessageResult m2 = tools.sendMessage("msg-edge-cursor", "bob", "status", "msg2", null, null, null, null, null);

        // afterId = m1's ID — should return ONLY m2 (m1 is excluded, it's == cursor not >)
        CheckResult result = tools.checkMessages("msg-edge-cursor", m1.messageId(), 10, null, null, null);

        assertEquals(1, result.messages().size());
        assertEquals(m2.messageId(), result.messages().get(0).messageId(),
                "afterId filter is strictly greater-than: message at cursor ID is excluded");
    }
}
