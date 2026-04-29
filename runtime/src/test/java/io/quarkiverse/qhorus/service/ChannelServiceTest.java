package io.quarkiverse.qhorus.service;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import io.quarkiverse.qhorus.api.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.channel.ChannelService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestTransaction
class ChannelServiceTest extends ChannelServiceContractTest {

    @Inject
    ChannelService svc;

    @Override
    protected Channel create(String name, String desc, ChannelSemantic sem) {
        return svc.create(name, desc, sem, null, null, null, null, null);
    }

    @Override
    protected Optional<Channel> findByName(String name) {
        return svc.findByName(name);
    }

    @Override
    protected List<Channel> listAll() {
        return svc.listAll();
    }

    @Override
    protected Channel pause(String name) {
        return svc.pause(name);
    }

    @Override
    protected Channel resume(String name) {
        return svc.resume(name);
    }
}
