package io.github.jesterportfolio.hearth.combat;

import io.github.jesterportfolio.hearth.persistence.ServerProgressAccess.Context;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 한 보스 도전의 채팅 운영 투표. 스토리 분기/DB 저장과 별개이며 새 도전을 생성하지 않습니다. */
public final class CombatRetryVote {
    public enum Phase { IDLE,OPEN,INPUT_CLOSED,UNDECIDED,CANCELLED,RETRY_REQUESTED,HOLD }
    public enum Choice { YES,NO,ABSTAIN }
    public record Receipt(UUID run,UUID attempt,UUID request){}
    public record View(UUID id,Phase phase,int targets,int yes,int no,int abstain,long remainingSeconds,Receipt receipt){}
    public record Input(UUID vote,Choice choice){}
    private final CombatAttempt attempt;private final Executor owner;private final Runnable check;private final LongSupplier clock;private final BooleanSupplier current;private final Consumer<String> notice;
    private final long duration;private Map<UUID,Object> roster=Map.of();private final Map<UUID,Choice> votes=new HashMap<>();private UUID id;private long deadline;private Phase phase=Phase.IDLE;
    private boolean preparationAvailable;
    public void preparationAvailable(boolean available){check.run();preparationAvailable=available;}
    private Receipt receipt;private CompletableFuture<View> decision;private volatile boolean deliveryFailed;private boolean closed;
    public CombatRetryVote(CombatAttempt attempt,Executor owner,Runnable check,LongSupplier clock,Duration limit,BooleanSupplier current,Consumer<String> notice){
        this.attempt=Objects.requireNonNull(attempt);this.owner=Objects.requireNonNull(owner);this.check=check;this.clock=clock;this.current=current;this.notice=notice;duration=limit.toNanos();if(duration<=0)throw new IllegalArgumentException("Invalid retry vote limit");
    }
    private void actor(Context ctx,Object token){check.run();if(closed||!current.getAsBoolean())throw new IllegalStateException("COMBAT_VOTE_STALE_SCOPE");attempt.view(ctx,token);}
    private boolean openAttempt(){var state=attempt.snapshot();return state.phase()!=CombatAttempt.Phase.CLOSED&&state.phase()!=CombatAttempt.Phase.HOLD;}
    private void announce(String text){try{notice.accept(text);}catch(RuntimeException ignored){}}
    private void start(){id=UUID.randomUUID();roster=attempt.onlineSessions();votes.clear();deadline=Math.addExact(clock.getAsLong(),duration);phase=Phase.OPEN;announce("보스 재도전 투표 · "+((duration+999999999L)/1000000000L)+"초 · 전체 대상의 50% 초과 찬성 필요");}
    public View request(Context ctx,Object token){actor(ctx,token);poll();if(receipt!=null)return view();if(phase==Phase.OPEN||phase==Phase.INPUT_CLOSED||phase==Phase.HOLD||!openAttempt())throw new IllegalStateException("COMBAT_VOTE_NOT_READY");if(attempt.onlineSessions().isEmpty())throw new IllegalStateException("COMBAT_VOTE_NO_TARGET");start();return view();}
    public void poll(){
        check.run();if(deliveryFailed){phase=Phase.HOLD;return;}if(closed)return;
        if(!current.getAsBoolean()){
            if(phase==Phase.OPEN){phase=Phase.CANCELLED;announce("월드/도전 유효성 변경으로 미확정 투표를 취소했습니다.");}
            else if(phase==Phase.INPUT_CLOSED){phase=Phase.HOLD;decision.completeExceptionally(new IllegalStateException("COMBAT_VOTE_STALE_SCOPE"));}
            return;
        }
        if(phase!=Phase.OPEN)return;
        if(!openAttempt()){phase=Phase.CANCELLED;announce("도전 종료 또는 보류로 미확정 투표를 취소했습니다.");return;}
        if(!sameRoster(roster,attempt.onlineSessions())){phase=Phase.CANCELLED;announce("접속 대상 변경으로 투표를 취소했습니다. 새 대상으로 재투표합니다.");if(!attempt.onlineSessions().isEmpty())start();return;}
        if(clock.getAsLong()>=deadline){phase=Phase.UNDECIDED;announce("보스 재도전 투표 종료 · 과반수 미달로 미확정입니다. 다시 요청할 수 있습니다.");}
    }
    private static boolean sameRoster(Map<UUID,Object> a,Map<UUID,Object> b){return a.size()==b.size()&&a.entrySet().stream().allMatch(e->b.get(e.getKey())==e.getValue());}
    public CompletionStage<View> cast(Context ctx,Object token,UUID ref,Choice choice){
        try{actor(ctx,token);poll();Objects.requireNonNull(choice);
            if(phase!=Phase.OPEN||!Objects.equals(id,ref)||roster.get(ctx.playerId())!=token)throw new IllegalStateException("COMBAT_STALE_VOTE");
            if(votes.putIfAbsent(ctx.playerId(),choice)!=null)throw new IllegalStateException("COMBAT_DUPLICATE_VOTE");
            if(choice!=Choice.YES||count(Choice.YES)<=roster.size()/2)return CompletableFuture.completedFuture(view());
            phase=Phase.INPUT_CLOSED;decision=new CompletableFuture<>();announce("과반수로 재도전 투표 입력을 마감했습니다. 기존 적용 작업 정리를 확인합니다.");
            attempt.barrier().whenCompleteAsync((v,error)->{
                if(decision.isDone())return;
                if(closed||!current.getAsBoolean()||error!=null||!openAttempt()){
                    phase=error!=null||!current.getAsBoolean()||attempt.snapshot().phase()==CombatAttempt.Phase.HOLD?Phase.HOLD:Phase.CANCELLED;
                    announce("재도전 확정을 보류했습니다. 기존 도전 결과와 저장 상태를 확인해야 합니다.");decision.completeExceptionally(new IllegalStateException("COMBAT_RETRY_NOT_CONFIRMED",error));return;
                }
                attempt.finish(CombatAttempt.Outcome.RETRY);
                var state=attempt.snapshot();
                if(state.outcome()!=CombatAttempt.Outcome.RETRY||state.error()!=null){phase=Phase.HOLD;decision.completeExceptionally(new IllegalStateException("COMBAT_RETRY_CLOSE_HOLD"));return;}
                receipt=new Receipt(state.run(),state.attempt(),id);phase=Phase.RETRY_REQUESTED;
                announce(preparationAvailable?"재도전 결정으로 기존 도전을 종료했습니다. 등록된 보스만 재도전 설정으로 저장·복귀·새 도전 준비를 확인합니다.":"재도전 결정으로 기존 도전을 종료했습니다. 다음 도전의 이동·초기화·생성은 아직 연결되지 않아 대기합니다.");decision.complete(view());
            },owner).exceptionally(error->{deliveryFailed=true;decision.completeExceptionally(error);return null;});return decision;
        }catch(RuntimeException error){return CompletableFuture.failedFuture(error);}
    }
    private int count(Choice choice){return (int)votes.values().stream().filter(v->v==choice).count();}
    public View view(){check.run();return new View(id,phase,roster.size(),count(Choice.YES),count(Choice.NO),count(Choice.ABSTAIN),phase==Phase.OPEN?Math.max(0,(deadline-clock.getAsLong()+999999999L)/1000000000L):0,receipt);}
    public List<String> lines(){var v=view();String state=switch(v.phase()){case IDLE->"투표 없음";case OPEN->"투표 진행 중";case INPUT_CLOSED->"입력 마감 · 기존 작업 정리 중";case UNDECIDED->"과반수 미달 · 미확정";case CANCELLED->"미확정 투표 취소";case RETRY_REQUESTED->preparationAvailable?"기존 도전 종료 · 등록된 다음 도전 준비 확인 필요":"기존 도전 종료 · 다음 도전 준비 미등록";case HOLD->"재도전 처리 보류";};return List.of("보스 재도전: "+state,"대상 "+v.targets()+" · 찬성 "+v.yes()+" · 반대 "+v.no()+" · 기권 "+v.abstain()+" · 남은 시간 "+v.remainingSeconds()+"초");}
    public CompletionStage<Void> barrier(){check.run();if(phase==Phase.HOLD||deliveryFailed)return CompletableFuture.failedFuture(new IllegalStateException("COMBAT_VOTE_HOLD"));return decision==null?CompletableFuture.completedFuture(null):decision.handle((v,error)->{if(phase==Phase.HOLD||deliveryFailed)throw new CompletionException(new IllegalStateException("COMBAT_VOTE_HOLD",error));return null;});}
    public void close(){check.run();closed=true;if(phase==Phase.OPEN)phase=Phase.CANCELLED;if(decision!=null&&!decision.isDone()){phase=Phase.HOLD;decision.completeExceptionally(new IllegalStateException("COMBAT_VOTE_CLOSED"));}}
    public static Optional<Input> input(String line){var p=line.split(" ",-1);if(p.length!=4||!p[0].equals("/hearthcombat")||!p[1].equals("vote"))return Optional.empty();try{UUID id=UUID.fromString(p[2]);if(!id.toString().equals(p[2]))return Optional.empty();return Optional.of(new Input(id,switch(p[3]){case "yes"->Choice.YES;case "no"->Choice.NO;case "abstain"->Choice.ABSTAIN;default->throw new IllegalArgumentException();}));}catch(IllegalArgumentException e){return Optional.empty();}}
}
