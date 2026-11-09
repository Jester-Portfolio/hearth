package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.combat.DungeonPlay;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.exploration.ExplorationView;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class FacilityObjectivesTest {
    static Map<String,Object> visit(){return new HashMap<>(Map.of("description","현재 채집장 방문 안내","min-x",20,"max-x",21,"min-y",64,"max-y",65,"min-z",20,"max-z",21));}
    static QuestCatalog catalog(){
        var first=QuestCatalogTest.quest("survey_marker","survey_guide");first.remove("objective");
        first.put("objectives",List.of(visit(),Map.of("description","나중 제작 목표","min-x",12,"max-x",14,"min-y",64,"max-y",65,"min-z",12,"max-z",14,"interaction","craft:bench:planks")));
        var other=QuestCatalogTest.quest("path_marker","path_guide");other.put("objective",visit());
        return QuestCatalog.parse(List.of(QuestCatalogTest.npc("survey_guide",3),QuestCatalogTest.npc("path_guide",10)),List.of(first,other));
    }
    static QuestStore.Saved row(QuestStore.Phase phase,long revision,int goal){return new QuestStore.Saved(phase,revision,goal);}
    static QuestJournal.View view(QuestStore.Saved first){return new QuestJournal.View(Map.of("survey_marker",first,"path_marker",row(QuestStore.Phase.NOT_ACCEPTED,-1,0)),new QuestJournalStore.Journal(Set.of(),new QuestJournalStore.Preferences(false,0),List.of()));}
    FacilityObjectives.Board gathering(QuestJournal.View v){return FacilityObjectives.gathering(catalog(),v,PreparationGuideTest.gathering(),Set.of("survey_marker"),List.of("채집 상태"));}
    static QuestJournal.View blocked(QuestJournal.View v){var x=v.exploration();return new QuestJournal.View(v.states(),v.journal(),new ExplorationView(x.registered(),x.regions(),x.places(),x.facts(),x.events(),x.members(),x.story(),x.conditions(),x.nextGoal(),Set.of("survey_marker"),x.branches(),x.effectStatus()));}
    @Test void gatheringSeparatesVisitNextGoalAndReportingWithoutAdvancing(){
        for(var expected:List.of(Map.entry(row(QuestStore.Phase.ACTIVE,0,0),FacilityObjectives.Kind.VISIT),Map.entry(row(QuestStore.Phase.ACTIVE,1,1),FacilityObjectives.Kind.NEXT),Map.entry(row(QuestStore.Phase.REPORT_READY,2,1),FacilityObjectives.Kind.REPORT))){
            var v=view(expected.getKey());var original=v.toString();var board=gathering(v);assertEquals(expected.getValue(),board.rows().getFirst().kind());assertEquals(original,v.toString());assertEquals(expected.getKey().revision(),board.rows().getFirst().revision());
            assertTrue(board.page(1).lines().stream().anyMatch(s->s.contains("방문 기록은 실제 채집")));assertFalse(board.page(1).book(board.title()).pages().isEmpty());
        }
    }
    @Test void futureGoalTextNeverAppearsInCurrentVisitGuidance(){var board=gathering(view(row(QuestStore.Phase.ACTIVE,0,0)));assertTrue(board.rows().getFirst().guidance().contains("현재 채집장"));assertFalse(board.toString().contains("나중 제작 목표"));}
    @Test void unacceptedCompletedFailedSkippedAndBlockedTitlesStayHidden(){
        for(var phase:List.of(QuestStore.Phase.NOT_ACCEPTED,QuestStore.Phase.COMPLETED,QuestStore.Phase.FAILED,QuestStore.Phase.SKIPPED)){
            var result=gathering(view(row(phase,phase==QuestStore.Phase.NOT_ACCEPTED?-1:3,0)));assertTrue(result.rows().isEmpty());assertFalse(result.toString().contains("survey_marker"));assertFalse(result.toString().contains(catalog().quest("survey_marker").title()));
        }
        assertTrue(gathering(blocked(view(row(QuestStore.Phase.ACTIVE,0,0)))).rows().isEmpty());
    }
    @Test void explicitCourseLinkDoesNotBorrowOtherQuestsWithSameVisitArea(){
        var v=view(row(QuestStore.Phase.NOT_ACCEPTED,-1,0));var states=new HashMap<>(v.states());states.put("path_marker",row(QuestStore.Phase.ACTIVE,0,0));assertTrue(gathering(new QuestJournal.View(states,v.journal())).rows().isEmpty());
        var own=gathering(view(row(QuestStore.Phase.ACTIVE,0,0)));assertEquals(1,own.rows().size());assertEquals("survey_marker",own.rows().getFirst().quest());
    }
    @Test void dungeonShowsSavedCurrentAndReportingStatesAndNoAdmissionAction(){
        var definition=new DungeonPlay.Definition("trial","survey_marker","survey_guide",new QuestContent.Area(12,14,64,67,12,14));
        for(var phase:List.of(QuestStore.Phase.ACTIVE,QuestStore.Phase.REPORT_READY)){
            var result=FacilityObjectives.dungeon(PreparationGuideTest.catalog(false),view(row(phase,2,0)),definition,List.of("길목 진행 중"));assertEquals(phase==QuestStore.Phase.ACTIVE?FacilityObjectives.Kind.DUNGEON:FacilityObjectives.Kind.REPORT,result.rows().getFirst().kind());
            assertTrue(result.page(1).lines().toString().contains("읽기 전용"));assertFalse(result.page(1).lines().toString().contains("방문 기록은"));
        }
    }
    @Test void staleGoalRevisionPhaseAndPublicAccessRejectShownSelection(){
        var shown=gathering(view(row(QuestStore.Phase.ACTIVE,0,0)));var selected=shown.rows().getFirst();FacilityObjectives.require(shown,shown,1,selected);
        for(var fresh:List.of(gathering(view(row(QuestStore.Phase.ACTIVE,1,0))),gathering(view(row(QuestStore.Phase.ACTIVE,1,1))),gathering(view(row(QuestStore.Phase.REPORT_READY,2,1))),gathering(blocked(view(row(QuestStore.Phase.ACTIVE,0,0))))))assertThrows(IllegalStateException.class,()->FacilityObjectives.require(fresh,shown,1,selected));
        var forged=new FacilityObjectives.Row("foreign","위조","NPC",QuestStore.Phase.ACTIVE,0,0,FacilityObjectives.Kind.VISIT,"위조");assertThrows(IllegalStateException.class,()->FacilityObjectives.require(shown,shown,1,forged));
    }
    @Test void countdownRefreshDoesNotInvalidateUnchangedGoalButSourceAndSharedRevisionDo(){
        var shown=gathering(view(row(QuestStore.Phase.ACTIVE,0,0)));FacilityObjectives.require(new FacilityObjectives.Board(shown.source(),shown.title(),shown.sharedRevision(),shown.rows(),List.of("다음 초기화 1초")),shown);
        assertThrows(IllegalStateException.class,()->FacilityObjectives.require(new FacilityObjectives.Board("other",shown.title(),shown.sharedRevision(),shown.rows(),shown.status()),shown));assertThrows(IllegalStateException.class,()->FacilityObjectives.require(new FacilityObjectives.Board(shown.source(),shown.title(),shown.sharedRevision()+1,shown.rows(),shown.status()),shown));
    }
    @Test void paginationAndKoreanEmojiTextAreBoundedAndImmutable(){
        var rows=new ArrayList<FacilityObjectives.Row>();for(int i=0;i<17;i++)rows.add(new FacilityObjectives.Row("goal_"+i,"개인 의뢰 "+i,"안내원",QuestStore.Phase.ACTIVE,i,0,FacilityObjectives.Kind.NEXT,"가나다🙂".repeat(70)));
        var board=new FacilityObjectives.Board("gathering:test","현장 안내",0,rows,List.of());var all=new ArrayList<FacilityObjectives.Row>();for(int n=1;n<=board.pages();n++){var page=board.page(n);all.addAll(page.rows());assertTrue(page.book(board.title()).pages().size()<99);assertTrue(page.rows().size()<=6);}assertEquals(rows,all);assertEquals(3,board.pages());assertThrows(IllegalArgumentException.class,()->board.page(0));assertThrows(IllegalArgumentException.class,()->board.page(4));assertThrows(UnsupportedOperationException.class,()->board.rows().clear());assertThrows(UnsupportedOperationException.class,()->board.page(1).rows().clear());
    }
    @Test void invalidDefinitionLinksAndIncompleteQueriesAreFailures(){
        var v=view(row(QuestStore.Phase.ACTIVE,0,0));assertThrows(RuntimeException.class,()->FacilityObjectives.gathering(catalog(),v,PreparationGuideTest.gathering(),Set.of("unknown"),List.of()));assertThrows(IllegalArgumentException.class,()->gathering(new QuestJournal.View(Map.of(),v.journal())));
        assertThrows(IllegalArgumentException.class,()->FacilityObjectives.dungeon(catalog(),v,new DungeonPlay.Definition("trial","survey_marker","survey_guide",new QuestContent.Area(12,14,64,67,12,14)),List.of()));
    }
    @Test void genericNpcGuideNeverAddsUnacceptedAdmissionAndKeepsRegisteredNpcScope(){
        var c=PreparationGuideTest.catalog(false);var v=view(row(QuestStore.Phase.NOT_ACCEPTED,-1,0));var buttons=QuestScreens.npcDungeon(List.of(),c,v.states(),"survey_guide","survey_guide","survey_marker");assertTrue(buttons.stream().anyMatch(b->b.action().equals("npc-dungeon-guide")));assertFalse(buttons.stream().anyMatch(b->b.action().equals("npc-dungeon")));assertTrue(QuestScreens.npcDungeon(List.of(),c,v.states(),"path_guide","survey_guide","survey_marker").isEmpty());
    }
}
