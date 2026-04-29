package io.quarkiverse.qhorus.runtime.message;

import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkiverse.qhorus.api.message.MessageTypeViolationException;
import io.quarkiverse.qhorus.runtime.channel.Channel;

@FunctionalInterface
public interface MessageTypePolicy {

    /**
     * Validates that {@code type} is permitted on {@code channel}.
     * Throws {@link MessageTypeViolationException} to reject; returns normally to allow.
     */
    void validate(Channel channel, MessageType type);
}
