package io.github.jesterportfolio.hearth.combat;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 종료 결과를 보존한 채 기존 저장 장벽과 Player 적용기로 한 참가자를 복귀시킵니다. */
public final class CombatReturn implements AutoCloseable {
    public enum Phase { IDLE,SAVING,MOVING,DONE,HOLD }
    private final CombatAttempt attempt;private final CombatDepartures departures;private final CombatDepartures.Claim claim;private final UUID run,world;private final CombatContent.Exit policy;private final CombatPlayers.PlayerPort player;
    private final Supplier<CompletionStage<Void>> saving;private final Runnable cleanup,check;private final Executor owner;private final BooleanSupplier scope;
    private CompletableFuture<Void> result;private Phase phase=Phase.IDLE;private boolean closed;private volatile boolean deliveryFailed;
    public CombatReturn(CombatAttempt attempt,CombatContent.Exit policy,CombatPlayers.PlayerPort player,Supplier<CompletionStage<Void>> saving,Runnable cleanup,Executor owner,Runnable check,BooleanSupplier scope){
        this.attempt=Objects.requireNonNull(attempt);this.departures=null;this.claim=null;this.run=attempt.snapshot().run();this.world=attempt.worldId();this.policy=Objects.requireNonNull(policy);this.player=Objects.requireNonNull(player);this.saving=Objects.requireNonNull(saving);this.cleanup=Objects.requireNonNull(cleanup);this.owner=owner;this.check=check;this.scope=scope;
    }
    public CombatReturn(CombatDepartures departures,CombatDepartures.Claim claim,CombatPlayers.PlayerPort player,Supplier<CompletionStage<Void>> saving,Runnable cleanup,Executor owner,Runnable check,BooleanSupplier scope){
        this.attempt=null;this.departures=Objects.requireNonNull(departures);this.claim=Objects.requireNonNull(claim);var ticket=claim.ticket();this.run=ticket.run();this.world=ticket.world();this.policy=ticket.policy();this.player=Objects.requireNonNull(player);this.saving=Objects.requireNonNull(saving);this.cleanup=Objects.requireNonNull(cleanup);this.owner=owner;this.check=check;this.scope=scope;
    }
    public static boolean ended(CombatAttempt.Snapshot s){return s.phase()==CombatAttempt.Phase.CLOSED&&s.error()==null&&Set.of(CombatAttempt.Outcome.VICTORY,CombatAttempt.Outcome.REQUIRED_FAILURE,CombatAttempt.Outcome.ALL_OFFLINE).contains(s.outcome());}
    private boolean authorized(){return attempt!=null?ended(attempt.snapshot()):departures.current(claim,player.context(),player.session());}
    private boolean current(){check.run();return !closed&&!deliveryFailed&&phase!=Phase.DONE&&phase!=Phase.HOLD&&scope.getAsBoolean()&&player.current()&&player.context().storyRunId().equals(run)&&player.position().world().equals(world)&&authorized();}
    private void require(){if(!current())throw new IllegalStateException("COMBAT_RETURN_STALE");if(attempt!=null)attempt.view(player.context(),player.session());}
    public CompletionStage<Void> begin(){
        check.run();if(result!=null)return result;
        if(!authorized())return CompletableFuture.failedFuture(new IllegalStateException("COMBAT_RETURN_NOT_ENDED"));
        result=new CompletableFuture<>();phase=Phase.SAVING;
        try{
            CompletableFuture.completedFuture(null).thenComposeAsync(v->{require();return Objects.requireNonNull(saving.get());},owner)
            .thenComposeAsync(v->{require();var pose=policy.pose(world);if(!player.safe(pose))throw new IllegalStateException("COMBAT_RETURN_UNSAFE");phase=Phase.MOVING;
                return Objects.requireNonNull(player.restore(pose,policy.healthFraction(),policy.effects(),()->phase==Phase.MOVING&&current()));
            },owner).thenAcceptAsync(ok->{require();if(!Boolean.TRUE.equals(ok)||!CombatPlayers.samePosition(policy.pose(world),player.position()))throw new IllegalStateException("COMBAT_RETURN_MOVE_FAILED");
                cleanup.run();phase=Phase.DONE;
            },owner).whenCompleteAsync((v,error)->{if(result.isDone())return;if(error!=null){phase=Phase.HOLD;result.completeExceptionally(error);}else result.complete(null);},owner)
            .exceptionally(error->{deliveryFailed=true;result.completeExceptionally(error);return null;});
        }catch(RuntimeException error){phase=Phase.HOLD;result.completeExceptionally(error);}
        return result;
    }
    public Phase phase(){check.run();return deliveryFailed?Phase.HOLD:phase;}
    public CompletionStage<Void> barrier(){check.run();return result==null?CompletableFuture.completedFuture(null):result;}
    @Override public void close(){check.run();closed=true;if(result!=null&&!result.isDone()){phase=Phase.HOLD;result.completeExceptionally(new IllegalStateException("COMBAT_RETURN_CLOSED"));}}
}
