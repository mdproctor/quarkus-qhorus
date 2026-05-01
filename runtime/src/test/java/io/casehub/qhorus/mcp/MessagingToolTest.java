package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.MessageResult;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class MessagingToolTest {

    @Inject
    QhorusMcpTools tools;

    // -----------------------------------------------------------------------
    // send_message
    // -----------------------------------------------------------------------

    @Test
    @TestTransaction
    void sendMessagePersistsAndReturnsResult() {
        tools.createChannel("msg-ch-1", "Test", null, null, null, null, null, null, null);

        MessageResult result = tools.sendMessage("msg-ch-1", "alice", "status", "Hello!", null, null, null, null, null);

        assertNotNull(result.messageId());
        assertEquals("msg-ch-1", result.channelName());
        assertEquals("alice", result.sender());
        assertEquals("STATUS", result.messageType());
    }

    @Test
    @TestTransaction
    void sendMessageRequestAutoGeneratesCorrelationId() {
        tools.createChannel("msg-ch-2", "Test", null, null, null, null, null, null, null);

        MessageResult result = tools.sendMessage("msg-ch-2", "alice", "query", "Question?", null, null, null, null, null);

        assertNotNull(result.correlationId(),
                "request type with no correlation_id should auto-generate one");
        assertFalse(result.correlationId().isBlank());
    }

    @Test
    @TestTransaction
    void sendMessageWithExplicitCorrelationId() {
        tools.createChannel("msg-ch-3", "Test", null, null, null, null, null, null, null);

        MessageResult result = tools.sendMessage("msg-ch-3", "alice", "query", "Ping", "my-corr-id", null, null, null, null);

        assertEquals("my-corr-id", result.correlationId());
    }

    @Test
    @TestTransaction
    void sendMessageReplyIncrementsParentReplyCount() {
        tools.createChannel("msg-ch-4", "Test", null, null, null, null, null, null, null);
        MessageResult request = tools.sendMessage("msg-ch-4", "alice", "query", "Question?", null, null, null, null, null);

        MessageResult reply = tools.sendMessage("msg-ch-4", "bob", "response", "Answer!", null, request.messageId(), null, null, null);

        assertEquals(request.messageId(), reply.inReplyTo());
        assertEquals(1, reply.parentReplyCount());
    }

    @Test
    @TestTransaction
    void sendMessageNonRequestTypeKeepsNullCorrelationId() {
        tools.createChannel("msg-corr-null", "Test", null, null, null, null, null, null, null);

        MessageResult result = tools.sendMessage("msg-corr-null", "alice", "status", "working...", null, null, null, null, null);

        assertNull(result.correlationId(),
                "status type with no correlation_id should remain null");
    }

    @Test
    @TestTransaction
    void sendMessageNonRequestTypePreservesExplicitCorrelationId() {
        tools.createChannel("msg-corr-ref", "Test", null, null, null, null, null, null, null);

        MessageResult result = tools.sendMessage("msg-corr-ref", "bob", "response", "Answer!", "ref-corr", null, null, null, null);

        assertEquals("ref-corr", result.correlationId());
    }

    @Test
    @TestTransaction
    void sendMessageToUnknownChannelThrows() {
        assertThrows(Exception.class, () -> tools.sendMessage("no-such-channel", "alice", "status", "Hello", null, null, null, null, null));
    }

    // -----------------------------------------------------------------------
    // check_messages
    // -----------------------------------------------------------------------

    @Test
    @TestTransaction
    void checkMessagesReturnsMessagesAfterCursor() {
        tools.createChannel("check-ch-1", "Test", null, null, null, null, null, null, null);
        MessageResult m1 = tools.sendMessage("check-ch-1", "alice", "status", "first", null, null, null, null, null);
        tools.sendMessage("check-ch-1", "bob", "status", "second", null, null, null, null, null);
        tools.sendMessage("check-ch-1", "carol", "status", "third", null, null, null, null, null);

        QhorusMcpTools.CheckResult result = tools.checkMessages("check-ch-1", m1.messageId(), 10, null, null, null);

        assertEquals(2, result.messages().size());
        assertEquals("second", result.messages().get(0).content());
        assertEquals("third", result.messages().get(1).content());
    }

    @Test
    @TestTransaction
    void checkMessagesExcludesEventType() {
        tools.createChannel("check-ch-2", "Test", null, null, null, null, null, null, null);
        MessageResult m1 = tools.sendMessage("check-ch-2", "alice", "status", "visible", null, null, null, null, null);
        tools.sendMessage("check-ch-2", "system", "event", "telemetry", null, null, null, null, null);
        tools.sendMessage("check-ch-2", "bob", "status", "also visible", null, null, null, null, null);

        QhorusMcpTools.CheckResult result = tools.checkMessages("check-ch-2", m1.messageId(), 10, null, null, null);

        assertEquals(1, result.messages().size());
        assertEquals("also visible", result.messages().get(0).content());
    }

    @Test
    @TestTransaction
    void checkMessagesFiltersBySender() {
        tools.createChannel("check-ch-3", "Test", null, null, null, null, null, null, null);
        tools.sendMessage("check-ch-3", "alice", "status", "from alice", null, null, null, null, null);
        tools.sendMessage("check-ch-3", "bob", "status", "from bob", null, null, null, null, null);

        QhorusMcpTools.CheckResult result = tools.checkMessages("check-ch-3", 0L, 10, "alice", null, null);

        assertEquals(1, result.messages().size());
        assertEquals("alice", result.messages().get(0).sender());
    }

    // -----------------------------------------------------------------------
    // get_replies
    // -----------------------------------------------------------------------

    @Test
    @TestTransaction
    void getRepliesReturnsDirectReplies() {
        tools.createChannel("replies-ch", "Test", null, null, null, null, null, null, null);
        MessageResult request = tools.sendMessage("replies-ch", "alice", "query", "Q?", null, null, null, null, null);
        tools.sendMessage("replies-ch", "bob", "response", "A1", null, request.messageId(), null, null, null);
        tools.sendMessage("replies-ch", "carol", "response", "A2", null, request.messageId(), null, null, null);

        List<QhorusMcpTools.MessageSummary> replies = tools.getReplies(request.messageId(), null, null, null);

        assertEquals(2, replies.size());
    }

    @Test
    @TestTransaction
    void getRepliesReturnsEmptyWhenNoReplies() {
        tools.createChannel("noreplies-ch", "Test", null, null, null, null, null, null, null);
        MessageResult msg = tools.sendMessage("noreplies-ch", "alice", "status", "standalone", null, null, null, null, null);

        List<QhorusMcpTools.MessageSummary> replies = tools.getReplies(msg.messageId(), null, null, null);

        assertTrue(replies.isEmpty());
    }

    // -----------------------------------------------------------------------
    // search_messages
    // -----------------------------------------------------------------------

    @Test
    @TestTransaction
    void searchMessagesFindsKeywordInContent() {
        tools.createChannel("search-ch-1", "Test", null, null, null, null, null, null, null);
        tools.sendMessage("search-ch-1", "alice", "status", "Found security vulnerability", null, null, null, null, null);
        tools.sendMessage("search-ch-1", "bob", "status", "Performance looks fine", null, null, null, null, null);

        List<QhorusMcpTools.MessageSummary> results = tools.searchMessages("security", null, 10, null);

        assertEquals(1, results.size());
        assertTrue(results.get(0).content().contains("security"));
    }

    @Test
    @TestTransaction
    void searchMessagesIsCaseInsensitive() {
        tools.createChannel("search-ch-2", "Test", null, null, null, null, null, null, null);
        tools.sendMessage("search-ch-2", "alice", "status", "CRITICAL: auth bypass", null, null, null, null, null);

        List<QhorusMcpTools.MessageSummary> results = tools.searchMessages("critical", null, 10, null);

        assertEquals(1, results.size());
    }

    @Test
    @TestTransaction
    void searchMessagesExcludesEventType() {
        tools.createChannel("search-ch-3", "Test", null, null, null, null, null, null, null);
        tools.sendMessage("search-ch-3", "system", "event", "critical system event", null, null, null, null, null);
        tools.sendMessage("search-ch-3", "alice", "status", "critical user message", null, null, null, null, null);

        List<QhorusMcpTools.MessageSummary> results = tools.searchMessages("critical", null, 10, null);

        // EVENT should be excluded
        assertEquals(1, results.size());
        assertEquals("alice", results.get(0).sender());
    }

    @Test
    @TestTransaction
    void searchMessagesWithChannelScope() {
        tools.createChannel("scoped-ch", "Scoped", null, null, null, null, null, null, null);
        tools.createChannel("other-ch", "Other", null, null, null, null, null, null, null);
        tools.sendMessage("scoped-ch", "alice", "status", "critical issue found", null, null, null, null, null);
        tools.sendMessage("other-ch", "bob", "status", "critical other issue", null, null, null, null, null);

        List<QhorusMcpTools.MessageSummary> results = tools.searchMessages("critical", "scoped-ch", 10, null);

        assertEquals(1, results.size(),
                "channel-scoped search should only return messages from the specified channel");
        assertEquals("alice", results.get(0).sender());
    }

    // -----------------------------------------------------------------------
    // check_messages — lastId cursor
    // -----------------------------------------------------------------------

    @Test
    @TestTransaction
    void checkMessagesLastIdIsIdOfLastReturnedMessage() {
        tools.createChannel("lastid-ch", "Test", null, null, null, null, null, null, null);
        tools.sendMessage("lastid-ch", "alice", "status", "first", null, null, null, null, null);
        MessageResult last = tools.sendMessage("lastid-ch", "bob", "status", "second", null, null, null, null, null);

        QhorusMcpTools.CheckResult result = tools.checkMessages("lastid-ch", 0L, 10, null, null, null);

        assertEquals(last.messageId(), result.lastId(),
                "lastId should be the ID of the last returned message");
    }

    @Test
    @TestTransaction
    void checkMessagesEmptyPollReturnsInputCursorAsLastId() {
        tools.createChannel("empty-poll-ch", "Test", null, null, null, null, null, null, null);
        tools.sendMessage("empty-poll-ch", "alice", "status", "only message", null, null, null, null, null);

        // Poll with afterId beyond all existing messages
        QhorusMcpTools.CheckResult result = tools.checkMessages("empty-poll-ch", Long.MAX_VALUE - 1, 10, null, null, null);

        assertTrue(result.messages().isEmpty());
        assertEquals(Long.MAX_VALUE - 1, result.lastId(),
                "empty poll should return the input cursor as lastId for stable re-polling");
    }
}
