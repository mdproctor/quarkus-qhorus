package io.quarkiverse.qhorus.runtime.channel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class ChannelService {

    @Transactional
    public Channel create(String name, String description, ChannelSemantic semantic, String barrierContributors) {
        return create(name, description, semantic, barrierContributors, null);
    }

    @Transactional
    public Channel create(String name, String description, ChannelSemantic semantic, String barrierContributors,
            String allowedWriters) {
        Channel channel = new Channel();
        channel.name = name;
        channel.description = description;
        channel.semantic = semantic;
        channel.barrierContributors = barrierContributors;
        channel.allowedWriters = (allowedWriters == null || allowedWriters.isBlank()) ? null : allowedWriters;
        channel.persist();
        return channel;
    }

    @Transactional
    public Channel setAllowedWriters(String name, String allowedWriters) {
        Channel ch = findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + name));
        ch.allowedWriters = (allowedWriters == null || allowedWriters.isBlank()) ? null : allowedWriters;
        return ch;
    }

    public Optional<Channel> findByName(String name) {
        return Channel.find("name", name).firstResultOptional();
    }

    public Optional<Channel> findById(UUID id) {
        return Channel.findByIdOptional(id);
    }

    @Transactional
    public Channel pause(String name) {
        Channel ch = findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + name));
        ch.paused = true;
        return ch;
    }

    @Transactional
    public Channel resume(String name) {
        Channel ch = findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + name));
        ch.paused = false;
        return ch;
    }

    public List<Channel> listAll() {
        return Channel.listAll();
    }

    @Transactional
    public void updateLastActivity(UUID channelId) {
        Channel channel = Channel.findById(channelId);
        if (channel != null) {
            channel.lastActivityAt = Instant.now();
        }
    }
}
