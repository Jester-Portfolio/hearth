package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.crafting.*;
import io.github.jesterportfolio.hearth.exploration.ExplorationView;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CraftingOrdersTest {
    final CraftContent content=CraftingQuestTest.recipes();
    final QuestCatalog catalog=CraftingQuestTest.catalog(true);
    QuestJournal.View view(QuestStore.Saved first,QuestStore.Saved second){
        return new QuestJournal.View(Map.of("survey_marker",first,"path_marker",second),new QuestJournalStore.Journal(Set.of(),new QuestJournalStore.Preferences(false,0),List.of()));
    }
    static QuestStore.Saved state(QuestStore.Phase phase,long revision,int objective){return new QuestStore.Saved(phase,revision,objective);}
    static QuestStore.Saved unaccepted(){return state(QuestStore.Phase.NOT_ACCEPTED,-1,0);}
    CraftingOrders.Board board(QuestJournal.View view){return CraftingOrders.build(catalog,content,"bench",view);}
    @Test void onlyAcceptedCurrentOrReportingOrdersAreShown(){
        for(var phase:QuestStore.Phase.values()){
            var result=board(view(phase==QuestStore.Phase.NOT_ACCEPTED?unaccepted():state(phase,3,0),unaccepted()));
            assertEquals(phase==QuestStore.Phase.ACTIVE||phase==QuestStore.Phase.REPORT_READY?1:0,result.orders().size());
            if(!result.orders().isEmpty())assertEquals(phase==QuestStore.Phase.ACTIVE?"planks":null,result.orders().getFirst().recipe());
        }
    }
    @Test void sameRecipeAcrossOrdersNeedsOnlyOneCraftEvent(){
        var result=board(view(state(QuestStore.Phase.ACTIVE,0,0),state(QuestStore.Phase.ACTIVE,0,0)));
        assertEquals(2,result.orders().size());assertEquals(List.of(new CraftingBatch.Selection("planks",1)),result.page(1).selections());
        // 다음 순서의 같은 목표는 이번 준비에 미리 추가하지 않습니다.
        assertEquals(0,result.orders().stream().filter(o->o.quest().equals("survey_marker")).findFirst().orElseThrow().objective());
    }
    @Test void reportingOrdersAreFirstAndNeverRequestRecrafting(){
        var result=board(view(state(QuestStore.Phase.ACTIVE,1,1),state(QuestStore.Phase.REPORT_READY,2,0)));
        assertEquals("path_marker",result.orders().getFirst().quest());assertNull(result.orders().getFirst().recipe());
        assertEquals(1,result.page(1).selections().size());assertTrue(result.orders().getFirst().guidance().contains(catalog.quest("path_marker").text().get("report-ready")));
    }
    @Test void anotherCurrentGoalHasGuidanceWithoutRevealingFutureCraftRecipe(){
        var q=QuestCatalogTest.quest("survey_marker","guide");q.remove("objective");q.put("objectives",List.of(Map.of("description","주변을 방문하세요","min-x",12,"max-x",14,"min-y",64,"max-y",67,"min-z",12,"max-z",14),Map.of("description","미래 제작 비공개","interaction","craft:bench:planks","min-x",12,"max-x",14,"min-y",64,"max-y",67,"min-z",12,"max-z",14)));
        var c=QuestCatalog.parse(List.of(QuestCatalogTest.npc("guide",3)),List.of(q));
        var v=new QuestJournal.View(Map.of("survey_marker",state(QuestStore.Phase.ACTIVE,0,0)),view(unaccepted(),unaccepted()).journal());
        var result=CraftingOrders.build(c,content,"bench",v);assertNull(result.orders().getFirst().recipe());assertTrue(result.orders().getFirst().guidance().contains("주변을 방문"));assertFalse(result.toString().contains("미래 제작"));assertTrue(result.page(1).selections().isEmpty());
    }
    @Test void blockedAndOtherFacilityOrdersAreExcluded(){
        var v=view(state(QuestStore.Phase.ACTIVE,1,0),unaccepted());var x=v.exploration();
        var filtered=new ExplorationView(x.registered(),x.regions(),x.places(),x.facts(),x.events(),x.members(),x.story(),x.conditions(),x.nextGoal(),Set.of("survey_marker"),x.branches(),x.effectStatus());
        var result=board(new QuestJournal.View(v.states(),v.journal(),filtered));assertTrue(result.orders().isEmpty());assertFalse(result.toString().contains(catalog.quest("survey_marker").title()));
        var other=new CraftContent(content.recipes(),Map.of("other",new CraftContent.Facility("other",0,64,0,List.of("planks"))));assertTrue(CraftingOrders.build(catalog,other,"other",v).orders().isEmpty());
    }
    @Test void personalSnapshotsNeverBorrowAnotherPlayersObjectives(){var completed=board(view(state(QuestStore.Phase.COMPLETED,5,1),unaccepted()));var own=board(view(unaccepted(),unaccepted()));assertTrue(completed.orders().isEmpty());assertTrue(own.orders().isEmpty());assertEquals(1,board(view(state(QuestStore.Phase.ACTIVE,0,0),unaccepted())).orders().size());}
    @Test void revisionGoalPhaseAndAccessChangesRejectOldSelection(){
        var shown=board(view(state(QuestStore.Phase.ACTIVE,0,0),unaccepted()));var selected=shown.orders().getFirst();CraftingOrders.require(shown,shown,1,selected);
        for(var changed:List.of(state(QuestStore.Phase.ACTIVE,1,0),state(QuestStore.Phase.ACTIVE,1,1),state(QuestStore.Phase.REPORT_READY,2,1),state(QuestStore.Phase.COMPLETED,3,1)))assertThrows(IllegalStateException.class,()->CraftingOrders.require(board(view(changed,unaccepted())),shown,1,selected));
        assertThrows(IllegalStateException.class,()->CraftingOrders.require(shown,shown,1,new CraftingOrders.Order("forged","위조","NPC",QuestStore.Phase.ACTIVE,0,0,"planks","위조")));
    }
    @Test void paginationDoesNotDropRowsOrIncludeOtherPageRecipes(){
        var rows=new ArrayList<CraftingOrders.Order>();for(int i=0;i<14;i++)rows.add(new CraftingOrders.Order("q_"+i,"의뢰 "+i,"NPC",QuestStore.Phase.ACTIVE,0,0,i<6?"planks":null,"목표"));
        var result=new CraftingOrders.Board("bench",1,rows);var all=new ArrayList<CraftingOrders.Order>();for(int page=1;page<=result.pages();page++)all.addAll(result.page(page).orders());assertEquals(rows,all);assertEquals(3,result.pages());assertTrue(result.page(2).selections().isEmpty());assertEquals(1,result.page(1).selections().size());assertThrows(IllegalArgumentException.class,()->result.page(0));assertThrows(IllegalArgumentException.class,()->result.page(4));
    }
    @Test void preparationStepsPreserveSupportedBatchCountsAndSafelySplitOtherCounts(){
        for(int times:List.of(1,4,8,2,3,16))assertEquals(CraftingBatch.CHOICES.contains(times)?times:1,CraftingOrders.step(new CraftingPreparation.Step("planks","판자",times,"OAK_PLANKS",4*times)).times());
    }
    @Test void incompleteStateUnknownFacilityAndMutationFail(){
        var v=view(state(QuestStore.Phase.ACTIVE,0,0),unaccepted());var original=v.toString();var result=board(v);assertEquals(original,v.toString());assertThrows(IllegalArgumentException.class,()->CraftingOrders.build(catalog,content,"unknown",v));assertThrows(IllegalArgumentException.class,()->board(new QuestJournal.View(Map.of(),v.journal())));assertThrows(UnsupportedOperationException.class,()->result.orders().clear());assertThrows(UnsupportedOperationException.class,()->result.page(1).orders().clear());
    }
}
