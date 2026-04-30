package io.casehub.qhorus.message;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class MessageServiceTest {

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    @Test
    @TestTransaction
    void sendMessagePersistsAllFields() {
        Channel ch = channelService.create("msg-test-1", "Test", ChannelSemantic.APPEND, null);

        Message msg = messageService.send(ch.id, "alice", MessageType.COMMAND, "Hello world",
                "corr-123", null);

        assertNotNull(msg.id);
        assertEquals(ch.id, msg.channelId);
        assertEquals("alice", msg.sender);
        assertEquals(MessageType.COMMAND, msg.messageType);
        assertEquals("Hello world", msg.content);
        assertEquals("corr-123", msg.correlationId);
        assertNull(msg.inReplyTo);
        assertEquals(0, msg.replyCount);
        assertNotNull(msg.createdAt);
    }

    @Test
    @TestTransaction
    void sendReplyIncrementsParentReplyCount() {
        Channel ch = channelService.create("msg-test-2", "Test", ChannelSemantic.APPEND, null);
        Message request = messageService.send(ch.id, "alice", MessageType.QUERY, "Question?",
                "corr-456", null);

        messageService.send(ch.id, "bob", MessageType.RESPONSE, "Answer!", "corr-456", request.id);

        Message refreshed = messageService.findById(request.id).orElseThrow();
        assertEquals(1, refreshed.replyCount);
    }

    @Test
    @TestTransaction
    void sendUpdatesChannelLastActivity() throws InterruptedException {
        Channel ch = channelService.create("msg-test-3", "Test", ChannelSemantic.APPEND, null);
        var activityBefore = ch.lastActivityAt;

        Thread.sleep(5);
        messageService.send(ch.id, "alice", MessageType.STATUS, "working...", null, null);

        Channel updated = channelService.findByName("msg-test-3").orElseThrow();
        assertTrue(updated.lastActivityAt.isAfter(activityBefore),
                "channel.lastActivityAt should advance after send");
    }

    @Test
    @TestTransaction
    void pollAfterReturnsMessagesAfterGivenIdInAscendingOrder() {
        Channel ch = channelService.create("msg-test-4", "Test", ChannelSemantic.APPEND, null);
        Message m1 = messageService.send(ch.id, "alice", MessageType.STATUS, "first", null, null);
        Message m2 = messageService.send(ch.id, "bob", MessageType.STATUS, "second", null, null);
        Message m3 = messageService.send(ch.id, "carol", MessageType.STATUS, "third", null, null);

        List<Message> after = messageService.pollAfter(ch.id, m1.id, 10);

        assertEquals(2, after.size());
        assertEquals(m2.id, after.get(0).id);
        assertEquals(m3.id, after.get(1).id);
        // Ordering must be deterministic — guaranteed by ORDER BY id ASC in the query
        assertTrue(after.get(0).id < after.get(1).id, "messages must be in ascending ID order");
    }

    @Test
    @TestTransaction
    void pollAfterWithZeroReturnsAllMessagesInOrder() {
        Channel ch = channelService.create("msg-test-zero", "Test", ChannelSemantic.APPEND, null);
        messageService.send(ch.id, "alice", MessageType.STATUS, "first", null, null);
        messageService.send(ch.id, "bob", MessageType.STATUS, "second", null, null);

        List<Message> all = messageService.pollAfter(ch.id, 0L, 10);

        assertEquals(2, all.size());
        assertEquals("first", all.get(0).content);
        assertEquals("second", all.get(1).content);
    }

    @Test
    @TestTransaction
    void pollAfterExcludesEventMessages() {
        Channel ch = channelService.create("msg-test-5", "Test", ChannelSemantic.APPEND, null);
        Message m1 = messageService.send(ch.id, "alice", MessageType.STATUS, "visible", null, null);
        messageService.send(ch.id, "system", MessageType.EVENT, "telemetry", null, null);
        Message m3 = messageService.send(ch.id, "bob", MessageType.STATUS, "also visible", null, null);

        List<Message> after = messageService.pollAfter(ch.id, m1.id, 10);

        // EVENT type is observer-only — excluded from agent context
        assertEquals(1, after.size());
        assertEquals(m3.id, after.get(0).id);
    }

    @Test
    @TestTransaction
    void findByCorrelationIdReturnsMatchingMessage() {
        Channel ch = channelService.create("msg-test-6", "Test", ChannelSemantic.APPEND, null);
        messageService.send(ch.id, "alice", MessageType.QUERY, "payload", "my-corr-id", null);

        Optional<Message> found = messageService.findByCorrelationId("my-corr-id");

        assertTrue(found.isPresent());
        assertEquals("my-corr-id", found.get().correlationId);
    }

    @Test
    @TestTransaction
    void findByCorrelationIdReturnsEmptyWhenNotFound() {
        Optional<Message> found = messageService.findByCorrelationId("no-such-corr");
        assertTrue(found.isEmpty());
    }

    // --- Pure enum tests — no DB interaction, no @TestTransaction overhead ---

    @Test
    void eventTypeIsNotAgentVisible() {
        assertFalse(MessageType.EVENT.isAgentVisible());
    }

    @Test
    void allOtherTypesAreAgentVisible() {
        assertTrue(MessageType.QUERY.isAgentVisible());
        assertTrue(MessageType.COMMAND.isAgentVisible());
        assertTrue(MessageType.RESPONSE.isAgentVisible());
        assertTrue(MessageType.STATUS.isAgentVisible());
        assertTrue(MessageType.DECLINE.isAgentVisible());
        assertTrue(MessageType.HANDOFF.isAgentVisible());
        assertTrue(MessageType.DONE.isAgentVisible());
        assertTrue(MessageType.FAILURE.isAgentVisible());
    }

}
