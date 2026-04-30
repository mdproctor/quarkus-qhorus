package io.casehub.qhorus.runtime.data;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.casehub.qhorus.runtime.store.ReactiveDataStore;
import io.casehub.qhorus.runtime.store.query.DataQuery;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

@Alternative
@ApplicationScoped
public class ReactiveDataService {

    @Inject
    ReactiveDataStore dataStore;

    /**
     * Store or update a shared data artefact.
     *
     * @param key human-readable key (unique)
     * @param description optional description (ignored on append chunks)
     * @param createdBy owner instance ID
     * @param content content to store or append
     * @param append if true, append to existing content; if false, create/overwrite
     * @param lastChunk if true, mark the artefact as complete
     */
    public Uni<SharedData> store(String key, String description, String createdBy,
            String content, boolean append, boolean lastChunk) {
        return Panache.withTransaction(() -> dataStore.findByKey(key).flatMap(existing -> {
            SharedData data;
            if (existing.isEmpty() || !append) {
                data = existing.orElse(new SharedData());
                if (data.key == null) {
                    data.key = key;
                    data.createdBy = createdBy;
                }
                if (description != null) {
                    data.description = description;
                }
                data.content = content;
            } else {
                data = existing.get();
                data.content = (data.content != null ? data.content : "") + content;
            }
            data.complete = lastChunk;
            data.sizeBytes = data.content != null ? data.content.length() : 0;
            return dataStore.put(data);
        }));
    }

    public Uni<Optional<SharedData>> getByKey(String key) {
        return dataStore.findByKey(key);
    }

    public Uni<Optional<SharedData>> getByUuid(UUID id) {
        return dataStore.find(id);
    }

    public Uni<List<SharedData>> listAll() {
        return dataStore.scan(DataQuery.all());
    }

    /**
     * Declare this instance holds a reference to an artefact. Idempotent — no duplicate
     * claims are created if called multiple times with the same (artefactId, instanceId) pair.
     */
    public Uni<Void> claim(UUID artefactId, UUID instanceId) {
        return Panache.withTransaction(() -> dataStore.hasClaim(artefactId, instanceId).flatMap(exists -> {
            if (exists) {
                return Uni.createFrom().voidItem();
            }
            ArtefactClaim claim = new ArtefactClaim();
            claim.artefactId = artefactId;
            claim.instanceId = instanceId;
            return dataStore.putClaim(claim).replaceWithVoid();
        }));
    }

    public Uni<Void> release(UUID artefactId, UUID instanceId) {
        return Panache.withTransaction(() -> dataStore.deleteClaim(artefactId, instanceId));
    }

    /**
     * An artefact is GC-eligible when it is complete AND has no active claims.
     */
    public Uni<Boolean> isGcEligible(UUID artefactId) {
        return dataStore.find(artefactId)
                .flatMap(opt -> {
                    if (opt.isEmpty() || !opt.get().complete) {
                        return Uni.createFrom().item(false);
                    }
                    return dataStore.countClaims(artefactId).map(count -> count == 0);
                });
    }
}
