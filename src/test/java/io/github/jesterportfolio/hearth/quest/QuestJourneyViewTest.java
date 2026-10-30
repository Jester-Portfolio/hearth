package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.story.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class QuestJourneyViewTest {
    final QuestCatalog catalog=QuestCatalogTest.catalog();final StoryStore.Snapshot shared=SharedStoryTest.empty();
    final Playthrough plan=PlaythroughTest.plan(List.of(PlaythroughTest.step("first","QUEST","survey_marker")));
    QuestJournal.View view(QuestStore.Phase phase){return PlaythroughTest.view(PlaythroughTest.rows(phase),shared,Set.of());}
    QuestJourneyView.Page page(QuestJournal.View view){return QuestJourneyView.build(plan,catalog,view,shared,SharedStory.Phase.READY);}
    @Test void unacceptedQuestOnlyConnectsToValidatedNpcWithoutDetailOrDirectAcceptance(){
        var p=page(view(QuestStore.Phase.NOT_ACCEPTED));assertTrue(p.links().stream().anyMatch(l->l.action()==QuestJourneyView.Action.TALK));assertFalse(p.links().stream().anyMatch(l->Set.of(QuestJourneyView.Action.DETAIL,QuestJourneyView.Action.TRACK,QuestJourneyView.Action.RETRY).contains(l.action())));assertEquals("survey_marker",p.guide().talkQuest());
    }
    @Test void activeGoalReportAndFailedRetryHaveDifferentSafeActions(){
        var active=page(view(QuestStore.Phase.ACTIVE));assertTrue(active.links().stream().anyMatch(l->l.action()==QuestJourneyView.Action.DETAIL));assertTrue(active.links().stream().anyMatch(l->l.action()==QuestJourneyView.Action.TRACK));assertFalse(active.links().stream().anyMatch(l->l.action()==QuestJourneyView.Action.TALK));
        assertTrue(page(view(QuestStore.Phase.REPORT_READY)).links().stream().anyMatch(l->l.action()==QuestJourneyView.Action.TALK));
        var failed=page(view(QuestStore.Phase.FAILED));assertTrue(failed.links().stream().anyMatch(l->l.action()==QuestJourneyView.Action.RETRY));assertFalse(failed.links().stream().anyMatch(l->l.action()==QuestJourneyView.Action.TRACK));
    }
    @Test void trackingChangeOldRevisionAndChangedSharedStateRejectOldBook(){
        var v=view(QuestStore.Phase.ACTIVE);var before=page(v);var link=before.links().getFirst();QuestJourneyView.require(before,before,link);
        var tracked=new QuestJournal.View(v.states(),new QuestJournalStore.Journal(Set.of("survey_marker"),v.journal().preferences(),v.journal().notices()),v.exploration());var next=page(tracked);assertTrue(next.links().stream().anyMatch(l->l.action()==QuestJourneyView.Action.UNTRACK));assertThrows(IllegalStateException.class,()->QuestJourneyView.require(next,before,link));
        var states=new HashMap<>(v.states());states.put("survey_marker",new QuestStore.Saved(QuestStore.Phase.ACTIVE,99));assertThrows(IllegalStateException.class,()->QuestJourneyView.require(page(new QuestJournal.View(states,v.journal(),v.exploration())),before,link));
        var stale=QuestJourneyView.build(plan,catalog,v,new StoryStore.Snapshot(new ExplorationStore.Story(null,null,false,4),Set.of(),Map.of()),SharedStory.Phase.READY);assertTrue(stale.links().isEmpty());assertThrows(IllegalStateException.class,()->QuestJourneyView.require(stale,before,link));
    }
    @Test void lockedAndUnknownStateNeverExposePrivateQuestOrGiveOldActions(){
        var v=PlaythroughTest.view(PlaythroughTest.rows(QuestStore.Phase.ACTIVE),shared,Set.of("survey_marker"));var page=page(v);assertFalse(page.toString().contains("survey_marker"));assertFalse(page.toString().contains(catalog.quest("survey_marker").title()));assertTrue(page.links().stream().anyMatch(l->l.action()==QuestJourneyView.Action.SHARED));
        for(var phase:List.of(SharedStory.Phase.HOLD,SharedStory.Phase.RESULT_UNKNOWN,SharedStory.Phase.PERSISTING))assertTrue(QuestJourneyView.build(plan,catalog,view(QuestStore.Phase.ACTIVE),shared,phase).links().isEmpty());
    }
    @Test void completionDoesNotBlockNextStepForUnclaimedRewardOrGrantAnything(){
        var v=view(QuestStore.Phase.COMPLETED);var rewards=Map.of("survey_marker",new QuestCompletionView.Reward("PENDING","지급 대기",true));var c=new QuestCompletionView(true,7,rewards,List.of());var with=new QuestJournal.View(v.states(),v.journal(),v.exploration(),c);var page=page(with);
        assertTrue(page.guide().complete());assertTrue(page.links().stream().anyMatch(l->l.action()==QuestJourneyView.Action.REWARDS));assertEquals(rewards,with.completion().rewards());assertEquals(v.states(),with.states());
        var hidden=PlaythroughTest.view(v.states(),shared,Set.of("survey_marker"));assertFalse(page(new QuestJournal.View(hidden.states(),hidden.journal(),hidden.exploration(),c)).links().stream().anyMatch(l->l.action()==QuestJourneyView.Action.REWARDS));
    }
    @Test void otherPlayerAndRunViewsNeverBorrowTrackingOrCompletion(){
        var p1=view(QuestStore.Phase.COMPLETED);var p2=view(QuestStore.Phase.NOT_ACCEPTED);assertTrue(page(p1).guide().complete());assertFalse(page(p2).guide().complete());assertTrue(page(p2).links().stream().anyMatch(l->l.action()==QuestJourneyView.Action.TALK));
        var other=new QuestJourneyView.Link("위조",QuestJourneyView.Action.RETRY,"path_marker",0);assertThrows(IllegalStateException.class,()->QuestJourneyView.require(page(p2),page(p2),other));
    }
    @Test void futureUndiscoveredPlaceOnlyOffersGeneralMapAndNoSecretIdOrPosition(){
        var places=PlaythroughTest.plan(List.of(PlaythroughTest.step("secret","PLACE","hidden_place")));var p=QuestJourneyView.build(places,catalog,view(QuestStore.Phase.COMPLETED),shared,SharedStory.Phase.READY);assertFalse(p.toString().contains("hidden_place"));assertTrue(p.links().stream().anyMatch(l->l.action()==QuestJourneyView.Action.MAP));
    }
    @Test void npcMenuJourneyDoesNotOverlapStoryQuestionsOrQuestButtons(){
        var rows=PlaythroughTest.rows(QuestStore.Phase.REPORT_READY);var base=QuestScreens.npc(catalog,"survey_guide",rows,q->true,0);assertEquals(base,QuestScreens.npcJourney(base,false));var menu=QuestScreens.npcJourney(QuestScreens.npcGuide(base,true),true);assertEquals(menu.size(),menu.stream().map(QuestScreens.Button::slot).distinct().count());assertEquals(1,menu.stream().filter(b->b.action().equals("npc-journey")).count());
    }
}
