package io.casehub.qhorus;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.channel.ReactiveChannelService;
import io.casehub.qhorus.runtime.data.ReactiveDataService;
import io.casehub.qhorus.runtime.instance.ReactiveInstanceService;
import io.casehub.qhorus.runtime.message.ReactiveMessageService;
import io.casehub.qhorus.service.ReactiveTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Reactive stack smoke test — validates full cross-domain workflow via reactive services.
 *
 * <p>
 * DISABLED: requires a native reactive datasource driver. H2 has no async driver;
 * only {@code quarkus-reactive-pg-client} with Docker/Dev Services enables this.
 * When Docker is available: remove {@code @Disabled}, add PostgreSQL Dev Services
 * to {@link ReactiveTestProfile}, and run.
 */
@Disabled("Requires reactive datasource (PostgreSQL + Docker). H2 has no reactive driver.")
@QuarkusTest
@TestProfile(ReactiveTestProfile.class)
class ReactiveSmokeTest {

    @Inject
    ReactiveChannelService channelService;

    @Inject
    ReactiveInstanceService instanceService;

    @Inject
    ReactiveMessageService messageService;

    @Inject
    ReactiveDataService dataService;

    @Test
    void reactiveServicesAreInjectable() {
        assertNotNull(channelService);
        assertNotNull(instanceService);
        assertNotNull(messageService);
        assertNotNull(dataService);
    }

    @Test
    void fullReactiveMeshWorkflow() {
        // Full cross-domain workflow — enable when reactive datasource is available.
        // Mirror of SmokeTest.fullMeshWorkflow() using reactive service chains.
        // See SmokeTest for the blocking equivalent.
        //
        // Implementation steps (when enabled):
        // 1. Register two agents via instanceService.register(...).await()
        // 2. Create a channel via channelService.create(...).await()
        // 3. Share an artefact via dataService.store(...).await()
        // 4. Alice sends a request via messageService.send(...).await()
        // 5. Bob polls via messageService.pollAfter(...).await()
        // 6. Bob sends a response and verify replyCount incremented
        // 7. Claim + release artefact; verify GC eligibility via dataService
        // 8. Verify channel.lastActivityAt advanced after messaging
    }
}
