package io.quarkiverse.qhorus.testing;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.store.query.ChannelQuery;

class InMemoryReactiveChannelStoreTest {

    private final InMemoryReactiveChannelStore store = new InMemoryReactiveChannelStore();

    @BeforeEach
    void reset() {
        store.clear();
    }

    @Test
    void put_assignsIdAndReturns() {
        Channel ch = channel("rx-put-" + UUID.randomUUID(), ChannelSemantic.APPEND);

        Channel saved = store.put(ch).await().indefinitely();

        assertThat(saved.id).isNotNull();
        assertThat(saved.name).isEqualTo(ch.name);
    }

    @Test
    void find_returnsEmpty_whenNotFound() {
        var result = store.find(UUID.randomUUID()).await().indefinitely();
        assertThat(result).isEmpty();
    }

    @Test
    void find_returnsChannel_whenExists() {
        Channel ch = channel("rx-find-" + UUID.randomUUID(), ChannelSemantic.COLLECT);
        store.put(ch).await().indefinitely();

        var found = store.find(ch.id).await().indefinitely();

        assertThat(found).isPresent();
        assertThat(found.get().semantic).isEqualTo(ChannelSemantic.COLLECT);
    }

    @Test
    void findByName_returnsChannel_whenExists() {
        Channel ch = channel("named-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        store.put(ch).await().indefinitely();

        var found = store.findByName(ch.name).await().indefinitely();

        assertThat(found).isPresent();
    }

    @Test
    void scan_byPaused_returnsOnlyPaused() {
        Channel active = channel("active-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        Channel paused = channel("paused-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        paused.paused = true;
        store.put(active).await().indefinitely();
        store.put(paused).await().indefinitely();

        List<Channel> results = store.scan(ChannelQuery.pausedOnly()).await().indefinitely();

        assertThat(results).anyMatch(c -> c.name.equals(paused.name));
        assertThat(results).noneMatch(c -> c.name.equals(active.name));
    }

    @Test
    void delete_removesChannel() {
        Channel ch = channel("rx-del-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        store.put(ch).await().indefinitely();

        store.delete(ch.id).await().indefinitely();

        assertThat(store.find(ch.id).await().indefinitely()).isEmpty();
    }

    private Channel channel(String name, ChannelSemantic semantic) {
        Channel ch = new Channel();
        ch.name = name;
        ch.semantic = semantic;
        return ch;
    }
}
