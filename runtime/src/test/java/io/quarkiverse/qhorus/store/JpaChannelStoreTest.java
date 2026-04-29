package io.quarkiverse.qhorus.store;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.api.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.store.ChannelStore;
import io.quarkiverse.qhorus.runtime.store.query.ChannelQuery;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class JpaChannelStoreTest {

    @Inject
    ChannelStore channelStore;

    @Test
    @TestTransaction
    void put_persistsChannelAndAssignsId() {
        Channel ch = new Channel();
        ch.name = "put-test-" + UUID.randomUUID();
        ch.semantic = ChannelSemantic.APPEND;

        Channel saved = channelStore.put(ch);

        assertNotNull(saved.id);
        assertEquals(ch.name, saved.name);
    }

    @Test
    @TestTransaction
    void find_returnsChannel_whenExists() {
        Channel ch = new Channel();
        ch.name = "find-test-" + UUID.randomUUID();
        ch.semantic = ChannelSemantic.APPEND;
        channelStore.put(ch);

        Optional<Channel> found = channelStore.find(ch.id);

        assertTrue(found.isPresent());
        assertEquals(ch.name, found.get().name);
    }

    @Test
    @TestTransaction
    void find_returnsEmpty_whenNotFound() {
        assertTrue(channelStore.find(UUID.randomUUID()).isEmpty());
    }

    @Test
    @TestTransaction
    void findByName_returnsChannel_whenExists() {
        Channel ch = new Channel();
        ch.name = "named-" + UUID.randomUUID();
        ch.semantic = ChannelSemantic.COLLECT;
        channelStore.put(ch);

        Optional<Channel> found = channelStore.findByName(ch.name);

        assertTrue(found.isPresent());
        assertEquals(ChannelSemantic.COLLECT, found.get().semantic);
    }

    @Test
    @TestTransaction
    void scan_pausedOnly_returnsOnlyPausedChannels() {
        String suffix = UUID.randomUUID().toString();

        Channel active = new Channel();
        active.name = "active-" + suffix;
        active.semantic = ChannelSemantic.APPEND;
        active.paused = false;
        channelStore.put(active);

        Channel paused = new Channel();
        paused.name = "paused-" + suffix;
        paused.semantic = ChannelSemantic.APPEND;
        paused.paused = true;
        channelStore.put(paused);

        // Note: factory method is pausedOnly() not paused() — naming collision with accessor
        List<Channel> results = channelStore.scan(ChannelQuery.pausedOnly());

        assertTrue(results.stream().anyMatch(c -> c.name.equals(paused.name)));
        assertTrue(results.stream().noneMatch(c -> c.name.equals(active.name)));
    }

    @Test
    @TestTransaction
    void delete_removesChannel() {
        Channel ch = new Channel();
        ch.name = "delete-test-" + UUID.randomUUID();
        ch.semantic = ChannelSemantic.APPEND;
        channelStore.put(ch);

        channelStore.delete(ch.id);

        assertTrue(channelStore.find(ch.id).isEmpty());
    }
}
