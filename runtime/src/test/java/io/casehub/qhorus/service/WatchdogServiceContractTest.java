package io.casehub.qhorus.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.watchdog.Watchdog;

/**
 * Contract test base for watchdog CRUD operations.
 *
 * <p>
 * No blocking runner exists for this base because there is no blocking {@code WatchdogService}
 * — the blocking MCP tools ({@code QhorusMcpTools.registerWatchdog}, etc.) call Panache entity
 * statics directly. Only {@link io.casehub.qhorus.runtime.watchdog.ReactiveWatchdogService}
 * was built as a service class, so the only runner is {@link ReactiveWatchdogServiceTest} (disabled
 * until Docker is available).
 */
public abstract class WatchdogServiceContractTest {

    protected abstract Watchdog register(String conditionType, String targetName,
            String notificationChannel);

    protected abstract List<Watchdog> listAll();

    protected abstract Boolean delete(UUID id);

    @Test
    void register_createsWatchdog() {
        Watchdog w = register("CHANNEL_IDLE", "*", "alerts");
        assertNotNull(w.id);
        assertEquals("CHANNEL_IDLE", w.conditionType);
    }

    @Test
    void listAll_includesRegistered() {
        register("BARRIER_STUCK", "ch-1", "notif");
        assertTrue(listAll().size() >= 1);
    }

    @Test
    void delete_returnsTrue_whenExists() {
        Watchdog w = register("QUEUE_DEPTH", "*", "notif");
        assertTrue(delete(w.id));
    }

    @Test
    void delete_returnsFalse_whenNotFound() {
        assertFalse(delete(UUID.randomUUID()));
    }
}
