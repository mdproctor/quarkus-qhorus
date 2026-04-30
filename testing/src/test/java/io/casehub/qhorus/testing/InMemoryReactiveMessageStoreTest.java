package io.casehub.qhorus.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.store.query.MessageQuery;
import io.casehub.qhorus.testing.contract.MessageStoreContractTest;

class InMemoryReactiveMessageStoreTest extends MessageStoreContractTest {
    private final InMemoryReactiveMessageStore store = new InMemoryReactiveMessageStore();

    @Override
    protected Message put(Message m) {
        return store.put(m).await().indefinitely();
    }

    @Override
    protected Optional<Message> find(Long id) {
        return store.find(id).await().indefinitely();
    }

    @Override
    protected List<Message> scan(MessageQuery q) {
        return store.scan(q).await().indefinitely();
    }

    @Override
    protected Map<UUID, Long> countAllByChannel() {
        return store.countAllByChannel().await().indefinitely();
    }

    @Override
    protected List<String> distinctSendersByChannel(UUID channelId, MessageType excludedType) {
        return store.distinctSendersByChannel(channelId, excludedType).await().indefinitely();
    }

    @Override
    protected void reset() {
        store.clear();
    }

    @Test
    void scan_poll_respectsAfterIdAndLimit() {
        UUID channelId = UUID.randomUUID();
        Message m1 = store.put(msg(channelId, "a", MessageType.COMMAND)).await().indefinitely();
        Message m2 = store.put(msg(channelId, "b", MessageType.COMMAND)).await().indefinitely();
        store.put(msg(channelId, "c", MessageType.COMMAND)).await().indefinitely();

        List<Message> results = store.scan(MessageQuery.poll(channelId, m1.id, 1)).await().indefinitely();
        assertEquals(1, results.size());
        assertEquals(m2.id, results.get(0).id);
    }

    @Test
    void deleteAll_removesAllMessagesInChannel() {
        UUID channelId = UUID.randomUUID();
        store.put(msg(channelId, "a", MessageType.COMMAND)).await().indefinitely();
        store.put(msg(channelId, "b", MessageType.COMMAND)).await().indefinitely();
        UUID otherId = UUID.randomUUID();
        store.put(msg(otherId, "c", MessageType.COMMAND)).await().indefinitely();

        store.deleteAll(channelId).await().indefinitely();
        assertEquals(0, store.countByChannel(channelId).await().indefinitely());
        assertEquals(1, store.countByChannel(otherId).await().indefinitely());
    }

    @Test
    void countByChannel_returnsCorrectCount() {
        UUID channelId = UUID.randomUUID();
        store.put(msg(channelId, "a", MessageType.COMMAND)).await().indefinitely();
        store.put(msg(channelId, "b", MessageType.RESPONSE)).await().indefinitely();
        store.put(msg(UUID.randomUUID(), "c", MessageType.EVENT)).await().indefinitely();

        assertEquals(2, store.countByChannel(channelId).await().indefinitely());
    }
}
