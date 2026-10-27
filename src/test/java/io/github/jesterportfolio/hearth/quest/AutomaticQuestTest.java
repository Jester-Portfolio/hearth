package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class AutomaticQuestTest {
    static QuestContent content(boolean automatic){
        var q=QuestCatalogTest.quest("survey_marker","guide");if(automatic)q.put("completion-mode","automatic");
        return QuestCatalog.parse(List.of(QuestCatalogTest.npc("guide",3)),List.of(q)).quest("survey_marker");
    }
    @Test void omittedModeDefaultsToReportAndInvalidValuesAreRejected(){
        assertEquals(QuestContent.CompletionMode.REPORT,content(false).completionMode());assertEquals(QuestContent.CompletionMode.AUTOMATIC,content(true).completionMode());
        for(Object bad:List.of("auto","AUTOMATIC",1,true)) {
            var q=QuestCatalogTest.quest("survey_marker","guide");q.put("completion-mode",bad);
            assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(QuestCatalogTest.npc("guide",3)),List.of(q)));
        }
        var q=QuestCatalogTest.quest("survey_marker","guide");q.put("completion-mode",null);
        assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(QuestCatalogTest.npc("guide",3)),List.of(q)));
    }
    @Test void automaticRequiresAcceptanceAndGoalAndPersistsOnlyOnceBeforeSuccess(){
        var f=new PersonalQuestTest.Fixture();var c=f.ctx(f.a,f.p);var q=f.open(c,new Object());var content=content(true);
        assertTrue(f.quests.reach(q,content,13,64,13).toCompletableFuture().isCompletedExceptionally());assertTrue(f.store.pending.isEmpty());
        f.quests.advance(q,QuestStore.Phase.ACTIVE);f.commit(0);
        assertTrue(f.quests.advance(q,QuestStore.Phase.COMPLETED).toCompletableFuture().isCompletedExceptionally());
        assertTrue(f.quests.reach(q,content,2,64,2).toCompletableFuture().isCompletedExceptionally());
        var saved=f.quests.reach(q,content,13,64,13);assertFalse(saved.toCompletableFuture().isDone());assertEquals(QuestStore.Phase.ACTIVE,q.saved().phase());
        assertTrue(f.quests.reach(q,content,13,64,13).toCompletableFuture().isCompletedExceptionally());assertEquals(2,f.store.pending.size());
        f.commit(1);assertEquals(new QuestStore.Saved(QuestStore.Phase.COMPLETED,1),saved.toCompletableFuture().join());
        assertTrue(f.quests.reach(q,content,13,64,13).toCompletableFuture().isCompletedExceptionally());assertEquals(2,f.store.pending.size());
        assertTrue(QuestStore.follows(QuestStore.Phase.ACTIVE,QuestStore.Phase.COMPLETED));
    }
    @Test void reportStillNeedsExplicitNpcReportAfterGoal(){
        var f=new PersonalQuestTest.Fixture();var q=f.open(f.ctx(f.a,f.p),new Object());f.quests.advance(q,QuestStore.Phase.ACTIVE);f.commit(0);
        var goal=f.quests.reach(q,content(false),13,64,13);f.commit(1);assertEquals(new QuestStore.Saved(QuestStore.Phase.REPORT_READY,1),goal.toCompletableFuture().join());
        f.quests.advance(q,QuestStore.Phase.COMPLETED);f.commit(2);assertEquals(new QuestStore.Saved(QuestStore.Phase.COMPLETED,2),q.saved());
    }
    @Test void failedAutomaticSaveKeepsActiveAndHoldAndOtherQuestValue(){
        var f=new PersonalQuestTest.Fixture(true);var c=f.ctx(f.a,f.p);var q=f.open(c,"survey_marker",new Object());
        f.store.rows.put(f.store.key(c,"path_marker"),new QuestStore.Saved(QuestStore.Phase.COMPLETED,2));
        f.quests.advance(q,QuestStore.Phase.ACTIVE);f.commit(0);
        var saved=f.quests.reach(q,content(true),13,64,13);f.store.pending.get(1).completeExceptionally(new StorageFailure("quest","23514"));f.pump();
        assertTrue(saved.toCompletableFuture().isCompletedExceptionally());assertTrue(q.held());assertEquals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,0),q.saved());
        assertEquals(q.saved(),f.store.rows.get(f.store.key(c)));assertEquals(new QuestStore.Saved(QuestStore.Phase.COMPLETED,2),f.store.rows.get(f.store.key(c,"path_marker")));
        assertTrue(f.quests.reach(q,content(true),13,64,13).toCompletableFuture().isCompletedExceptionally());assertEquals(2,f.store.pending.size());
    }
    @Test void oldSessionCannotApplyLateCompletionAndFreshSessionReadsConfirmedDB(){
        var f=new PersonalQuestTest.Fixture();var c=f.ctx(f.a,f.p);var old=f.open(c,new Object());f.quests.advance(old,QuestStore.Phase.ACTIVE);f.commit(0);
        var write=f.quests.reach(old,content(true),13,64,13);var next=f.quests.open(c,new Object(),()->true);f.pump();assertFalse(next.toCompletableFuture().isDone());
        f.commit(1);assertTrue(write.toCompletableFuture().isCompletedExceptionally());assertEquals(QuestStore.Phase.ACTIVE,old.saved().phase());
        assertEquals(new QuestStore.Saved(QuestStore.Phase.COMPLETED,1),next.toCompletableFuture().join().saved());
    }
    @Test void existingStoredStatesAreNotConvertedByNewMode(){
        var f=new PersonalQuestTest.Fixture();var c=f.ctx(f.a,f.p);var old=new QuestStore.Saved(QuestStore.Phase.REPORT_READY,5);f.store.rows.put(f.store.key(c),old);
        var q=f.open(c,new Object());assertEquals(old,q.saved());assertTrue(f.quests.reach(q,content(true),13,64,13).toCompletableFuture().isCompletedExceptionally());
        assertEquals(old,f.store.rows.get(f.store.key(c)));assertTrue(f.store.pending.isEmpty());
    }
}
