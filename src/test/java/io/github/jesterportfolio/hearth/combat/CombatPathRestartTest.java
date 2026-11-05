package io.github.jesterportfolio.hearth.combat;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.jesterportfolio.hearth.combat.CombatContent.*;

final class CombatPathRestartTest {
    static final class Fixture {
        final CombatPlayersTest.Fixture f=new CombatPlayersTest.Fixture();final CompletableFuture<Void> saved=new CompletableFuture<>(),reset=new CompletableFuture<>();final AtomicInteger resets=new AtomicInteger(),starts=new AtomicInteger();
        final FieldReset plan=new FieldReset(FieldReset.Mode.AFTER_CHECKPOINT,List.of("first","next"),"first");
        final Retry policy=new Retry("FROM_PATH",new FieldContent.Point(7,64,8),90,0,0.5,List.of(),plan);final UUID request=UUID.randomUUID();final CombatRestart job;
        Fixture(boolean failed){f.fight.finish(failed?CombatAttempt.Outcome.REQUIRED_FAILURE:CombatAttempt.Outcome.RETRY);job=new CombatRestart(f.fight,policy,List.of(f.p,f.q),()->saved,()->{resets.incrementAndGet();return reset;},roster->{starts.incrementAndGet();return new CombatAttempt(f.run,f.world,1,CombatAttemptTest.content(Pool.SHARED,1,LocationMode.START),roster,new CombatAttemptTest.Actors(),f.owner,()->{},f.clock::get);},f.owner,()->{},()->f.generation.get()==1);}
        CompletionStage<CombatAttempt> begin(boolean failed){return failed?job.beginPathFailure(request):job.begin(new CombatRetryVote.Receipt(f.run,f.fight.snapshot().attempt(),request));}
        void save(){saved.complete(null);f.pump();}void moves(){f.p.applying.complete(true);f.pump();f.q.applying.complete(true);f.pump();}
    }
    @Test void pathFailureAndBossVoteBothSaveMoveResetThenStartOnce(){for(boolean failed:List.of(true,false)){var x=new Fixture(failed);var outcome=x.f.fight.snapshot().outcome();var work=x.begin(failed);assertSame(work,x.begin(failed));x.f.pump();assertEquals(0,x.f.p.restores);x.save();assertEquals(0,x.resets.get());x.f.p.applying.complete(true);x.f.pump();assertEquals(0,x.resets.get());x.f.q.applying.complete(true);x.f.pump();assertEquals(CombatRestart.Phase.RESETTING,x.job.phase());assertEquals(1,x.resets.get());assertEquals(0,x.starts.get());assertFalse(x.f.p.applyGuard.getAsBoolean());assertFalse(x.f.q.applyGuard.getAsBoolean());x.reset.complete(null);x.f.pump();assertEquals(CombatRestart.Phase.STARTED,x.job.phase());assertEquals(1,x.starts.get());assertEquals(0,work.toCompletableFuture().join().snapshot().sharedUsed());assertEquals(outcome,x.f.fight.snapshot().outcome());}}
    @Test void saveMoveAndResetFailuresNeverCreateNewPath(){for(int kind=0;kind<3;kind++){var x=new Fixture(true);var work=x.begin(true);if(kind==0){x.saved.completeExceptionally(new IllegalStateException("DB_FAILED"));x.f.pump();assertEquals(0,x.resets.get());}else{x.save();if(kind==1){x.f.p.applying.complete(false);x.f.pump();assertEquals(0,x.resets.get());}else{x.moves();x.reset.completeExceptionally(new IllegalStateException("RESET_FAILED"));x.f.pump();}}assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertEquals(CombatRestart.Phase.HOLD,x.job.phase());assertEquals(0,x.starts.get());}}
    @Test void generationAndShutdownDuringResetRejectLateCompletion(){for(boolean closed:List.of(true,false)){var x=new Fixture(true);var work=x.begin(true);x.save();x.moves();if(closed)x.job.close();else x.f.generation.incrementAndGet();x.reset.complete(null);x.f.pump();assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertEquals(0,x.starts.get());}}
    @Test void wrongEntryPointAndRequestCannotReuseConfirmation(){var x=new Fixture(true);var work=x.begin(true);assertTrue(x.job.begin(new CombatRetryVote.Receipt(x.f.run,x.f.fight.snapshot().attempt(),x.request)).toCompletableFuture().isCompletedExceptionally());assertTrue(x.job.beginPathFailure(UUID.randomUUID()).toCompletableFuture().isCompletedExceptionally());x.save();x.moves();x.reset.complete(null);x.f.pump();assertSame(work,x.begin(true));}
    @Test void missingResetScopeOrPathContentIsRejected(){var x=new Fixture(true);assertThrows(IllegalArgumentException.class,()->new Retry("FROM_PATH",new FieldContent.Point(0,64,0),0,0,0.5,List.of()));assertThrows(IllegalArgumentException.class,()->new Retry("BOSS_ONLY",new FieldContent.Point(0,64,0),0,0,0.5,List.of(),x.plan));assertThrows(IllegalArgumentException.class,()->new CombatRestart(x.f.fight,x.policy,List.of(x.f.p),()->x.saved,r->x.f.fight,x.f.owner,()->{},()->true));var base=CombatAttemptTest.content(Pool.SHARED,1,LocationMode.START);assertThrows(IllegalArgumentException.class,()->new CombatContent(base.id(),base.title(),20,2,3000,base.revival(),x.policy));}
}
