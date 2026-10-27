package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PersonalQuestTest {
    static class Store implements QuestStore {
        record Key(UUID run,UUID player,String quest){}
        final Map<Key,Saved> rows=new HashMap<>();
        final List<CompletableFuture<Saved>> pending=new ArrayList<>();
        final List<Runnable> commit=new ArrayList<>();int reads;
        boolean deferReads;
        final List<CompletableFuture<Saved>> readResults=new ArrayList<>();final List<Saved> readSnapshots=new ArrayList<>();
        Key key(ServerProgressAccess.Context c){return key(c,"survey_marker");}
        Key key(ServerProgressAccess.Context c,String id){return new Key(c.storyRunId(),c.playerId(),id);}
        public CompletionStage<Saved> readQuest(ServerProgressAccess.Context c,String id){
            reads++;var row=rows.getOrDefault(key(c,id),new Saved(Phase.NOT_ACCEPTED,-1));
            if(!deferReads)return CompletableFuture.completedFuture(row);
            var f=new CompletableFuture<Saved>();readResults.add(f);readSnapshots.add(row);return f;
        }
        public CompletionStage<Saved> advanceQuest(ServerProgressAccess.Context c,String id,Saved before,Phase next){
            var f=new CompletableFuture<Saved>();pending.add(f);commit.add(()->{
                if(!rows.getOrDefault(key(c,id),new Saved(Phase.NOT_ACCEPTED,-1)).equals(before)){f.completeExceptionally(new IllegalStateException("CAS"));return;}
                var row=new Saved(next,before.revision()+1);rows.put(key(c,id),row);f.complete(row);
            });return f;
        }
    }
    static class Fixture {
        final Store store=new Store();final ArrayDeque<Runnable> queue=new ArrayDeque<>();
        final PersonalQuest quests;
        Fixture(){this(false);}
        Fixture(boolean multi){quests=new PersonalQuest(store,multi?Set.of("survey_marker","path_marker"):Set.of("survey_marker"),queue::add,()->{});}
        final ServerProgressAccess access=new ServerProgressAccess();final UUID a=UUID.randomUUID(),b=UUID.randomUUID(),p=UUID.randomUUID(),p2=UUID.randomUUID();
        boolean valid=true;
        ServerProgressAccess.Context ctx(UUID run,UUID player){return access.forVerifiedServerLink(run,player);}
        void pump(){int n=100;while(!queue.isEmpty()){assertTrue(n-->0);queue.remove().run();}}
        PersonalQuest.Session open(ServerProgressAccess.Context c,Object token){var f=quests.open(c,token,()->valid);pump();return f.toCompletableFuture().join();}
        PersonalQuest.Session open(ServerProgressAccess.Context c,String id,Object token){var f=quests.open(c,id,token,()->valid);pump();return f.toCompletableFuture().join();}
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
    }    @Test void multipleQuestsShareBarrierWithoutSharingState() {
        var f=new Fixture(true);var c=f.ctx(f.a,f.p);var q1=f.open(c,"survey_marker",new Object());var q2=f.open(c,"path_marker",new Object());
        var other=f.open(f.ctx(f.a,f.p2),"path_marker",new Object());var otherRun=f.open(f.ctx(f.b,f.p),"survey_marker",new Object());
        f.quests.advance(q1,QuestStore.Phase.ACTIVE);f.commit(0);
        f.quests.advance(q1,QuestStore.Phase.REPORT_READY);f.commit(1);
        f.quests.advance(q1,QuestStore.Phase.COMPLETED);f.commit(2);
        assertEquals(QuestStore.Phase.NOT_ACCEPTED,q2.saved().phase());assertEquals(QuestStore.Phase.NOT_ACCEPTED,other.saved().phase());assertEquals(QuestStore.Phase.NOT_ACCEPTED,otherRun.saved().phase());
        f.quests.advance(q2,QuestStore.Phase.ACTIVE);f.quests.advance(other,QuestStore.Phase.ACTIVE);
        var barrier=f.quests.barrier(f.a);f.commit(3);assertFalse(barrier.toCompletableFuture().isDone());f.commit(4);assertTrue(barrier.toCompletableFuture().isDone());
        assertEquals(QuestStore.Phase.COMPLETED,q1.saved().phase());
    }
    @Test void multipleQuestStaleWritesReconnectAndFailureAreScoped() {
        var f=new Fixture(true);var c=f.ctx(f.a,f.p);var q1=f.open(c,"survey_marker",new Object());var q2=f.open(c,"path_marker",new Object());
        var w1=f.quests.advance(q1,QuestStore.Phase.ACTIVE);var w2=f.quests.advance(q2,QuestStore.Phase.ACTIVE);var barrier=f.quests.barrier(f.a);
        var fresh=f.quests.open(c,"survey_marker",new Object(),()->true);f.pump();assertFalse(fresh.toCompletableFuture().isDone());
        f.commit(0);assertTrue(w1.toCompletableFuture().isCompletedExceptionally());assertEquals(QuestStore.Phase.NOT_ACCEPTED,q1.saved().phase());
        assertEquals(QuestStore.Phase.ACTIVE,fresh.toCompletableFuture().join().saved().phase());assertFalse(barrier.toCompletableFuture().isDone());
        f.store.pending.get(1).completeExceptionally(new StorageFailure("quest","23514"));f.pump();
        assertTrue(w2.toCompletableFuture().isCompletedExceptionally());assertTrue(barrier.toCompletableFuture().isCompletedExceptionally());
        assertTrue(q2.held());assertEquals(QuestStore.Phase.ACTIVE,f.store.rows.get(f.store.key(c,"survey_marker")).phase());assertFalse(f.store.rows.containsKey(f.store.key(c,"path_marker")));
    }
    @Test void unknownQuestCannotReadOrWriteAndImplicitSelectionIsRejected() {
        var f=new Fixture(true);var c=f.ctx(f.a,f.p);
        assertTrue(f.quests.open(c,"missing",new Object(),()->true).toCompletableFuture().isCompletedExceptionally());
        assertTrue(f.quests.open(c,new Object(),()->true).toCompletableFuture().isCompletedExceptionally());assertEquals(0,f.store.reads);assertTrue(f.store.pending.isEmpty());
    }
    @Test void completedLegacyIdRestoresAfterAddingSecondContentWithoutRecreation() {
        var f=new Fixture();var c=f.ctx(f.a,f.p);var old=f.open(c,new Object());
        f.quests.advance(old,QuestStore.Phase.ACTIVE);f.commit(0);
        f.quests.advance(old,QuestStore.Phase.REPORT_READY);f.commit(1);
        f.quests.advance(old,QuestStore.Phase.COMPLETED);f.commit(2);
        var expanded=new PersonalQuest(f.store,Set.of("survey_marker","path_marker"),f.queue::add,()->{});
        var restored=expanded.open(c,"survey_marker",new Object(),()->true);
        var added=expanded.open(c,"path_marker",new Object(),()->true);f.pump();
        assertEquals(new QuestStore.Saved(QuestStore.Phase.COMPLETED,2),restored.toCompletableFuture().join().saved());
        assertEquals(QuestStore.Phase.NOT_ACCEPTED,added.toCompletableFuture().join().saved().phase());assertEquals(3,f.store.pending.size());
    }
    @Test void listsAreReadOnlyAndIsolatedForPlayerRunAndQuest() {
        var f=new Fixture(true);var c=f.ctx(f.a,f.p);var other=f.ctx(f.a,f.p2);var otherRun=f.ctx(f.b,f.p);
        f.store.rows.put(f.store.key(c,"survey_marker"),new QuestStore.Saved(QuestStore.Phase.COMPLETED,2));
        f.store.rows.put(f.store.key(other,"path_marker"),new QuestStore.Saved(QuestStore.Phase.REPORT_READY,1));
        f.store.rows.put(f.store.key(otherRun,"survey_marker"),new QuestStore.Saved(QuestStore.Phase.ACTIVE,0));
        var before=Map.copyOf(f.store.rows);
        var one=f.quests.list(c,new Object(),()->true);var two=f.quests.list(other,new Object(),()->true);var three=f.quests.list(otherRun,new Object(),()->true);f.pump();
        assertEquals(QuestStore.Phase.COMPLETED,one.toCompletableFuture().join().get("survey_marker").phase());
        assertEquals(QuestStore.Phase.NOT_ACCEPTED,one.toCompletableFuture().join().get("path_marker").phase());
        assertEquals(QuestStore.Phase.REPORT_READY,two.toCompletableFuture().join().get("path_marker").phase());
        assertEquals(QuestStore.Phase.ACTIVE,three.toCompletableFuture().join().get("survey_marker").phase());
        assertEquals(before,f.store.rows);assertTrue(f.store.pending.isEmpty());
    }
    @Test void listDistinguishesEmptyFromErrorAndLimitsDuplicateReads() {
        var f=new Fixture(true);var c=f.ctx(f.a,f.p);f.store.deferReads=true;
        var first=f.quests.list(c,new Object(),()->true);f.pump();int reads=f.store.reads;
        assertTrue(f.quests.list(c,new Object(),()->true).toCompletableFuture().isCompletedExceptionally());assertEquals(reads,f.store.reads);
        f.store.readResults.getFirst().completeExceptionally(new StorageFailure("read_quest","08006"));f.pump();
        assertTrue(first.toCompletableFuture().isCompletedExceptionally());assertTrue(f.quests.settled(f.a));
        f.store.deferReads=false;var empty=f.quests.list(c,new Object(),()->true);f.pump();
        assertTrue(empty.toCompletableFuture().join().values().stream().allMatch(row->row.phase()==QuestStore.Phase.NOT_ACCEPTED));assertTrue(f.store.rows.isEmpty());
    }
    @Test void listRejectsWritingHoldAndChangesDuringRead() {
        var f=new Fixture(true);var c=f.ctx(f.a,f.p);var q=f.open(c,"survey_marker",new Object());
        f.store.deferReads=true;var query=f.quests.list(c,new Object(),()->true);f.pump();
        f.quests.advance(q,QuestStore.Phase.ACTIVE);assertTrue(f.quests.list(c,new Object(),()->true).toCompletableFuture().isCompletedExceptionally());f.commit(0);
        f.store.readResults.get(0).complete(f.store.readSnapshots.get(0));f.pump();f.store.readResults.get(1).complete(f.store.readSnapshots.get(1));f.pump();
        assertTrue(query.toCompletableFuture().isCompletedExceptionally());
        f.quests.advance(q,QuestStore.Phase.REPORT_READY);f.store.pending.get(1).completeExceptionally(new StorageFailure("quest","23514"));f.pump();
        int before=f.store.reads;assertTrue(f.quests.list(c,new Object(),()->true).toCompletableFuture().isCompletedExceptionally());assertEquals(before,f.store.reads);
        assertEquals(QuestStore.Phase.ACTIVE,q.saved().phase());
    }
    @Test void logoutSessionReplacementAndShutdownSuppressLateListAndDrainReads() {
        var f=new Fixture(true);var c=f.ctx(f.a,f.p);f.store.deferReads=true;
        var result=f.quests.list(c,new Object(),()->f.valid);f.pump();var barrier=f.quests.barrier(f.a);assertFalse(barrier.toCompletableFuture().isDone());
        f.valid=false;f.store.readResults.get(0).complete(f.store.readSnapshots.get(0));f.pump();
        assertTrue(result.toCompletableFuture().isCompletedExceptionally());assertTrue(barrier.toCompletableFuture().isDone());
        f.valid=true;var next=f.quests.list(c,new Object(),()->f.valid);f.pump();var closing=f.quests.shutdown();assertFalse(closing.toCompletableFuture().isDone());
        f.store.readResults.get(1).complete(f.store.readSnapshots.get(1));f.pump();assertTrue(next.toCompletableFuture().isCompletedExceptionally());assertTrue(closing.toCompletableFuture().isDone());
    }
    @Test void aWriteBetweenQueryCompletionAndDisplayRejectsOldOutput() {
        var f=new Fixture(true);var c=f.ctx(f.a,f.p);var q=f.open(c,"survey_marker",new Object());
        var query=f.quests.list(c,new Object(),()->true);f.pump();var rows=query.toCompletableFuture().join();
        assertDoesNotThrow(()->f.quests.requireListDisplay(c,rows));
        f.quests.advance(q,QuestStore.Phase.ACTIVE);assertThrows(IllegalStateException.class,()->f.quests.requireListDisplay(c,rows));
        f.commit(0);assertThrows(IllegalStateException.class,()->f.quests.requireListDisplay(c,rows));
    }

}
