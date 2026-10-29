package io.github.jesterportfolio.hearth.quest;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class InteractionQuestTest {
    static QuestContent content(){var q=QuestCatalogTest.quest("survey_marker","guide");q.remove("objective");var npc=new HashMap<String,Object>(Map.of("description","대화 목표","interaction","npc:guide","min-x",2,"max-x",5,"min-y",64,"max-y",67,"min-z",2,"max-z",5));var block=new HashMap<>(QuestBatchTest.goal(12,"블록 목표"));block.put("interaction","block:13:64:13:LEVER");q.put("objectives",List.of(npc,block,QuestBatchTest.goal(18,"방문 목표")));return QuestCatalog.parse(List.of(QuestCatalogTest.npc("guide",3)),List.of(q)).quest("survey_marker");}
    @Test void interactionsAndVisitFollowOrderWithoutItemConsumption(){var f=new PersonalQuestTest.Fixture();var c=content();var q=f.open(f.ctx(f.a,f.p),new Object());assertTrue(f.quests.interact(q,c,"npc:guide").toCompletableFuture().isCompletedExceptionally());f.quests.accept(q,c);f.commit(0);
        assertTrue(f.quests.reach(q,c,3,64,3).toCompletableFuture().isCompletedExceptionally());assertTrue(f.quests.interact(q,c,"npc:wrong").toCompletableFuture().isCompletedExceptionally());
        f.quests.interact(q,c,"npc:guide");assertTrue(f.quests.interact(q,c,"npc:guide").toCompletableFuture().isCompletedExceptionally());f.commit(1);assertEquals(1,q.saved().objectiveIndex());
        assertTrue(f.quests.interact(q,c,"block:13:64:13:STONE").toCompletableFuture().isCompletedExceptionally());f.quests.interact(q,c,"block:13:64:13:LEVER");f.commit(2);f.quests.reach(q,c,19,64,13);f.commit(3);assertEquals(QuestStore.Phase.REPORT_READY,q.saved().phase());
    }
    @Test void failedInteractionSaveKeepsOldStateAndHold(){var f=new PersonalQuestTest.Fixture();var c=content();var q=f.open(f.ctx(f.a,f.p),new Object());f.quests.accept(q,c);f.commit(0);var old=q.saved();var work=f.quests.interact(q,c,"npc:guide");f.store.pending.get(1).completeExceptionally(new StorageFailure("quest","23514"));f.pump();assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertEquals(old,q.saved());assertTrue(q.held());}
    @Test void malformedTargetsFailAndLegacyGoalsRemainVisits(){assertNull(QuestCatalogTest.catalog().quest("survey_marker").goals().getFirst().interaction());for(String target:List.of("npc:missing","block:99:64:13:LEVER","block:x:64:13:LEVER","block:13:64:13:bad")){var q=QuestCatalogTest.quest("q","guide");var g=new HashMap<>(QuestBatchTest.goal(12,"목표"));g.put("interaction",target);q.remove("objective");q.put("objectives",List.of(g));assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(QuestCatalogTest.npc("guide",3)),List.of(q)));}}
}
