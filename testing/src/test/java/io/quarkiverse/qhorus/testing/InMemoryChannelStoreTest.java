package io.quarkiverse.qhorus.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.store.query.ChannelQuery;
import io.quarkiverse.qhorus.testing.contract.ChannelStoreContractTest;

class InMemoryChannelStoreTest extends ChannelStoreContractTest {
    private final InMemoryChannelStore store = new InMemoryChannelStore();

    @Override
    protected Channel put(Channel c) {
        return store.put(c);
    }

    @Override
    protected Optional<Channel> find(UUID id) {
        return store.find(id);
    }

    @Override
    protected Optional<Channel> findByName(String n) {
        return store.findByName(n);
    }

    @Override
    protected List<Channel> scan(ChannelQuery q) {
        return store.scan(q);
    }

    @Override
    protected void delete(UUID id) {
        store.delete(id);
    }

    @Override
    protected void reset() {
        store.clear();
    }

    @Test
    void scan_bySemantic_returnsMatching() {
        Channel barrier = channel("barrier-" + UUID.randomUUID(), ChannelSemantic.BARRIER);
        Channel append = channel("append-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        store.put(barrier);
        store.put(append);
        List<Channel> results = store.scan(ChannelQuery.bySemantic(ChannelSemantic.BARRIER));
        assertEquals(1, results.size());
        assertEquals(ChannelSemantic.BARRIER, results.get(0).semantic);
    }

    @Test
    void delete_nonexistent_doesNotThrow() {
        assertDoesNotThrow(() -> store.delete(UUID.randomUUID()));
    }

    @Test
    void clear_removesAll() {
        store.put(channel("temp-" + UUID.randomUUID(), ChannelSemantic.APPEND));
        store.clear();
        assertTrue(store.scan(ChannelQuery.all()).isEmpty());
    }
}
