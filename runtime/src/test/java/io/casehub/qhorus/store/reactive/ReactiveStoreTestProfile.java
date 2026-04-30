package io.casehub.qhorus.store.reactive;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

public class ReactiveStoreTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.datasource.reactive", "true",
                "quarkus.datasource.reactive.url", "h2:mem:qhorus-reactive-test",
                "quarkus.hibernate-reactive.database.generation", "drop-and-create",
                "quarkus.arc.selected-alternatives",
                String.join(",",
                        "io.casehub.qhorus.runtime.store.jpa.ChannelReactivePanacheRepo",
                        "io.casehub.qhorus.runtime.store.jpa.ReactiveJpaChannelStore",
                        "io.casehub.qhorus.runtime.store.jpa.MessageReactivePanacheRepo",
                        "io.casehub.qhorus.runtime.store.jpa.ReactiveJpaMessageStore",
                        "io.casehub.qhorus.runtime.store.jpa.InstanceReactivePanacheRepo",
                        "io.casehub.qhorus.runtime.store.jpa.CapabilityReactivePanacheRepo",
                        "io.casehub.qhorus.runtime.store.jpa.ReactiveJpaInstanceStore",
                        "io.casehub.qhorus.runtime.store.jpa.SharedDataReactivePanacheRepo",
                        "io.casehub.qhorus.runtime.store.jpa.ArtefactClaimReactivePanacheRepo",
                        "io.casehub.qhorus.runtime.store.jpa.ReactiveJpaDataStore",
                        "io.casehub.qhorus.runtime.store.jpa.WatchdogReactivePanacheRepo",
                        "io.casehub.qhorus.runtime.store.jpa.ReactiveJpaWatchdogStore"));
    }
}
