package io.casehub.qhorus.runtime.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.runtime.instance.Instance;
import io.casehub.qhorus.runtime.store.query.InstanceQuery;
import io.smallrye.mutiny.Uni;

public interface ReactiveInstanceStore {
    Uni<Instance> put(Instance instance);

    Uni<Optional<Instance>> find(UUID id);

    Uni<Optional<Instance>> findByInstanceId(String instanceId);

    Uni<List<Instance>> scan(InstanceQuery query);

    Uni<Void> putCapabilities(UUID instanceId, List<String> tags);

    Uni<Void> deleteCapabilities(UUID instanceId);

    Uni<List<String>> findCapabilities(UUID instanceId);

    Uni<Void> delete(UUID id);
}
