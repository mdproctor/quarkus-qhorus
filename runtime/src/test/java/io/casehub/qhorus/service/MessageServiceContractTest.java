package io.casehub.qhorus.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Message;

public abstract class MessageServiceContractTest {

    protected abstract Message send(UUID channelId, String sender, MessageType type,
            String content, String correlationId, Long inReplyTo);

    protected abstract Optional<Message> findById(Long id);

    protected abstract List<Message> pollAfter(UUID channelId, Long afterId, int limit);

    @Test
    void send_returnsPersistedMessage() {
        UUID ch = UUID.randomUUID();
        Message m = send(ch, "alice", MessageType.COMMAND, "hello", "corr-1", null);
        assertNotNull(m.id);
        assertEquals("alice", m.sender);
        assertEquals(MessageType.COMMAND, m.messageType);
    }

    @Test
    void findById_returnsMessage_whenExists() {
        UUID ch = UUID.randomUUID();
        Message sent = send(ch, "alice", MessageType.STATUS, "content", null, null);
        Optional<Message> found = findById(sent.id);
        assertTrue(found.isPresent());
        assertEquals("alice", found.get().sender);
    }

    @Test
    void findById_returnsEmpty_whenAbsent() {
        assertTrue(findById(Long.MAX_VALUE).isEmpty());
    }

    @Test
    void pollAfter_excludesEventType() {
        UUID ch = UUID.randomUUID();
        send(ch, "alice", MessageType.COMMAND, "req", null, null);
        send(ch, "system", MessageType.EVENT, "evt", null, null);
        List<Message> polled = pollAfter(ch, 0L, 20);
        assertTrue(polled.stream().noneMatch(m -> m.messageType == MessageType.EVENT));
    }

    @Test
    void pollAfter_returnsOnlyAfterCursor() {
        UUID ch = UUID.randomUUID();
        Message first = send(ch, "alice", MessageType.COMMAND, "first", null, null);
        send(ch, "alice", MessageType.STATUS, "second", null, null);
        List<Message> polled = pollAfter(ch, first.id, 20);
        assertTrue(polled.stream().noneMatch(m -> m.id <= first.id));
    }
}
