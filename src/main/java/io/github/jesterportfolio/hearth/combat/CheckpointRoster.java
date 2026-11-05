package io.github.jesterportfolio.hearth.combat;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
/** 재시작 시 현재 진행 중인 개인 퀘스트와 던전 참가 범위를 재확인합니다. */
public final class CheckpointRoster {
 private CheckpointRoster(){}
 public static CompletionStage<Set<UUID>> select(UUID run,Map<UUID,ServerProgressAccess.Context> candidates,QuestStore store,String quest,Executor owner,BooleanSupplier current){
  var snapshot=Map.copyOf(candidates);if(snapshot.isEmpty()||snapshot.size()>32||snapshot.entrySet().stream().anyMatch(e->!e.getKey().equals(e.getValue().playerId())||!run.equals(e.getValue().storyRunId())))return CompletableFuture.failedFuture(new IllegalArgumentException("CHECKPOINT_ROSTER_OWNER"));
  var selected=new HashSet<UUID>();CompletionStage<Void> work=CompletableFuture.completedFuture(null);
  for(var entry:snapshot.entrySet())work=work.thenComposeAsync(v->{if(!current.getAsBoolean())throw new IllegalStateException("CHECKPOINT_ROSTER_STALE");return store.readQuest(entry.getValue(),quest).thenAcceptAsync(row->{if(!current.getAsBoolean())throw new IllegalStateException("CHECKPOINT_ROSTER_STALE");if(row.phase()==QuestStore.Phase.ACTIVE||row.phase()==QuestStore.Phase.REPORT_READY)selected.add(entry.getKey());},owner);},owner);
  return work.thenApplyAsync(v->{if(!current.getAsBoolean()||selected.isEmpty())throw new IllegalStateException("CHECKPOINT_ROSTER_EMPTY_OR_STALE");return Set.copyOf(selected);},owner);
 }
}
