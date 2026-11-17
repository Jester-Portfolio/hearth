package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.inventory.InventoryPayload;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StoryAdmissionTest {
    final ServerProgressAccess access=new ServerProgressAccess();final UUID run=UUID.randomUUID(),player=UUID.randomUUID();final StoryBinding binding=new StoryBinding(run,"hearth_story:one",UUID.randomUUID(),"v1","a".repeat(64),"b".repeat(64));
    final InventoryPayload empty=InventoryPayload.pack(new byte[41][]);final ServerProgressAccess.Context context=access.forVerifiedServerLink(run,player);final AtomicInteger calls=new AtomicInteger();
    StoryAdmission service(StoryAdmission.Accounts accounts){return new StoryAdmission(Map.of(player,binding),empty,(c,b,create,p)->{calls.incrementAndGet();return accounts.admit(c,b,create,p);},Runnable::run);}
    @Test void unknownMemberAndWrongRunNeverTouchStorage(){var s=service((c,b,n,p)->{throw new AssertionError();});assertThrows(CompletionException.class,()->s.open(access.forVerifiedServerLink(run,UUID.randomUUID()),null,true,()->true).toCompletableFuture().join());assertThrows(CompletionException.class,()->s.open(access.forVerifiedServerLink(UUID.randomUUID(),player),null,true,()->true).toCompletableFuture().join());assertEquals(0,calls.get());}
    @Test void foreignOwnerAndUnmanagedItemsNeverTouchStorage(){var s=service((c,b,n,p)->{throw new AssertionError();});assertThrows(CompletionException.class,()->s.open(context,UUID.randomUUID().toString(),true,()->true).toCompletableFuture().join());assertThrows(CompletionException.class,()->s.open(context,null,false,()->true).toCompletableFuture().join());assertEquals(0,calls.get());}
    @Test void emptyAuthenticatedSourceRequestsOnlyEmptyCreation(){var expected=new InventoryStore.Stored(empty,4);var s=service((c,b,n,p)->{assertSame(context,c);assertEquals(binding,b);assertTrue(n);assertEquals(empty,p);return CompletableFuture.completedFuture(expected);});assertSame(expected,s.open(context,null,true,()->true).toCompletableFuture().join());}
    @Test void ownedNonemptySourceRequiresExistingStorage(){var s=service((c,b,n,p)->{assertFalse(n);return CompletableFuture.failedFuture(new IllegalStateException("missing"));});assertThrows(CompletionException.class,()->s.open(context,run.toString(),false,()->true).toCompletableFuture().join());assertEquals(1,calls.get());}
    @Test void lateConnectionReplacementCannotDeliverStoredInventory(){var pending=new CompletableFuture<InventoryStore.Stored>();var valid=new AtomicBoolean(true);var s=service((c,b,n,p)->pending);var result=s.open(context,null,true,valid::get);valid.set(false);pending.complete(new InventoryStore.Stored(empty,0));assertThrows(CompletionException.class,()->result.toCompletableFuture().join());}
    @Test void staleBeforeAdmissionDoesNotWrite(){var s=service((c,b,n,p)->{throw new AssertionError();});assertThrows(CompletionException.class,()->s.open(context,null,true,()->false).toCompletableFuture().join());assertEquals(0,calls.get());}
    @Test void postgresFailureIsNotConvertedIntoEmptyInventory(){var s=service((c,b,n,p)->CompletableFuture.failedFuture(new IllegalStateException("commit unknown")));assertThrows(CompletionException.class,()->s.open(context,null,true,()->true).toCompletableFuture().join());}
    @Test void onlyKnownDatabaseSuccessPublishesMembershipAndStaleConnectionStillCannotReceiveInventory(){var pending=new CompletableFuture<InventoryStore.Stored>();var current=new AtomicBoolean(true);var observed=new ArrayList<ServerProgressAccess.Context>();var s=new StoryAdmission(Map.of(player,binding),empty,(c,b,n,p)->pending,Runnable::run,observed::add);var result=s.open(context,null,true,current::get);assertTrue(observed.isEmpty());current.set(false);pending.complete(new InventoryStore.Stored(empty,0));assertThrows(CompletionException.class,()->result.toCompletableFuture().join());assertEquals(List.of(context),observed);}
    @Test void databaseFailureNeverPublishesPhantomMember(){var observed=new ArrayList<ServerProgressAccess.Context>();var s=new StoryAdmission(Map.of(player,binding),empty,(c,b,n,p)->CompletableFuture.failedFuture(new IllegalStateException("COMMIT_UNKNOWN")),Runnable::run,observed::add);assertThrows(CompletionException.class,()->s.open(context,null,true,()->true).toCompletableFuture().join());assertTrue(observed.isEmpty());}
    @Test void suppliedInitialItemsCannotBeConfigured(){var slots=new byte[41][];slots[0]=new byte[]{1};assertThrows(IllegalArgumentException.class,()->new StoryAdmission(Map.of(player,binding),InventoryPayload.pack(slots),(c,b,n,p)->{throw new AssertionError();},Runnable::run));}
}
