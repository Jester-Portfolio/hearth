package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class QuestStartTest {
    static Map<String,Object> definition(String completion){
        var q=QuestCatalogTest.quest("survey_marker","guide");q.put("start-mode","automatic");q.put("completion-mode",completion);
        q.put("start-area",Map.of("min-x",5,"max-x",7,"min-y",64,"max-y",67,"min-z",8,"max-z",10));return q;
    }
    static QuestContent content(String completion){return QuestCatalog.parse(List.of(QuestCatalogTest.npc("guide",3)),List.of(definition(completion))).quest("survey_marker");}
    static java.util.concurrent.CompletionStage<Optional<QuestStore.Saved>> enter(PersonalQuestTest.Fixture f,QuestContent c,Object token,boolean goal){return f.quests.enter(f.ctx(f.a,f.p),c,token,()->f.valid,2,64,2,goal?13:6,64,goal?13:9);}
    @Test void defaultNpcAndMalformedAutomaticDefinitions(){
        assertEquals(QuestContent.StartMode.NPC,QuestCatalogTest.catalog().quest("survey_marker").startMode());
        for(Object bad:List.of("auto",true,1)){
            var q=definition("report");q.put("start-mode",bad);assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(QuestCatalogTest.npc("guide",3)),List.of(q)));
        }
        for(Object bad:List.of("bad",Map.of(),Map.of("min-x",8,"max-x",7,"min-y",64,"max-y",67,"min-z",8,"max-z",10))){
            var q=definition("report");q.put("start-area",bad);assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(QuestCatalogTest.npc("guide",3)),List.of(q)));
        }
    }
    @Test void startIsSeparateFromGoalAndBothCompletionModesWork(){
        for(String mode:List.of("report","automatic")){
            var f=new PersonalQuestTest.Fixture();var c=content(mode);Object token=new Object();var q=f.open(f.ctx(f.a,f.p),token);
            assertTrue(f.quests.accept(q,c).toCompletableFuture().isCompletedExceptionally());
            var start=enter(f,c,token,false);f.pump();assertFalse(start.toCompletableFuture().isDone());assertEquals(QuestStore.Phase.NOT_ACCEPTED,q.saved().phase());
            var barrier=f.quests.barrier(f.a);assertFalse(barrier.toCompletableFuture().isDone());f.commit(0);
            assertEquals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,0),start.toCompletableFuture().join().orElseThrow());assertTrue(barrier.toCompletableFuture().isDone());
            var goal=enter(f,c,token,true);f.pump();f.commit(1);assertEquals(mode.equals("report")?QuestStore.Phase.REPORT_READY:QuestStore.Phase.COMPLETED,goal.toCompletableFuture().join().orElseThrow().phase());
            if(mode.equals("report")){f.quests.advance(q,QuestStore.Phase.COMPLETED);f.commit(2);}
            assertEquals(new QuestStore.Saved(QuestStore.Phase.COMPLETED,mode.equals("report")?2:1),q.saved());
        }
    }
    @Test void noReadsForSubBlockMotionStayingInsideOrNpcStartAndDuplicateIsBounded(){
        var f=new PersonalQuestTest.Fixture();var c=content("report");var ctx=f.ctx(f.a,f.p);Object token=new Object();
        f.quests.enter(ctx,c,token,()->true,6,64,9,6.2,64,9);f.quests.enter(ctx,c,token,()->true,5,64,9,6,64,9);
        var npc=QuestCatalogTest.catalog().quest("survey_marker");f.quests.enter(ctx,npc,token,()->true,2,64,2,6,64,9);assertEquals(0,f.store.reads);
        var one=enter(f,c,token,false);f.pump();int reads=f.store.reads;
        assertTrue(enter(f,c,token,false).toCompletableFuture().join().isEmpty());assertEquals(reads,f.store.reads);assertEquals(1,f.store.pending.size());f.commit(0);
        assertTrue(one.toCompletableFuture().join().isPresent());var duplicate=enter(f,c,token,false);f.pump();assertTrue(duplicate.toCompletableFuture().join().isEmpty());assertEquals(1,f.store.pending.size());assertEquals(reads,f.store.reads);
    }
    @Test void failurePreservesNotAcceptedHoldAndOtherOwner(){
        var f=new PersonalQuestTest.Fixture();var c=content("report");Object token=new Object();var q=f.open(f.ctx(f.a,f.p),token);
        var request=enter(f,c,token,false);f.pump();f.store.pending.getFirst().completeExceptionally(new StorageFailure("quest","23514"));f.pump();
        assertTrue(request.toCompletableFuture().isCompletedExceptionally());assertTrue(q.held());assertEquals(QuestStore.Phase.NOT_ACCEPTED,q.saved().phase());assertTrue(f.store.rows.isEmpty());
        var again=enter(f,c,token,false);f.pump();assertTrue(again.toCompletableFuture().isCompletedExceptionally());assertEquals(1,f.store.pending.size());
        var other=f.open(f.ctx(f.b,f.p),new Object());assertEquals(QuestStore.Phase.NOT_ACCEPTED,other.saved().phase());
    }
    @Test void sessionReplacementSuppressesOldResultAndKeepsSavedStart(){
        var f=new PersonalQuestTest.Fixture();var c=content("report");Object token=new Object();var old=f.open(f.ctx(f.a,f.p),token);
        var request=enter(f,c,token,false);f.pump();var fresh=f.quests.open(f.ctx(f.a,f.p),new Object(),()->true);f.pump();assertFalse(fresh.toCompletableFuture().isDone());f.commit(0);
        assertTrue(request.toCompletableFuture().isCompletedExceptionally());assertEquals(QuestStore.Phase.NOT_ACCEPTED,old.saved().phase());assertEquals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,0),fresh.toCompletableFuture().join().saved());
        assertTrue(f.quests.barrier(f.a).toCompletableFuture().isDone());
    }
    @Test void disconnectDuringStartAndOverlappingAreasDoNotCompleteOnStart(){
        var f=new PersonalQuestTest.Fixture();var q=definition("automatic");q.put("start-area",q.get("objective"));var c=QuestCatalog.parse(List.of(QuestCatalogTest.npc("guide",3)),List.of(q)).quest("survey_marker");Object token=new Object();
        var start=enter(f,c,token,true);f.pump();f.commit(0);assertEquals(QuestStore.Phase.ACTIVE,start.toCompletableFuture().join().orElseThrow().phase());
        var next=enter(f,c,token,true);f.pump();f.valid=false;f.commit(1);assertTrue(next.toCompletableFuture().isCompletedExceptionally());
    }
}
