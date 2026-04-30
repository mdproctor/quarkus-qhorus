package io.casehub.qhorus.service;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;

import io.casehub.qhorus.runtime.watchdog.ReactiveWatchdogService;
import io.casehub.qhorus.runtime.watchdog.Watchdog;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@Disabled("ReactiveWatchdogService calls Panache.withTransaction() — requires reactive datasource.")
@QuarkusTest
@TestProfile(ReactiveTestProfile.class)
class ReactiveWatchdogServiceTest extends WatchdogServiceContractTest {

    @Inject
    ReactiveWatchdogService svc;

    @Override
    protected Watchdog register(String conditionType, String targetName, String notificationChannel) {
        return svc.register(conditionType, targetName, null, null, notificationChannel, "test")
                .await().indefinitely();
    }

    @Override
    protected List<Watchdog> listAll() {
        return svc.listAll().await().indefinitely();
    }

    @Override
    protected Boolean delete(UUID id) {
        return svc.delete(id).await().indefinitely();
    }
}
