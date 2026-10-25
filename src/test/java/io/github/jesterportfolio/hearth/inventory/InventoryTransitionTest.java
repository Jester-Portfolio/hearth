package io.github.jesterportfolio.hearth.inventory;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class InventoryTransitionTest {
    private static InventoryPayload payload(int n) { byte[][] slots=new byte[41][]; slots[0]=new byte[]{(byte)n}; return InventoryPayload.pack(slots); }
    private static final class Fake implements InventoryStore {
        final List<CompletableFuture<Stored>> writes=new ArrayList<>(), reads=new ArrayList<>();
        final List<InventoryPayload> snapshots=new ArrayList<>();
        public CompletionStage<Stored> createInventory(ServerProgressAccess.Context c,InventoryPayload p) { throw new UnsupportedOperationException(); }
        public CompletionStage<Stored> readInventory(ServerProgressAccess.Context c) { var f=new CompletableFuture<Stored>(); reads.add(f); return f; }
        public CompletionStage<Stored> saveInventory(ServerProgressAccess.Context c,long r,InventoryPayload p) { var f=new CompletableFuture<Stored>(); writes.add(f); snapshots.add(p); return f; }
    }
    private static final class Fixture {
        final Fake db=new Fake(); final ArrayDeque<Runnable> queue=new ArrayDeque<>(); final Thread owner=Thread.currentThread();
        final ServerProgressAccess access=new ServerProgressAccess();
        final UUID player=UUID.randomUUID(); final ServerProgressAccess.Context a=access.forVerifiedServerLink(UUID.randomUUID(),player), b=access.forVerifiedServerLink(UUID.randomUUID(),player);
        final AtomicReference<UUID> current=new AtomicReference<>(a.storyRunId()); final AtomicReference<InventoryPayload> memory=new AtomicReference<>(payload(1));
        final AtomicInteger applied=new AtomicInteger(); final AtomicBoolean failApply=new AtomicBoolean(), badTarget=new AtomicBoolean();
        final InventorySaveSession session; final InventoryTransition transition;
        Fixture() {
            session=make(a,new InventoryStore.Stored(memory.get(),0));
            transition=new InventoryTransition(db,session,queue::add,this::check,p -> { if (badTarget.get()) throw new IllegalArgumentException(); },
                (ctx,p) -> { if (failApply.get()) throw new IllegalStateException(); applied.incrementAndGet(); memory.set(p.payload()); current.set(ctx.storyRunId()); },this::make,c -> {});
        }
        void check() { assertSame(owner,Thread.currentThread()); }
        InventorySaveSession make(ServerProgressAccess.Context c,InventoryStore.Stored s) { return new InventorySaveSession(db,c,s.revision(),memory::get,queue::add,this::check,() -> c.storyRunId().equals(current.get()),code -> {}); }
        void pump() { int max=100; while (!queue.isEmpty()) { assertTrue(max-->0); queue.remove().run(); } }
        void saved(int i,long revision) { db.writes.get(i).complete(new InventoryStore.Stored(db.snapshots.get(i),revision)); pump(); }
        void target() { db.reads.getLast().complete(new InventoryStore.Stored(payload(2),7)); pump(); }
    }
    private static String failed(CompletionStage<?> f) { var e=assertThrows(CompletionException.class,() -> f.toCompletableFuture().join()); return ((StorageFailure)e.getCause()).code(); }
    @Test void waitsForOldFlightAndLatestSourceBeforeLoadingTarget() {
        var f=new Fixture(); f.session.markDirty(); f.session.flush(); f.memory.set(payload(3)); f.session.markDirty();
        var move=f.transition.move(f.b); assertEquals("BUSY",failed(f.transition.move(f.b)));
        assertThrows(StorageFailure.class,f.session::markDirty); f.pump(); assertTrue(f.db.reads.isEmpty());
        f.saved(0,1); assertEquals(2,f.db.writes.size()); assertTrue(f.db.reads.isEmpty());
        assertEquals(payload(3),f.db.snapshots.get(1)); f.saved(1,2); assertEquals(1,f.db.reads.size());
        assertFalse(move.toCompletableFuture().isDone()); f.target(); assertTrue(move.toCompletableFuture().isDone());
        assertEquals(1,f.applied.get()); assertEquals(f.b.storyRunId(),f.transition.active().context().storyRunId()); assertEquals(7,f.transition.active().revision());
    }
    @Test void sourceFailureOrRevisionConflictNeverLoadsTarget() {
        for (String code:List.of("23514","REVISION_CONFLICT_OR_MISSING")) {
            var f=new Fixture(); f.session.markDirty(); var move=f.transition.move(f.b); f.pump();
            f.db.writes.getFirst().completeExceptionally(new StorageFailure("test",code)); f.pump();
            assertEquals(code,failed(move)); assertTrue(f.db.reads.isEmpty()); assertTrue(f.session.dirty()); assertEquals(InventoryTransition.State.HOLD,f.transition.state());
        }
    }
    @Test void missingTargetNeverCreatesEmptyReplacement() {
        var f=new Fixture(); var move=f.transition.move(f.b); f.pump(); f.db.reads.getFirst().completeExceptionally(new StorageFailure("test","MISSING_INVENTORY")); f.pump();
        assertEquals("MISSING_INVENTORY",failed(move)); assertEquals(0,f.applied.get()); assertEquals(payload(1),f.memory.get());
    }
    @Test void corruptTargetAndApplicationFailureRemainHeld() {
        for (boolean invalid:List.of(true,false)) {
            var f=new Fixture(); f.badTarget.set(invalid); f.failApply.set(!invalid);
            var move=f.transition.move(f.b); f.pump(); f.target();
            assertEquals(invalid ? "TARGET_INVALID" : "APPLY_FAILED",failed(move));
            assertEquals(InventoryTransition.State.HOLD,f.transition.state()); assertEquals(0,f.applied.get()); assertSame(f.session,f.transition.active());
        }
    }
    @Test void previousGenerationNeverAppliesTarget() {
        var f=new Fixture(); var move=f.transition.move(f.b); f.pump(); f.transition.invalidate(); f.target();
        assertEquals("STALE_CONTEXT",failed(move)); assertEquals(0,f.applied.get());
    }
    @Test void logoutDrainsLatestAndRefusesNewRequests() {
        var f=new Fixture(); f.session.markDirty(); f.session.flush(); f.memory.set(payload(4)); f.session.markDirty();
        var close=f.transition.logout(); assertEquals("NOT_ACTIVE",failed(f.transition.move(f.b)));
        assertEquals("CLOSED",failed(f.session.flush())); assertThrows(StorageFailure.class,f.session::markDirty);
        f.saved(0,1); assertFalse(close.toCompletableFuture().isDone()); f.saved(1,2);
        assertTrue(close.toCompletableFuture().isDone()); assertFalse(f.session.dirty()); assertEquals(InventoryTransition.State.CLOSED,f.transition.state());
    }
}
