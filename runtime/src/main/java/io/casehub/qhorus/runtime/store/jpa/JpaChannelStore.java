package io.casehub.qhorus.runtime.store.jpa;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.store.ChannelStore;
import io.casehub.qhorus.runtime.store.query.ChannelQuery;

@ApplicationScoped
public class JpaChannelStore implements ChannelStore {

    @Override
    @Transactional
    public Channel put(Channel channel) {
        channel.persistAndFlush();
        return channel;
    }

    @Override
    public Optional<Channel> find(UUID id) {
        return Channel.findByIdOptional(id);
    }

    @Override
    public Optional<Channel> findByName(String name) {
        return Channel.find("name", name).firstResultOptional();
    }

    @Override
    public List<Channel> scan(ChannelQuery q) {
        StringBuilder jpql = new StringBuilder("FROM Channel WHERE 1=1");
        List<Object> params = new ArrayList<>();
        int idx = 1;

        if (q.paused() != null) {
            jpql.append(" AND paused = ?").append(idx++);
            params.add(q.paused());
        }
        if (q.semantic() != null) {
            jpql.append(" AND semantic = ?").append(idx++);
            params.add(q.semantic());
        }
        if (q.namePattern() != null) {
            jpql.append(" AND name LIKE ?").append(idx++);
            params.add(q.namePattern().replace("*", "%"));
        }

        return Channel.list(jpql.toString(), params.toArray());
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        Channel.deleteById(id);
    }
}
