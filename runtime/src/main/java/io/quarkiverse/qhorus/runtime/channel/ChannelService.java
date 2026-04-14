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
        Channel channel = new Channel();
        channel.name = name;
        channel.description = description;
        channel.semantic = semantic;
        channel.barrierContributors = barrierContributors;
        channel.persist();
        return channel;
    }

    public Optional<Channel> findByName(String name) {
        return Channel.find("name", name).firstResultOptional();
    }

    public Optional<Channel> findById(UUID id) {
        return Channel.findByIdOptional(id);
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
