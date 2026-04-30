package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Issue #29 — Implement instance-exact dispatch on read side.
 *
 * <p>
 * Verifies that when {@code reader_instance_id} is provided to check_messages,
 * get_replies, and search_messages, only messages with {@code target IS NULL} or
 * {@code target = 'instance:{reader_instance_id}'} are returned. Omitting
 * {@code reader_instance_id} preserves existing broadcast-to-all behaviour.
 *
 * <p>
 * Refs #29, Epic #27.
 */
@QuarkusTest
class InstanceExactDispatchTest {

    @Inject
    QhorusMcpTools tools;

    // =========================================================================
    // check_messages — APPEND channel
    // =========================================================================

    @Test
    @TestTransaction
    void broadcastMessageVisibleToReaderWithReaderInstanceId() {
        tools.createChannel("ied-ap-1", "Test", "APPEND", null);
        tools.sendMessage("ied-ap-1", "alice", "status", "broadcast", null, null, null, null);

        QhorusMcpTools.CheckResult result = tools.checkMessages("ied-ap-1", 0L, 10, null, "bob");
        assertEquals(1, result.messages().size(), "broadcast message should be visible to any reader");
    }

    @Test
    @TestTransaction
    void instanceTargetedMessageVisibleToCorrectReader() {
        tools.createChannel("ied-ap-2", "Test", "APPEND", null);
        tools.sendMessage("ied-ap-2", "alice", "command", "for bob", null, null, null, "instance:bob");

        QhorusMcpTools.CheckResult result = tools.checkMessages("ied-ap-2", 0L, 10, null, "bob");
        assertEquals(1, result.messages().size());
        assertEquals("instance:bob", result.messages().get(0).target());
    }

    @Test
    @TestTransaction
    void instanceTargetedMessageHiddenFromOtherReader() {
        tools.createChannel("ied-ap-3", "Test", "APPEND", null);
        tools.sendMessage("ied-ap-3", "alice", "command", "for bob", null, null, null, "instance:bob");

        QhorusMcpTools.CheckResult result = tools.checkMessages("ied-ap-3", 0L, 10, null, "charlie");
        assertTrue(result.messages().isEmpty(), "message targeted at bob should be invisible to charlie");
    }

    @Test
    @TestTransaction
    void omittingReaderInstanceIdShowsAllMessagesIncludingTargeted() {
        tools.createChannel("ied-ap-4", "Test", "APPEND", null);
        tools.sendMessage("ied-ap-4", "alice", "status", "broadcast", null, null, null, null);
        tools.sendMessage("ied-ap-4", "alice", "command", "for bob", null, null, null, "instance:bob");

        // No reader_instance_id → backward-compat: all messages visible
        QhorusMcpTools.CheckResult result = tools.checkMessages("ied-ap-4", 0L, 10, null, null);
        assertEquals(2, result.messages().size(), "omitting reader shows all messages");
    }

    @Test
    @TestTransaction
    void mixedBroadcastAndTargetedMessagesFilteredCorrectly() {
        tools.createChannel("ied-ap-5", "Test", "APPEND", null);
        tools.sendMessage("ied-ap-5", "alice", "status", "broadcast", null, null, null, null);
        tools.sendMessage("ied-ap-5", "alice", "command", "for bob", null, null, null, "instance:bob");
        tools.sendMessage("ied-ap-5", "alice", "command", "for charlie", null, null, null, "instance:charlie");

        QhorusMcpTools.CheckResult bobView = tools.checkMessages("ied-ap-5", 0L, 10, null, "bob");
        assertEquals(2, bobView.messages().size(), "bob sees broadcast + his targeted message");

        QhorusMcpTools.CheckResult charlieView = tools.checkMessages("ied-ap-5", 0L, 10, null, "charlie");
        assertEquals(2, charlieView.messages().size(), "charlie sees broadcast + her targeted message");
    }

    // =========================================================================
    // check_messages — EPHEMERAL channel
    // =========================================================================

    @Test
    @TestTransaction
    void instanceTargetedEphemeralMessageVisibleToCorrectReaderAndConsumed() {
        tools.createChannel("ied-eph-1", "Test", "EPHEMERAL", null);
        tools.sendMessage("ied-eph-1", "alice", "command", "for bob", null, null, null, "instance:bob");

        // Bob reads it — should be delivered and deleted
        QhorusMcpTools.CheckResult bobRead = tools.checkMessages("ied-eph-1", 0L, 10, null, "bob");
        assertEquals(1, bobRead.messages().size());

        // Bob reads again — gone
        QhorusMcpTools.CheckResult bobReadAgain = tools.checkMessages("ied-eph-1", 0L, 10, null, "bob");
        assertTrue(bobReadAgain.messages().isEmpty(), "EPHEMERAL message consumed after delivery to bob");
    }

    @Test
    @TestTransaction
    void instanceTargetedEphemeralMessageNotConsumedByWrongReader() {
        tools.createChannel("ied-eph-2", "Test", "EPHEMERAL", null);
        tools.sendMessage("ied-eph-2", "alice", "command", "for bob", null, null, null, "instance:bob");

        // Charlie reads — should see nothing and NOT consume bob's message
        QhorusMcpTools.CheckResult charlieRead = tools.checkMessages("ied-eph-2", 0L, 10, null, "charlie");
        assertTrue(charlieRead.messages().isEmpty(), "charlie should not see bob's targeted message");

        // Bob reads — message should still be there
        QhorusMcpTools.CheckResult bobRead = tools.checkMessages("ied-eph-2", 0L, 10, null, "bob");
        assertEquals(1, bobRead.messages().size(), "bob's message must still exist after charlie polled");
    }

    // =========================================================================
    // get_replies
    // =========================================================================

    @Test
    @TestTransaction
    void instanceTargetedReplyVisibleToCorrectReader() {
        tools.createChannel("ied-rep-1", "Test", "APPEND", null);
        QhorusMcpTools.MessageResult parent = tools.sendMessage("ied-rep-1", "alice", "query", "question", null, null, null,
                null);
        tools.sendMessage("ied-rep-1", "bob", "response", "answer for alice",
                null, parent.messageId(), null, "instance:alice");

        List<QhorusMcpTools.MessageSummary> replies = tools.getReplies(parent.messageId(), "alice");
        assertEquals(1, replies.size(), "alice should see the reply targeted at her");
    }

    @Test
    @TestTransaction
    void instanceTargetedReplyHiddenFromOtherReader() {
        tools.createChannel("ied-rep-2", "Test", "APPEND", null);
        QhorusMcpTools.MessageResult parent = tools.sendMessage("ied-rep-2", "alice", "query", "question", null, null, null,
                null);
        tools.sendMessage("ied-rep-2", "bob", "response", "answer for alice",
                null, parent.messageId(), null, "instance:alice");

        List<QhorusMcpTools.MessageSummary> replies = tools.getReplies(parent.messageId(), "charlie");
        assertTrue(replies.isEmpty(), "charlie should not see reply targeted at alice");
    }

    @Test
    @TestTransaction
    void omittingReaderFromGetRepliesShowsAllReplies() {
        tools.createChannel("ied-rep-3", "Test", "APPEND", null);
        QhorusMcpTools.MessageResult parent = tools.sendMessage("ied-rep-3", "alice", "query", "question", null, null, null,
                null);
        tools.sendMessage("ied-rep-3", "bob", "response", "broadcast reply",
                null, parent.messageId(), null, null);
        tools.sendMessage("ied-rep-3", "bob", "response", "answer for alice",
                null, parent.messageId(), null, "instance:alice");

        // No reader — backward compat, all replies visible
        List<QhorusMcpTools.MessageSummary> replies = tools.getReplies(parent.messageId(), null);
        assertEquals(2, replies.size());
    }

    // =========================================================================
    // search_messages
    // =========================================================================

    @Test
    @TestTransaction
    void instanceTargetedMessageFoundBySearchForCorrectReader() {
        tools.createChannel("ied-srch-1", "Test", "APPEND", null);
        tools.sendMessage("ied-srch-1", "alice", "status", "secret for bob", null, null, null, "instance:bob");

        List<QhorusMcpTools.MessageSummary> results = tools.searchMessages("secret", "ied-srch-1", 10, "bob");
        assertEquals(1, results.size());
    }

    @Test
    @TestTransaction
    void instanceTargetedMessageNotFoundBySearchForOtherReader() {
        tools.createChannel("ied-srch-2", "Test", "APPEND", null);
        tools.sendMessage("ied-srch-2", "alice", "status", "secret for bob", null, null, null, "instance:bob");

        List<QhorusMcpTools.MessageSummary> results = tools.searchMessages("secret", "ied-srch-2", 10, "charlie");
        assertTrue(results.isEmpty(), "charlie should not find bob's targeted message via search");
    }

    @Test
    @TestTransaction
    void omittingReaderFromSearchShowsAllMessages() {
        tools.createChannel("ied-srch-3", "Test", "APPEND", null);
        tools.sendMessage("ied-srch-3", "alice", "status", "secret for bob", null, null, null, "instance:bob");
        tools.sendMessage("ied-srch-3", "alice", "status", "secret broadcast", null, null, null, null);

        // No reader — backward compat
        List<QhorusMcpTools.MessageSummary> results = tools.searchMessages("secret", "ied-srch-3", 10, null);
        assertEquals(2, results.size());
    }
}
