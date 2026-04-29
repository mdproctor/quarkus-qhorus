package io.quarkiverse.qhorus.message;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkiverse.qhorus.api.message.MessageTypeViolationException;

class MessageTypeViolationExceptionTest {

    @Test
    void message_includesChannelName() {
        var ex = new MessageTypeViolationException("case-abc/observe", MessageType.QUERY, "EVENT");
        assertTrue(ex.getMessage().contains("case-abc/observe"));
    }

    @Test
    void message_includesAttemptedType() {
        var ex = new MessageTypeViolationException("case-abc/observe", MessageType.QUERY, "EVENT");
        assertTrue(ex.getMessage().contains("QUERY"));
    }

    @Test
    void message_includesAllowedTypes() {
        var ex = new MessageTypeViolationException("case-abc/observe", MessageType.QUERY, "EVENT");
        assertTrue(ex.getMessage().contains("EVENT"));
    }

    @Test
    void isRuntimeException() {
        assertInstanceOf(RuntimeException.class,
                new MessageTypeViolationException("ch", MessageType.STATUS, "EVENT"));
    }
}
