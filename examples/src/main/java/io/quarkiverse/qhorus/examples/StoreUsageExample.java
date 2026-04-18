package io.quarkiverse.qhorus.examples;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.message.MessageType;
import io.quarkiverse.qhorus.runtime.store.ChannelStore;
import io.quarkiverse.qhorus.runtime.store.MessageStore;
import io.quarkiverse.qhorus.runtime.store.query.ChannelQuery;
import io.quarkiverse.qhorus.runtime.store.query.MessageQuery;

/**
 * Example showing how to use Qhorus store interfaces in a consuming service.
 *
 * In production: JpaChannelStore and JpaMessageStore are the default CDI beans.
 * In tests: add quarkus-qhorus-testing at test scope — InMemory*Store activates
 * automatically via @Alternative @Priority(1), no database required.
 */
@ApplicationScoped
public class StoreUsageExample {

    // Package-private for direct injection in unit tests
    @Inject
    ChannelStore channelStore;

    @Inject
    MessageStore messageStore;

    /** Create a channel and return it. */
    public Channel createChannel(String name, ChannelSemantic semantic) {
        Channel ch = new Channel();
        ch.name = name;
        ch.semantic = semantic;
        return channelStore.put(ch);
    }

    /** Post a message to a named channel. Returns empty if channel not found. */
    public Optional<Message> postMessage(String channelName, String sender, String content) {
        return channelStore.findByName(channelName).map(ch -> {
            Message m = new Message();
            m.channelId = ch.id;
            m.sender = sender;
            m.messageType = MessageType.REQUEST;
            m.content = content;
            return messageStore.put(m);
        });
    }

    /** Retrieve all non-EVENT messages for a channel after a cursor. */
    public List<Message> pollMessages(UUID channelId, Long afterId) {
        return messageStore.scan(
                MessageQuery.poll(channelId, afterId, 20)
                        .toBuilder()
                        .excludeTypes(List.of(MessageType.EVENT))
                        .build());
    }

    /** List all paused channels. */
    public List<Channel> pausedChannels() {
        return channelStore.scan(ChannelQuery.pausedOnly());
    }
}
