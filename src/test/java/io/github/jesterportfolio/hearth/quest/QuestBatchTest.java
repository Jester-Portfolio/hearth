package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class QuestBatchTest {
    static QuestCatalog content(boolean automatic){
        var q=QuestCatalogTest.quest("survey_marker","guide");q.remove("objective");
        q.put("objectives",List.of(goal(12,"첫 목표"),goal(18,"다음 목표"),goal(24,"마지막 목표")));q.put("completion-mode",automatic?"automatic":"report");
        return QuestCatalog.parse(List.of(QuestCatalogTest.npc("guide",3)),List.of(q));
    }
    static Map<String,Object> goal(int x,String label){return Map.of("description",label,"min-x",x,"max-x",x+2,"min-y",64,"max-y",67,"min-z",12,"max-z",14);}
    @Test void multiStepOrderCASAndFinalModesPreserveLegacySingleGoal(){
        assertEquals(1,QuestCatalogTest.catalog().quest("survey_marker").goals().size());
        for(boolean automatic:List.of(false,true)){
            var f=new PersonalQuestTest.Fixture();var c=content(automatic).quest("survey_marker");var q=f.open(f.ctx(f.a,f.p),new Object());var signals=new ArrayList<Boolean>();f.quests.changed(ctx->signals.add(!q.busy()));
            f.quests.accept(q,c);f.commit(0);
            assertTrue(f.quests.reach(q,c,19,64,13).toCompletableFuture().isCompletedExceptionally());
            for(int i=0;i<3;i++){
                var work=f.quests.reach(q,c,13+i*6,64,13);assertTrue(f.quests.reach(q,c,13+i*6,64,13).toCompletableFuture().isCompletedExceptionally());
                f.commit(i+1);assertEquals(i<2?QuestStore.Phase.ACTIVE:automatic?QuestStore.Phase.COMPLETED:QuestStore.Phase.REPORT_READY,work.toCompletableFuture().join().phase());
                assertEquals(Math.min(i+1,2),q.saved().objectiveIndex());
                if(i<2)assertTrue(f.quests.reach(q,c,13+i*6,64,13).toCompletableFuture().isCompletedExceptionally());
            }
            if(!automatic){f.quests.advance(q,QuestStore.Phase.COMPLETED);f.commit(4);}
            assertEquals(new QuestStore.Saved(QuestStore.Phase.COMPLETED,automatic?3:4,2),q.saved());assertFalse(signals.isEmpty());assertTrue(signals.stream().allMatch(Boolean::booleanValue));
        }
    }
    @Test void stepFailureStaleSessionAndRestoreKeepIndexAndVersion(){
        var f=new PersonalQuestTest.Fixture();var ctx=f.ctx(f.a,f.p);var c=content(false).quest("survey_marker");var q=f.open(ctx,new Object());f.quests.accept(q,c);f.commit(0);
        var first=f.quests.reach(q,c,13,64,13);var fresh=f.quests.open(ctx,new Object(),()->true);f.pump();f.commit(1);
        assertTrue(first.toCompletableFuture().isCompletedExceptionally());var restored=fresh.toCompletableFuture().join();assertEquals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,1,1),restored.saved());
        f.quests.reach(restored,c,19,64,13);f.store.pending.get(2).completeExceptionally(new StorageFailure("quest","23514"));f.pump();assertTrue(restored.held());assertEquals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,1,1),restored.saved());
    }
    static final class JournalStore implements QuestJournalStore {
        final Map<String,Journal> rows=new HashMap<>();boolean fail,delay;CompletableFuture<Journal> pending;
        String key(ServerProgressAccess.Context c){return c.storyRunId()+"/"+c.playerId();}
        Journal get(ServerProgressAccess.Context c){return rows.getOrDefault(key(c),new Journal(Set.of(),new Preferences(true,-1),List.of()));}
        public CompletionStage<Journal> readJournal(ServerProgressAccess.Context c){if(delay){pending=new CompletableFuture<>();return pending;}return CompletableFuture.completedFuture(get(c));}
        CompletionStage<Void> save(ServerProgressAccess.Context c,Journal j){if(fail)return CompletableFuture.failedFuture(new StorageFailure("journal","08006"));rows.put(key(c),j);return CompletableFuture.completedFuture(null);}
        public CompletionStage<Void> track(ServerProgressAccess.Context c,String q,QuestStore.Saved expected,boolean enabled){var j=get(c);var ids=new HashSet<>(j.tracked());if(enabled)ids.add(q);else ids.remove(q);return save(c,new Journal(ids,j.preferences(),j.notices()));}
        public CompletionStage<Void> hud(ServerProgressAccess.Context c,Preferences expected,boolean enabled){var j=get(c);return save(c,new Journal(j.tracked(),new Preferences(enabled,expected.revision()+1),j.notices()));}
        public CompletionStage<Void> readNotice(ServerProgressAccess.Context c,String q,long revision){return readAllNotices(c);}
        public CompletionStage<Void> readAllNotices(ServerProgressAccess.Context c){var j=get(c);return save(c,new Journal(j.tracked(),j.preferences(),j.notices().stream().map(n->new Notice(n.quest(),n.revision(),n.kind(),n.created(),true)).toList()));}
    }
    @Test void trackingHudSettingsAndScreensArePersonalAndReadOnly(){
        var f=new PersonalQuestTest.Fixture();var ctx=f.ctx(f.a,f.p);Object token=new Object();var c=content(false);var q=f.open(ctx,token);f.quests.accept(q,c.quest("survey_marker"));f.commit(0);
        var store=new JournalStore();var journal=new QuestJournal(store,f.quests,c,f.queue::add,()->{});var signals=new ArrayList<Boolean>();journal.changed(x->signals.add(journal.settled(f.a)));
        var tracked=journal.track(ctx,token,()->true,"survey_marker",true);f.pump();var view=tracked.toCompletableFuture().join();assertEquals(1,journal.hudLines(view).size());assertTrue(journal.hudLines(view).getFirst().contains("[1/3]"));
        assertEquals(6,QuestScreens.screen(c,view,"home",null,0).size());assertEquals("추적 해제",QuestScreens.screen(c,view,"detail","survey_marker",0).stream().filter(b->b.action().equals("track")).findFirst().orElseThrow().label());
        var before=Map.copyOf(f.store.rows);var off=journal.hud(ctx,token,()->true,false);f.pump();assertTrue(journal.hudLines(off.toCompletableFuture().join()).isEmpty());assertEquals(before,f.store.rows);
        assertTrue(store.get(f.ctx(f.b,f.p)).preferences().hudEnabled());assertTrue(store.get(f.ctx(f.a,f.p2)).tracked().isEmpty());
        var untrack=journal.track(ctx,token,()->true,"survey_marker",false);f.pump();assertTrue(untrack.toCompletableFuture().join().journal().tracked().isEmpty());assertEquals(List.of(true,true,true),signals);
    }
    @Test void invalidTrackingFailsWithoutHoldAndLateUIReadCannotDisplay(){
        var f=new PersonalQuestTest.Fixture();var ctx=f.ctx(f.a,f.p);var store=new JournalStore();var journal=new QuestJournal(store,f.quests,content(false),f.queue::add,()->{});
        var denied=journal.track(ctx,new Object(),()->true,"survey_marker",true);f.pump();assertTrue(denied.toCompletableFuture().isCompletedExceptionally());assertTrue(journal.settled(f.a));
        store.delay=true;var view=journal.load(ctx,new Object(),()->f.valid);f.pump();assertFalse(view.toCompletableFuture().isDone());assertTrue(journal.load(ctx,new Object(),()->true).toCompletableFuture().isCompletedExceptionally());
        f.valid=false;store.pending.complete(store.get(ctx));f.pump();assertTrue(view.toCompletableFuture().isCompletedExceptionally());assertTrue(journal.settled(f.a));
    }
    @Test void UIStorageFailurePreservesSettingsHoldAndQuestRevision(){
        var f=new PersonalQuestTest.Fixture();var ctx=f.ctx(f.a,f.p);var store=new JournalStore();var journal=new QuestJournal(store,f.quests,content(false),f.queue::add,()->{});store.fail=true;
        var saved=journal.hud(ctx,new Object(),()->true,false);f.pump();assertTrue(saved.toCompletableFuture().isCompletedExceptionally());assertTrue(store.get(ctx).preferences().hudEnabled());assertTrue(f.store.rows.isEmpty());assertFalse(journal.settled(f.a));
    }
    @Test void noticeReadAndPlaceholderScreensDoNotInventStoryData(){
        var c=content(false);var notice=new QuestJournalStore.Notice("survey_marker",0,"STARTED",java.time.Instant.EPOCH,false);
        var view=new QuestJournal.View(Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.ACTIVE,0)),new QuestJournalStore.Journal(Set.of(),new QuestJournalStore.Preferences(true,-1),List.of(notice)));
        assertTrue(QuestScreens.screen(c,view,"notices",null,0).stream().anyMatch(b->b.label().contains("미확인 1개")));
        for(String page:List.of("map","diary","community"))assertTrue(QuestScreens.screen(c,view,page,null,0).stream().anyMatch(b->b.label().startsWith("미구현")));
    }
}
