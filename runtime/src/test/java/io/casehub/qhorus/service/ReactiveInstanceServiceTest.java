package io.casehub.qhorus.service;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;

import io.casehub.qhorus.runtime.instance.Instance;
import io.casehub.qhorus.runtime.instance.ReactiveInstanceService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@Disabled("ReactiveInstanceService calls Panache.withTransaction() — requires reactive datasource.")
@QuarkusTest
@TestProfile(ReactiveTestProfile.class)
class ReactiveInstanceServiceTest extends InstanceServiceContractTest {

    @Inject
    ReactiveInstanceService svc;

    @Override
    protected Instance register(String instanceId, String desc) {
        return svc.register(instanceId, desc, java.util.List.of()).await().indefinitely();
    }

    @Override
    protected Optional<Instance> findByInstanceId(String instanceId) {
        return svc.findByInstanceId(instanceId).await().indefinitely();
    }

    @Override
    protected List<Instance> listAll() {
        return svc.listAll().await().indefinitely();
    }
}
