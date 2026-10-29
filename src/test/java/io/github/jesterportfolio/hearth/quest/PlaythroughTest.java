package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.story.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.exploration.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PlaythroughTest {
    static Map<String,Object> step(String id,String kind,String ref){return new HashMap<>(Map.of("id",id,"kind",kind,"reference",ref,"title","공개 안내","hint","공개 시험 안내입니다."));}
    static Playthrough plan(List<?> rows){return Playthrough.parse(Map.of("format",1,"steps",rows),QuestCatalogTest.catalog(),SharedStoryTest.definitions(),ExplorationTest.content());}
    static QuestJournal.View view(Map<String,QuestStore.Saved> rows,StoryStore.Snapshot shared,Set<String> blocked){
        var x=ExplorationView.empty();var e=new ExplorationView(true,x.regions(),x.places(),x.facts(),x.events(),x.members(),shared.story(),x.conditions(),null,blocked,List.of());
        return new QuestJournal.View(rows,new QuestJournalStore.Journal(Set.of(),new QuestJournalStore.Preferences(true,0),List.of()),e);
    }
    static Map<String,QuestStore.Saved> rows(QuestStore.Phase phase){return Map.of("survey_marker",new QuestStore.Saved(phase,phase==QuestStore.Phase.NOT_ACCEPTED?-1:phase.ordinal()-1),"path_marker",new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1));}
    @Test void personalQuestGuidanceUsesCurrentStateAndNeverAcceptsOrReports(){
        var p=plan(List.of(step("first","QUEST","survey_marker")));var shared=SharedStoryTest.empty();
        for(var phase:QuestStore.Phase.values()){
            var rows=rows(phase);var guide=p.guide(QuestCatalogTest.catalog(),view(rows,shared,Set.of()),shared,SharedStory.Phase.READY);
            assertEquals(phase==QuestStore.Phase.COMPLETED||phase==QuestStore.Phase.SKIPPED,guide.complete());
            assertEquals(phase==QuestStore.Phase.NOT_ACCEPTED||phase==QuestStore.Phase.REPORT_READY?"survey_marker":null,guide.talkQuest());
            assertFalse(Objects.toString(guide.command(),"").contains("accept"));assertFalse(Objects.toString(guide.command(),"").contains("report"));assertEquals(rows(phase),rows);
        }
    }
    @Test void lockedQuestsAndUnknownPlacesDoNotRevealFutureContent(){
        var shared=SharedStoryTest.empty();var p=plan(List.of(step("first","QUEST","survey_marker")));
        var guide=p.guide(QuestCatalogTest.catalog(),view(rows(QuestStore.Phase.NOT_ACCEPTED),shared,Set.of("survey_marker")),shared,SharedStory.Phase.READY);
        assertNull(guide.talkQuest());assertFalse(guide.toString().contains("survey_marker"));
        var place=plan(List.of(step("secret","PLACE","hidden_place"))).guide(QuestCatalogTest.catalog(),view(rows(QuestStore.Phase.COMPLETED),shared,Set.of()),shared,SharedStory.Phase.READY);
        assertFalse(place.toString().contains("hidden_place"));assertFalse(place.toString().contains("21"));assertFalse(place.complete());
    }
    @Test void sharedStageDoesNotCompletePersonalStepsAndTerminalRequiresCompletion(){
        var shared=new StoryStore.Snapshot(new ExplorationStore.Story("open","공개 단계",true,3),Set.of("arrival","vote"),Map.of());
        var p=plan(List.of(step("shared","STAGE","arrival"),step("personal","QUEST","survey_marker"),step("finish","STAGE_COMPLETE","open")));
        assertEquals("survey_marker",p.guide(QuestCatalogTest.catalog(),view(rows(QuestStore.Phase.NOT_ACCEPTED),shared,Set.of()),shared,SharedStory.Phase.READY).talkQuest());
        assertFalse(p.guide(QuestCatalogTest.catalog(),view(rows(QuestStore.Phase.COMPLETED),shared,Set.of()),shared,SharedStory.Phase.READY).complete());
        var done=new StoryStore.Snapshot(shared.story(),Set.of("arrival","vote","open"),Map.of());assertTrue(p.guide(QuestCatalogTest.catalog(),view(rows(QuestStore.Phase.COMPLETED),done,Set.of()),done,SharedStory.Phase.READY).complete());
    }
    @Test void busyUnknownAndOldRevisionReturnNoAction(){
        var shared=SharedStoryTest.empty();var p=plan(List.of(step("first","QUEST","survey_marker")));var v=view(rows(QuestStore.Phase.NOT_ACCEPTED),shared,Set.of());
        for(var phase:List.of(SharedStory.Phase.HOLD,SharedStory.Phase.PERSISTING,SharedStory.Phase.RESULT_UNKNOWN)){
            var guide=p.guide(QuestCatalogTest.catalog(),v,shared,phase);assertNull(guide.command());assertNull(guide.talkQuest());assertFalse(guide.complete());
        }
        var newer=new StoryStore.Snapshot(new ExplorationStore.Story("arrival","공개 단계",false,0),Set.of(),Map.of());assertNull(p.guide(QuestCatalogTest.catalog(),v,newer,SharedStory.Phase.READY).command());
    }
    @Test void missingDuplicateAndUnsupportedReferencesFailBeforeActivation(){
        for(var bad:List.of(List.of(step("missing","QUEST","missing")),List.of(step("duplicate","QUEST","survey_marker"),step("duplicate","STAGE","arrival")),List.of(step("first","QUEST","survey_marker"),step("second","QUEST","survey_marker")),List.of(step("wrong","REWARD","survey_marker"))))assertThrows(IllegalArgumentException.class,()->plan(bad));
    }
}
