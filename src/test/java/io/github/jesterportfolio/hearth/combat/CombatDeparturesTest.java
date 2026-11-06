package io.github.jesterportfolio.hearth.combat;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CombatDeparturesTest {
    static final class Fixture {
        final CombatPlayersTest.Fixture f=new CombatPlayersTest.Fixture();
        final CombatContent.Exit exit=new CombatContent.Exit(new FieldContent.Point(7,64,8),31,12,0.5,List.of());
        final CombatDepartures ledger;final CombatPlayersTest.Body fresh;final CombatDepartures.Claim claim;
        final CompletableFuture<Void> saved=new CompletableFuture<>();final AtomicInteger cleaned=new AtomicInteger();boolean valid=true;
        Fixture(boolean allOffline){
            f.bridge.died(f.p);f.bridge.leave(f.p);if(allOffline)f.bridge.leave(f.q);else f.fight.victory();
            ledger=new CombatDepartures(f.fight.snapshot(),f.world,exit,Map.of(f.p.ctx.playerId(),"SURVIVAL",f.q.ctx.playerId(),"ADVENTURE"));f.bridge.close();
            fresh=new CombatPlayersTest.Body(f.p.ctx,f.p.position);claim=ledger.claim(fresh.ctx,fresh.token);
        }
        CombatReturn job(){return new CombatReturn(ledger,claim,fresh,()->saved,()->{fresh.cleanup();ledger.complete(claim);cleaned.incrementAndGet();},f.owner,()->{},()->valid);}
        void pump(){f.pump();}
    }
    @Test void disconnectedDeadMemberReturnsWithoutReopeningOrChangingConfirmedBattle(){
        for(boolean offline:List.of(false,true)){var x=new Fixture(offline);var state=x.f.fight.snapshot();var inventory=x.fresh.inventory.clone();var job=x.job();var work=job.begin();assertSame(work,job.begin());x.pump();assertEquals(0,x.fresh.restores);assertFalse(job.barrier().toCompletableFuture().isDone());
            x.saved.complete(null);x.pump();assertEquals(1,x.fresh.restores);x.fresh.applying.complete(true);x.pump();assertEquals(CombatReturn.Phase.DONE,job.phase());assertEquals(x.exit.pose(x.f.world),x.fresh.position);assertEquals(0.5,x.fresh.health);assertArrayEquals(inventory,x.fresh.inventory);assertEquals(state,x.f.fight.snapshot());assertEquals(1,x.cleaned.get());assertFalse(x.ledger.pending(x.fresh.ctx));assertTrue(x.ledger.pending(x.f.q.ctx));assertFalse(x.ledger.empty());assertFalse(x.fresh.applyGuard.getAsBoolean());assertThrows(IllegalStateException.class,()->x.ledger.claim(x.fresh.ctx,new Object()));}
    }
    @Test void claimsArePerPlayerAndRejectDuplicateSessionWrongRunAndStrangers(){
        var x=new Fixture(false);assertThrows(IllegalStateException.class,()->x.ledger.claim(x.fresh.ctx,x.fresh.token));assertThrows(IllegalStateException.class,()->x.ledger.claim(x.fresh.ctx,new Object()));
        var wrong=x.f.access.forVerifiedServerLink(UUID.randomUUID(),x.fresh.ctx.playerId());assertFalse(x.ledger.pending(wrong));assertThrows(IllegalStateException.class,()->x.ledger.claim(wrong,new Object()));
        var unknown=x.f.access.forVerifiedServerLink(x.f.run,UUID.randomUUID());assertThrows(IllegalStateException.class,()->x.ledger.claim(unknown,new Object()));assertFalse(x.ledger.current(x.claim,x.fresh.ctx,new Object()));
        var other=x.ledger.claim(x.f.q.ctx,new Object());x.ledger.complete(other);assertTrue(x.ledger.current(x.claim,x.fresh.ctx,x.fresh.token));assertThrows(IllegalStateException.class,()->x.ledger.complete(other));
    }
    @Test void failedSaveRetainsPendingTicketAndNeverMovesOrClaimsSuccess(){
        var x=new Fixture(false);var job=x.job();var work=job.begin();x.saved.completeExceptionally(new IllegalStateException("DB_FAILED"));x.pump();x.ledger.hold(x.claim);
        assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertEquals(CombatReturn.Phase.HOLD,job.phase());assertEquals(0,x.fresh.restores);assertEquals(0,x.cleaned.get());assertTrue(x.ledger.pending(x.fresh.ctx));assertThrows(IllegalStateException.class,()->x.ledger.claim(x.fresh.ctx,new Object()));assertThrows(IllegalStateException.class,()->x.ledger.complete(x.claim));
    }
    @Test void staleSessionGenerationMoveFailureAndCloseNeverConsumeTicket(){
        for(int kind=0;kind<4;kind++){var x=new Fixture(false);var job=x.job();var work=job.begin();x.saved.complete(null);x.pump();if(kind==0)x.fresh.live=false;else if(kind==1)x.valid=false;else if(kind==2)job.close();
            x.fresh.applying.complete(kind!=3);x.pump();assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertEquals(0,x.cleaned.get());assertTrue(x.ledger.pending(x.fresh.ctx));assertEquals(CombatReturn.Phase.HOLD,job.phase());assertFalse(x.fresh.applyGuard.getAsBoolean());}
    }
    @Test void sameWorldNewGenerationUsesNewGuardButWrongWorldIsRejected(){
        var x=new Fixture(true);x.f.generation.incrementAndGet();var job=x.job();job.begin();x.saved.complete(null);x.pump();x.fresh.applying.complete(true);x.pump();assertEquals(CombatReturn.Phase.DONE,job.phase());
        var y=new Fixture(true);y.fresh.position=new CombatContent.Pose(UUID.randomUUID(),3,64,4,0,0);var wrong=y.job();var work=wrong.begin();y.saved.complete(null);y.pump();assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertEquals(0,y.fresh.restores);assertTrue(y.ledger.pending(y.fresh.ctx));
    }
    @Test void activeRetryRetiredHeldAndIncompleteModeMetadataCannotIssueTickets(){
        var f=new CombatPlayersTest.Fixture();var exit=new Fixture(false).exit;var modes=Map.of(f.p.ctx.playerId(),"SURVIVAL",f.q.ctx.playerId(),"ADVENTURE");assertThrows(IllegalArgumentException.class,()->new CombatDepartures(f.fight.snapshot(),f.world,exit,modes));
        for(var outcome:List.of(CombatAttempt.Outcome.RETRY,CombatAttempt.Outcome.RETIRED)){var other=new CombatPlayersTest.Fixture();other.fight.finish(outcome);assertThrows(IllegalArgumentException.class,()->new CombatDepartures(other.fight.snapshot(),other.world,exit,Map.of(other.p.ctx.playerId(),"SURVIVAL",other.q.ctx.playerId(),"SURVIVAL")));}
        f.fight.victory();assertThrows(IllegalArgumentException.class,()->new CombatDepartures(f.fight.snapshot(),f.world,exit,Map.of(f.p.ctx.playerId(),"SURVIVAL")));assertThrows(IllegalArgumentException.class,()->new CombatDepartures(f.fight.snapshot(),f.world,exit,Map.of(f.p.ctx.playerId(),"SPECTATOR",f.q.ctx.playerId(),"SURVIVAL")));
    }
    @Test void ownerDeliveryFailureKeepsTicketAndInvalidatesApplyGuard(){
        var x=new Fixture(false);x.f.reject.set(true);var job=x.job();var work=job.begin();assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertEquals(CombatReturn.Phase.HOLD,job.phase());assertTrue(x.ledger.pending(x.fresh.ctx));assertEquals(0,x.fresh.restores);
    }    @Test void cleanupWaitsForExistingWorkAndRejectsFailedOrCancelledBarrier(){
        var pending=new CompletableFuture<Void>();assertFalse(CombatDepartures.settled(List.of(pending)));pending.complete(null);assertTrue(CombatDepartures.settled(List.of(pending)));assertThrows(IllegalStateException.class,()->CombatDepartures.settled(List.of(CompletableFuture.failedFuture(new IllegalStateException("SAVE_FAILED")))));var cancelled=new CompletableFuture<Void>();cancelled.cancel(false);assertThrows(IllegalStateException.class,()->CombatDepartures.settled(List.of(cancelled)));
    }
    @Test void pendingOldMemberAndNewAttemptForReturnedMemberHaveIndependentReceipts(){
        var x=new Fixture(false);var returned=x.ledger.claim(x.f.q.ctx,new Object());x.ledger.complete(returned);var state=x.f.fight.snapshot();var next=new CombatAttempt.Snapshot(UUID.randomUUID(),state.run(),state.generation(),0,CombatAttempt.Phase.CLOSED,CombatAttempt.Outcome.VICTORY,1,0,0,0,null,List.of(state.members().get(1)));
        var another=new CombatDepartures(next,x.f.world,x.exit,Map.of(x.f.q.ctx.playerId(),"ADVENTURE"));assertFalse(x.ledger.overlaps(another));assertTrue(x.ledger.current(x.claim,x.fresh.ctx,x.fresh.token));var freshToken=new Object();var fresh=another.claim(x.f.q.ctx,freshToken);another.complete(fresh);assertTrue(x.ledger.pending(x.fresh.ctx));assertTrue(another.empty());assertTrue(x.ledger.overlaps(x.ledger));
    }

}
