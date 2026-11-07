package io.github.jesterportfolio.hearth.gathering;
import java.util.*;
import java.util.concurrent.*;

public interface GatheringStore {
    enum Phase{OPEN,RESETTING,ERROR_HOLD}
    record State(UUID run,String dungeon,UUID world,long revision,long generation,String area,String template,String hash,Phase phase,UUID request,boolean emptyReset,long nextReset){public State{Objects.requireNonNull(run);Objects.requireNonNull(world);Objects.requireNonNull(phase);GatheringContent.key(dungeon);GatheringContent.key(template);if(revision<0||generation<0||nextReset<0||area==null||hash==null)throw new IllegalArgumentException("GATHERING_STATE");}}
    CompletionStage<State> read();CompletionStage<State> create(State state);CompletionStage<State> save(State expected,State next);
}
