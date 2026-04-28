package io.quarkiverse.qhorus.testing.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.store.query.ChannelQuery;

public abstract class ChannelStoreContractTest {

    protected abstract Channel put(Channel channel);

    protected abstract Optional<Channel> find(UUID id);

    protected abstract Optional<Channel> findByName(String name);

    protected abstract List<Channel> scan(ChannelQuery query);

    protected abstract void delete(UUID id);

    protected abstract void reset();

    @BeforeEach
    void beforeEach() {
        reset();
    }

    @Test
    void put_assignsId_whenNull() {
        Channel ch = channel("put-null-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        assertNotNull(put(ch).id);
    }

    @Test
    void put_preservesExistingId() {
        Channel ch = channel("put-preset-" + UUID.randomUUID(), ChannelSemantic.COLLECT);
        ch.id = UUID.randomUUID();
        UUID expected = ch.id;
        assertEquals(expected, put(ch).id);
    }

    @Test
    void find_returnsChannel_whenPresent() {
        Channel ch = channel("find-present-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        put(ch);
        assertTrue(find(ch.id).isPresent());
    }

    @Test
    void find_returnsEmpty_whenAbsent() {
        assertTrue(find(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findByName_returnsChannel_whenExists() {
        String name = "findname-" + UUID.randomUUID();
        Channel ch = channel(name, ChannelSemantic.BARRIER);
        put(ch);
        Optional<Channel> found = findByName(name);
        assertTrue(found.isPresent());
        assertEquals(ChannelSemantic.BARRIER, found.get().semantic);
    }

    @Test
    void findByName_returnsEmpty_whenNoMatch() {
        assertTrue(findByName("nosuch-" + UUID.randomUUID()).isEmpty());
    }

    @Test
    void scan_all_returnsAllPutChannels() {
        put(channel("scan-a-" + UUID.randomUUID(), ChannelSemantic.APPEND));
        put(channel("scan-b-" + UUID.randomUUID(), ChannelSemantic.COLLECT));
        assertTrue(scan(ChannelQuery.all()).size() >= 2);
    }

    @Test
    void scan_pausedOnly_returnsOnlyPaused() {
        Channel active = channel("active-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        active.paused = false;
        put(active);
        Channel paused = channel("paused-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        paused.paused = true;
        put(paused);
        List<Channel> results = scan(ChannelQuery.pausedOnly());
        assertTrue(results.stream().anyMatch(c -> c.name.equals(paused.name)));
        assertTrue(results.stream().noneMatch(c -> c.name.equals(active.name)));
    }

    @Test
    void delete_removesChannel() {
        Channel ch = channel("del-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        put(ch);
        delete(ch.id);
        assertTrue(find(ch.id).isEmpty());
    }

    @Test
    void put_and_find_preserves_allowedTypes() {
        Channel ch = channel("allowed-types-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        ch.allowedTypes = "EVENT";
        put(ch);
        Channel found = find(ch.id).orElseThrow();
        assertEquals("EVENT", found.allowedTypes);
    }

    @Test
    void put_and_find_preserves_null_allowedTypes() {
        Channel ch = channel("null-allowed-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        ch.allowedTypes = null;
        put(ch);
        Channel found = find(ch.id).orElseThrow();
        assertNull(found.allowedTypes);
    }

    protected Channel channel(String name, ChannelSemantic semantic) {
        Channel ch = new Channel();
        ch.name = name;
        ch.semantic = semantic;
        return ch;
    }
}
