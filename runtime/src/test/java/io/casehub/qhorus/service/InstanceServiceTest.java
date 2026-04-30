package io.casehub.qhorus.service;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import io.casehub.qhorus.runtime.instance.Instance;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestTransaction
class InstanceServiceTest extends InstanceServiceContractTest {

    @Inject
    InstanceService svc;

    @Override
    protected Instance register(String instanceId, String desc) {
        return svc.register(instanceId, desc, java.util.List.of());
    }

    @Override
    protected Optional<Instance> findByInstanceId(String instanceId) {
        return svc.findByInstanceId(instanceId);
    }

    @Override
    protected List<Instance> listAll() {
        return svc.listAll();
    }
}
