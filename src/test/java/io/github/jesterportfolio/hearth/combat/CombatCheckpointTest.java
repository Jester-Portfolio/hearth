package io.github.jesterportfolio.hearth.combat;
import java.util.*;
import java.util.concurrent.*;
import io.github.jesterportfolio.hearth.persistence.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class CombatCheckpointTest {
 static final class Store implements QuestStore,CheckpointStore {
  UUID excluded;boolean fail;int writes;Optional<CheckpointStore.Saved> value=Optional.empty();
  public CompletionStage<QuestStore.Saved> readQuest(ServerProgressAccess.Context c,String q){return CompletableFuture.completedFuture(new QuestStore.Saved(c.playerId().equals(excluded)?QuestStore.Phase.NOT_ACCEPTED:QuestStore.Phase.ACTIVE,c.playerId().equals(excluded)?-1:0));}
  public CompletionStage<QuestStore.Saved> advanceQuest(ServerProgressAccess.Context c,String q,QuestStore.Saved s,QuestStore.Phase p){throw new AssertionError();}
  public CompletionStage<Optional<CheckpointStore.Saved>> checkpoint(ServerProgressAccess.Context c,String d,String q,String p){return CompletableFuture.completedFuture(value);}
  public CompletionStage<Void> checkpointWrite(ServerProgressAccess.Context c,String d,String q,String p,UUID r,boolean complete){writes++;if(fail)return CompletableFuture.failedFuture(new IllegalStateException("DB_FAILED"));value=Optional.of(new CheckpointStore.Saved(r,complete));return CompletableFuture.completedFuture(null);}
 }
 static final class Fixture {
  final CombatPlayersTest.Fixture f=new CombatPlayersTest.Fixture();final Store store=new Store();final CompletableFuture<Void> saved=new CompletableFuture<>();boolean valid=true;
  final CombatContent.Checkpoint point=new CombatContent.Checkpoint("public_cp","trial",new FieldContent.Box(0,8,63,67,0,8),new CombatContent.Exit(new FieldContent.Point(3,64,4),0,0,1,List.of()));
  final CombatCheckpoint cp=new CombatCheckpoint(f.fight,"dungeon",point,List.of(f.p,f.q),store,store,()->saved,f.owner,()->{},()->valid);
  Fixture(){f.p.health=0.3;f.q.health=0.2;f.bridge.died(f.q);}
  void pump(){f.pump();}void apply(){saved.complete(null);pump();f.p.applying.complete(true);pump();f.q.applying.complete(true);pump();}
 }
 @Test void allSelectedHealDeadRecoverWithoutCountersAndDuplicateIsOneOperation(){var x=new Fixture();var work=x.cp.arrive();assertSame(work,x.cp.arrive());x.pump();assertEquals(0,x.f.p.restores);x.apply();assertTrue(work.toCompletableFuture().join());assertEquals(1,x.f.p.health);assertEquals(1,x.f.q.health);assertEquals(0,x.f.fight.snapshot().sharedUsed());assertFalse(x.f.fight.snapshot().members().stream().anyMatch(CombatAttempt.Member::dead));assertTrue(x.store.value.orElseThrow().complete());}
 @Test void unrelatedQuestDoesNotWaitOrRevive(){var x=new Fixture();x.store.excluded=x.f.q.ctx.playerId();var work=x.cp.arrive();x.apply();assertTrue(work.toCompletableFuture().join());assertEquals(0,x.f.q.restores);assertEquals(0.2,x.f.q.health);assertTrue(x.f.fight.snapshot().members().stream().anyMatch(CombatAttempt.Member::dead));}
 @Test void activatedCheckpointNeverHealsAgainAfterNewDeath(){var x=new Fixture();x.store.value=Optional.of(new CheckpointStore.Saved(UUID.randomUUID(),true));var work=x.cp.arrive();x.pump();assertFalse(work.toCompletableFuture().join());assertEquals(0,x.f.p.restores);assertEquals(0,x.f.q.restores);}
 @Test void storageFailureAndStaleSessionBlockHealing(){for(boolean failure:List.of(true,false)){var x=new Fixture();var work=x.cp.arrive();x.pump();if(failure)x.store.fail=true;else x.valid=false;x.apply();assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertEquals(0,x.f.p.restores);assertEquals(CombatCheckpoint.Phase.HOLD,x.cp.phase());}}
 @Test void oneSurvivorOutsideAndPendingRecordAreRejected(){for(boolean outside:List.of(true,false)){var x=new Fixture();if(outside)x.f.p.position=new CombatContent.Pose(x.f.world,30,64,30,0,0);else x.store.value=Optional.of(new CheckpointStore.Saved(UUID.randomUUID(),false));var work=x.cp.arrive();x.pump();assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertEquals(0,x.f.p.restores);}}
 @Test void questChangingWhileSaveWaitsBlocksRecovery(){var x=new Fixture();var work=x.cp.arrive();x.pump();x.store.excluded=x.f.p.ctx.playerId();x.apply();assertTrue(work.toCompletableFuture().isCompletedExceptionally());assertEquals(0,x.f.p.restores);}

}
