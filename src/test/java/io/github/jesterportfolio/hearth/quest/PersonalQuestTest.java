package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PersonalQuestTest {
    static class Store implements QuestStore {
        record Key(UUID run,UUID player){}
        final Map<Key,Saved> rows=new HashMap<>();
        final List<CompletableFuture<Saved>> pending=new ArrayList<>();
        final List<Runnable> commit=new ArrayList<>();int reads;
        Key key(ServerProgressAccess.Context c){return new Key(c.storyRunId(),c.playerId());}
        public CompletionStage<Saved> readQuest(ServerProgressAccess.Context c,String id){reads++;return CompletableFuture.completedFuture(rows.getOrDefault(key(c),new Saved(Phase.NOT_ACCEPTED,-1)));}
        public CompletionStage<Saved> advanceQuest(ServerProgressAccess.Context c,String id,Saved before,Phase next){
            var f=new CompletableFuture<Saved>();pending.add(f);commit.add(()->{
                if(!rows.getOrDefault(key(c),new Saved(Phase.NOT_ACCEPTED,-1)).equals(before)){f.completeExceptionally(new IllegalStateException("CAS"));return;}
                var row=new Saved(next,before.revision()+1);rows.put(key(c),row);f.complete(row);
            });return f;
        }
    }
    static class Fixture {
        final Store store=new Store();final ArrayDeque<Runnable> queue=new ArrayDeque<>();
        final PersonalQuest quests=new PersonalQuest(store,"survey_marker",queue::add,()->{});
        final ServerProgressAccess access=new ServerProgressAccess();final UUID a=UUID.randomUUID(),b=UUID.randomUUID(),p=UUID.randomUUID(),p2=UUID.randomUUID();
        boolean valid=true;
        ServerProgressAccess.Context ctx(UUID run,UUID player){return access.forVerifiedServerLink(run,player);}
        void pump(){int n=100;while(!queue.isEmpty()){assertTrue(n-->0);queue.remove().run();}}
        PersonalQuest.Session open(ServerProgressAccess.Context c,Object token){var f=quests.open(c,token,()->valid);pump();return f.toCompletableFuture().join();}
        void commit(int i){store.commit.get(i).run();pump();}
    }
    @Test void orderAndDuplicateReportWaitForConfirmedDB() {
        var f=new Fixture();var q=f.open(f.ctx(f.a,f.p),new Object());
        assertTrue(f.quests.advance(q,QuestStore.Phase.REPORT_READY).toCompletableFuture().isCompletedExceptionally());assertEquals(0,f.store.pending.size());
        var accept=f.quests.advance(q,QuestStore.Phase.ACTIVE);assertEquals(QuestStore.Phase.NOT_ACCEPTED,q.saved().phase());
        assertTrue(f.quests.advance(q,QuestStore.Phase.ACTIVE).toCompletableFuture().isCompletedExceptionally());f.commit(0);assertTrue(accept.toCompletableFuture().isDone());
        f.quests.advance(q,QuestStore.Phase.REPORT_READY);f.commit(1);
        var report=f.quests.advance(q,QuestStore.Phase.COMPLETED);
        assertEquals(QuestStore.Phase.REPORT_READY,q.saved().phase());assertTrue(f.quests.advance(q,QuestStore.Phase.COMPLETED).toCompletableFuture().isCompletedExceptionally());
        f.commit(2);assertEquals(2,report.toCompletableFuture().join().revision());
        assertTrue(f.quests.advance(q,QuestStore.Phase.COMPLETED).toCompletableFuture().isCompletedExceptionally());assertEquals(3,f.store.pending.size());
    }
    @Test void reconnectWaitsOldWriteAndLateResultCannotApply() {
        var f=new Fixture();var c=f.ctx(f.a,f.p);var old=f.open(c,new Object());
        var writing=f.quests.advance(old,QuestStore.Phase.ACTIVE);
        var next=f.quests.open(c,new Object(),()->true);f.pump();assertFalse(next.toCompletableFuture().isDone());assertEquals(1,f.store.reads);
        f.commit(0);assertTrue(writing.toCompletableFuture().isCompletedExceptionally());assertEquals(QuestStore.Phase.NOT_ACCEPTED,old.saved().phase());
        assertEquals(QuestStore.Phase.ACTIVE,next.toCompletableFuture().join().saved().phase());assertEquals(2,f.store.reads);
    }
    @Test void failedDBPreservesStateAndBlocksBarrierAndReconnect() {
        var f=new Fixture();var c=f.ctx(f.a,f.p);var q=f.open(c,new Object());var writing=f.quests.advance(q,QuestStore.Phase.ACTIVE);
        f.store.pending.getFirst().completeExceptionally(new StorageFailure("quest","23514"));f.pump();
        assertTrue(writing.toCompletableFuture().isCompletedExceptionally());assertTrue(q.held());assertEquals(QuestStore.Phase.NOT_ACCEPTED,q.saved().phase());
        assertTrue(f.quests.barrier(f.a).toCompletableFuture().isCompletedExceptionally());assertFalse(f.quests.settled(f.a));
        var next=f.quests.open(c,new Object(),()->true);f.pump();assertTrue(next.toCompletableFuture().isCompletedExceptionally());assertEquals(1,f.store.reads);assertTrue(f.store.rows.isEmpty());
    }
    @Test void failedCompletionRemainsReportReadyAndDuplicateReadUsesSameLoad() {
        var f=new Fixture();var c=f.ctx(f.a,f.p);var token=new Object();var q=f.open(c,token);
        assertSame(q,f.open(c,token));assertEquals(1,f.store.reads);
        f.quests.advance(q,QuestStore.Phase.ACTIVE);f.commit(0);
        f.quests.advance(q,QuestStore.Phase.REPORT_READY);f.commit(1);
        var report=f.quests.advance(q,QuestStore.Phase.COMPLETED);
        f.store.pending.get(2).completeExceptionally(new StorageFailure("quest","23514"));f.pump();
        assertTrue(report.toCompletableFuture().isCompletedExceptionally());assertTrue(q.held());
        assertEquals(new QuestStore.Saved(QuestStore.Phase.REPORT_READY,1),q.saved());
        assertEquals(q.saved(),f.store.rows.get(f.store.key(c)));
    }
    @Test void ownerIsolationAndAllWritesInBarrier() {
        var f=new Fixture();var a1=f.open(f.ctx(f.a,f.p),new Object());var a2=f.open(f.ctx(f.a,f.p2),new Object());var b1=f.open(f.ctx(f.b,f.p),new Object());
        f.quests.advance(a1,QuestStore.Phase.ACTIVE);f.quests.advance(a2,QuestStore.Phase.ACTIVE);var barrier=f.quests.barrier(f.a);
        f.commit(0);assertFalse(barrier.toCompletableFuture().isDone());assertEquals(QuestStore.Phase.NOT_ACCEPTED,a2.saved().phase());assertEquals(QuestStore.Phase.NOT_ACCEPTED,b1.saved().phase());
        f.commit(1);assertTrue(barrier.toCompletableFuture().isDone());assertTrue(f.quests.settled(f.a));f.quests.release(f.a);
    }
    @Test void invalidatedClientAndShutdownNeverApplyOldResult() {
        var f=new Fixture();var q=f.open(f.ctx(f.a,f.p),new Object());var writing=f.quests.advance(q,QuestStore.Phase.ACTIVE);f.valid=false;
        var closed=f.quests.shutdown();assertFalse(closed.toCompletableFuture().isDone());f.commit(0);
        assertTrue(writing.toCompletableFuture().isCompletedExceptionally());assertEquals(QuestStore.Phase.NOT_ACCEPTED,q.saved().phase());assertTrue(closed.toCompletableFuture().isDone());
        assertTrue(f.quests.open(f.ctx(f.a,f.p),new Object(),()->true).toCompletableFuture().isCompletedExceptionally());
    }
}
