package io.quarkiverse.qhorus.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.api.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.store.query.ChannelQuery;
import io.quarkiverse.qhorus.testing.contract.ChannelStoreContractTest;

class InMemoryReactiveChannelStoreTest extends ChannelStoreContractTest {
    private final InMemoryReactiveChannelStore store = new InMemoryReactiveChannelStore();

    @Override
    protected Channel put(Channel c) {
        return store.put(c).await().indefinitely();
    }

    @Override
    protected Optional<Channel> find(UUID id) {
        return store.find(id).await().indefinitely();
    }

    @Override
    protected Optional<Channel> findByName(String n) {
        return store.findByName(n).await().indefinitely();
    }

    @Override
    protected List<Channel> scan(ChannelQuery q) {
        return store.scan(q).await().indefinitely();
    }

    @Override
    protected void delete(UUID id) {
        store.delete(id).await().indefinitely();
    }

    @Override
    protected void reset() {
        store.clear();
    }

    @Test
    void scan_bySemantic_returnsMatching() {
        Channel barrier = channel("barrier-" + UUID.randomUUID(), ChannelSemantic.BARRIER);
        Channel append = channel("append-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        store.put(barrier).await().indefinitely();
        store.put(append).await().indefinitely();
        List<Channel> results = store.scan(ChannelQuery.bySemantic(ChannelSemantic.BARRIER)).await().indefinitely();
        assertEquals(1, results.size());
        assertEquals(ChannelSemantic.BARRIER, results.get(0).semantic);
    }

    @Test
    void delete_nonexistent_doesNotThrow() {
        assertDoesNotThrow(() -> store.delete(UUID.randomUUID()).await().indefinitely());
    }

    @Test
    void clear_removesAll() {
        store.put(channel("temp-" + UUID.randomUUID(), ChannelSemantic.APPEND)).await().indefinitely();
        store.clear();
        assertTrue(store.scan(ChannelQuery.all()).await().indefinitely().isEmpty());
    }
}
