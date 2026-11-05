package io.github.jesterportfolio.hearth.combat;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 같은 던전 도전과 활성 개인 퀘스트를 함께 진행하는 온라인 참가자만 회복합니다. */
public final class CombatCheckpoint implements AutoCloseable {
 public enum Phase{IDLE,CHECKING,SAVING,APPLYING,DONE,HOLD}
 private final CombatAttempt attempt;private final String dungeon;private final CombatContent.Checkpoint point;private final List<CombatPlayers.PlayerPort> candidates;
 private final QuestStore quests;private final CheckpointStore store;private final Supplier<CompletionStage<Void>> saving;private final Executor owner;private final Runnable check;private final BooleanSupplier scope;
 private final List<CombatPlayers.PlayerPort> selected=new ArrayList<>();private final Map<UUID,Boolean> dead=new HashMap<>();private Map<UUID,Object> roster;private CombatPlayers.PlayerPort applying;
 private CompletableFuture<Boolean> result;private Phase phase=Phase.IDLE;private boolean closed;private volatile boolean deliveryFailed;
 public CombatCheckpoint(CombatAttempt attempt,String dungeon,CombatContent.Checkpoint point,List<CombatPlayers.PlayerPort> candidates,QuestStore quests,CheckpointStore store,Supplier<CompletionStage<Void>> saving,Executor owner,Runnable check,BooleanSupplier scope){this.attempt=attempt;this.dungeon=dungeon;this.point=point;this.candidates=List.copyOf(candidates);this.quests=quests;this.store=store;this.saving=saving;this.owner=owner;this.check=check;this.scope=scope;}
 private boolean current(){check.run();return !closed&&!deliveryFailed&&scope.getAsBoolean()&&attempt.snapshot().error()==null&&(attempt.snapshot().phase()==CombatAttempt.Phase.ACTIVE||attempt.snapshot().phase()==CombatAttempt.Phase.PAUSED)&&attempt.onlineSessions().equals(roster)&&candidates.stream().allMatch(p->p.current()&&p.context().storyRunId().equals(attempt.snapshot().run())&&p.position().world().equals(attempt.worldId())&&attempt.onlineSessions().get(p.context().playerId())==p.session())&&selected.stream().allMatch(p->attempt.snapshot().members().stream().anyMatch(m->m.player().equals(p.context().playerId())&&m.dead()==dead.get(m.player())&&!m.returning()&&!m.reviving()&&!m.protectedReturn()));}
 private void require(){if(!current())throw new IllegalStateException("CHECKPOINT_STALE");}
 private boolean gathered(){return !selected.isEmpty()&&selected.stream().anyMatch(p->!dead.get(p.context().playerId()))&&selected.stream().filter(p->!dead.get(p.context().playerId())).allMatch(p->{var x=p.position();return point.area().contains(new FieldContent.Point(x.x(),x.y(),x.z()));});}
 public CompletionStage<Boolean> arrive(){
  check.run();if(result!=null)return result;roster=attempt.onlineSessions();result=new CompletableFuture<>();phase=Phase.CHECKING;
  if(candidates.isEmpty()||candidates.size()!=roster.size()||candidates.stream().map(p->p.context().playerId()).distinct().count()!=candidates.size()){phase=Phase.HOLD;result.completeExceptionally(new IllegalStateException("CHECKPOINT_ROSTER"));return result;}
  CompletionStage<Void> work=CompletableFuture.completedFuture(null);
  for(var p:candidates)work=work.thenComposeAsync(v->{require();return quests.readQuest(p.context(),point.quest()).thenAcceptAsync(q->{require();if(q.phase()==QuestStore.Phase.ACTIVE||q.phase()==QuestStore.Phase.REPORT_READY){selected.add(p);dead.put(p.context().playerId(),attempt.snapshot().members().stream().filter(m->m.player().equals(p.context().playerId())).findFirst().orElseThrow().dead());}},owner);},owner);
  UUID request=UUID.randomUUID();
  work.thenComposeAsync(v->{require();if(!gathered())throw new IllegalStateException("CHECKPOINT_NOT_GATHERED");return store.checkpoint(selected.getFirst().context(),dungeon,point.quest(),point.id());},owner)
  .thenComposeAsync(row->{require();if(row.isPresent()){if(!row.get().complete())throw new IllegalStateException("CHECKPOINT_PENDING_HOLD");phase=Phase.DONE;return CompletableFuture.completedFuture(false);}
   phase=Phase.SAVING;return saving.get().thenComposeAsync(v->{require();if(!gathered())throw new IllegalStateException("CHECKPOINT_LEFT_AREA");return eligible().thenComposeAsync(ignored->store.checkpointWrite(selected.getFirst().context(),dungeon,point.quest(),point.id(),request,false),owner);},owner).thenComposeAsync(v->{require();phase=Phase.APPLYING;CompletionStage<Void> moves=CompletableFuture.completedFuture(null);
    for(var p:selected)moves=moves.thenComposeAsync(x->{require();applying=p;var target=dead.get(p.context().playerId())?point.entry().pose(attempt.worldId()):p.position();if(!p.safe(target))throw new IllegalStateException("CHECKPOINT_UNSAFE");return p.restore(target,1,List.of(),()->phase==Phase.APPLYING&&applying==p&&current()).thenAcceptAsync(ok->{require();if(!Boolean.TRUE.equals(ok)||!CombatPlayers.samePosition(target,p.position())||Math.abs(p.healthFraction()-1)>0.00001)throw new IllegalStateException("CHECKPOINT_APPLY_FAILED");applying=null;},owner);},owner);
    return moves;
   },owner).thenComposeAsync(v->{require();var tokens=new HashMap<UUID,Object>();selected.forEach(p->tokens.put(p.context().playerId(),p.session()));attempt.checkpointRecovered(tokens);return store.checkpointWrite(selected.getFirst().context(),dungeon,point.quest(),point.id(),request,true);},owner).thenApplyAsync(v->{if(closed||!scope.getAsBoolean()||!attempt.onlineSessions().equals(roster)||candidates.stream().anyMatch(p->!p.current()))throw new IllegalStateException("CHECKPOINT_STALE_COMMIT");phase=Phase.DONE;return true;},owner);
  },owner).whenCompleteAsync((ok,error)->{if(result.isDone())return;if(error!=null){phase=Phase.HOLD;result.completeExceptionally(error);}else result.complete(ok);},owner).exceptionally(error->{deliveryFailed=true;result.completeExceptionally(error);return null;});return result;
 }
 public Set<UUID> participants(){return selected.stream().map(p->p.context().playerId()).collect(java.util.stream.Collectors.toUnmodifiableSet());}
 private CompletionStage<Void> eligible(){CompletionStage<Void> work=CompletableFuture.completedFuture(null);for(var p:selected)work=work.thenComposeAsync(v->quests.readQuest(p.context(),point.quest()).thenAcceptAsync(q->{require();if(q.phase()!=QuestStore.Phase.ACTIVE&&q.phase()!=QuestStore.Phase.REPORT_READY)throw new IllegalStateException("CHECKPOINT_QUEST_CHANGED");},owner),owner);return work;}
 public Phase phase(){return deliveryFailed?Phase.HOLD:phase;}
 public CompletionStage<Void> barrier(){return result==null?CompletableFuture.completedFuture(null):result.thenAccept(v->{});}
 public void close(){closed=true;if(result!=null&&!result.isDone()){phase=Phase.HOLD;result.completeExceptionally(new IllegalStateException("CHECKPOINT_CLOSED"));}}
}
