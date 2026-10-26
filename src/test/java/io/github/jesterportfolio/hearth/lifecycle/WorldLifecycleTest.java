package io.github.jesterportfolio.hearth.lifecycle;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class WorldLifecycleTest {
    static final class Ops implements WorldLifecycle.Operations {
        final List<CompletableFuture<Void>> loads=new ArrayList<>(), quiet=new ArrayList<>(), saves=new ArrayList<>();
        final List<CompletableFuture<Boolean>> unloads=new ArrayList<>();
        public CompletionStage<Void> loadAndPrepare() { var f=new CompletableFuture<Void>(); loads.add(f); return f; }
        public CompletionStage<Void> quiesce() { var f=new CompletableFuture<Void>(); quiet.add(f); return f; }
        public CompletionStage<Void> save() { var f=new CompletableFuture<Void>(); saves.add(f); return f; }
        public CompletionStage<Boolean> unload() { var f=new CompletableFuture<Boolean>(); unloads.add(f); return f; }
    }
    static final class Fixture {
        final Ops ops=new Ops(); final ArrayDeque<Runnable> queue=new ArrayDeque<>(); final List<Runnable> timers=new ArrayList<>();
        final List<WorldLifecycle.State> events=new ArrayList<>(); final WorldLifecycle world;
        Fixture() {
            Thread owner=Thread.currentThread();
            world=new WorldLifecycle(new WorldLifecycle.Mapping(UUID.randomUUID(),UUID.randomUUID(),"hearth_verify:test",Path.of("/tmp/test-world")),ops,queue::add,
                () -> assertSame(owner,Thread.currentThread()),(duration,action) -> { timers.add(action); return () -> {}; },Duration.ofSeconds(2),2,events::add);
        }
        void pump() { int n=100; while (!queue.isEmpty()) { assertTrue(n-->0); queue.remove().run(); } }
        WorldLifecycle.Admission enter(UUID player) { var f=world.reserve(UUID.randomUUID(),player); if (!f.toCompletableFuture().isDone()) { ops.loads.getLast().complete(null); pump(); } var a=f.toCompletableFuture().getNow(null); assertTrue(world.commit(a)); return a; }
        void saved() { ops.quiet.getLast().complete(null); pump(); ops.saves.getLast().complete(null); pump(); ops.unloads.getLast().complete(true); pump(); }
    }
    private static String failed(CompletionStage<?> work) { return ((LifecycleFailure)assertThrows(CompletionException.class,() -> work.toCompletableFuture().join()).getCause()).code(); }
    @Test void concurrentLoadAndReservationDuplicateAreCoalesced() {
        var f=new Fixture(); UUID player=UUID.randomUUID(), request=UUID.randomUUID();
        var one=f.world.reserve(request,player); assertSame(one,f.world.reserve(request,player));
        var two=f.world.reserve(UUID.randomUUID(),UUID.randomUUID()); assertEquals(1,f.ops.loads.size());
        f.ops.loads.getFirst().complete(null); f.pump(); assertEquals(WorldLifecycle.State.READY,f.world.state());
        assertTrue(f.world.commit(one.toCompletableFuture().getNow(null))); assertTrue(f.world.commit(one.toCompletableFuture().getNow(null)));
        assertEquals(1,f.world.participantCount()); assertEquals(1,f.world.pendingCount()); assertEquals(1,f.ops.loads.size());
        assertTrue(f.world.commit(two.toCompletableFuture().getNow(null))); assertEquals(2,f.world.participantCount());
        f.world.reserve(UUID.randomUUID(),player); assertEquals(1,f.ops.loads.size());
    }
    @Test void participantsAndReservationsBothPreventIdle() {
        var f=new Fixture(); UUID one=UUID.randomUUID(),two=UUID.randomUUID();f.enter(one); f.enter(two);
        f.world.leave(one); assertEquals(WorldLifecycle.State.ACTIVE,f.world.state());
        UUID request=UUID.randomUUID();f.world.reserve(request,one); f.world.leave(two);
        assertNotEquals(WorldLifecycle.State.IDLE,f.world.state()); f.world.cancel(request); assertEquals(WorldLifecycle.State.IDLE,f.world.state());
    }
    @Test void idleReentryCancelsOldTimersAndOnlyLatestExpirySaves() {
        var f=new Fixture(); UUID p=UUID.randomUUID(); f.enter(p); f.world.leave(p); Runnable old=f.timers.getLast();
        var request=f.world.reserve(UUID.randomUUID(),p); old.run(); assertTrue(f.ops.quiet.isEmpty());
        assertTrue(f.world.commit(request.toCompletableFuture().getNow(null))); f.world.leave(p); old.run(); assertTrue(f.ops.quiet.isEmpty());
        f.timers.getLast().run(); f.timers.getLast().run(); assertEquals(1,f.ops.quiet.size()); assertEquals(WorldLifecycle.State.QUIESCING,f.world.state());
    }
    @Test void entryDuringSaveWaitsForUnloadAndFreshPreparation() {
        var f=new Fixture(); UUID p=UUID.randomUUID(); f.enter(p); f.world.leave(p); f.timers.getLast().run();
        f.ops.quiet.getFirst().complete(null); f.pump(); assertEquals(WorldLifecycle.State.SAVING,f.world.state());
        var pending=f.world.reserve(UUID.randomUUID(),p); assertFalse(pending.toCompletableFuture().isDone()); assertEquals(0,f.world.readyReservationCount());
        f.ops.saves.getFirst().complete(null);f.pump();assertEquals(WorldLifecycle.State.UNLOADING,f.world.state());
        f.ops.unloads.getFirst().complete(true);f.pump();assertEquals(2,f.ops.loads.size()); assertFalse(pending.toCompletableFuture().isDone());
        f.ops.loads.getLast().complete(null);f.pump();assertTrue(f.world.commit(pending.toCompletableFuture().getNow(null))); assertEquals(WorldLifecycle.State.ACTIVE,f.world.state());
    }
    @Test void loadFailureAndOneRunHoldDoNotAlterOtherRun() {
        var a=new Fixture(); var b=new Fixture(); b.enter(UUID.randomUUID());
        var request=a.world.reserve(UUID.randomUUID(),UUID.randomUUID()); a.ops.loads.getFirst().completeExceptionally(new LifecycleFailure("MISSING_WORLD"));a.pump();
        assertEquals("MISSING_WORLD",failed(request));assertEquals(WorldLifecycle.State.RECOVERY_REQUIRED,a.world.state()); assertEquals(WorldLifecycle.State.ACTIVE,b.world.state());
        assertEquals("HELD",failed(a.world.reserve(UUID.randomUUID(),UUID.randomUUID())));
    }
    @Test void staleLoadAndSaveCallbacksNeverClearRecoveryHold() {
        var f=new Fixture(); var request=f.world.reserve(UUID.randomUUID(),UUID.randomUUID());f.world.invalidate("audit");f.ops.loads.getFirst().complete(null);f.pump();
        assertEquals("STALE_GENERATION",failed(request));assertEquals(WorldLifecycle.State.RECOVERY_REQUIRED,f.world.state());
        var save=new Fixture();UUID p=UUID.randomUUID();save.enter(p);save.world.leave(p);save.timers.getLast().run();save.world.invalidate("audit");
        save.ops.quiet.getFirst().complete(null);save.pump();assertTrue(save.ops.saves.isEmpty());assertEquals(WorldLifecycle.State.RECOVERY_REQUIRED,save.world.state());
    }
    @Test void falseUnloadAndSaveFailureNeverClaimOffline() {
        var f=new Fixture();UUID p=UUID.randomUUID();f.enter(p);f.world.leave(p);f.timers.getLast().run();f.ops.quiet.getFirst().complete(null);f.pump();
        f.ops.saves.getFirst().completeExceptionally(new LifecycleFailure("DB_SAVE_FAILED"));f.pump(); assertTrue(f.ops.unloads.isEmpty()); assertEquals(WorldLifecycle.State.ERROR_HOLD,f.world.state());
        var g=new Fixture();g.enter(p);g.world.leave(p);g.timers.getLast().run();g.ops.quiet.getFirst().complete(null);g.pump();g.ops.saves.getFirst().complete(null);g.pump();g.ops.unloads.getFirst().complete(false);g.pump();
        assertEquals(WorldLifecycle.State.ERROR_HOLD,g.world.state());assertEquals("UNLOAD_FALSE",g.world.failureCode());
    }
    @Test void boundedReservationsAndRequestOwnership() {
        var f=new Fixture();UUID id=UUID.randomUUID();f.world.reserve(id,UUID.randomUUID());
        assertEquals("REQUEST_OWNER_MISMATCH",failed(f.world.reserve(id,UUID.randomUUID())));f.world.reserve(UUID.randomUUID(),UUID.randomUUID());
        assertEquals("RESERVATION_FULL",failed(f.world.reserve(UUID.randomUUID(),UUID.randomUUID())));assertEquals(1,f.ops.loads.size());
    }
}
