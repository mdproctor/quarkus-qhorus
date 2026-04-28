package io.quarkiverse.qhorus.runtime.channel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.qhorus.runtime.store.ChannelStore;
import io.quarkiverse.qhorus.runtime.store.query.ChannelQuery;

@ApplicationScoped
public class ChannelService {

    @Inject
    ChannelStore channelStore;

    @Transactional
    public Channel create(String name, String description, ChannelSemantic semantic, String barrierContributors) {
        return create(name, description, semantic, barrierContributors, null);
    }

    @Transactional
    public Channel create(String name, String description, ChannelSemantic semantic, String barrierContributors,
            String allowedWriters) {
        return create(name, description, semantic, barrierContributors, allowedWriters, null);
    }

    @Transactional
    public Channel create(String name, String description, ChannelSemantic semantic, String barrierContributors,
            String allowedWriters, String adminInstances) {
        return create(name, description, semantic, barrierContributors, allowedWriters, adminInstances, null, null);
    }

    @Transactional
    public Channel create(String name, String description, ChannelSemantic semantic, String barrierContributors,
            String allowedWriters, String adminInstances, Integer rateLimitPerChannel, Integer rateLimitPerInstance) {
        return create(name, description, semantic, barrierContributors, allowedWriters,
                adminInstances, rateLimitPerChannel, rateLimitPerInstance, null);
    }

    @Transactional
    public Channel create(String name, String description, ChannelSemantic semantic, String barrierContributors,
            String allowedWriters, String adminInstances, Integer rateLimitPerChannel, Integer rateLimitPerInstance,
            String allowedTypes) {
        Channel channel = new Channel();
        channel.name = name;
        channel.description = description;
        channel.semantic = semantic;
        channel.barrierContributors = barrierContributors;
        channel.allowedWriters = (allowedWriters == null || allowedWriters.isBlank()) ? null : allowedWriters;
        channel.adminInstances = (adminInstances == null || adminInstances.isBlank()) ? null : adminInstances;
        channel.rateLimitPerChannel = rateLimitPerChannel;
        channel.rateLimitPerInstance = rateLimitPerInstance;
        channel.allowedTypes = (allowedTypes == null || allowedTypes.isBlank()) ? null : allowedTypes;
        channelStore.put(channel);
        return channel;
    }

    @Transactional
    public Channel setRateLimits(String name, Integer rateLimitPerChannel, Integer rateLimitPerInstance) {
        Channel ch = findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + name));
        ch.rateLimitPerChannel = rateLimitPerChannel;
        ch.rateLimitPerInstance = rateLimitPerInstance;
        return ch;
    }

    @Transactional
    public Channel setAllowedWriters(String name, String allowedWriters) {
        Channel ch = findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + name));
        ch.allowedWriters = (allowedWriters == null || allowedWriters.isBlank()) ? null : allowedWriters;
        return ch;
    }

    @Transactional
    public Channel setAdminInstances(String name, String adminInstances) {
        Channel ch = findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + name));
        ch.adminInstances = (adminInstances == null || adminInstances.isBlank()) ? null : adminInstances;
        return ch;
    }

    public Optional<Channel> findByName(String name) {
        return channelStore.findByName(name);
    }

    public Optional<Channel> findById(UUID id) {
        return channelStore.find(id);
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
        return channelStore.scan(ChannelQuery.all());
    }

    @Transactional
    public void updateLastActivity(UUID channelId) {
        Channel channel = channelStore.find(channelId).orElse(null);
        if (channel != null) {
            channel.lastActivityAt = Instant.now();
        }
    }
}
