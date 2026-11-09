package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.story.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CourseRouteTest {
    final QuestCatalog catalog=FacilityObjectivesTest.catalog();final StoryStore.Snapshot shared=SharedStoryTest.empty();
    final PreparationGuide preparation=new PreparationGuide(List.of(new PreparationGuide.Entry("gather","공개 채집",PreparationGuide.Kind.GATHERING,"material_site","materials",null,List.of(),"현장 안내"),new PreparationGuide.Entry("bench","공개 작업대",PreparationGuide.Kind.CRAFTING,"bench","workshop",null,List.of("planks"),"준비 안내")),Map.of(),Map.of());
    Playthrough plan(){return new Playthrough(List.of(new Playthrough.Step("tools",Playthrough.Kind.QUEST,"survey_marker","개인 실습","기존 의뢰 안내")));}
    static Map<String,Object> binding(String step,int goal,String card){return new HashMap<>(Map.of("step",step,"objective",goal,"preparation",card));}
    Map<String,Object> root(){return Map.of("routes",List.of(binding("tools",0,"gather"),binding("tools",1,"bench")));}
    CourseRoute routes(Map<?,?> data,Playthrough plan){return CourseRoute.parse(data,plan,catalog,preparation,PreparationGuideTest.exploration());}
    QuestJournal.View view(QuestStore.Phase phase,long revision,int objective){var v=PreparationGuideTest.view(phase,Set.of(),true);var states=new HashMap<>(v.states());states.put("survey_marker",new QuestStore.Saved(phase,revision,objective));return new QuestJournal.View(states,v.journal(),v.exploration());}
    PreparationGuide.Page cards(){return new PreparationGuide.Page(List.of(new PreparationGuide.Card("gather","공개된 채집장",List.of("공개 탐색 구역","준비 상태"),null,-1),new PreparationGuide.Card("bench","공개된 작업대",List.of("원목 2개 필요","보유 0개"),null,-1)));}
    CourseRoute.Page page(QuestJournal.View view,PreparationGuide.Page cards){return routes(root(),plan()).build(plan(),catalog,view,shared,SharedStory.Phase.READY,cards);}
    @Test void currentGoalSelectsOnlyItsExistingFacilityAndDoesNotWriteProgress(){
        var v=view(QuestStore.Phase.ACTIVE,0,0);String before=v.toString();var gather=page(v,cards());assertEquals("gather",gather.preparation());assertTrue(gather.lines().toString().contains("공개된 채집장"));assertFalse(gather.lines().toString().contains("원목 2개"));assertEquals(before,v.toString());
        var bench=page(view(QuestStore.Phase.ACTIVE,1,1),cards());assertEquals("bench",bench.preparation());assertTrue(bench.lines().toString().contains("원목 2개"));assertFalse(bench.lines().toString().contains("공개된 채집장"));assertThrows(IllegalStateException.class,()->CourseRoute.require(bench,gather));
    }
    @Test void unacceptedReportingFailedAndCompletedDoNotSuggestMoreCraftingOrGathering(){
        for(var phase:List.of(QuestStore.Phase.NOT_ACCEPTED,QuestStore.Phase.REPORT_READY,QuestStore.Phase.FAILED,QuestStore.Phase.COMPLETED,QuestStore.Phase.SKIPPED)){var p=page(view(phase,phase==QuestStore.Phase.NOT_ACCEPTED?-1:3,0),cards());assertNull(p.preparation());assertFalse(p.lines().toString().contains("원목 2개"));assertFalse(p.lines().toString().contains("공개된 채집장"));}
    }
    @Test void filteredFacilityNeverFallsBackToItsDefinitionNameOrCoordinates(){var shown=page(view(QuestStore.Phase.ACTIVE,0,0),cards());var fresh=page(view(QuestStore.Phase.ACTIVE,0,0),new PreparationGuide.Page(List.of()));assertNull(fresh.preparation());assertTrue(fresh.lines().toString().contains("현재 공개된 시설"));assertFalse(fresh.lines().toString().contains("material_site"));assertFalse(fresh.lines().toString().contains("20.5"));assertThrows(IllegalStateException.class,()->CourseRoute.require(fresh,shown));}
    @Test void changedMaterialCountsCanOpenFreshReadOnlyPreparationForSameGoal(){var shown=page(view(QuestStore.Phase.ACTIVE,1,1),cards());var fresh=page(view(QuestStore.Phase.ACTIVE,1,1),new PreparationGuide.Page(List.of(new PreparationGuide.Card("bench","공개된 작업대",List.of("원목 2개 필요","보유 2개"),null,-1))));CourseRoute.require(fresh,shown);assertNotEquals(shown.lines(),fresh.lines());}
    @Test void blockedQuestAndHoldHaveNoPrivateFacilityTarget(){var v=PreparationGuideTest.view(QuestStore.Phase.ACTIVE,Set.of("survey_marker"),true);var blocked=page(v,cards());assertNull(blocked.preparation());assertFalse(blocked.lines().toString().contains("공개된 채집장"));var hold=routes(root(),plan()).build(plan(),catalog,view(QuestStore.Phase.ACTIVE,0,0),shared,SharedStory.Phase.HOLD,cards());assertFalse(hold.board().available());assertNull(hold.preparation());assertThrows(IllegalStateException.class,()->CourseRoute.require(hold,hold));}
    @Test void placeLinksRequireSamePublicPlaceAndUseFilteredView(){var p=new Playthrough(List.of(new Playthrough.Step("place",Playthrough.Kind.PLACE,"materials","미공개 원본 위치","비공개")));var r=routes(Map.of("routes",List.of(binding("place",-1,"gather"))),p);var page=r.build(p,catalog,view(QuestStore.Phase.ACTIVE,0,0),shared,SharedStory.Phase.READY,cards());assertEquals("gather",page.preparation());assertFalse(page.lines().toString().contains("미공개 원본"));assertThrows(IllegalArgumentException.class,()->routes(Map.of("routes",List.of(binding("place",-1,"bench"))),p));}
    @Test void missingDuplicateFractionalAndMismatchedLinksFailBeforeActivation(){
        for(var bad:List.of(Map.of("routes",List.of(binding("unknown",0,"gather"))),Map.of("routes",List.of(binding("tools",0,"unknown"))),Map.of("routes",List.of(binding("tools",0,"bench"))),Map.of("routes",List.of(binding("tools",1,"gather"))),Map.of("routes",List.of(binding("tools",3,"bench"))),Map.of("routes",List.of(binding("tools",0,"gather"),binding("tools",0,"bench"))),Map.of("routes",List.of(Map.of("step","tools","objective",0.5,"preparation","gather"))),Map.of("routes",List.of())))assertThrows(RuntimeException.class,()->routes(bad,plan()));
    }
    @Test void pageAndDefinitionAreImmutableAndDoNotAcceptOldRevision(){var r=routes(root(),plan());assertThrows(UnsupportedOperationException.class,()->r.bindings().clear());var p=page(view(QuestStore.Phase.ACTIVE,0,0),cards());assertThrows(UnsupportedOperationException.class,()->p.lines().clear());assertFalse(p.book().pages().isEmpty());assertThrows(IllegalStateException.class,()->CourseRoute.require(page(view(QuestStore.Phase.ACTIVE,1,0),cards()),p));}
}
