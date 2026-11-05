package io.github.jesterportfolio.hearth.combat;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 길목의 온라인 생존자 집결 후 저장·입장·온라인 사망자 복귀를 확인하고 보스 명단을 고정합니다. */
public final class CombatGathering implements AutoCloseable {
    public enum Phase { IDLE,SAVING,MOVING,STARTING,STARTED,HOLD }
    private final CombatAttempt path;private final CombatContent.Path policy;private final List<CombatPlayers.PlayerPort> players;
    private final Supplier<CompletionStage<Void>> saving;private final Function<List<CombatAttempt.Entrant>,CombatAttempt> start;
    private final Executor owner;private final Runnable check;private final BooleanSupplier scope;
    private final Map<UUID,Boolean> dead=new HashMap<>();private Map<UUID,Object> roster;private CombatPlayers.PlayerPort applying;
    private UUID request;private CompletableFuture<CombatAttempt> result;private Phase phase=Phase.IDLE;private boolean closed;private volatile boolean deliveryFailed;
    public CombatGathering(CombatAttempt path,CombatContent.Path policy,List<CombatPlayers.PlayerPort> players,Supplier<CompletionStage<Void>> saving,Function<List<CombatAttempt.Entrant>,CombatAttempt> start,Executor owner,Runnable check,BooleanSupplier scope){
        this.path=Objects.requireNonNull(path);this.policy=Objects.requireNonNull(policy);this.players=List.copyOf(players);this.saving=saving;this.start=start;this.owner=owner;this.check=check;this.scope=scope;
        var ids=new HashSet<UUID>();var tokens=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());if(players.isEmpty()||players.size()>32||players.stream().anyMatch(p->p.session()==null||!tokens.add(p.session())||!p.context().storyRunId().equals(path.snapshot().run())||!ids.add(p.context().playerId())))throw new IllegalArgumentException("Invalid gathering roster");
    }
    public static boolean sourceReady(CombatAttempt.Snapshot s){return s.error()==null&&s.phase()!=CombatAttempt.Phase.HOLD&&(s.phase()!=CombatAttempt.Phase.CLOSED||s.outcome()==CombatAttempt.Outcome.VICTORY);}
    private boolean current(){
        check.run();if(closed||deliveryFailed||phase==Phase.HOLD||phase==Phase.STARTED||!scope.getAsBoolean()||!path.onlineSessions().equals(roster))return false;
        var s=path.snapshot();if(!sourceReady(s))return false;
        return players.stream().allMatch(p->p.current()&&p.position().world().equals(path.worldId())&&s.members().stream().anyMatch(m->m.player().equals(p.context().playerId())&&m.online()&&m.dead()==dead.get(p.context().playerId())&&!m.returning()&&!m.reviving()&&!m.protectedReturn()));
    }
    private void require(){if(!current())throw new IllegalStateException("COMBAT_GATHERING_STALE");for(var p:players)path.view(p.context(),p.session());}
    private boolean gathered(){return players.stream().filter(p->!dead.get(p.context().playerId())).allMatch(p->{var at=p.position();return policy.gathering().contains(new FieldContent.Point(at.x(),at.y(),at.z()));});}
    public CompletionStage<CombatAttempt> begin(UUID id){
        check.run();Objects.requireNonNull(id);if(request!=null)return request.equals(id)?result:CompletableFuture.failedFuture(new IllegalStateException("COMBAT_GATHERING_REQUEST_CONFLICT"));
        roster=players.stream().collect(java.util.stream.Collectors.toMap(p->p.context().playerId(),CombatPlayers.PlayerPort::session));dead.clear();
        for(var m:path.snapshot().members())if(m.online())dead.put(m.player(),m.dead());
        if(!dead.keySet().equals(roster.keySet())||dead.values().stream().allMatch(Boolean::booleanValue)||!current()||!gathered())return CompletableFuture.failedFuture(new IllegalStateException("COMBAT_GATHERING_NOT_READY"));
        request=id;result=new CompletableFuture<>();phase=Phase.SAVING;
        try{
            CompletionStage<Void> work=CompletableFuture.completedFuture(null).thenComposeAsync(v->{require();if(!gathered())throw new IllegalStateException("COMBAT_GATHERING_LEFT_AREA");return Objects.requireNonNull(saving.get());},owner).thenRunAsync(()->{require();if(!gathered())throw new IllegalStateException("COMBAT_GATHERING_LEFT_AREA");},owner);
            for(var p:players)work=work.thenComposeAsync(v->{require();phase=Phase.MOVING;applying=p;var pose=policy.bossEntry().pose(path.worldId());if(!p.safe(pose))throw new IllegalStateException("COMBAT_GATHERING_UNSAFE_ENTRY");
                double health=dead.get(p.context().playerId())?policy.bossEntry().healthFraction():p.healthFraction();CombatPlayers.restoredHealth(1,health);
                return Objects.requireNonNull(p.restore(pose,health,dead.get(p.context().playerId())?policy.bossEntry().effects():List.of(),()->applying==p&&phase==Phase.MOVING&&current())).thenAcceptAsync(ok->{require();if(!Boolean.TRUE.equals(ok)||!CombatPlayers.samePosition(pose,p.position()))throw new IllegalStateException("COMBAT_GATHERING_MOVE_FAILED");applying=null;},owner);
            },owner);
            work.thenApplyAsync(v->{require();phase=Phase.STARTING;var entrants=players.stream().map(p->new CombatAttempt.Entrant(p.context(),p.session(),p.position())).toList();var next=Objects.requireNonNull(start.apply(entrants));var s=next.snapshot();
                if(s.attempt().equals(path.snapshot().attempt())||!s.run().equals(path.snapshot().run())||s.generation()!=path.snapshot().generation()||!next.worldId().equals(path.worldId())||s.phase()!=CombatAttempt.Phase.ACTIVE||s.difficultyPlayers()!=players.size()||!next.onlineSessions().equals(roster)||s.sharedUsed()!=0||s.reserved()!=0||s.members().stream().anyMatch(m->!m.online()||m.dead()||m.used()!=0||m.reviving()||m.returning())){next.invalidate();throw new IllegalStateException("COMBAT_GATHERING_INVALID_BOSS");}
                phase=Phase.STARTED;return next;
            },owner).whenCompleteAsync((next,error)->{if(result.isDone())return;if(error!=null){phase=Phase.HOLD;result.completeExceptionally(error);}else result.complete(next);},owner).exceptionally(error->{deliveryFailed=true;result.completeExceptionally(error);return null;});
        }catch(RuntimeException error){phase=Phase.HOLD;result.completeExceptionally(error);}
        return result;
    }
    public Phase phase(){check.run();return deliveryFailed?Phase.HOLD:phase;}
    public CompletionStage<Void> barrier(){check.run();return result==null?CompletableFuture.completedFuture(null):result.thenAccept(v->{});}
    @Override public void close(){check.run();closed=true;if(result!=null&&!result.isDone()){phase=Phase.HOLD;result.completeExceptionally(new IllegalStateException("COMBAT_GATHERING_CLOSED"));}}
}
