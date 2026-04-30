package io.casehub.qhorus.testing.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.store.query.MessageQuery;

public abstract class MessageStoreContractTest {

    protected abstract Message put(Message message);

    protected abstract Optional<Message> find(Long id);

    protected abstract List<Message> scan(MessageQuery query);

    protected abstract Map<UUID, Long> countAllByChannel();

    protected abstract List<String> distinctSendersByChannel(UUID channelId, MessageType excludedType);

    protected abstract void reset();

    @BeforeEach
    void beforeEach() {
        reset();
    }

    @Test
    void put_assignsId_whenNull() {
        assertNotNull(put(msg(UUID.randomUUID(), "alice", MessageType.COMMAND)).id);
    }

    @Test
    void put_idsAreMonotonicallyIncreasing() {
        UUID ch = UUID.randomUUID();
        Message m1 = put(msg(ch, "alice", MessageType.COMMAND));
        Message m2 = put(msg(ch, "bob", MessageType.RESPONSE));
        assertTrue(m2.id > m1.id);
    }

    @Test
    void find_returnsMessage_whenPresent() {
        Message saved = put(msg(UUID.randomUUID(), "alice", MessageType.COMMAND));
        assertTrue(find(saved.id).isPresent());
    }

    @Test
    void find_returnsEmpty_whenAbsent() {
        assertTrue(find(Long.MAX_VALUE).isEmpty());
    }

    @Test
    void scan_byChannel_returnsOnlyThatChannel() {
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        put(msg(ch1, "alice", MessageType.COMMAND));
        put(msg(ch2, "bob", MessageType.COMMAND));
        List<Message> results = scan(MessageQuery.builder().channelId(ch1).build());
        assertTrue(results.stream().allMatch(m -> ch1.equals(m.channelId)));
        assertEquals(1, results.size());
    }

    @Test
    void scan_excludesEventType() {
        UUID ch = UUID.randomUUID();
        put(msg(ch, "alice", MessageType.COMMAND));
        put(msg(ch, "system", MessageType.EVENT));
        List<Message> results = scan(MessageQuery.builder()
                .channelId(ch)
                .excludeTypes(List.of(MessageType.EVENT))
                .build());
        assertTrue(results.stream().noneMatch(m -> m.messageType == MessageType.EVENT));
        assertEquals(1, results.size());
    }

    @Test
    void countAllByChannel_returnsCountPerChannel() {
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        put(msg(ch1, "alice", MessageType.COMMAND));
        put(msg(ch1, "bob", MessageType.RESPONSE));
        put(msg(ch2, "carol", MessageType.COMMAND));

        Map<UUID, Long> counts = countAllByChannel();
        assertEquals(2L, counts.get(ch1));
        assertEquals(1L, counts.get(ch2));
    }

    @Test
    void distinctSendersByChannel_excludesSpecifiedType() {
        UUID ch = UUID.randomUUID();
        put(msg(ch, "alice", MessageType.COMMAND));
        put(msg(ch, "bob", MessageType.RESPONSE));
        put(msg(ch, "system", MessageType.EVENT));

        List<String> senders = distinctSendersByChannel(ch, MessageType.EVENT);
        assertTrue(senders.contains("alice"));
        assertTrue(senders.contains("bob"));
        assertFalse(senders.contains("system"));
    }

    @Test
    void distinctSendersByChannel_returnsDistinct() {
        UUID ch = UUID.randomUUID();
        put(msg(ch, "alice", MessageType.COMMAND));
        put(msg(ch, "alice", MessageType.RESPONSE));

        List<String> senders = distinctSendersByChannel(ch, MessageType.EVENT);
        assertEquals(1, senders.size());
        assertEquals("alice", senders.get(0));
    }

    protected Message msg(UUID channelId, String sender, MessageType type) {
        Message m = new Message();
        m.channelId = channelId;
        m.sender = sender;
        m.messageType = type;
        m.content = "content";
        return m;
    }
}
