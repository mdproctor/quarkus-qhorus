package io.casehub.qhorus.store.reactive;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.store.ReactiveMessageStore;
import io.casehub.qhorus.runtime.store.query.MessageQuery;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;

// H2 has no native reactive driver; Quarkus reactive pool requires a native reactive client
// extension (pg, mysql, etc.) or Dev Services (Docker). Enable when a reactive datasource
// is available in the test environment.
@Disabled("Requires reactive datasource — H2 has no reactive driver; run with Dev Services/PostgreSQL")
@QuarkusTest
@TestProfile(ReactiveStoreTestProfile.class)
class ReactiveJpaMessageStoreTest {

    @Inject
    ReactiveMessageStore store;

    @Test
    @RunOnVertxContext
    void put_assignsIdAndReturns(UniAsserter asserter) {
        Message m = message(UUID.randomUUID(), "alice");
        asserter.assertThat(
                () -> Panache.withTransaction(() -> store.put(m)),
                saved -> assertNotNull(saved.id));
    }

    @Test
    @RunOnVertxContext
    void find_returnsEmpty_whenNotFound(UniAsserter asserter) {
        asserter.assertThat(
                () -> store.find(Long.MAX_VALUE),
                opt -> assertTrue(opt.isEmpty()));
    }

    @Test
    @RunOnVertxContext
    void scan_byChannel_returnsMatchingMessages(UniAsserter asserter) {
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        Message m1 = message(ch1, "alice");
        Message m2 = message(ch2, "bob");

        asserter
                .execute(() -> Panache.withTransaction(() -> store.put(m1)))
                .execute(() -> Panache.withTransaction(() -> store.put(m2)))
                .assertThat(
                        () -> store.scan(MessageQuery.forChannel(ch1)),
                        results -> {
                            assertEquals(1, results.size());
                            assertEquals("alice", results.get(0).sender);
                        });
    }

    @Test
    @RunOnVertxContext
    void countByChannel_returnsCorrectCount(UniAsserter asserter) {
        UUID ch = UUID.randomUUID();
        asserter
                .execute(() -> Panache.withTransaction(() -> store.put(message(ch, "x"))))
                .execute(() -> Panache.withTransaction(() -> store.put(message(ch, "y"))))
                .assertThat(
                        () -> store.countByChannel(ch),
                        count -> assertEquals(2, count));
    }

    @Test
    @RunOnVertxContext
    void deleteAll_removesAllMessagesForChannel(UniAsserter asserter) {
        UUID ch = UUID.randomUUID();
        asserter
                .execute(() -> Panache.withTransaction(() -> store.put(message(ch, "a"))))
                .execute(() -> Panache.withTransaction(() -> store.put(message(ch, "b"))))
                .execute(() -> store.deleteAll(ch))
                .assertThat(
                        () -> store.countByChannel(ch),
                        count -> assertEquals(0, count));
    }

    private Message message(UUID channelId, String sender) {
        Message m = new Message();
        m.channelId = channelId;
        m.sender = sender;
        m.messageType = MessageType.COMMAND;
        m.content = "hello";
        return m;
    }
}
