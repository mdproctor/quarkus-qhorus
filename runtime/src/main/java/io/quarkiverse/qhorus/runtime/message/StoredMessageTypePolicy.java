package io.quarkiverse.qhorus.runtime.message;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.qhorus.runtime.channel.Channel;

@ApplicationScoped
public class StoredMessageTypePolicy implements MessageTypePolicy {

    @Override
    public void validate(Channel channel, MessageType type) {
        if (channel.allowedTypes == null || channel.allowedTypes.isBlank()) {
            return;
        }
        Set<MessageType> allowed = Arrays.stream(channel.allowedTypes.split(","))
                .map(String::trim)
                .map(MessageType::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        if (!allowed.contains(type)) {
            throw new MessageTypeViolationException(channel.name, type, channel.allowedTypes);
        }
    }
}
