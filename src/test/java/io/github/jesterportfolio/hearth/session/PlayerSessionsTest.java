package io.github.jesterportfolio.hearth.session;

import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.lifecycle.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PlayerSessionsTest {
    static InventoryPayload payload(int n) { byte[][] slots=new byte[41][];slots[0]=new byte[]{(byte)n};return InventoryPayload.pack(slots); }
    static class Store implements InventoryStore,ProgressStore {
        final List<CompletableFuture<Stored>> reads=new ArrayList<>(),writes=new ArrayList<>();
        final List<InventoryPayload> snapshots=new ArrayList<>();
        public CompletionStage<Stored> readInventory(ServerProgressAccess.Context c) { var f=new CompletableFuture<Stored>();reads.add(f);return f; }
        public CompletionStage<Stored> createInventory(ServerProgressAccess.Context c,InventoryPayload p) { throw new AssertionError("No creation"); }
        public CompletionStage<Stored> saveInventory(ServerProgressAccess.Context c,long r,InventoryPayload p) { var f=new CompletableFuture<Stored>();writes.add(f);snapshots.add(p);return f; }
        public CompletionStage<State> readPersonal(ServerProgressAccess.Context c) { return CompletableFuture.completedFuture(new State("p",0)); }
        public CompletionStage<State> readShared(ServerProgressAccess.Context c){return CompletableFuture.completedFuture(new State("r",0));}
        public CompletionStage<Void> migrate(){throw new AssertionError();} public CompletionStage<Void> createRun(UUID id,String v){throw new AssertionError();}
        public CompletionStage<Void> createPlayer(ServerProgressAccess.Context c,String v){throw new AssertionError();}
        public CompletionStage<State> saveShared(ServerProgressAccess.Context c,long r,String v){throw new AssertionError();}
        public CompletionStage<State> savePersonal(ServerProgressAccess.Context c,long r,String v){throw new AssertionError();}
        public CompletionStage<Void> closeAsync(){return CompletableFuture.completedFuture(null);}
    }
    static class Port implements PlayerSessions.Port {
        boolean online=true;int applies,moves;InventoryPayload memory=payload(9);CompletableFuture<Boolean> movement=CompletableFuture.completedFuture(true);
        public boolean connected(){return online;} public InventoryPayload capture(){return memory;}
        public void apply(InventoryPayload p){applies++;memory=p;} public CompletionStage<Boolean> move(){moves++;return movement;}
    }
    static class Fixture {
        final ArrayDeque<Runnable> queue=new ArrayDeque<>();final Store store=new Store();final UUID run=UUID.randomUUID();
        final ServerProgressAccess access=new ServerProgressAccess();final Set<InventorySaveSession> autosave=new HashSet<>();
        final CompletableFuture<Void> loaded=new CompletableFuture<>();final List<Runnable> timers=new ArrayList<>();
        final WorldLifecycle world=new WorldLifecycle(new WorldLifecycle.Mapping(run,UUID.randomUUID(),"hearth_verify:unit",Path.of("/tmp/unit")),new WorldLifecycle.Operations(){
            public CompletionStage<Void> loadAndPrepare(){return loaded;}public CompletionStage<Void> quiesce(){return sessions.barrier(run);}
            public CompletionStage<Void> save(){return CompletableFuture.completedFuture(null);}public CompletionStage<Boolean> unload(){return CompletableFuture.completedFuture(true);}
        },queue::add,() -> {},(d,r) -> {timers.add(r);return () -> {};},Duration.ofSeconds(1),16,s -> {});
        final PlayerSessions sessions=new PlayerSessions(store,store,queue::add,() -> {},p -> {if(p.equals(payload(99)))throw new IllegalArgumentException("bad payload");},autosave::add,autosave::remove,c -> {});
        ServerProgressAccess.Context context(UUID player){return access.forVerifiedServerLink(run,player);}
        void pump(){int n=200;while(!queue.isEmpty()){assertTrue(n-->0);queue.remove().run();}}
        PlayerSessions.Session open(UUID id,Port p){var s=sessions.connect(context(id),world,p);pump();return s;}
        void read(int i,int count,long rev){store.reads.get(i).complete(new InventoryStore.Stored(payload(count),rev));pump();}
        PlayerSessions.Session active(UUID id,Port p){var s=open(id,p);loaded.complete(null);pump();read(store.reads.size()-1,1,0);assertEquals(PlayerSessions.State.ACTIVE,s.state());return s;}
        void saved(int i,long rev){store.writes.get(i).complete(new InventoryStore.Stored(store.snapshots.get(i),rev));pump();}
    }
    @Test void noApplicationBeforeWorldAndInventoryAndMovementComplete() {
        var f=new Fixture();var p=new Port();p.movement=new CompletableFuture<>();var s=f.open(UUID.randomUUID(),p);
        assertEquals(0,p.applies);f.loaded.complete(null);f.pump();assertEquals(0,p.applies);
        f.read(0,1,0);assertEquals(1,p.applies);assertFalse(s.ready().toCompletableFuture().isDone());assertEquals(0,f.world.participantCount());
        assertFalse(f.sessions.playable(s));p.movement.complete(true);f.pump();assertTrue(f.sessions.playable(s));assertEquals(1,f.world.participantCount());
    }
    @Test void reconnectWaitsForOldFlightAndFinalSnapshotAndOldQuitIsIdempotent() {
        var f=new Fixture();var id=UUID.randomUUID();var p=new Port();var old=f.active(id,p);
        p.memory=payload(2);f.sessions.refresh(old);old.save().flush();p.memory=payload(3);
        var closing=f.sessions.disconnect(old);assertSame(closing,f.sessions.disconnect(old));p.online=false;
        var nextPort=new Port();var next=f.open(id,nextPort);assertEquals(1,f.store.reads.size());assertEquals(0,nextPort.applies);
        f.saved(0,1);assertEquals(payload(3),f.store.snapshots.get(1));assertFalse(closing.toCompletableFuture().isDone());
        f.saved(1,2);assertEquals(2,f.store.reads.size());f.read(1,3,2);
        assertEquals(PlayerSessions.State.ACTIVE,next.state());assertEquals(1,nextPort.applies);assertEquals(payload(3),nextPort.memory);
        f.sessions.disconnect(old);assertEquals(1,f.world.participantCount());
    }
    @Test void quitDuringPreparationRejectsOldRestoreAndAllowsFreshConnection() {
        var f=new Fixture();var id=UUID.randomUUID();var p=new Port();var old=f.open(id,p);
        f.loaded.complete(null);f.pump();var closing=f.sessions.disconnect(old);p.online=false;f.pump();
        var n=new Port();var next=f.open(id,n);assertEquals(1,f.store.reads.size());f.read(0,1,0);
        assertTrue(closing.toCompletableFuture().isDone());assertEquals(0,p.applies);assertEquals(2,f.store.reads.size());
        f.read(1,2,0);assertEquals(PlayerSessions.State.ACTIVE,next.state());assertEquals(1,n.applies);
    }
    @Test void quitDuringMoveKeepsReservationUntilMoveSettlesAndNeverCommitsStaleClient() {
        var f=new Fixture();var id=UUID.randomUUID();var p=new Port();p.movement=new CompletableFuture<>();var s=f.open(id,p);f.loaded.complete(null);f.pump();f.read(0,1,0);
        var closed=f.sessions.disconnect(s);p.online=false;f.pump();assertFalse(closed.toCompletableFuture().isDone());assertEquals(1,f.world.pendingCount());
        p.movement.complete(true);f.pump();assertEquals(0,f.world.participantCount());assertEquals(0,f.world.pendingCount());assertTrue(closed.toCompletableFuture().isDone());
    }
    @Test void moveFailureOrBadPayloadHoldsWithoutWritingOrEmptyReplacement() {
        for(boolean corrupt:List.of(false,true)) {
            var f=new Fixture();var p=new Port();p.movement=CompletableFuture.completedFuture(false);var s=f.open(UUID.randomUUID(),p);f.loaded.complete(null);f.pump();f.read(0,corrupt?99:1,0);
            assertEquals(PlayerSessions.State.HOLD,s.state());assertEquals(0,f.world.participantCount());assertEquals(0,f.world.pendingCount());assertTrue(f.store.writes.isEmpty());
            assertFalse(f.sessions.clear(f.run));assertTrue(s.ready().toCompletableFuture().isCompletedExceptionally());
        }
    }
    @Test void failedSaveKeepsDirtyRevisionHoldAndBlocksReconnect() {
        var f=new Fixture();var id=UUID.randomUUID();var p=new Port();var s=f.active(id,p);p.memory=payload(2);var close=f.sessions.disconnect(s);f.pump();
        f.store.writes.getFirst().completeExceptionally(new StorageFailure("unit","23514"));f.pump();
        assertEquals(PlayerSessions.State.HOLD,s.state());assertTrue(s.save().dirty());assertEquals(0,s.save().revision());assertTrue(close.toCompletableFuture().isCompletedExceptionally());
        var next=f.open(id,new Port());assertTrue(next.ready().toCompletableFuture().isCompletedExceptionally());assertEquals(1,f.store.reads.size());assertFalse(f.sessions.clear(f.run));
    }
    @Test void barrierIncludesAllDepartingSessionsAndRefusesActiveOnes() {
        var f=new Fixture();var p1=new Port();var one=f.active(UUID.randomUUID(),p1);var p2=new Port();var two=f.active(UUID.randomUUID(),p2);
        assertTrue(f.sessions.barrier(f.run).toCompletableFuture().isCompletedExceptionally());
        p1.memory=payload(2);p2.memory=payload(3);f.sessions.disconnect(one);f.sessions.disconnect(two);f.pump();var barrier=f.sessions.barrier(f.run);
        assertFalse(barrier.toCompletableFuture().isDone());f.saved(0,1);assertFalse(barrier.toCompletableFuture().isDone());f.saved(1,1);
        assertTrue(barrier.toCompletableFuture().isDone());assertTrue(f.sessions.clear(f.run));assertTrue(f.autosave.isEmpty());
    }
    @Test void invalidatedWorldGenerationRejectsPendingInventoryBeforeApply() {
        var f=new Fixture();var port=new Port();var session=f.open(UUID.randomUUID(),port);f.loaded.complete(null);f.pump();
        f.world.invalidate("injected_world_epoch");f.read(0,1,0);
        assertEquals(0,port.applies);assertEquals(0,port.moves);assertEquals(PlayerSessions.State.HOLD,session.state());
        assertEquals("STALE_WORLD",session.error());assertEquals(WorldLifecycle.State.RECOVERY_REQUIRED,f.world.state());assertTrue(f.store.writes.isEmpty());
    }
    @Test void equivalentSerializedFormsDoNotBecomeDirty() {
        var f=new Fixture();var coordinator=new PlayerSessions(f.store,f.store,f.queue::add,() -> {},p -> {},
            (x,y)->x.slots()[0][0]%10==y.slots()[0][0]%10,s -> {},s -> {},c -> {});
        var port=new Port();var session=coordinator.connect(f.context(UUID.randomUUID()),f.world,port);f.pump();f.loaded.complete(null);f.pump();f.read(0,1,0);
        port.memory=payload(11);coordinator.refresh(session);assertFalse(session.save().dirty());assertTrue(f.store.writes.isEmpty());
        port.memory=payload(2);coordinator.refresh(session);assertTrue(session.save().dirty());
    }
    @Test void waitingReconnectCanDisconnectWithoutReplacingPriorLease() {
        var f=new Fixture();var id=UUID.randomUUID();var p=new Port();var first=f.active(id,p);p.memory=payload(2);f.sessions.disconnect(first);f.pump();
        var next=f.open(id,new Port());f.sessions.disconnect(next);f.pump();f.saved(0,1);
        assertEquals(1,f.store.reads.size());assertTrue(next.closed().toCompletableFuture().isDone());assertEquals(0,f.world.participantCount());
    }
}
