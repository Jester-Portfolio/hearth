package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.exploration.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NpcTopicsTest {
    static Map<String,Object> clue(){return new HashMap<>(Map.of("id","clue","label","조사 후 질문","text","본인에게만 공개할 답변","requires-completed","survey_marker","journal","found_fact"));}
    static QuestCatalog catalog(List<?> topics){var npc=QuestCatalogTest.npc("survey_guide",3);npc.put("dialogue-pages",List.of("공개 인사"));npc.put("topics",topics);return QuestCatalog.parse(List.of(npc),List.of(QuestCatalogTest.quest("survey_marker","survey_guide")));}
    static QuestCatalog catalog(){return catalog(List.of(Map.of("id","general","label","일반 질문","text","공개 답변"),clue()));}
    static final class Store implements ExplorationStore {
        final Map<String,Map<String,Fact>> facts=new HashMap<>();int writes;boolean fail,delay;CompletableFuture<Boolean> pending;Runnable commit;
        String key(ServerProgressAccess.Context c){return c.storyRunId()+"/"+c.playerId();}
        public CompletionStage<Data> readExploration(ServerProgressAccess.Context c){var list=new ArrayList<>(facts.getOrDefault(key(c),Map.of()).values());return CompletableFuture.completedFuture(new Data(Map.of(),list,List.of(),ExplorationTest.empty().story()));}
        public CompletionStage<Boolean> recordFact(ServerProgressAccess.Context c,ExplorationContent.Fact f,boolean shared){
            assertFalse(shared);writes++;if(fail)return CompletableFuture.failedFuture(new StorageFailure("diary","08006"));
            var result=new CompletableFuture<Boolean>();commit=()->{var rows=facts.computeIfAbsent(key(c),k->new HashMap<>());boolean first=rows.putIfAbsent(f.id(),new Fact(f.id(),f.category(),f.title(),f.summary(),f.detail(),f.version(),Instant.EPOCH,Instant.EPOCH,false))==null;result.complete(first);};
            if(delay)pending=result;else commit.run();return result;
        }
        public CompletionStage<Boolean> recordPlace(ServerProgressAccess.Context c,ExplorationContent.Place p,ExplorationContent.Fact f,boolean d,boolean v){throw new AssertionError();}
        public CompletionStage<Void> saveStory(ServerProgressAccess.Context c,Story s,String stage,String title,Boolean open){throw new AssertionError();}
    }
    static final class Fixture {
        final PersonalQuestTest.Fixture f=new PersonalQuestTest.Fixture();final QuestCatalog catalog=catalog();final QuestBatchTest.JournalStore ui=new QuestBatchTest.JournalStore();final Store store=new Store();final QuestJournal journal=new QuestJournal(ui,f.quests,catalog,f.queue::add,()->{});
        final ServerProgressAccess.Context ctx=f.ctx(f.a,f.p);final Object token=new Object();
        Fixture(){journal.exploration(store,ExplorationContent.parse(ExplorationTest.root(),catalog),new CommunityBoundary(c->List.of(),(c,online)->Optional.empty()));}
        void completed(){f.store.rows.put(f.store.key(ctx),new QuestStore.Saved(QuestStore.Phase.COMPLETED,2));}
    }
    @Test void lockedQuestionsNeverExposeLabelOrAnswerAndPermissionStillApplies(){
        var c=catalog();var initial=c.availableTopics("survey_guide",Map.of(),id->true);assertEquals(1,initial.size());assertFalse(initial.toString().contains("조사 후"));assertFalse(initial.toString().contains("본인에게"));
        var rows=Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.COMPLETED,2));assertEquals(2,c.availableTopics("survey_guide",rows,id->true).size());assertEquals(1,c.availableTopics("survey_guide",rows,id->false).size());
        assertTrue(QuestCatalogTest.catalog().topics("survey_guide").isEmpty());
    }
    @Test void invalidTopicsAndUnknownJournalFailBeforeActivation(){
        for(Object bad:List.of(List.of(),"bad",List.of(clue(),clue()),List.of(Map.of("id","q","label","","text","답변")),List.of(Map.of("id","q","label","질문","text","답변","requires-completed","missing"))))assertThrows(IllegalArgumentException.class,()->catalog(bad instanceof List<?> l?l:List.of(bad)));
        var bad=clue();bad.put("journal","missing");assertThrows(IllegalArgumentException.class,()->ExplorationContent.parse(ExplorationTest.root(),catalog(List.of(bad))));
    }
    @Test void nonceIsScopedOneUseAndCloseOrWorldUnloadInvalidatesIt(){
        var c=catalog();var d=new QuestDialogue(c);UUID p=UUID.randomUUID(),r=UUID.randomUUID(),w=UUID.randomUUID(),e=UUID.randomUUID();Object s=new Object();
        var t=d.topicOptions(p,s,r,w,e,"survey_guide",c.availableTopics("survey_guide",Map.of(),id->true)).getFirst();
        assertThrows(IllegalArgumentException.class,()->d.authorizeTopic(p,new Object(),r,w,e,t.npc(),t.topic(),t.nonce()));
        assertThrows(IllegalArgumentException.class,()->d.authorizeTopic(p,s,UUID.randomUUID(),w,e,t.npc(),t.topic(),t.nonce()));
        assertThrows(IllegalArgumentException.class,()->d.authorizeTopic(p,s,r,UUID.randomUUID(),e,t.npc(),t.topic(),t.nonce()));
        assertThrows(IllegalArgumentException.class,()->d.authorizeTopic(p,s,r,w,UUID.randomUUID(),t.npc(),t.topic(),t.nonce()));
        assertThrows(IllegalArgumentException.class,()->d.authorizeTopic(p,s,r,w,e,"wrong",t.topic(),t.nonce()));
        assertThrows(IllegalArgumentException.class,()->d.authorizeTopic(UUID.randomUUID(),s,r,w,e,t.npc(),t.topic(),t.nonce()));
        assertSame(t,d.authorizeTopic(p,s,r,w,e,t.npc(),t.topic(),t.nonce()));assertThrows(IllegalArgumentException.class,()->d.authorizeTopic(p,s,r,w,e,t.npc(),t.topic(),t.nonce()));
        d.clearWorld(w);assertFalse(d.current(t));
        d.options(p,s,r,w,e,"survey_guide");var page=d.beginPages(p,s,r,w,e,"survey_guide").orElseThrow();var fresh=d.topicOptions(p,s,r,w,e,"survey_guide",c.topics("survey_guide")).getFirst();d.turn(p,s,r,w,e,"survey_guide",page.nonce(),"close");assertFalse(d.current(fresh));
    }
    @Test void generalAnswerIsReadOnlyAndLockedQuestionCannotCreateFact(){
        var x=new Fixture();var denied=x.journal.topic(x.ctx,x.token,()->true,"survey_guide","clue");x.f.pump();assertTrue(denied.toCompletableFuture().isCompletedExceptionally());assertEquals(0,x.store.writes);assertTrue(x.journal.settled(x.f.a));
        var general=x.journal.topic(x.ctx,x.token,()->true,"survey_guide","general");x.f.pump();assertFalse(general.toCompletableFuture().isCompletedExceptionally());assertTrue(x.f.store.rows.isEmpty());assertTrue(x.ui.rows.isEmpty());assertEquals(0,x.store.writes);
    }
    @Test void importantAnswerUsesPersonalStorageAndExistingScreensWithoutNotifications(){
        var x=new Fixture();x.completed();var before=Map.copyOf(x.f.store.rows);var work=x.journal.topic(x.ctx,x.token,()->true,"survey_guide","clue");x.f.pump();var view=work.toCompletableFuture().join();
        assertEquals(1,view.exploration().facts().size());assertEquals(before,x.f.store.rows);assertTrue(view.journal().notices().isEmpty());
        assertTrue(QuestScreens.screen(x.catalog,view,"EXPLORATION",null,0).stream().anyMatch(b->"P:found_fact".equals(b.quest())));
        assertFalse(x.store.facts.containsKey(x.store.key(x.f.ctx(x.f.a,x.f.p2))));assertFalse(x.store.facts.containsKey(x.store.key(x.f.ctx(x.f.b,x.f.p))));
        var duplicate=x.journal.topic(x.ctx,x.token,()->true,"survey_guide","clue");x.f.pump();assertEquals(1,duplicate.toCompletableFuture().join().exploration().facts().size());
    }
    @Test void staleSessionOrWriteRaceRejectsBeforeRecordAndNoFalseEmptySuccess(){
        var x=new Fixture();x.completed();x.ui.delay=true;var work=x.journal.topic(x.ctx,x.token,()->x.f.valid,"survey_guide","clue");x.f.pump();x.f.valid=false;x.ui.pending.complete(x.ui.get(x.ctx));x.f.pump();assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertEquals(0,x.store.writes);
        var y=new Fixture();var q=y.f.open(y.ctx,y.token);y.f.quests.accept(q,y.catalog.quest("survey_marker"));var busy=y.journal.topic(y.ctx,y.token,()->true,"survey_guide","clue");y.f.pump();assertTrue(busy.toCompletableFuture().isCompletedExceptionally());assertEquals(0,y.store.writes);
    }
    @Test void pendingWriteIsInBarrierAndStaleResultCannotDisplay(){
        var x=new Fixture();x.completed();x.store.delay=true;var work=x.journal.topic(x.ctx,x.token,()->x.f.valid,"survey_guide","clue");x.f.pump();var duplicate=x.journal.topic(x.ctx,x.token,()->true,"survey_guide","clue");assertTrue(duplicate.toCompletableFuture().isCompletedExceptionally());var barrier=x.journal.barrier(x.f.a);assertFalse(barrier.toCompletableFuture().isDone());
        x.f.valid=false;x.store.commit.run();x.f.pump();assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertTrue(barrier.toCompletableFuture().isDone());assertEquals(1,x.store.facts.get(x.store.key(x.ctx)).size());
    }
    @Test void storageFailurePreservesHoldWithoutStoppingOtherRun(){
        var x=new Fixture();x.completed();x.store.fail=true;var work=x.journal.topic(x.ctx,x.token,()->true,"survey_guide","clue");x.f.pump();assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertFalse(x.journal.settled(x.f.a));assertTrue(x.journal.barrier(x.f.a).toCompletableFuture().isCompletedExceptionally());assertTrue(x.store.facts.isEmpty());assertTrue(x.journal.settled(x.f.b));
    }
}
