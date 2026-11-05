package io.github.jesterportfolio.hearth.combat;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class CheckpointRosterTest {
 static final class Store implements QuestStore {
  final Map<UUID,CompletableFuture<Saved>> reads=new HashMap<>();int calls;
  public CompletionStage<Saved> readQuest(ServerProgressAccess.Context c,String id){calls++;return reads.computeIfAbsent(c.playerId(),k->new CompletableFuture<>());}
  public CompletionStage<Saved> advanceQuest(ServerProgressAccess.Context c,String q,Saved row,Phase next){throw new AssertionError("read only");}
 }
 @Test void currentQuestMembershipIsResolvedInsteadOfOldCachedParticipants(){var access=new ServerProgressAccess();var run=UUID.randomUUID();var p=UUID.randomUUID();var q=UUID.randomUUID();var store=new Store();var work=CheckpointRoster.select(run,Map.of(p,access.forVerifiedServerLink(run,p),q,access.forVerifiedServerLink(run,q)),store,"trial",Runnable::run,()->true);while(!work.toCompletableFuture().isDone()){for(var entry:List.copyOf(store.reads.entrySet()))entry.getValue().complete(new QuestStore.Saved(entry.getKey().equals(p)?QuestStore.Phase.ACTIVE:QuestStore.Phase.COMPLETED,0));}assertEquals(Set.of(p),work.toCompletableFuture().join());assertEquals(2,store.calls);}
 @Test void staleOrFailedReadNeverReturnsAnEmptySuccess(){for(boolean error:List.of(true,false)){var access=new ServerProgressAccess();var run=UUID.randomUUID();var p=UUID.randomUUID();var store=new Store();var valid=new AtomicBoolean(true);var work=CheckpointRoster.select(run,Map.of(p,access.forVerifiedServerLink(run,p)),store,"trial",Runnable::run,valid::get);if(error)store.reads.get(p).completeExceptionally(new IllegalStateException("DB_FAILED"));else{valid.set(false);store.reads.get(p).complete(new QuestStore.Saved(QuestStore.Phase.ACTIVE,0));}assertTrue(work.toCompletableFuture().isCompletedExceptionally());}}
 @Test void wrongRunAndEmptyEligibilityAreRejected(){var access=new ServerProgressAccess();var run=UUID.randomUUID();var p=UUID.randomUUID();var store=new Store();assertTrue(CheckpointRoster.select(run,Map.of(p,access.forVerifiedServerLink(UUID.randomUUID(),p)),store,"trial",Runnable::run,()->true).toCompletableFuture().isCompletedExceptionally());assertEquals(0,store.calls);var work=CheckpointRoster.select(run,Map.of(p,access.forVerifiedServerLink(run,p)),store,"trial",Runnable::run,()->true);store.reads.get(p).complete(new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1));assertTrue(work.toCompletableFuture().isCompletedExceptionally());}
}
