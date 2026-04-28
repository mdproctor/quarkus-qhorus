package io.quarkiverse.qhorus.runtime.channel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.quarkiverse.qhorus.runtime.store.MessageStore;
import io.quarkiverse.qhorus.runtime.store.ReactiveChannelStore;
import io.quarkiverse.qhorus.runtime.store.query.ChannelQuery;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

@Alternative
@ApplicationScoped
public class ReactiveChannelService {

    @Inject
    ReactiveChannelStore channelStore;

    @Inject
    public MessageStore messageStore;

    public Uni<Channel> create(String name, String description, ChannelSemantic semantic,
            String barrierContributors, String allowedWriters, String adminInstances,
            Integer rateLimitPerChannel, Integer rateLimitPerInstance) {
        return create(name, description, semantic, barrierContributors, allowedWriters,
                adminInstances, rateLimitPerChannel, rateLimitPerInstance, null);
    }

    public Uni<Channel> create(String name, String description, ChannelSemantic semantic,
            String barrierContributors, String allowedWriters, String adminInstances,
            Integer rateLimitPerChannel, Integer rateLimitPerInstance, String allowedTypes) {
        return Panache.withTransaction(() -> {
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
            return channelStore.put(channel);
        });
    }

    public Uni<Channel> create(String name, String description, ChannelSemantic semantic,
            String barrierContributors) {
        return create(name, description, semantic, barrierContributors, null, null, null, null);
    }

    public Uni<Channel> create(String name, String description, ChannelSemantic semantic,
            String barrierContributors, String allowedWriters) {
        return create(name, description, semantic, barrierContributors, allowedWriters, null, null, null);
    }

    public Uni<Channel> create(String name, String description, ChannelSemantic semantic,
            String barrierContributors, String allowedWriters, String adminInstances) {
        return create(name, description, semantic, barrierContributors, allowedWriters, adminInstances, null, null);
    }

    public Uni<Channel> setRateLimits(String name, Integer rateLimitPerChannel, Integer rateLimitPerInstance) {
        return Panache.withTransaction(() -> channelStore.findByName(name)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + name)))
                .map(ch -> {
                    ch.rateLimitPerChannel = rateLimitPerChannel;
                    ch.rateLimitPerInstance = rateLimitPerInstance;
                    return ch;
                }));
    }

    public Uni<Channel> setAllowedWriters(String name, String allowedWriters) {
        return Panache.withTransaction(() -> channelStore.findByName(name)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + name)))
                .map(ch -> {
                    ch.allowedWriters = (allowedWriters == null || allowedWriters.isBlank()) ? null
                            : allowedWriters;
                    return ch;
                }));
    }

    public Uni<Channel> setAdminInstances(String name, String adminInstances) {
        return Panache.withTransaction(() -> channelStore.findByName(name)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + name)))
                .map(ch -> {
                    ch.adminInstances = (adminInstances == null || adminInstances.isBlank()) ? null
                            : adminInstances;
                    return ch;
                }));
    }

    public Uni<Optional<Channel>> findByName(String name) {
        return channelStore.findByName(name);
    }

    public Uni<Optional<Channel>> findById(UUID id) {
        return channelStore.find(id);
    }

    public Uni<Channel> pause(String name) {
        return Panache.withTransaction(() -> channelStore.findByName(name)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + name)))
                .map(ch -> {
                    ch.paused = true;
                    return ch;
                }));
    }

    public Uni<Channel> resume(String name) {
        return Panache.withTransaction(() -> channelStore.findByName(name)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + name)))
                .map(ch -> {
                    ch.paused = false;
                    return ch;
                }));
    }

    public Uni<List<Channel>> listAll() {
        return channelStore.scan(ChannelQuery.all());
    }

    /**
     * Delete a channel by name. Uses blocking {@code MessageStore} for count and purge
     * (no reactive equivalents). Infrequent admin operation — blocking is acceptable.
     *
     * @param name the channel name
     * @param force when false, rejects if the channel has messages
     * @return number of messages deleted
     */
    public Uni<Long> delete(final String name, final boolean force) {
        return Panache.withTransaction(() -> channelStore.findByName(name)
                .map(opt -> opt.orElseThrow(
                        () -> new IllegalArgumentException("Channel not found: " + name)))
                .map(ch -> {
                    int messageCount = messageStore.countByChannel(ch.id);
                    if (messageCount > 0 && !force) {
                        throw new IllegalStateException(
                                "Channel '" + name + "' has " + messageCount
                                        + " messages. Pass force=true to delete anyway.");
                    }
                    if (messageCount > 0) {
                        messageStore.deleteAll(ch.id);
                    }
                    channelStore.delete(ch.id);
                    return (long) messageCount;
                }));
    }

    public Uni<Void> updateLastActivity(UUID channelId) {
        return Panache.withTransaction(() -> channelStore.find(channelId)
                .invoke(opt -> opt.ifPresent(ch -> ch.lastActivityAt = Instant.now()))
                .replaceWithVoid());
    }
}
