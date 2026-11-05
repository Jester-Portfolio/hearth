package io.github.jesterportfolio.hearth.combat;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 기존 보스 도전 한 건의 저장→온라인 참가자 복귀→교체. 인벤토리/월드 초기화 체계는 만들지 않습니다. */
public final class CombatRestart implements AutoCloseable {
    public enum Phase{IDLE,SAVING,PREPARING,RESETTING,STARTING,STARTED,HOLD}
    private final CombatAttempt previous;private final CombatContent.Retry policy;private final List<CombatPlayers.PlayerPort> players;
    private final Supplier<CompletionStage<Void>> saving,resetting;private final Function<List<CombatAttempt.Entrant>,CombatAttempt> replace;
    private final Executor owner;private final Runnable check;private final BooleanSupplier scope;
    private CombatAttempt.Outcome sourceOutcome;private CombatPlayers.PlayerPort applying;private CombatRetryVote.Receipt request;private CompletableFuture<CombatAttempt> result;private Phase phase=Phase.IDLE;private boolean closed;private volatile boolean deliveryFailed;
    public CombatRestart(CombatAttempt previous,CombatContent.Retry policy,List<CombatPlayers.PlayerPort> players,Supplier<CompletionStage<Void>> saving,Function<List<CombatAttempt.Entrant>,CombatAttempt> replace,Executor owner,Runnable check,BooleanSupplier scope){
        this(previous,policy,players,saving,()->CompletableFuture.completedFuture(null),replace,owner,check,scope);
        if(policy.scope().equals("FROM_PATH"))throw new IllegalArgumentException("Path reset backend required");
    }
    public CombatRestart(CombatAttempt previous,CombatContent.Retry policy,List<CombatPlayers.PlayerPort> players,Supplier<CompletionStage<Void>> saving,Supplier<CompletionStage<Void>> resetting,Function<List<CombatAttempt.Entrant>,CombatAttempt> replace,Executor owner,Runnable check,BooleanSupplier scope){
        this.resetting=Objects.requireNonNull(resetting);
        this.previous=Objects.requireNonNull(previous);this.policy=Objects.requireNonNull(policy);this.players=List.copyOf(players);this.saving=saving;this.replace=replace;this.owner=owner;this.check=check;this.scope=scope;
        var ids=new HashSet<UUID>();var tokens=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());var before=previous.snapshot();
        if(players.isEmpty()||players.size()>32||players.stream().anyMatch(p->p.session()==null||!p.context().storyRunId().equals(before.run())||!ids.add(p.context().playerId())||!tokens.add(p.session())))throw new IllegalArgumentException("Invalid retry roster");
    }
    private boolean current(){check.run();return !closed&&!deliveryFailed&&phase!=Phase.HOLD&&phase!=Phase.STARTED&&(result==null||!result.isDone())&&scope.getAsBoolean()&&players.stream().allMatch(p->p.current()&&p.position().world().equals(previous.worldId()));}
    private void require(){if(!current())throw new IllegalStateException("COMBAT_RESTART_STALE");}
    public CompletionStage<CombatAttempt> begin(CombatRetryVote.Receipt receipt){return begin(receipt,CombatAttempt.Outcome.RETRY);}
    public CompletionStage<CombatAttempt> beginPathFailure(UUID request){
        check.run();if(!policy.scope().equals("FROM_PATH"))return CompletableFuture.failedFuture(new IllegalStateException("PATH_RETRY_POLICY_REQUIRED"));
        return begin(new CombatRetryVote.Receipt(previous.snapshot().run(),previous.snapshot().attempt(),request),CombatAttempt.Outcome.REQUIRED_FAILURE);
    }
    private CompletionStage<CombatAttempt> begin(CombatRetryVote.Receipt receipt,CombatAttempt.Outcome expected){
        check.run();if(request!=null)return request.equals(receipt)&&sourceOutcome==expected?result:CompletableFuture.failedFuture(new IllegalStateException("COMBAT_RESTART_REQUEST_CONFLICT"));
        var state=previous.snapshot();if(receipt==null||receipt.request()==null||!state.run().equals(receipt.run())||!state.attempt().equals(receipt.attempt())||state.phase()!=CombatAttempt.Phase.CLOSED||state.outcome()!=expected||state.error()!=null)return CompletableFuture.failedFuture(new IllegalStateException("COMBAT_RESTART_NOT_CONFIRMED"));
        sourceOutcome=expected;request=receipt;result=new CompletableFuture<>();phase=Phase.SAVING;
        try{
        CompletionStage<Void> work=CompletableFuture.completedFuture(null).thenComposeAsync(v->{require();return Objects.requireNonNull(saving.get());},owner);
        for(var player:players)work=work.thenComposeAsync(v->{
            require();phase=Phase.PREPARING;applying=player;var pose=policy.pose(previous.worldId());if(!player.safe(pose))throw new IllegalStateException("COMBAT_RESTART_UNSAFE_ENTRY");
            return Objects.requireNonNull(player.restore(pose,policy.healthFraction(),policy.effects(),()->applying==player&&phase==Phase.PREPARING&&current())).thenAcceptAsync(ok->{require();if(!Boolean.TRUE.equals(ok)||!CombatPlayers.samePosition(pose,player.position()))throw new IllegalStateException("COMBAT_RESTART_MOVE_FAILED");applying=null;},owner);
        },owner);
        work=work.thenComposeAsync(v->{require();phase=Phase.RESETTING;return Objects.requireNonNull(resetting.get());},owner);
        work.thenApplyAsync(v->{
            require();phase=Phase.STARTING;var roster=players.stream().map(p->new CombatAttempt.Entrant(p.context(),p.session(),p.position())).toList();var next=Objects.requireNonNull(replace.apply(roster));var fresh=next.snapshot();
            if(fresh.attempt().equals(state.attempt())||!next.worldId().equals(previous.worldId())||roster.stream().anyMatch(e->next.onlineSessions().get(e.context().playerId())!=e.session())||!fresh.run().equals(state.run())||fresh.generation()!=state.generation()||fresh.phase()!=CombatAttempt.Phase.ACTIVE||fresh.difficultyPlayers()!=players.size()||fresh.sharedUsed()!=0||fresh.reserved()!=0||!fresh.members().stream().map(CombatAttempt.Member::player).collect(java.util.stream.Collectors.toSet()).equals(roster.stream().map(e->e.context().playerId()).collect(java.util.stream.Collectors.toSet()))||fresh.members().stream().anyMatch(m->!m.online()||m.dead()||m.returning()||m.reviving()||m.used()!=0)){
                next.invalidate();throw new IllegalStateException("COMBAT_RESTART_INVALID_RESULT");
            }
            phase=Phase.STARTED;return next;
        },owner).whenCompleteAsync((next,error)->{if(result.isDone())return;if(error!=null){phase=Phase.HOLD;result.completeExceptionally(error);}else result.complete(next);},owner).exceptionally(error->{deliveryFailed=true;result.completeExceptionally(error);return null;});
        }catch(RuntimeException error){deliveryFailed=true;phase=Phase.HOLD;result.completeExceptionally(error);}
        return result;
    }
    public Phase phase(){check.run();return deliveryFailed?Phase.HOLD:phase;}
    public List<String> lines(){return List.of("다음 도전 준비: "+switch(phase()){case IDLE->"대기";case SAVING->"기존 데이터 저장 확인 중";case PREPARING->"참가자 복귀 적용 중";case RESETTING->"등록된 관리 구역 초기화 확인 중";case STARTING->"새 도전 명단·전투 확정 중";case STARTED->"새 도전 시작 확인";case HOLD->"처리 보류 · 자동 재실행 없음";});}
    public CompletionStage<Void> barrier(){check.run();if(phase()==Phase.HOLD)return CompletableFuture.failedFuture(new IllegalStateException("COMBAT_RESTART_HOLD"));return result==null?CompletableFuture.completedFuture(null):result.thenAccept(v->{});}
    @Override public void close(){check.run();if(phase==Phase.STARTED)return;closed=true;if(result!=null&&!result.isDone()){phase=Phase.HOLD;result.completeExceptionally(new IllegalStateException("COMBAT_RESTART_CLOSED"));}}
}
