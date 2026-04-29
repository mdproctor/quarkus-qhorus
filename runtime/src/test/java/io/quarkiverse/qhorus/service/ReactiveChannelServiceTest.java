package io.quarkiverse.qhorus.service;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;

import io.quarkiverse.qhorus.api.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.channel.ReactiveChannelService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@Disabled("ReactiveChannelService calls Panache.withTransaction() — requires reactive datasource. "
        + "H2 has no reactive driver. Enable when Docker/PostgreSQL Dev Services is available.")
@QuarkusTest
@TestProfile(ReactiveTestProfile.class)
class ReactiveChannelServiceTest extends ChannelServiceContractTest {

    @Inject
    ReactiveChannelService svc;

    @Override
    protected Channel create(String name, String desc, ChannelSemantic sem) {
        return svc.create(name, desc, sem, null, null, null, null, null).await().indefinitely();
    }

    @Override
    protected Optional<Channel> findByName(String name) {
        return svc.findByName(name).await().indefinitely();
    }

    @Override
    protected List<Channel> listAll() {
        return svc.listAll().await().indefinitely();
    }

    @Override
    protected Channel pause(String name) {
        return svc.pause(name).await().indefinitely();
    }

    @Override
    protected Channel resume(String name) {
        return svc.resume(name).await().indefinitely();
    }
}
