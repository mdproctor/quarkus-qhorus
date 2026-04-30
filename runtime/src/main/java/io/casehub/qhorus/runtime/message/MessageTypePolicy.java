package io.casehub.qhorus.runtime.message;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageTypeViolationException;
import io.casehub.qhorus.runtime.channel.Channel;

@FunctionalInterface
public interface MessageTypePolicy {

    /**
     * Validates that {@code type} is permitted on {@code channel}.
     * Throws {@link MessageTypeViolationException} to reject; returns normally to allow.
     */
    void validate(Channel channel, MessageType type);
}
