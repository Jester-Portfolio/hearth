package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class QuestPrerequisiteTest {
    static QuestCatalog catalog(boolean automatic){
        var follow=QuestCatalogTest.quest("path_marker","survey_guide");follow.put("requires-completed","survey_marker");
        if(automatic){follow.put("start-mode","automatic");follow.put("start-area",Map.of("min-x",5,"max-x",7,"min-y",64,"max-y",67,"min-z",8,"max-z",10));}
        return QuestCatalog.parse(List.of(QuestCatalogTest.npc("survey_guide",3)),List.of(QuestCatalogTest.quest("survey_marker","survey_guide"),follow));
    }
    @Test void rejectsMissingSelfAndCyclesAndPreservesDefault(){
        for(String ref:List.of("missing","one")){
            var q=QuestCatalogTest.quest("one","guide");q.put("requires-completed",ref);
            assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(QuestCatalogTest.npc("guide",3)),List.of(q)));
        }
        var a=QuestCatalogTest.quest("one","guide");var b=QuestCatalogTest.quest("two","guide");a.put("requires-completed","two");b.put("requires-completed","one");
        assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(QuestCatalogTest.npc("guide",3)),List.of(a,b)));
        assertNull(catalog(false).quest("survey_marker").requiresCompleted());assertEquals("survey_marker",catalog(true).quest("path_marker").requiresCompleted());
    }
    @Test void bothStartModesLockWithoutHoldAndUnlockOnlyAfterSameOwnerCompletion(){
        for(boolean auto:List.of(false,true)){
            var f=new PersonalQuestTest.Fixture(true);var ctx=f.ctx(f.a,f.p);var c=catalog(auto).quest("path_marker");Object token=new Object();var q=f.open(ctx,c.id(),token);
            var blocked=auto?f.quests.start(q,c,6,64,9):f.quests.accept(q,c);f.pump();assertTrue(blocked.toCompletableFuture().isCompletedExceptionally());assertFalse(q.held());assertEquals(QuestStore.Phase.NOT_ACCEPTED,q.saved().phase());assertTrue(f.store.pending.isEmpty());
            f.store.rows.put(f.store.key(ctx,"survey_marker"),new QuestStore.Saved(QuestStore.Phase.COMPLETED,2));
            var started=auto?f.quests.start(q,c,6,64,9):f.quests.accept(q,c);f.pump();assertFalse(started.toCompletableFuture().isDone());var barrier=f.quests.barrier(f.a);assertFalse(barrier.toCompletableFuture().isDone());
            assertTrue((auto?f.quests.start(q,c,6,64,9):f.quests.accept(q,c)).toCompletableFuture().isCompletedExceptionally());
            f.commit(0);assertEquals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,0),started.toCompletableFuture().join());assertFalse(barrier.toCompletableFuture().isCompletedExceptionally());
            for(var other:List.of(f.ctx(f.a,f.p2),f.ctx(f.b,f.p))){var otherQ=f.open(other,c.id(),new Object());var denied=auto?f.quests.start(otherQ,c,6,64,9):f.quests.accept(otherQ,c);f.pump();assertTrue(denied.toCompletableFuture().isCompletedExceptionally());}
        }
    }
    @Test void hiddenUntilUnlockedButAlreadyAcceptedLegacyStateRemainsAvailable(){
        var cat=catalog(false);var c=cat.quest("path_marker");var missing=new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1);
        var rows=Map.of("survey_marker",missing,"path_marker",missing);assertFalse(QuestListView.available(c,rows));assertTrue(QuestListView.available(cat.quest("survey_marker"),rows));
        assertTrue(QuestListView.available(c,Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.COMPLETED,2),"path_marker",missing)));
        for(var phase:List.of(QuestStore.Phase.ACTIVE,QuestStore.Phase.REPORT_READY,QuestStore.Phase.COMPLETED))assertTrue(QuestListView.available(c,Map.of("survey_marker",missing,"path_marker",new QuestStore.Saved(phase,phase.ordinal()-1))));
        assertThrows(NullPointerException.class,()->QuestListView.available(c,Map.of("path_marker",missing)));
        var f=new PersonalQuestTest.Fixture(true);var ctx=f.ctx(f.a,f.p);f.store.rows.put(f.store.key(ctx,c.id()),new QuestStore.Saved(QuestStore.Phase.REPORT_READY,1));var q=f.open(ctx,c.id(),new Object());f.quests.advance(q,QuestStore.Phase.COMPLETED);f.commit(0);assertEquals(new QuestStore.Saved(QuestStore.Phase.COMPLETED,2),q.saved());
    }
    @Test void automaticRequiresFreshEntryAndQueryDoesNotMutateRows(){
        var f=new PersonalQuestTest.Fixture(true);var ctx=f.ctx(f.a,f.p);var c=catalog(true).quest("path_marker");Object token=new Object();
        var blocked=f.quests.enter(ctx,c,token,()->true,2,64,2,6,64,9);f.pump();assertTrue(blocked.toCompletableFuture().join().isEmpty());assertTrue(f.store.rows.isEmpty());
        f.store.rows.put(f.store.key(ctx,"survey_marker"),new QuestStore.Saved(QuestStore.Phase.COMPLETED,2));int reads=f.store.reads;
        assertTrue(f.quests.enter(ctx,c,token,()->true,5,64,9,6,64,9).toCompletableFuture().join().isEmpty());assertEquals(reads,f.store.reads);
        var started=f.quests.enter(ctx,c,token,()->true,2,64,2,6,64,9);f.pump();f.commit(0);assertEquals(QuestStore.Phase.ACTIVE,started.toCompletableFuture().join().orElseThrow().phase());
    }
    @Test void dependencySessionOrWriteChangeBetweenReadAndInsertRejectsStart(){
        var f=new PersonalQuestTest.Fixture(true);var ctx=f.ctx(f.a,f.p);var c=catalog(false).quest("path_marker");
        f.store.rows.put(f.store.key(ctx,"survey_marker"),new QuestStore.Saved(QuestStore.Phase.COMPLETED,2));
        f.open(ctx,"survey_marker",new Object());var q=f.open(ctx,c.id(),new Object());f.store.deferReads=true;
        var started=f.quests.accept(q,c);f.pump();f.quests.open(ctx,"survey_marker",new Object(),()->true);f.pump();
        f.store.readResults.get(0).complete(f.store.readSnapshots.get(0));f.pump();assertTrue(started.toCompletableFuture().isCompletedExceptionally());assertFalse(q.held());assertTrue(f.store.pending.isEmpty());
    }
    @Test void queryFailureHoldAndSessionChangeNeverInsertOrUnlock(){
        for(boolean stale:List.of(false,true)){
            var f=new PersonalQuestTest.Fixture(true);var ctx=f.ctx(f.a,f.p);var c=catalog(false).quest("path_marker");var q=f.open(ctx,c.id(),new Object());f.store.deferReads=true;
            var started=f.quests.accept(q,c);f.pump();
            if(stale){f.valid=false;f.store.readResults.getFirst().complete(new QuestStore.Saved(QuestStore.Phase.COMPLETED,2));}
            else f.store.readResults.getFirst().completeExceptionally(new StorageFailure("read_quest","08006"));
            f.pump();assertTrue(started.toCompletableFuture().isCompletedExceptionally());assertTrue(f.store.pending.isEmpty());assertEquals(QuestStore.Phase.NOT_ACCEPTED,q.saved().phase());assertEquals(!stale,q.held());
        }
    }
    @Test void inFlightOrHeldPrerequisiteCannotAuthorizeTarget(){
        var f=new PersonalQuestTest.Fixture(true);var ctx=f.ctx(f.a,f.p);var c=catalog(false).quest("path_marker");var pre=f.open(ctx,"survey_marker",new Object());var q=f.open(ctx,c.id(),new Object());
        f.quests.advance(pre,QuestStore.Phase.ACTIVE);assertTrue(f.quests.accept(q,c).toCompletableFuture().isCompletedExceptionally());
        f.store.pending.getFirst().completeExceptionally(new StorageFailure("quest","23514"));f.pump();assertTrue(f.quests.accept(q,c).toCompletableFuture().isCompletedExceptionally());assertEquals(1,f.store.pending.size());assertEquals(QuestStore.Phase.NOT_ACCEPTED,q.saved().phase());
    }
    @Test void targetWriteFailureKeepsOriginalRowsAndHold(){
        var f=new PersonalQuestTest.Fixture(true);var ctx=f.ctx(f.a,f.p);var c=catalog(false).quest("path_marker");
        var completed=new QuestStore.Saved(QuestStore.Phase.COMPLETED,2);f.store.rows.put(f.store.key(ctx,"survey_marker"),completed);
        var q=f.open(ctx,c.id(),new Object());var started=f.quests.accept(q,c);f.pump();
        f.store.pending.getFirst().completeExceptionally(new StorageFailure("quest","23514"));f.pump();
        assertTrue(started.toCompletableFuture().isCompletedExceptionally());assertTrue(q.held());assertEquals(QuestStore.Phase.NOT_ACCEPTED,q.saved().phase());
        assertFalse(f.store.rows.containsKey(f.store.key(ctx,c.id())));assertEquals(completed,f.store.rows.get(f.store.key(ctx,"survey_marker")));assertTrue(f.quests.barrier(f.a).toCompletableFuture().isCompletedExceptionally());
    }

}
