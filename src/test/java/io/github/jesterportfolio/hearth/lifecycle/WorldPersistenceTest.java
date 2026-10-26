package io.github.jesterportfolio.hearth.lifecycle;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class WorldPersistenceTest {
    static final class Boundary implements WorldPersistence.Boundary {
        final List<String> calls=new ArrayList<>(); String fail; CompletableFuture<Void> blocked; boolean unloaded=true,cleared;
        CompletionStage<Void> step(String name) {
            calls.add(name); if (name.equals(fail)) return CompletableFuture.failedFuture(new LifecycleFailure(name));
            if (name.equals("inventory") && blocked!=null) return blocked;
            return CompletableFuture.completedFuture(null);
        }
        public CompletionStage<Void> verifyStoredMapping() { return step("mapping"); }
        public CompletionStage<Void> loadWorld() { return step("load"); }
        public CompletionStage<Void> restoreRegions() { return step("regions_load"); }
        public CompletionStage<Void> verifyProgressAndInventory() { return step("data_load"); }
        public CompletionStage<Void> drainInventory() { return step("inventory"); }
        public CompletionStage<Void> verifyProgress() { return step("progress"); }
        public CompletionStage<Void> saveRegions() { return step("regions_save"); }
        public CompletionStage<Void> saveWorld() { return step("world_save"); }
        public CompletionStage<Void> checkUnloadBlockers() { return step("blockers"); }
        public CompletionStage<Boolean> unloadWorld() { calls.add("unload"); return CompletableFuture.completedFuture(unloaded); }
        public void clearWorldReferences() { cleared=true; calls.add("clear_refs"); }
    }
    static final class Fixture {
        final Boundary b=new Boundary();final ArrayDeque<Runnable> queue=new ArrayDeque<>();final AtomicLong epoch=new AtomicLong();
        final WorldPersistence ops=new WorldPersistence(b,queue::add,() -> {},epoch::get);
        void pump() { while (!queue.isEmpty()) queue.remove().run(); }
    }
    @Test void exactOrderAndBlockedDirtyPreventAnySaveOrUnload() {
        var f=new Fixture(); f.b.blocked=new CompletableFuture<>();
        var work=f.ops.loadAndPrepare().thenCompose(v -> f.ops.quiesce()).thenCompose(v -> f.ops.save()).thenCompose(v -> f.ops.unload());f.pump();
        assertEquals(List.of("mapping","load","regions_load","data_load","inventory"),f.b.calls);assertFalse(work.toCompletableFuture().isDone());
        f.b.blocked.complete(null);f.pump();assertTrue(work.toCompletableFuture().getNow(false));
        assertEquals(List.of("mapping","load","regions_load","data_load","inventory","progress","regions_save","world_save","blockers","unload","clear_refs"),f.b.calls);
    }
    @Test void everyFailureStopsLaterStagesAndFalseUnloadRetainsReferences() {
        for (String failure:List.of("mapping","load","regions_load","data_load","inventory","progress","regions_save","world_save","blockers")) {
            var f=new Fixture();f.b.fail=failure;
            var work=f.ops.loadAndPrepare().thenCompose(v -> f.ops.quiesce()).thenCompose(v -> f.ops.save()).thenCompose(v -> f.ops.unload());f.pump();
            assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertEquals(failure,f.b.calls.getLast());assertFalse(f.b.cleared);
        }
        var f=new Fixture();f.b.unloaded=false;var work=f.ops.unload();f.pump();assertFalse(work.toCompletableFuture().getNow(true));assertFalse(f.b.cleared);
    }
    @Test void staleCompletionCannotStartFollowingWorldOperation() {
        var g=new Fixture();var save=g.ops.save();g.epoch.incrementAndGet();g.pump();
        assertTrue(save.toCompletableFuture().isCompletedExceptionally());assertEquals(List.of("progress"),g.b.calls);
    }
}
