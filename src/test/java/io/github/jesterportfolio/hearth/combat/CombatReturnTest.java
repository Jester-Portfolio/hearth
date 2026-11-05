package io.github.jesterportfolio.hearth.combat;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CombatReturnTest {
    static final class Fixture {
        final CombatPlayersTest.Fixture f=new CombatPlayersTest.Fixture();final CompletableFuture<Void> saved=new CompletableFuture<>();final AtomicInteger cleaned=new AtomicInteger();boolean valid=true;
        final CombatContent.Exit policy=new CombatContent.Exit(new FieldContent.Point(7,64,8),31,12,0.5,List.of());
        final CombatReturn job;
        Fixture(){f.bridge.died(f.p);f.fight.victory();job=new CombatReturn(f.fight,policy,f.p,()->saved,()->{f.p.cleanup();cleaned.incrementAndGet();},f.owner,()->{},()->valid&&f.generation.get()==1);}
        void pump(){f.pump();}void save(){saved.complete(null);pump();}void apply(){f.p.applying.complete(true);pump();}
    }
    @Test void savesThenReturnsDeadParticipantAndCleansExactlyOnce(){var x=new Fixture();var before=x.f.p.inventory.clone();var work=x.job.begin();assertSame(work,x.job.begin());x.pump();assertEquals(0,x.f.p.restores);assertFalse(x.job.barrier().toCompletableFuture().isDone());x.save();assertEquals(1,x.f.p.restores);assertTrue(x.f.p.applyGuard.getAsBoolean());x.apply();assertEquals(CombatReturn.Phase.DONE,x.job.phase());assertEquals(1,x.cleaned.get());assertFalse(x.f.p.applyGuard.getAsBoolean());assertEquals(x.policy.pose(x.f.world),x.f.p.position);assertArrayEquals(before,x.f.p.inventory);assertEquals(CombatAttempt.Outcome.VICTORY,x.f.fight.snapshot().outcome());assertFalse(work.toCompletableFuture().isCompletedExceptionally());}
    @Test void failedSaveNeverMovesOrCleans(){var x=new Fixture();var work=x.job.begin();x.saved.completeExceptionally(new IllegalStateException("DB_FAILED"));x.pump();assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertEquals(CombatReturn.Phase.HOLD,x.job.phase());assertEquals(0,x.f.p.restores);assertEquals(0,x.cleaned.get());}
    @Test void failedMoveAndUnsafeDestinationKeepOutcomeAndHold(){for(boolean unsafe:List.of(true,false)){var x=new Fixture();x.f.p.safe=!unsafe;var work=x.job.begin();x.save();if(!unsafe){x.f.p.applying.complete(false);x.pump();}assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertEquals(0,x.cleaned.get());assertEquals(CombatAttempt.Outcome.VICTORY,x.f.fight.snapshot().outcome());}}
    @Test void sessionWorldAndCloseChangesBlockLateApplication(){for(int kind=0;kind<3;kind++){var x=new Fixture();var work=x.job.begin();x.save();if(kind==0)x.f.p.live=false;else if(kind==1)x.f.generation.incrementAndGet();else x.job.close();assertFalse(x.f.p.applyGuard.getAsBoolean());x.apply();assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertEquals(0,x.cleaned.get());assertEquals(1,x.f.p.health);}}
    @Test void activeRetryRetiredAndHeldAttemptsCannotUseNormalExit(){for(var outcome:List.of(CombatAttempt.Outcome.NONE,CombatAttempt.Outcome.RETRY,CombatAttempt.Outcome.RETIRED)){var f=new CombatPlayersTest.Fixture();if(outcome!=CombatAttempt.Outcome.NONE)f.fight.finish(outcome);var x=new Fixture();var job=new CombatReturn(f.fight,x.policy,f.p,()->x.saved,()->fail("cleanup"),f.owner,()->{},()->true);assertTrue(job.begin().toCompletableFuture().isCompletedExceptionally());assertEquals(CombatReturn.Phase.IDLE,job.phase());}}
    @Test void wrongPlayerOwnerAndFailedCleanupNeverReportDone(){var x=new Fixture();x.valid=false;var work=x.job.begin();x.save();assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertEquals(0,x.f.p.restores);
        var y=new Fixture();var job=new CombatReturn(y.f.fight,y.policy,y.f.p,()->y.saved,()->{throw new IllegalStateException("CLEANUP_FAILED");},y.f.owner,()->{},()->true);work=job.begin();y.save();y.apply();assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertEquals(CombatReturn.Phase.HOLD,job.phase());}
    @Test void rejectedOwnerDoesNotMoveOrClaimSuccess(){var x=new Fixture();x.f.reject.set(true);var work=x.job.begin();assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertEquals(CombatReturn.Phase.HOLD,x.job.phase());assertEquals(0,x.f.p.restores);}
    @Test void foreignRunPortAndInvalidExitContentAreRejected(){
        var x=new Fixture();var other=new Fixture();var job=new CombatReturn(x.f.fight,x.policy,other.f.p,()->x.saved,()->fail("wrong cleanup"),x.f.owner,()->{},()->true);
        var work=job.begin();x.save();assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertEquals(0,other.f.p.restores);
        assertThrows(IllegalArgumentException.class,()->new CombatContent.Exit(new FieldContent.Point(0,64,0),0,0,0,List.of()));
        assertThrows(IllegalArgumentException.class,()->new CombatContent.Exit(null,0,0,0.5,List.of()));
    }

}
