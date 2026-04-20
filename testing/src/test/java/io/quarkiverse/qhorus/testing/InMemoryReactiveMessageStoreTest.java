package io.quarkiverse.qhorus.testing;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.message.MessageType;
import io.quarkiverse.qhorus.runtime.store.query.MessageQuery;

class InMemoryReactiveMessageStoreTest {

    private final InMemoryReactiveMessageStore store = new InMemoryReactiveMessageStore();

    @BeforeEach
    void reset() {
        store.clear();
    }

    @Test
    void put_assignsIdAndReturns() {
        Message m = message(UUID.randomUUID(), "alice");
        Message saved = store.put(m).await().indefinitely();
        assertThat(saved.id).isNotNull();
    }

    @Test
    void find_returnsEmpty_whenNotFound() {
        assertThat(store.find(999L).await().indefinitely()).isEmpty();
    }

    @Test
    void scan_byChannel_returnsMatchingMessages() {
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        store.put(message(ch1, "alice")).await().indefinitely();
        store.put(message(ch2, "bob")).await().indefinitely();

        List<Message> results = store.scan(MessageQuery.forChannel(ch1)).await().indefinitely();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).sender).isEqualTo("alice");
    }

    @Test
    void deleteAll_removesAllForChannel() {
        UUID ch = UUID.randomUUID();
        store.put(message(ch, "a")).await().indefinitely();
        store.put(message(ch, "b")).await().indefinitely();

        store.deleteAll(ch).await().indefinitely();

        assertThat(store.countByChannel(ch).await().indefinitely()).isZero();
    }

    @Test
    void countByChannel_countsCorrectly() {
        UUID ch = UUID.randomUUID();
        store.put(message(ch, "x")).await().indefinitely();
        store.put(message(ch, "y")).await().indefinitely();

        assertThat(store.countByChannel(ch).await().indefinitely()).isEqualTo(2);
    }

    private Message message(UUID channelId, String sender) {
        Message m = new Message();
        m.channelId = channelId;
        m.sender = sender;
        m.messageType = MessageType.REQUEST;
        m.content = "hello";
        return m;
    }
}
