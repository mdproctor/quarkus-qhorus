package io.quarkiverse.qhorus.store;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.api.channel.ChannelSemantic;
import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.store.MessageStore;
import io.quarkiverse.qhorus.runtime.store.query.MessageQuery;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class JpaMessageStoreTest {

    @Inject
    MessageStore messageStore;

    private Channel createChannel() {
        Channel ch = new Channel();
        ch.name = "msg-test-" + UUID.randomUUID();
        ch.semantic = ChannelSemantic.APPEND;
        ch.persist();
        return ch;
    }

    private Message buildMessage(UUID channelId, String sender, MessageType type) {
        Message m = new Message();
        m.channelId = channelId;
        m.sender = sender;
        m.messageType = type;
        m.content = "hello from " + sender;
        return m;
    }

    @Test
    @TestTransaction
    void put_persistsMessageAndAssignsId() {
        Channel ch = createChannel();
        Message m = buildMessage(ch.id, "agent-a", MessageType.COMMAND);

        Message saved = messageStore.put(m);

        assertNotNull(saved.id);
        assertEquals("agent-a", saved.sender);
        assertEquals(ch.id, saved.channelId);
    }

    @Test
    @TestTransaction
    void find_returnsMessage_whenExists() {
        Channel ch = createChannel();
        Message m = buildMessage(ch.id, "agent-b", MessageType.RESPONSE);
        messageStore.put(m);

        Optional<Message> found = messageStore.find(m.id);

        assertTrue(found.isPresent());
        assertEquals(m.id, found.get().id);
    }

    @Test
    @TestTransaction
    void find_returnsEmpty_whenNotFound() {
        assertTrue(messageStore.find(Long.MAX_VALUE).isEmpty());
    }

    @Test
    @TestTransaction
    void scan_forChannel_returnsAllChannelMessages() {
        Channel ch = createChannel();
        messageStore.put(buildMessage(ch.id, "agent-a", MessageType.COMMAND));
        messageStore.put(buildMessage(ch.id, "agent-b", MessageType.RESPONSE));

        List<Message> results = messageStore.scan(MessageQuery.forChannel(ch.id));

        assertEquals(2, results.size());
    }

    @Test
    @TestTransaction
    void scan_excludeTypes_omitsExcludedType() {
        Channel ch = createChannel();
        messageStore.put(buildMessage(ch.id, "agent-a", MessageType.COMMAND));
        Message evt = buildMessage(ch.id, "sys", MessageType.EVENT);
        evt.content = "{\"tool_name\":\"t\",\"duration_ms\":1}";
        messageStore.put(evt);

        List<Message> results = messageStore.scan(
                MessageQuery.builder()
                        .channelId(ch.id)
                        .excludeTypes(List.of(MessageType.EVENT))
                        .build());

        assertEquals(1, results.size());
        assertEquals(MessageType.COMMAND, results.get(0).messageType);
    }

    @Test
    @TestTransaction
    void scan_afterId_returnsCursorResults() {
        Channel ch = createChannel();
        Message first = messageStore.put(buildMessage(ch.id, "agent-a", MessageType.COMMAND));
        Message second = messageStore.put(buildMessage(ch.id, "agent-a", MessageType.STATUS));

        List<Message> results = messageStore.scan(
                MessageQuery.builder().channelId(ch.id).afterId(first.id).build());

        assertEquals(1, results.size());
        assertEquals(second.id, results.get(0).id);
    }

    @Test
    @TestTransaction
    void scan_bySender_returnsMatchingOnly() {
        Channel ch = createChannel();
        messageStore.put(buildMessage(ch.id, "agent-a", MessageType.COMMAND));
        messageStore.put(buildMessage(ch.id, "agent-b", MessageType.COMMAND));

        List<Message> results = messageStore.scan(
                MessageQuery.builder().channelId(ch.id).sender("agent-a").build());

        assertEquals(1, results.size());
        assertEquals("agent-a", results.get(0).sender);
    }

    @Test
    @TestTransaction
    void scan_contentPattern_returnsMatchingOnly() {
        Channel ch = createChannel();
        Message m = buildMessage(ch.id, "agent-a", MessageType.COMMAND);
        m.content = "special-keyword-here";
        messageStore.put(m);
        messageStore.put(buildMessage(ch.id, "agent-b", MessageType.COMMAND));

        List<Message> results = messageStore.scan(
                MessageQuery.builder().channelId(ch.id).contentPattern("special-keyword").build());

        assertEquals(1, results.size());
    }

    @Test
    @TestTransaction
    void scan_inReplyTo_returnsOnlyReplies() {
        Channel ch = createChannel();
        Message parent = messageStore.put(buildMessage(ch.id, "agent-a", MessageType.COMMAND));
        Message reply = buildMessage(ch.id, "agent-b", MessageType.RESPONSE);
        reply.inReplyTo = parent.id;
        messageStore.put(reply);

        List<Message> results = messageStore.scan(
                MessageQuery.replies(ch.id, parent.id));

        assertEquals(1, results.size());
        assertEquals(reply.id, results.get(0).id);
    }

    @Test
    @TestTransaction
    void countByChannel_returnsCorrectCount() {
        Channel ch = createChannel();
        messageStore.put(buildMessage(ch.id, "agent-a", MessageType.COMMAND));
        messageStore.put(buildMessage(ch.id, "agent-b", MessageType.RESPONSE));

        assertEquals(2, messageStore.countByChannel(ch.id));
    }

    @Test
    @TestTransaction
    void deleteAll_removesAllChannelMessages() {
        Channel ch = createChannel();
        messageStore.put(buildMessage(ch.id, "agent-a", MessageType.COMMAND));
        messageStore.put(buildMessage(ch.id, "agent-b", MessageType.RESPONSE));

        messageStore.deleteAll(ch.id);

        assertEquals(0, messageStore.countByChannel(ch.id));
    }

    @Test
    @TestTransaction
    void delete_removesSpecificMessage() {
        Channel ch = createChannel();
        Message m = messageStore.put(buildMessage(ch.id, "agent-a", MessageType.COMMAND));

        messageStore.delete(m.id);

        assertTrue(messageStore.find(m.id).isEmpty());
    }

    @Test
    @TestTransaction
    void countAllByChannel_returnsCorrectCountsPerChannel() {
        Channel ch1 = createChannel();
        Channel ch2 = createChannel();
        messageStore.put(buildMessage(ch1.id, "agent-a", MessageType.COMMAND));
        messageStore.put(buildMessage(ch1.id, "agent-b", MessageType.RESPONSE));
        messageStore.put(buildMessage(ch2.id, "agent-c", MessageType.STATUS));

        Map<UUID, Long> counts = messageStore.countAllByChannel();

        assertTrue(counts.containsKey(ch1.id));
        assertTrue(counts.containsKey(ch2.id));
        assertEquals(2L, counts.get(ch1.id));
        assertEquals(1L, counts.get(ch2.id));
    }

    @Test
    @TestTransaction
    void countAllByChannel_doesNotContainChannelWithNoMessages() {
        // Create a channel but persist no messages for it — it must not appear in the count map
        Channel isolatedChannel = createChannel();

        Map<UUID, Long> counts = messageStore.countAllByChannel();

        assertFalse(counts.containsKey(isolatedChannel.id),
                "Channel with no messages must not appear in countAllByChannel result");
    }

    @Test
    @TestTransaction
    void distinctSendersByChannel_excludesSpecifiedType_andDeduplicates() {
        Channel ch = createChannel();
        messageStore.put(buildMessage(ch.id, "agent-a", MessageType.COMMAND));
        messageStore.put(buildMessage(ch.id, "agent-a", MessageType.STATUS)); // duplicate sender
        messageStore.put(buildMessage(ch.id, "agent-b", MessageType.RESPONSE));
        Message evt = buildMessage(ch.id, "sys-monitor", MessageType.EVENT);
        evt.content = "{\"tool_name\":\"t\",\"duration_ms\":1}";
        messageStore.put(evt);

        List<String> senders = messageStore.distinctSendersByChannel(ch.id, MessageType.EVENT);

        assertTrue(senders.contains("agent-a"), "agent-a should be present");
        assertTrue(senders.contains("agent-b"), "agent-b should be present");
        assertFalse(senders.contains("sys-monitor"), "EVENT sender should be excluded");
        // Deduplication: agent-a posted twice but must appear only once
        assertEquals(1, senders.stream().filter("agent-a"::equals).count());
    }
}
