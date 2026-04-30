package io.casehub.qhorus.api.message;

public class MessageTypeViolationException extends IllegalStateException {

    public MessageTypeViolationException(String channel, MessageType attempted, String allowed) {
        super("Channel '" + channel + "' does not permit " + attempted + ". Allowed: " + allowed);
    }
}
