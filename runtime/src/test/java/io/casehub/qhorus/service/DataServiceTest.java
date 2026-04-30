package io.casehub.qhorus.service;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import io.casehub.qhorus.runtime.data.DataService;
import io.casehub.qhorus.runtime.data.SharedData;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestTransaction
class DataServiceTest extends DataServiceContractTest {

    @Inject
    DataService svc;

    @Override
    protected SharedData store(String key, String content) {
        return svc.store(key, null, "test", content, false, true);
    }

    @Override
    protected Optional<SharedData> getByKey(String key) {
        return svc.getByKey(key);
    }

    @Override
    protected List<SharedData> listAll() {
        return svc.listAll();
    }
}
