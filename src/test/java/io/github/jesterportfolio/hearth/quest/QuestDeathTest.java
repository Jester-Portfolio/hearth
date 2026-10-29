package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 일반 조작 권한과 서버 확정 사망 사건을 구분하는 제어 경계 시험. 실제 Player가 아닙니다. */
final class QuestDeathTest {
    static final class Fixture {
        final QuestCompletionTest.Store store=new QuestCompletionTest.Store();
        final ArrayDeque<Runnable> queue=new ArrayDeque<>();final QuestCatalog catalog=QuestBatchTest.content(false);
        final PersonalQuest quests=new PersonalQuest(store,catalog.questIds(),queue::add,()->{});
        final ServerProgressAccess access=new ServerProgressAccess();final UUID run=UUID.randomUUID(),player=UUID.randomUUID();
        final ServerProgressAccess.Context ctx=access.forVerifiedServerLink(run,player);final Object token=new Object();
        boolean connected=true,actions=true;
        Fixture(QuestRules.Restart restart){quests.rules(new QuestRules(Map.of("survey_marker",new QuestRules.Rule(restart,Set.of("DEATH"),restart==QuestRules.Restart.CHECKPOINT?List.of(1):List.of(),0,null)),Map.of()));}
        void pump(){int n=500;while(!queue.isEmpty()){assertTrue(n-->0);queue.remove().run();}}
        void seed(ServerProgressAccess.Context c,String id,QuestStore.Phase phase,long revision,int index){store.rows.put(store.key(c,id),new QuestStore.Saved(phase,revision,index));}
        PersonalQuest.Session open(){var f=quests.open(ctx,"survey_marker",token,()->actions&&connected);pump();return f.toCompletableFuture().join();}
        CompletableFuture<Void> die(){var f=quests.died(ctx,token,()->connected,()->actions&&connected,catalog).toCompletableFuture();pump();return f;}
        void commit(){store.commit.getLast().run();pump();}
    }
    @Test void deathWithoutOpenQuestLoadsPersistedStateForAllRestartPolicies(){for(var mode:QuestRules.Restart.values()){
        var f=new Fixture(mode);f.seed(f.ctx,"survey_marker",QuestStore.Phase.ACTIVE,4,2);f.actions=false;
        var death=f.die();assertFalse(death.isDone());assertFalse(f.quests.barrier(f.run).toCompletableFuture().isDone());
        assertTrue(f.quests.list(f.ctx,f.token,()->true).toCompletableFuture().isCompletedExceptionally());f.commit();death.join();
        var saved=f.store.rows.get(f.store.key(f.ctx));assertEquals(QuestStore.Phase.FAILED,saved.phase());assertEquals(5,saved.revision());
        assertEquals(switch(mode){case KEEP->2;case CHECKPOINT->1;case BEGINNING->0;},saved.objectiveIndex());
        assertTrue(f.quests.settled(f.run));assertEquals(1,f.store.pending.size());
    }}
    @Test void duplicateCoalescesAndDoesNotUnlockOrdinaryActions(){var f=new Fixture(QuestRules.Restart.KEEP);f.seed(f.ctx,"survey_marker",QuestStore.Phase.ACTIVE,0,0);var q=f.open();f.actions=false;
        var death=f.die();assertSame(death,f.quests.died(f.ctx,f.token,()->true,()->false,f.catalog));
        assertTrue(f.quests.reach(q,f.catalog.quest("survey_marker"),13,64,13).toCompletableFuture().isCompletedExceptionally());f.commit();death.join();
        f.die().join();assertEquals(1,f.store.pending.size());f.actions=true;f.quests.retry(q,f.catalog.quest("survey_marker"));f.commit();assertEquals(QuestStore.Phase.ACTIVE,q.saved().phase());assertEquals(2,q.saved().revision());
    }
    @Test void inflightObjectiveCommitDrainsAndFreshRevisionIsUsedWhileDead(){var f=new Fixture(QuestRules.Restart.KEEP);f.seed(f.ctx,"survey_marker",QuestStore.Phase.ACTIVE,0,0);var q=f.open();
        var step=f.quests.reach(q,f.catalog.quest("survey_marker"),13,64,13);f.actions=false;var death=f.die();assertEquals(1,f.store.pending.size());f.commit();
        assertTrue(step.toCompletableFuture().isCompletedExceptionally());assertFalse(death.isDone());assertEquals(2,f.store.pending.size());f.commit();death.join();assertEquals(2,q.saved().revision());assertEquals(1,q.saved().objectiveIndex());
    }
    @Test void acceptedDeathPersistsAfterQuitButNewSessionCannotReplacePendingWork(){var f=new Fixture(QuestRules.Restart.KEEP);f.seed(f.ctx,"survey_marker",QuestStore.Phase.ACTIVE,0,0);f.actions=false;var death=f.die();f.connected=false;
        assertTrue(f.quests.open(f.ctx,"survey_marker",new Object(),()->true).toCompletableFuture().isCompletedExceptionally());f.commit();death.join();
        var restored=f.quests.open(f.ctx,"survey_marker",new Object(),()->true);f.pump();assertEquals(QuestStore.Phase.FAILED,restored.toCompletableFuture().join().saved().phase());
    }
    @Test void dbFailureHoldsOriginalAndOtherPlayerRunAndUnrelatedQuestRemainUnchanged(){var f=new Fixture(QuestRules.Restart.BEGINNING);f.seed(f.ctx,"survey_marker",QuestStore.Phase.ACTIVE,3,2);
        var other=f.access.forVerifiedServerLink(f.run,UUID.randomUUID());var otherRun=f.access.forVerifiedServerLink(UUID.randomUUID(),f.player);f.seed(other,"survey_marker",QuestStore.Phase.ACTIVE,7,1);f.seed(otherRun,"survey_marker",QuestStore.Phase.ACTIVE,8,1);f.seed(f.ctx,"path_marker",QuestStore.Phase.ACTIVE,6,0);var before=Map.copyOf(f.store.rows);
        f.actions=false;var death=f.die();f.store.pending.getLast().completeExceptionally(new StorageFailure("quest","08006"));f.pump();assertTrue(death.isCompletedExceptionally());assertEquals(before,f.store.rows);assertFalse(f.quests.settled(f.run));assertTrue(f.quests.barrier(f.run).toCompletableFuture().isCompletedExceptionally());
        var healthy=f.quests.list(otherRun,new Object(),()->true);f.pump();assertFalse(healthy.toCompletableFuture().isCompletedExceptionally());
    }
    @Test void terminalAndUnacceptedRowsAreNotFailed(){for(var phase:List.of(QuestStore.Phase.NOT_ACCEPTED,QuestStore.Phase.REPORT_READY,QuestStore.Phase.COMPLETED,QuestStore.Phase.SKIPPED,QuestStore.Phase.FAILED)){
        var f=new Fixture(QuestRules.Restart.KEEP);if(phase!=QuestStore.Phase.NOT_ACCEPTED)f.seed(f.ctx,"survey_marker",phase,3,0);f.actions=false;f.die().join();assertTrue(f.store.pending.isEmpty());
    }}
    @Test void invalidConnectionAndCompetingTokenCannotSubmitDeath(){var f=new Fixture(QuestRules.Restart.KEEP);f.connected=false;assertTrue(f.die().isCompletedExceptionally());assertEquals(0,f.store.reads);f.connected=true;f.seed(f.ctx,"survey_marker",QuestStore.Phase.ACTIVE,0,0);var death=f.die();assertTrue(f.quests.died(f.ctx,new Object(),()->true,()->true,f.catalog).toCompletableFuture().isCompletedExceptionally());f.commit();death.join();}
    @Test void shutdownDoesNotApplyLateResultOrReportSuccessfulDrain(){var f=new Fixture(QuestRules.Restart.KEEP);f.seed(f.ctx,"survey_marker",QuestStore.Phase.ACTIVE,0,0);var q=f.open();f.actions=false;var death=f.die();var shutdown=f.quests.shutdown();f.commit();assertTrue(death.isCompletedExceptionally());assertTrue(shutdown.toCompletableFuture().isCompletedExceptionally());assertEquals(QuestStore.Phase.ACTIVE,q.saved().phase());assertEquals(QuestStore.Phase.FAILED,f.store.rows.get(f.store.key(f.ctx)).phase());}
}
