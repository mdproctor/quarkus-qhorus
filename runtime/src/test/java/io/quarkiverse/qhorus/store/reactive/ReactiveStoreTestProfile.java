package io.quarkiverse.qhorus.store.reactive;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

public class ReactiveStoreTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.datasource.reactive", "true",
                "quarkus.arc.selected-alternatives",
                String.join(",",
                        "io.quarkiverse.qhorus.runtime.store.jpa.ChannelReactivePanacheRepo",
                        "io.quarkiverse.qhorus.runtime.store.jpa.ReactiveJpaChannelStore",
                        "io.quarkiverse.qhorus.runtime.store.jpa.MessageReactivePanacheRepo",
                        "io.quarkiverse.qhorus.runtime.store.jpa.ReactiveJpaMessageStore",
                        "io.quarkiverse.qhorus.runtime.store.jpa.InstanceReactivePanacheRepo",
                        "io.quarkiverse.qhorus.runtime.store.jpa.CapabilityReactivePanacheRepo",
                        "io.quarkiverse.qhorus.runtime.store.jpa.ReactiveJpaInstanceStore",
                        "io.quarkiverse.qhorus.runtime.store.jpa.SharedDataReactivePanacheRepo",
                        "io.quarkiverse.qhorus.runtime.store.jpa.ArtefactClaimReactivePanacheRepo",
                        "io.quarkiverse.qhorus.runtime.store.jpa.ReactiveJpaDataStore",
                        "io.quarkiverse.qhorus.runtime.store.jpa.WatchdogReactivePanacheRepo",
                        "io.quarkiverse.qhorus.runtime.store.jpa.ReactiveJpaWatchdogStore"));
    }
}
