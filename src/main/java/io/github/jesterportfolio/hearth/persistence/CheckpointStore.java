package io.github.jesterportfolio.hearth.persistence;
import java.util.*;
import java.util.concurrent.*;
public interface CheckpointStore {
    record Saved(UUID request,boolean complete){}
    CompletionStage<Optional<Saved>> checkpoint(ServerProgressAccess.Context ctx,String dungeon,String quest,String checkpoint);
    CompletionStage<Void> checkpointWrite(ServerProgressAccess.Context ctx,String dungeon,String quest,String checkpoint,UUID request,boolean complete);
}
