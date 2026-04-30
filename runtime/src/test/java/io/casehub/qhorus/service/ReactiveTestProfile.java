package io.casehub.qhorus.service;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Activates reactive service alternatives for integration testing.
 *
 * <p>
 * NOTE: Reactive services call {@code Panache.withTransaction()} which requires a
 * native reactive datasource driver. H2 has no reactive driver — tests using this
 * profile must be {@code @Disabled} until a PostgreSQL Dev Services or Docker
 * environment is available.
 */
public class ReactiveTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.arc.selected-alternatives",
                String.join(",",
                        "io.casehub.qhorus.runtime.channel.ReactiveChannelService",
                        "io.casehub.qhorus.runtime.instance.ReactiveInstanceService",
                        "io.casehub.qhorus.runtime.message.ReactiveMessageService",
                        "io.casehub.qhorus.runtime.data.ReactiveDataService",
                        "io.casehub.qhorus.runtime.watchdog.ReactiveWatchdogService",
                        "io.casehub.qhorus.runtime.ledger.ReactiveLedgerWriteService"));
    }
}
