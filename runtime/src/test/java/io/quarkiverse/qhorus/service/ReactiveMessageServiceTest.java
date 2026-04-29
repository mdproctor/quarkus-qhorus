package io.quarkiverse.qhorus.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;

import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.message.ReactiveMessageService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@Disabled("ReactiveMessageService calls Panache.withTransaction() — requires reactive datasource.")
@QuarkusTest
@TestProfile(ReactiveTestProfile.class)
class ReactiveMessageServiceTest extends MessageServiceContractTest {

    @Inject
    ReactiveMessageService svc;

    @Override
    protected Message send(UUID channelId, String sender, MessageType type,
            String content, String correlationId, Long inReplyTo) {
        return svc.send(channelId, sender, type, content, correlationId, inReplyTo, null, null)
                .await().indefinitely();
    }

    @Override
    protected Optional<Message> findById(Long id) {
        return svc.findById(id).await().indefinitely();
    }

    @Override
    protected List<Message> pollAfter(UUID channelId, Long afterId, int limit) {
        return svc.pollAfter(channelId, afterId, limit).await().indefinitely();
    }
}
