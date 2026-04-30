package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.ToolCallException;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Issue #44 — Watchdog tools return informative errors when disabled (default).
 * Refs #44, Epic #36.
 */
@QuarkusTest
class WatchdogDisabledTest {

    @Inject
    QhorusMcpTools tools;

    @Test
    @TestTransaction
    void registerWatchdogDisabledThrows() {
        ToolCallException ex = assertThrows(ToolCallException.class,
                () -> tools.registerWatchdog("BARRIER_STUCK", "test-channel", 300, null,
                        "alerts", "human"));
        assertTrue(ex.getMessage().toLowerCase().contains("watchdog"),
                "error should mention watchdog");
    }

    @Test
    @TestTransaction
    void listWatchdogsDisabledThrows() {
        assertThrows(ToolCallException.class, () -> tools.listWatchdogs());
    }

    @Test
    @TestTransaction
    void deleteWatchdogDisabledThrows() {
        assertThrows(ToolCallException.class,
                () -> tools.deleteWatchdog(java.util.UUID.randomUUID().toString()));
    }
}
