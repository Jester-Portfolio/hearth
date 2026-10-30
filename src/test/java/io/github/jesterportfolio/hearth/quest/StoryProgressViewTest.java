package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.story.*;
import io.github.jesterportfolio.hearth.exploration.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StoryProgressViewTest {
    static final CommunityBoundary.Status CONDITIONS=new CommunityBoundary.Status(true,List.of("본인: 조건 충족","다른 구성원: 조건 미충족"));
    StoryGuide.Action action(SharedStoryTest.F f){return StoryGuide.panel(f.a,f.story.content(),f.story.cached(f.a),f.story.phase(f.a),CONDITIONS).action().orElseThrow();}
    @Test void panelUsesCurrentPublicGoalAndBooleanConditionsWithoutBranchOrPrivateDetails(){
        var f=new SharedStoryTest.F();var before=f.story.cached(f.a);var initial=StoryGuide.panel(f.a,f.story.content(),before,f.story.phase(f.a),CONDITIONS);
        assertEquals("/hearthstory next",initial.action().orElseThrow().command());assertTrue(initial.lines().contains("다른 구성원: 조건 미충족"));assertSame(before,f.story.cached(f.a));
        f.readyVote();var panel=StoryGuide.panel(f.a,f.story.content(),f.story.cached(f.a),f.story.phase(f.a),CONDITIONS);assertEquals("/hearthstory vote",panel.action().orElseThrow().command());
        assertFalse(panel.lines().toString().contains("survey_marker"));assertFalse(panel.lines().toString().contains("path_marker"));assertFalse(panel.lines().toString().contains("route_one"));
    }
    @Test void holdUnknownAndBusyNeverOfferExecutionOrOldConditions(){
        var f=new SharedStoryTest.F();f.readyVote();
        for(var phase:List.of(SharedStory.Phase.HOLD,SharedStory.Phase.RESULT_UNKNOWN)){
            var panel=StoryGuide.panel(f.a,f.story.content(),f.story.cached(f.a),phase,CONDITIONS);assertTrue(panel.action().isEmpty());assertFalse(panel.lines().toString().contains("구성원"));
        }
        assertTrue(StoryGuide.panel(f.a,f.story.content(),null,SharedStory.Phase.READY,CONDITIONS).action().isEmpty());
        for(var phase:List.of(SharedStory.Phase.OPEN,SharedStory.Phase.CHECKING,SharedStory.Phase.PERSISTING,SharedStory.Phase.INPUT_CLOSED))assertTrue(StoryGuide.panel(f.a,f.story.content(),f.story.cached(f.a),phase,CONDITIONS).action().isEmpty());
    }
    @Test void oldRevisionWrongRunAndForgedCommandAreRejectedBeforeAnySave(){
        var f=new SharedStoryTest.F();var initial=action(f);f.survey();f.ok(f.story.act(f.c,f.t1,()->true,initial));int writes=f.store.commits;
        f.bad(f.story.act(f.c,f.t1,()->true,initial));f.bad(f.story.act(f.d,f.t1,()->true,action(f)));
        var a=action(f);f.bad(f.story.act(f.c,f.t1,()->true,new StoryGuide.Action(a.run(),a.revision(),a.stage(),"/hearthstory resolve","처리")));assertEquals(writes,f.store.commits);assertNull(f.story.cached(f.b).story().stage());
    }
    @Test void sameRevisionWithChangedStageOrCompletedStateCannotUseOldAction(){
        var f=new SharedStoryTest.F();var a=action(f);var current=f.story.cached(f.a);
        assertThrows(IllegalStateException.class,()->StoryGuide.require(f.story.content(),f.a,new StoryStore.Snapshot(new ExplorationStore.Story("arrival","공개 조사",false,a.revision()),Set.of(),Map.of()),SharedStory.Phase.READY,a));
        assertThrows(IllegalStateException.class,()->StoryGuide.require(f.story.content(),f.a,current,SharedStory.Phase.OPEN,a));
    }
    @Test void executionRechecksOnlineConditionsAndDoesNotApplyBeforeCommit(){
        var f=new SharedStoryTest.F();var a=action(f);f.store.survey.put(f.a,Set.of(f.p1));f.bad(f.story.act(f.c,f.t1,()->true,a));assertEquals(0,f.store.commits);
        f.survey();f.store.delay=true;var work=f.story.act(f.c,f.t1,()->true,a);f.pump();assertFalse(work.toCompletableFuture().isDone());assertNull(f.story.cached(f.a).story().stage());f.bad(f.story.act(f.c,f.t1,()->true,a));
        f.store.finish();f.ok(work);assertEquals("arrival",f.story.cached(f.a).story().stage());
    }
    @Test void closedSessionAndRosterReplacementRejectDelayedRequest(){
        var f=new SharedStoryTest.F();var a=action(f);f.survey();f.store.checkDelay=true;var valid=new boolean[]{true};var work=f.story.act(f.c,f.t1,()->valid[0],a);f.pump();valid[0]=false;f.store.conditions.complete(Map.of(f.p1,true,f.p2,true));f.bad(work);assertEquals(0,f.store.commits);
        var other=new SharedStoryTest.F();other.survey();var old=action(other);other.roster.put(other.p1,new Object());other.story.rosterChanged(other.c);other.bad(other.story.act(other.c,other.t1,()->true,old));assertEquals(0,other.store.commits);
    }
    @Test void voteActionOnlyOpensChatVoteAndNeverChoosesOrCommitsBranch(){
        var f=new SharedStoryTest.F();f.readyVote();var a=action(f);int commits=f.store.commits;f.ok(f.story.act(f.c,f.t1,()->true,a));assertEquals(SharedStory.Phase.OPEN,f.story.phase(f.a));assertTrue(f.story.cached(f.a).branches().isEmpty());assertEquals(commits,f.store.commits);f.bad(f.story.act(f.c,f.t1,()->true,a));
    }
    @Test void dbFailurePreservesOldSharedStateAndHold(){
        var f=new SharedStoryTest.F();f.survey();var a=action(f);var before=f.story.cached(f.a);f.store.fail=true;f.bad(f.story.act(f.c,f.t1,()->true,a));assertEquals(before,f.story.cached(f.a));assertEquals(SharedStory.Phase.HOLD,f.story.phase(f.a));assertTrue(StoryGuide.panel(f.a,f.story.content(),f.story.cached(f.a),f.story.phase(f.a),CONDITIONS).action().isEmpty());assertNull(f.story.cached(f.b).story().stage());
    }
    @Test void unknownCommitNeverConfirmsOrAllowsNewUiRequest(){
        var f=new SharedStoryTest.F();f.survey();var a=action(f);var before=f.story.cached(f.a);f.store.unknown=true;f.bad(f.story.act(f.c,f.t1,()->true,a));assertEquals(SharedStory.Phase.RESULT_UNKNOWN,f.story.phase(f.a));assertEquals(before,f.story.cached(f.a));int writes=f.store.commits;f.bad(f.story.act(f.c,f.t1,()->true,a));assertEquals(writes,f.store.commits);assertTrue(StoryGuide.panel(f.a,f.story.content(),f.story.cached(f.a),f.story.phase(f.a),CONDITIONS).action().isEmpty());
    }
    @Test void onlyRegisteredGuideNpcsReceiveSharedButtonAndCommunityHasThreeSections(){
        var c=QuestCatalog.parse(List.of(QuestCatalogTest.npc("survey_guide",3)),List.of(QuestCatalogTest.quest("survey_marker","survey_guide")));var rows=Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1));var base=QuestScreens.npc(c,"survey_guide",rows,q->true,0);
        assertEquals(base,QuestScreens.npcGuide(base,false));var buttons=QuestScreens.npcGuide(base,true);assertEquals(1,buttons.stream().filter(b->b.action().equals("npc-story")).count());assertEquals(buttons.size(),buttons.stream().map(QuestScreens.Button::slot).distinct().count());
        var f=new NpcTopicsTest.Fixture();var work=f.journal.load(f.ctx,f.token,()->true);f.f.pump();var view=work.toCompletableFuture().join();var community=QuestScreens.screen(f.catalog,view,"community",null,0);assertEquals(3,community.stream().filter(b->Set.of("members","story","conditions").contains(b.action())).count());assertFalse(community.stream().anyMatch(b->b.action().contains("vote")));
    }
}
