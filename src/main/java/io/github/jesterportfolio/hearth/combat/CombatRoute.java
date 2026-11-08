package io.github.jesterportfolio.hearth.combat;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static io.github.jesterportfolio.hearth.combat.CombatContent.*;

/** 같은 길목 도전 내부의 이동 조정자. 회복/부활/보상/새 전투를 생성하지 않습니다. */
public final class CombatRoute implements AutoCloseable {
    public enum Phase { IDLE,SAVING,MOVING,DONE,HOLD }
    private final CombatAttempt attempt;private final Route route;private final Executor owner;private final Runnable check;private final BooleanSupplier scope;
    private final Map<UUID,CompletionStage<Void>> requests=new HashMap<>();private Phase phase=Phase.IDLE;private int index;private boolean closed;private volatile boolean deliveryFailed;private UUID request;private CompletionStage<Void> pending;private CombatPlayers.PlayerPort applying;
    public CombatRoute(CombatAttempt attempt,Route route,boolean restarting,Executor owner,Runnable check,BooleanSupplier scope){this.attempt=Objects.requireNonNull(attempt);this.route=Objects.requireNonNull(route);this.owner=owner;this.check=check;this.scope=scope;index=restarting?route.restartIndex():0;}
    public int index(){check.run();return index;}public boolean atBoss(){check.run();return index==route.sections().size();}
    public Optional<Section> section(){check.run();return atBoss()?Optional.empty():Optional.of(route.sections().get(index));}
    public Phase phase(){check.run();return deliveryFailed?Phase.HOLD:phase;}public boolean blocked(){check.run();return deliveryFailed||phase==Phase.SAVING||phase==Phase.MOVING||phase==Phase.HOLD;}
    private boolean current(List<CombatPlayers.PlayerPort> players,Map<UUID,Object> roster,Map<UUID,Boolean> dead,int expected){
        check.run();if(closed||deliveryFailed||phase==Phase.HOLD||index!=expected||!scope.getAsBoolean()||!CombatGathering.sourceReady(attempt.snapshot())||!attempt.onlineSessions().equals(roster))return false;
        return players.stream().allMatch(p->p.current()&&p.position().world().equals(attempt.worldId())&&attempt.snapshot().members().stream().anyMatch(m->m.player().equals(p.context().playerId())&&m.dead()==dead.get(m.player())&&!m.returning()&&!m.protectedReturn()&&!m.reviving()));
    }
    private boolean gathered(List<CombatPlayers.PlayerPort> players,Map<UUID,Boolean> dead,Section section){return players.stream().anyMatch(p->!dead.get(p.context().playerId()))&&players.stream().filter(p->!dead.get(p.context().playerId())).allMatch(p->{var q=p.position();return section.gathering().contains(new FieldContent.Point(q.x(),q.y(),q.z()));});}
    public CompletionStage<Void> advance(String expectedSection,UUID id,List<CombatPlayers.PlayerPort> input,Supplier<CompletionStage<Void>> saving,BooleanSupplier checkpoint){
        check.run();Objects.requireNonNull(id);var duplicate=requests.get(id);if(duplicate!=null)return duplicate;
        if(closed||blocked()||atBoss()||!section().orElseThrow().id().equals(expectedSection))return CompletableFuture.failedFuture(new IllegalStateException("ROUTE_NOT_READY"));
        var players=List.copyOf(input);var roster=new HashMap<UUID,Object>();var dead=new HashMap<UUID,Boolean>();var identities=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());var state=attempt.snapshot();
        if(players.isEmpty()||players.size()>32||players.stream().anyMatch(p->p.session()==null||!identities.add(p.session())||!p.context().storyRunId().equals(state.run())||roster.put(p.context().playerId(),p.session())!=null))return CompletableFuture.failedFuture(new IllegalStateException("ROUTE_ROSTER"));
        state.members().stream().filter(CombatAttempt.Member::online).forEach(m->dead.put(m.player(),m.dead()));int from=index;var stage=section().orElseThrow();
        if(!dead.keySet().equals(roster.keySet())||!current(players,roster,dead,from)||!checkpoint.getAsBoolean()||!gathered(players,dead,stage))return CompletableFuture.failedFuture(new IllegalStateException("ROUTE_NOT_GATHERED"));
        var result=new CompletableFuture<Void>();request=id;requests.put(id,result);phase=Phase.SAVING;
        Runnable valid=()->{if(result.isDone()||request!=id||!current(players,roster,dead,from)||!checkpoint.getAsBoolean())throw new IllegalStateException("ROUTE_STALE");for(var p:players)attempt.view(p.context(),p.session());};
        CompletionStage<Void> work=CompletableFuture.completedFuture(null).thenComposeAsync(v->{valid.run();return Objects.requireNonNull(saving.get());},owner).thenRunAsync(()->{valid.run();if(!gathered(players,dead,stage))throw new IllegalStateException("ROUTE_LEFT_AREA");},owner);
        for(var p:players)if(!dead.get(p.context().playerId()))work=work.thenComposeAsync(v->{valid.run();phase=Phase.MOVING;applying=p;var pose=stage.nextEntry().pose(attempt.worldId());if(!p.safe(pose))throw new IllegalStateException("ROUTE_UNSAFE");double health=p.healthFraction();CombatPlayers.restoredHealth(1,health);
            return Objects.requireNonNull(p.restore(pose,health,List.of(),()->applying==p&&phase==Phase.MOVING&&!result.isDone()&&checkpoint.getAsBoolean()&&current(players,roster,dead,from))).thenAcceptAsync(ok->{valid.run();if(!Boolean.TRUE.equals(ok)||!CombatPlayers.samePosition(pose,p.position())||Math.abs(health-p.healthFraction())>.00001)throw new IllegalStateException("ROUTE_MOVE_FAILED");applying=null;},owner);
        },owner);
        pending=work.toCompletableFuture().orTimeout(30,TimeUnit.SECONDS).whenCompleteAsync((v,error)->{if(result.isDone())return;if(error!=null){phase=Phase.HOLD;result.completeExceptionally(error);}else{index++;phase=Phase.DONE;result.complete(null);}},owner);pending.exceptionally(error->{deliveryFailed=true;result.completeExceptionally(error);return null;});return result;
    }
    public List<String> lines(){check.run();return List.of(atBoss()?"길목 이동 완료 · 보스 집결 대기":"현재 길목: "+section().orElseThrow().title(),"길목 이동 상태: "+switch(phase){case IDLE->"집결 대기";case SAVING->"저장 확인 중";case MOVING->"입장 이동 중";case DONE->"구간 이동 완료";case HOLD->"보류 · 관리자 확인 필요";});}
    public CompletionStage<Void> barrier(){check.run();return pending==null?CompletableFuture.completedFuture(null):pending;}
    public void close(){check.run();closed=true;if(blocked()&&phase!=Phase.HOLD){phase=Phase.HOLD;var p=requests.get(request);if(p!=null)p.toCompletableFuture().completeExceptionally(new IllegalStateException("ROUTE_CLOSED"));}}
}
