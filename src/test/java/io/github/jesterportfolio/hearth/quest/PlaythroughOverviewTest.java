package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.exploration.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.story.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PlaythroughOverviewTest {
    final QuestCatalog catalog=QuestCatalogTest.catalog();final StoryStore.Snapshot shared=SharedStoryTest.empty();
    final Playthrough personal=PlaythroughTest.plan(List.of(PlaythroughTest.step("first","QUEST","survey_marker"),PlaythroughTest.step("later","QUEST","path_marker")));
    QuestJournal.View view(QuestStore.Phase phase){return PlaythroughTest.view(PlaythroughTest.rows(phase),shared,Set.of());}
    PlaythroughOverview.Board board(QuestJournal.View view){return PlaythroughOverview.build(personal,catalog,view,shared,SharedStory.Phase.READY);}
    static QuestJournal.View places(QuestJournal.View v,List<ExplorationView.Place> places){var x=v.exploration();var next=new ExplorationView(x.registered(),x.regions(),places,x.facts(),x.events(),x.members(),x.story(),x.conditions(),x.nextGoal(),x.blockedQuests(),x.branches(),x.effectStatus());return new QuestJournal.View(v.states(),v.journal(),next,v.completion());}
    @Test void currentAndKnownPersonalStatesHaveDistinctStatusAndNoFutureOffers(){
        for(var phase:QuestStore.Phase.values()){
            var p=board(view(phase));var row=p.rows().getFirst();assertEquals(QuestListView.status(phase),row.status());assertEquals(phase==QuestStore.Phase.COMPLETED||phase==QuestStore.Phase.SKIPPED,row.fulfilled());
            assertEquals(phase==QuestStore.Phase.NOT_ACCEPTED?null:"survey_marker",row.quest());assertEquals(!row.fulfilled(),row.current());
            if(!row.fulfilled()){assertEquals(1,p.rows().size());assertFalse(p.toString().contains("path_marker"));}
        }
    }
    @Test void futureAcceptedQuestIsVisibleWithoutChangingNextAction(){var v=view(QuestStore.Phase.ACTIVE);var states=new HashMap<>(v.states());states.put("path_marker",new QuestStore.Saved(QuestStore.Phase.REPORT_READY,3));var p=board(new QuestJournal.View(states,v.journal(),v.exploration()));assertEquals(2,p.rows().size());assertFalse(p.rows().getLast().current());assertEquals("보고 대기",p.rows().getLast().status());assertEquals("survey_marker",p.guide().quest());}
    @Test void blockedAndPrerequisiteLockedRowsHaveNoPrivateIdentifiersOrTitles(){
        var v=PlaythroughTest.view(PlaythroughTest.rows(QuestStore.Phase.ACTIVE),shared,Set.of("survey_marker","path_marker"));var p=board(v);assertEquals(PlaythroughOverview.Kind.WAITING,p.rows().getFirst().kind());for(String secret:List.of("survey_marker","path_marker",catalog.quest("survey_marker").title()))assertFalse(p.toString().contains(secret));
        var q=QuestCatalogTest.quest("path_marker","path_guide");q.put("requires-completed","survey_marker");var locked=QuestCatalog.parse(List.of(QuestCatalogTest.npc("survey_guide",3),QuestCatalogTest.npc("path_guide",10)),List.of(QuestCatalogTest.quest("survey_marker","survey_guide"),q));var plan=new Playthrough(List.of(new Playthrough.Step("private",Playthrough.Kind.QUEST,"path_marker","미공개 제목","미공개 설명")));
        var hidden=PlaythroughOverview.build(plan,locked,view(QuestStore.Phase.ACTIVE),shared,SharedStory.Phase.READY);for(String secret:List.of("path_marker","미공개 제목","미공개 설명"))assertFalse(hidden.toString().contains(secret));
    }
    @Test void stageArrivalAndStageCompletionStaySeparateFromPersonalProgress(){
        var state=new StoryStore.Snapshot(new ExplorationStore.Story("open","공개 단계",true,3),Set.of("arrival","vote"),Map.of());var plan=PlaythroughTest.plan(List.of(PlaythroughTest.step("arrival","STAGE","open"),PlaythroughTest.step("person","QUEST","survey_marker"),PlaythroughTest.step("finish","STAGE_COMPLETE","open")));
        var p=PlaythroughOverview.build(plan,catalog,PlaythroughTest.view(PlaythroughTest.rows(QuestStore.Phase.ACTIVE),state,Set.of()),state,SharedStory.Phase.READY);
        assertEquals("공유 단계 도달",p.rows().getFirst().status());assertTrue(p.rows().getFirst().fulfilled());assertEquals("진행 중",p.rows().get(1).status());assertTrue(p.rows().get(1).current());assertEquals("공유 조건 확인 대기",p.rows().getLast().status());assertFalse(p.rows().getLast().fulfilled());
    }
    @Test void futureSharedStageDefinitionsAreHiddenUntilReachedOrCurrent(){var plan=PlaythroughTest.plan(List.of(PlaythroughTest.step("first","QUEST","survey_marker"),PlaythroughTest.step("future","STAGE","open")));var p=PlaythroughOverview.build(plan,catalog,view(QuestStore.Phase.ACTIVE),shared,SharedStory.Phase.READY);assertEquals(1,p.rows().size());assertFalse(p.toString().contains("future"));}
    @Test void placesUseOnlyFilteredNamesAndLocationWithoutHiddenDefinitions(){
        var plan=PlaythroughTest.plan(List.of(PlaythroughTest.step("secret","PLACE","hidden_place"),PlaythroughTest.step("public","PLACE","public_place")));var v=places(view(QuestStore.Phase.COMPLETED),List.of(new ExplorationView.Place("public_place","공개 장소","지역","시설","공개 탐색 구역","미발견",false,false,false)));
        var p=PlaythroughOverview.build(plan,catalog,v,shared,SharedStory.Phase.READY);assertEquals(2,p.rows().size());assertFalse(p.toString().contains("hidden_place"));assertFalse(p.toString().contains("secret"));assertTrue(p.page(1).lines().contains("공개 탐색 구역"));
        var found=places(v,List.of(new ExplorationView.Place("hidden_place","본인 발견 장소","지역","시설","공개된 위치","발견",true,true,false)));var fresh=PlaythroughOverview.build(plan,catalog,found,shared,SharedStory.Phase.READY);assertTrue(fresh.rows().getFirst().fulfilled());assertEquals("개인 발견 기록",fresh.rows().getFirst().status());assertThrows(IllegalStateException.class,()->PlaythroughOverview.require(fresh,p));
    }
    @Test void changedRevisionTrackingAndCompletionEpochRejectShownDetails(){
        var v=view(QuestStore.Phase.ACTIVE);var shown=board(v);var row=shown.rows().getFirst();PlaythroughOverview.require(shown,shown,row);
        var states=new HashMap<>(v.states());states.put("survey_marker",new QuestStore.Saved(QuestStore.Phase.ACTIVE,99));assertThrows(IllegalStateException.class,()->PlaythroughOverview.require(board(new QuestJournal.View(states,v.journal(),v.exploration())),shown,row));
        var tracked=new QuestJournalStore.Journal(Set.of("survey_marker"),v.journal().preferences(),v.journal().notices());var tracking=board(new QuestJournal.View(v.states(),tracked,v.exploration()));assertTrue(tracking.rows().getFirst().tracked());assertThrows(IllegalStateException.class,()->PlaythroughOverview.require(tracking,shown,row));
        var completion=new QuestCompletionView(true,9,Map.of(),List.of());assertThrows(IllegalStateException.class,()->PlaythroughOverview.require(board(new QuestJournal.View(v.states(),v.journal(),v.exploration(),completion)),shown,row));
    }
    @Test void invalidSharedStateIsUnavailableRatherThanEmptyCompletedCourse(){var v=view(QuestStore.Phase.ACTIVE);for(var phase:List.of(SharedStory.Phase.HOLD,SharedStory.Phase.RESULT_UNKNOWN,SharedStory.Phase.PERSISTING)){var p=PlaythroughOverview.build(personal,catalog,v,shared,phase);assertFalse(p.available());assertTrue(p.rows().isEmpty());assertFalse(p.guide().complete());assertFalse(p.page(1).lines().toString().contains("안내 조건 충족"));assertThrows(IllegalStateException.class,()->PlaythroughOverview.require(p,p));}var newer=new StoryStore.Snapshot(new ExplorationStore.Story("arrival","공개 단계",true,4),Set.of(),Map.of());assertFalse(PlaythroughOverview.build(personal,catalog,v,newer,SharedStory.Phase.READY).available());assertFalse(PlaythroughOverview.build(personal,catalog,v,null,SharedStory.Phase.READY).available());}
    @Test void paginationCoversEveryVisibleItemAndFitsBookBudget(){
        var steps=new ArrayList<Playthrough.Step>();var known=new ArrayList<ExplorationView.Place>();for(int i=0;i<64;i++){String id="place_"+i;steps.add(new Playthrough.Step("step_"+i,Playthrough.Kind.PLACE,id,"숨긴 원본 제목","숨긴 원본 설명"));known.add(new ExplorationView.Place(id,"공개 장소 "+"가".repeat(70),"지역","시설","공개된 위치 "+"나".repeat(60),"발견",true,true,false));}
        var p=PlaythroughOverview.build(new Playthrough(steps),catalog,places(view(QuestStore.Phase.COMPLETED),known),shared,SharedStory.Phase.READY);assertEquals(11,p.pages());var all=new ArrayList<PlaythroughOverview.Row>();for(int n=1;n<=p.pages();n++){var page=p.page(n);all.addAll(page.rows());assertTrue(page.rows().size()<=6);assertTrue(QuestBookView.paginate(page.lines()).size()+5<100);}assertEquals(p.rows(),all);assertFalse(p.toString().contains("숨긴 원본"));assertThrows(IllegalArgumentException.class,()->p.page(0));assertThrows(IllegalArgumentException.class,()->p.page(12));
    }
    @Test void incompleteStatesFailAndForgedOrUnacceptedRowsCannotOpenDetails(){var v=view(QuestStore.Phase.ACTIVE);assertThrows(IllegalArgumentException.class,()->board(new QuestJournal.View(Map.of(),v.journal(),v.exploration())));var p=board(v);var fake=new PlaythroughOverview.Row("forged",PlaythroughOverview.Kind.PERSONAL,"위조","진행 중",true,false,false,"survey_marker",0,List.of());assertThrows(IllegalStateException.class,()->PlaythroughOverview.require(p,p,fake));var unaccepted=board(view(QuestStore.Phase.NOT_ACCEPTED));assertThrows(IllegalStateException.class,()->PlaythroughOverview.require(unaccepted,unaccepted,unaccepted.rows().getFirst()));}
    @Test void recordsAreImmutableReadOnlyAndDoNotBorrowAnotherPersonsProgress(){var completed=board(view(QuestStore.Phase.COMPLETED));var v=view(QuestStore.Phase.NOT_ACCEPTED);var before=Map.copyOf(v.states());var own=board(v);assertTrue(completed.rows().getFirst().fulfilled());assertFalse(own.rows().getFirst().fulfilled());assertEquals(before,v.states());assertThrows(UnsupportedOperationException.class,()->own.rows().clear());assertThrows(UnsupportedOperationException.class,()->own.rows().getFirst().lines().clear());assertThrows(UnsupportedOperationException.class,()->own.page(1).lines().clear());assertThrows(IllegalStateException.class,()->PlaythroughOverview.require(own,completed));}
    @Test void journeyLinksCourseOnlyForAvailableSnapshot(){var v=view(QuestStore.Phase.ACTIVE);assertTrue(QuestJourneyView.build(personal,catalog,v,shared,SharedStory.Phase.READY).links().stream().anyMatch(l->l.action()==QuestJourneyView.Action.COURSE));assertFalse(QuestJourneyView.build(personal,catalog,v,shared,SharedStory.Phase.HOLD).links().stream().anyMatch(l->l.action()==QuestJourneyView.Action.COURSE));}
}
