package io.quarkiverse.qhorus.store.reactive;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.store.ReactiveChannelStore;
import io.quarkiverse.qhorus.runtime.store.query.ChannelQuery;
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
class ReactiveJpaChannelStoreTest {

    @Inject
    ReactiveChannelStore store;

    @Test
    @RunOnVertxContext
    void put_persistsChannelAndAssignsId(UniAsserter asserter) {
        Channel ch = new Channel();
        ch.name = "rx-put-" + UUID.randomUUID();
        ch.semantic = ChannelSemantic.APPEND;

        asserter.assertThat(
                () -> Panache.withTransaction(() -> store.put(ch)),
                saved -> {
                    assertNotNull(saved.id);
                    assertEquals(ChannelSemantic.APPEND, saved.semantic);
                });
    }

    @Test
    @RunOnVertxContext
    void find_returnsEmpty_whenNotFound(UniAsserter asserter) {
        asserter.assertThat(
                () -> store.find(UUID.randomUUID()),
                opt -> assertTrue(opt.isEmpty()));
    }

    @Test
    @RunOnVertxContext
    void findByName_returnsChannel_whenExists(UniAsserter asserter) {
        Channel ch = new Channel();
        ch.name = "rx-named-" + UUID.randomUUID();
        ch.semantic = ChannelSemantic.COLLECT;

        asserter
                .execute(() -> Panache.withTransaction(() -> store.put(ch)))
                .assertThat(
                        () -> store.findByName(ch.name),
                        opt -> {
                            assertTrue(opt.isPresent());
                            assertEquals(ChannelSemantic.COLLECT, opt.get().semantic);
                        });
    }

    @Test
    @RunOnVertxContext
    void scan_pausedOnly_returnsOnlyPaused(UniAsserter asserter) {
        Channel active = new Channel();
        active.name = "rx-active-" + UUID.randomUUID();
        active.semantic = ChannelSemantic.APPEND;
        active.paused = false;

        Channel paused = new Channel();
        paused.name = "rx-paused-" + UUID.randomUUID();
        paused.semantic = ChannelSemantic.APPEND;
        paused.paused = true;

        asserter
                .execute(() -> Panache.withTransaction(() -> store.put(active)))
                .execute(() -> Panache.withTransaction(() -> store.put(paused)))
                .assertThat(
                        () -> store.scan(ChannelQuery.pausedOnly()),
                        results -> {
                            assertTrue(results.stream().anyMatch(c -> c.name.equals(paused.name)));
                            assertTrue(results.stream().noneMatch(c -> c.name.equals(active.name)));
                        });
    }

    @Test
    @RunOnVertxContext
    void delete_removesChannel(UniAsserter asserter) {
        Channel ch = new Channel();
        ch.name = "rx-del-" + UUID.randomUUID();
        ch.semantic = ChannelSemantic.APPEND;

        asserter
                .execute(() -> Panache.withTransaction(() -> store.put(ch)))
                .execute(() -> store.delete(ch.id))
                .assertThat(
                        () -> store.find(ch.id),
                        opt -> assertTrue(opt.isEmpty()));
    }
}
