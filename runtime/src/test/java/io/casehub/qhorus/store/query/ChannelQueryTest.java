package io.casehub.qhorus.store.query;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.store.query.ChannelQuery;

class ChannelQueryTest {

    @Test
    void all_matchesAnyChannel() {
        Channel ch = new Channel();
        ch.paused = false;
        assertTrue(ChannelQuery.all().matches(ch));
    }

    @Test
    void all_matchesPausedChannel() {
        Channel ch = new Channel();
        ch.paused = true;
        assertTrue(ChannelQuery.all().matches(ch));
    }

    @Test
    void pausedOnly_matchesPausedChannel() {
        Channel ch = new Channel();
        ch.paused = true;
        assertTrue(ChannelQuery.pausedOnly().matches(ch));
    }

    @Test
    void pausedOnly_doesNotMatchActiveChannel() {
        Channel ch = new Channel();
        ch.paused = false;
        assertFalse(ChannelQuery.pausedOnly().matches(ch));
    }

    @Test
    void bySemantic_matchesCorrectSemantic() {
        Channel ch = new Channel();
        ch.semantic = ChannelSemantic.APPEND;
        assertTrue(ChannelQuery.bySemantic(ChannelSemantic.APPEND).matches(ch));
        assertFalse(ChannelQuery.bySemantic(ChannelSemantic.COLLECT).matches(ch));
    }

    @Test
    void byName_matchesGlobPattern() {
        Channel ch = new Channel();
        ch.name = "agent-events";
        assertTrue(ChannelQuery.byName("agent-*").matches(ch));
        assertFalse(ChannelQuery.byName("task-*").matches(ch));
    }

    @Test
    void byName_doesNotMatchNullName() {
        Channel ch = new Channel();
        ch.name = null;
        assertFalse(ChannelQuery.byName("agent-*").matches(ch));
    }

    @Test
    void builder_combinesPredicates() {
        Channel ch = new Channel();
        ch.paused = true;
        ch.semantic = ChannelSemantic.BARRIER;

        ChannelQuery match = ChannelQuery.builder().paused(true).semantic(ChannelSemantic.BARRIER).build();
        assertTrue(match.matches(ch));

        ChannelQuery noMatch = ChannelQuery.builder().paused(true).semantic(ChannelSemantic.APPEND).build();
        assertFalse(noMatch.matches(ch));
    }

    @Test
    void toBuilder_roundTrips() {
        ChannelQuery original = ChannelQuery.builder().paused(false).semantic(ChannelSemantic.COLLECT).build();
        ChannelQuery copy = original.toBuilder().build();

        Channel ch = new Channel();
        ch.paused = false;
        ch.semantic = ChannelSemantic.COLLECT;

        assertTrue(original.matches(ch));
        assertTrue(copy.matches(ch));
    }
}
