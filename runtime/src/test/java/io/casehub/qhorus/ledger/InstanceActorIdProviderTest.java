package io.casehub.qhorus.ledger;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.spi.InstanceActorIdProvider;
import io.casehub.qhorus.runtime.ledger.DefaultInstanceActorIdProvider;

class InstanceActorIdProviderTest {

    private final InstanceActorIdProvider provider = new DefaultInstanceActorIdProvider();

    @Test
    void default_returnsInputUnchanged() {
        assertEquals("claudony-worker-abc123", provider.resolve("claudony-worker-abc123"));
    }

    @Test
    void default_returnsPersonaFormatUnchanged() {
        assertEquals("claude:analyst@v1", provider.resolve("claude:analyst@v1"));
    }

    @Test
    void default_returnsEmptyStringUnchanged() {
        assertEquals("", provider.resolve(""));
    }

    @Test
    void customImplementation_canMapToPersonaFormat() {
        InstanceActorIdProvider custom = instanceId -> instanceId.startsWith("claudony-worker-") ? "claude:analyst@v1"
                : instanceId;
        assertEquals("claude:analyst@v1", custom.resolve("claudony-worker-abc123"));
        assertEquals("other-agent", custom.resolve("other-agent"));
    }

    @Test
    void isFunctionalInterface_lambdaCompiles() {
        InstanceActorIdProvider p = id -> "mapped-" + id;
        assertEquals("mapped-foo", p.resolve("foo"));
    }
}
