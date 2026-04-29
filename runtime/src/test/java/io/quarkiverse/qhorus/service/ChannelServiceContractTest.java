package io.quarkiverse.qhorus.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.api.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.channel.Channel;

public abstract class ChannelServiceContractTest {

    protected abstract Channel create(String name, String desc, ChannelSemantic sem);

    protected abstract Optional<Channel> findByName(String name);

    protected abstract List<Channel> listAll();

    protected abstract Channel pause(String name);

    protected abstract Channel resume(String name);

    @Test
    void create_persistsAndReturnsChannel() {
        String name = "svc-create-" + UUID.randomUUID();
        Channel ch = create(name, "desc", ChannelSemantic.APPEND);
        assertNotNull(ch.id);
        assertEquals(name, ch.name);
        assertEquals(ChannelSemantic.APPEND, ch.semantic);
    }

    @Test
    void findByName_returnsChannel_whenExists() {
        String name = "svc-find-" + UUID.randomUUID();
        create(name, "desc", ChannelSemantic.COLLECT);
        Optional<Channel> found = findByName(name);
        assertTrue(found.isPresent());
        assertEquals(ChannelSemantic.COLLECT, found.get().semantic);
    }

    @Test
    void findByName_returnsEmpty_whenNotFound() {
        assertTrue(findByName("no-such-" + UUID.randomUUID()).isEmpty());
    }

    @Test
    void listAll_includesCreatedChannels() {
        create("list-a-" + UUID.randomUUID(), "desc", ChannelSemantic.APPEND);
        create("list-b-" + UUID.randomUUID(), "desc", ChannelSemantic.BARRIER);
        assertTrue(listAll().size() >= 2);
    }

    @Test
    void pause_and_resume_toggleFlag() {
        String name = "svc-pause-" + UUID.randomUUID();
        create(name, "desc", ChannelSemantic.APPEND);
        Channel paused = pause(name);
        assertTrue(paused.paused);
        Channel resumed = resume(name);
        assertFalse(resumed.paused);
    }
}
