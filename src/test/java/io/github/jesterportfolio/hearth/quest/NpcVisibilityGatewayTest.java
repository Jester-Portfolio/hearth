package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.exploration.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.story.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NpcVisibilityGatewayTest {
    static final class F {
        final PersonalQuestTest.Fixture f=new PersonalQuestTest.Fixture(true);final Object token=new Object();final ServerProgressAccess.Context ctx=f.ctx(f.a,f.p);
        final QuestCatalog catalog;final SharedStoryTest.Store sharedStore=new SharedStoryTest.Store();final NpcTopicsTest.Store facts=new NpcTopicsTest.Store();final QuestBatchTest.JournalStore ui=new QuestBatchTest.JournalStore();final SharedStory story;final QuestJournal journal;
        F(){var witness=GeneralNpcTest.witness("witness",21);witness.put("z",21);witness.put("topics",List.of(Map.of("id","general","label","공개 질문","text","원본 답변"),Map.of("id","clue","label","개인 질문","text","개인 답변","requires-completed","survey_marker","journal","found_fact")));
            catalog=QuestCatalog.parse(List.of(QuestCatalogTest.npc("survey_guide",3),QuestCatalogTest.npc("path_guide",10),witness),List.of(QuestCatalogTest.quest("survey_marker","survey_guide"),QuestCatalogTest.quest("path_marker","path_guide")));
            story=new SharedStory(sharedStore,SharedStoryTest.definitions(),f.queue::add,()->{},c->Map.of(f.p,token),()->0,java.time.Duration.ofSeconds(30));
            journal=new QuestJournal(ui,f.quests,catalog,f.queue::add,()->{});journal.sharedStory(story,new StoryAccess(story));
            journal.exploration(new ExplorationStore(){
                public CompletionStage<Data> readExploration(ServerProgressAccess.Context c){return facts.readExploration(c).thenApply(raw->new Data(raw.places(),raw.facts(),raw.events(),sharedStore.snapshots.getOrDefault(c.storyRunId(),SharedStoryTest.empty()).story()));}
                public CompletionStage<Boolean> recordFact(ServerProgressAccess.Context c,ExplorationContent.Fact fact,boolean shared){return facts.recordFact(c,fact,shared);}
                public CompletionStage<Boolean> recordPlace(ServerProgressAccess.Context c,ExplorationContent.Place p,ExplorationContent.Fact fact,boolean d,boolean v){throw new AssertionError();}
                public CompletionStage<Void> saveStory(ServerProgressAccess.Context c,Story before,String stage,String title,Boolean open){throw new AssertionError();}
            },ExplorationContent.parse(ExplorationTest.root(),catalog),new CommunityBoundary(c->List.of(),(c,m)->Optional.empty()));
        }
        <T>T ok(CompletionStage<T> work){f.pump();return work.toCompletableFuture().join();}
        void bad(CompletionStage<?> work){f.pump();assertTrue(work.toCompletableFuture().isCompletedExceptionally());}
        QuestJournal.View load(){return ok(journal.load(ctx,token,()->true));}
        void open(){sharedStore.survey.put(f.a,Set.of(f.p));sharedStore.path.put(f.a,Set.of(f.p));ok(story.advance(ctx,token,()->true));ok(story.advance(ctx,token,()->true));var vote=ok(story.openVote(ctx,token,()->true));ok(story.cast(ctx,token,()->true,vote.id(),"one"));}
    }
    @Test void unrelatedQuestPermissionCannotRevealNpcInsideLockedLocation(){var x=new F();var view=x.load();assertTrue(x.journal.questAllowed(x.ctx,"survey_marker"));assertTrue(view.blockedNpcs().contains("witness"));assertFalse(x.journal.npcAllowed(x.ctx,"witness"));assertTrue(x.journal.npcAllowed(x.ctx,"survey_guide"));assertThrows(UnsupportedOperationException.class,()->view.blockedNpcs().clear());var forged=new QuestJournal.View(view.states(),view.journal(),view.exploration(),view.completion());assertThrows(IllegalStateException.class,()->x.journal.projectInformation(x.ctx,forged,()->true));}
    @Test void bothUnrecordedAndImportantQuestionsAtHiddenNpcCannotReachStorage(){var x=new F();x.load();x.f.store.rows.put(x.f.store.key(x.ctx),new QuestStore.Saved(QuestStore.Phase.COMPLETED,2));for(String topic:List.of("general","clue"))x.bad(x.journal.topic(x.ctx,x.token,()->true,"witness",topic));assertEquals(0,x.facts.writes);assertEquals(0,x.journal.informationStamp(x.ctx));assertTrue(x.facts.facts.isEmpty());assertTrue(x.ui.rows.isEmpty());}
    @Test void sharedUnlockReprojectsVisibilityAndKeepsPersonalFactOwned(){var x=new F();var old=x.load();x.open();var fresh=x.load();assertFalse(fresh.blockedNpcs().contains("witness"));assertThrows(IllegalStateException.class,()->x.journal.requireView(x.ctx,old));assertTrue(NpcGuidance.build(x.catalog,fresh,null,Set.of()).contacts().isEmpty());x.f.store.rows.put(x.f.store.key(x.ctx),new QuestStore.Saved(QuestStore.Phase.COMPLETED,2));var own=x.ok(x.journal.topic(x.ctx,x.token,()->true,"witness","clue"));assertTrue(NpcGuidance.build(x.catalog,own,null,Set.of()).contacts().stream().anyMatch(c->c.npc().equals("witness")));var other=x.ok(x.journal.load(x.f.ctx(x.f.a,x.f.p2),new Object(),()->true));assertTrue(NpcContacts.forFact(x.catalog,other,"P:found_fact").isEmpty());assertEquals(1,x.facts.writes);}
    @Test void sharedHoldRejectsOldDirectoryAndEvenPublicNpcQuestionsWithoutWriting(){var x=new F();var old=x.load();x.sharedStore.survey.put(x.f.a,Set.of(x.f.p));x.sharedStore.fail=true;x.bad(x.story.advance(x.ctx,x.token,()->true));assertFalse(x.journal.npcAllowed(x.ctx,"survey_guide"));assertThrows(IllegalStateException.class,()->x.journal.projectInformation(x.ctx,old,()->true));x.bad(x.journal.topic(x.ctx,x.token,()->true,"witness","general"));assertEquals(0,x.facts.writes);}
    @Test void accessChangeDuringPendingTopicReadCannotCreateFactOrReturnAnswer(){var x=new F();x.load();x.open();x.f.store.rows.put(x.f.store.key(x.ctx),new QuestStore.Saved(QuestStore.Phase.COMPLETED,2));x.ui.delay=true;var pending=x.journal.topic(x.ctx,x.token,()->true,"witness","clue");x.f.pump();assertFalse(pending.toCompletableFuture().isDone());x.sharedStore.fail=true;x.bad(x.story.advance(x.ctx,x.token,()->true));x.ui.pending.complete(x.ui.get(x.ctx));x.f.pump();assertTrue(pending.toCompletableFuture().isCompletedExceptionally());assertEquals(0,x.facts.writes);assertTrue(x.facts.facts.isEmpty());}
}
