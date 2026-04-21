package io.quarkiverse.qhorus.testing.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.message.MessageType;
import io.quarkiverse.qhorus.runtime.store.query.MessageQuery;

public abstract class MessageStoreContractTest {

    protected abstract Message put(Message message);

    protected abstract Optional<Message> find(Long id);

    protected abstract List<Message> scan(MessageQuery query);

    protected abstract void reset();

    @BeforeEach
    void beforeEach() {
        reset();
    }

    @Test
    void put_assignsId_whenNull() {
        assertNotNull(put(msg(UUID.randomUUID(), "alice", MessageType.REQUEST)).id);
    }

    @Test
    void put_idsAreMonotonicallyIncreasing() {
        UUID ch = UUID.randomUUID();
        Message m1 = put(msg(ch, "alice", MessageType.REQUEST));
        Message m2 = put(msg(ch, "bob", MessageType.RESPONSE));
        assertTrue(m2.id > m1.id);
    }

    @Test
    void find_returnsMessage_whenPresent() {
        Message saved = put(msg(UUID.randomUUID(), "alice", MessageType.REQUEST));
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
        put(msg(ch1, "alice", MessageType.REQUEST));
        put(msg(ch2, "bob", MessageType.REQUEST));
        List<Message> results = scan(MessageQuery.builder().channelId(ch1).build());
        assertTrue(results.stream().allMatch(m -> ch1.equals(m.channelId)));
        assertEquals(1, results.size());
    }

    @Test
    void scan_excludesEventType() {
        UUID ch = UUID.randomUUID();
        put(msg(ch, "alice", MessageType.REQUEST));
        put(msg(ch, "system", MessageType.EVENT));
        List<Message> results = scan(MessageQuery.builder()
                .channelId(ch)
                .excludeTypes(List.of(MessageType.EVENT))
                .build());
        assertTrue(results.stream().noneMatch(m -> m.messageType == MessageType.EVENT));
        assertEquals(1, results.size());
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
