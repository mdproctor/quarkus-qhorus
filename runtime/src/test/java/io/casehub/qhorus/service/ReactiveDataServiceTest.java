package io.casehub.qhorus.service;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;

import io.casehub.qhorus.runtime.data.ReactiveDataService;
import io.casehub.qhorus.runtime.data.SharedData;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@Disabled("ReactiveDataService calls Panache.withTransaction() — requires reactive datasource.")
@QuarkusTest
@TestProfile(ReactiveTestProfile.class)
class ReactiveDataServiceTest extends DataServiceContractTest {

    @Inject
    ReactiveDataService svc;

    @Override
    protected SharedData store(String key, String content) {
        return svc.store(key, null, "test", content, false, true).await().indefinitely();
    }

    @Override
    protected Optional<SharedData> getByKey(String key) {
        return svc.getByKey(key).await().indefinitely();
    }

    @Override
    protected List<SharedData> listAll() {
        return svc.listAll().await().indefinitely();
    }
}
