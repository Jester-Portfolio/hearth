package io.github.jesterportfolio.hearth.economy;

import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class EconomyAdmissionTest {
    private static final class F implements EconomyAdmission.Accounts {
        final UUID realm=UUID.randomUUID();final ServerProgressAccess.Context context=new ServerProgressAccess().forVerifiedServerLink(realm,UUID.randomUUID());final InventoryPayload empty=InventoryPayload.pack(new byte[41][]);
        final CompletableFuture<EconomyStore.Account> result=new CompletableFuture<>();final Queue<Runnable> queue=new ArrayDeque<>();int reads,creates;
        final EconomyAdmission admission=new EconomyAdmission(realm,this,empty,queue::add);
        public CompletionStage<EconomyStore.Account> existing(ServerProgressAccess.Context context){reads++;return result;}
        public CompletionStage<EconomyStore.Account> ensureEmpty(ServerProgressAccess.Context context,InventoryPayload payload){assertSame(empty,payload);creates++;return result;}
        void pump(){while(!queue.isEmpty())queue.remove().run();}
        EconomyStore.Account account(){return new EconomyStore.Account(0,new InventoryStore.Stored(empty,0));}
    }
    @Test void unmanagedNonemptyAndForeignOwnerNeverTouchDatabase(){var f=new F();for(String owner:new String[]{null,UUID.randomUUID().toString(),"invalid"}){assertTrue(f.admission.open(f.context,owner,false,()->true).toCompletableFuture().isCompletedExceptionally());}assertEquals(0,f.reads+f.creates);}
    @Test void emptyFirstLoginIsPreparedInDatabaseBeforeAcceptance(){var f=new F();var pending=f.admission.open(f.context,null,true,()->true);assertFalse(pending.toCompletableFuture().isDone());assertEquals(1,f.creates);f.result.complete(f.account());assertFalse(pending.toCompletableFuture().isDone());f.pump();assertEquals(f.account(),pending.toCompletableFuture().join());}
    @Test void ownedNonemptyLoginOnlyReadsExistingNeverCreatesEmptyReplacement(){var f=new F();var pending=f.admission.open(f.context,f.realm.toString(),false,()->true);assertEquals(1,f.reads);assertEquals(0,f.creates);f.result.completeExceptionally(new IllegalStateException("MISSING"));f.pump();assertTrue(pending.toCompletableFuture().isCompletedExceptionally());}
    @Test void lateCommitCannotAdmitReplacedSessionOrChangedOriginalInventory(){var f=new F();var current=new AtomicBoolean(true);var pending=f.admission.open(f.context,null,true,current::get);current.set(false);f.result.complete(f.account());f.pump();assertTrue(pending.toCompletableFuture().isCompletedExceptionally());assertEquals(1,f.creates);}
    @Test void failureAndSynchronousFailureNeverBecomeEmptySuccess(){var f=new F();var pending=f.admission.open(f.context,null,true,()->true);f.result.completeExceptionally(new IllegalStateException("COMMIT_UNKNOWN"));f.pump();assertTrue(pending.toCompletableFuture().isCompletedExceptionally());var failing=new EconomyAdmission(f.realm,new EconomyAdmission.Accounts(){public CompletionStage<EconomyStore.Account> existing(ServerProgressAccess.Context c){throw new IllegalStateException();}public CompletionStage<EconomyStore.Account> ensureEmpty(ServerProgressAccess.Context c,InventoryPayload p){throw new IllegalStateException();}},f.empty,Runnable::run);assertTrue(failing.open(f.context,null,true,()->true).toCompletableFuture().isCompletedExceptionally());}
    @Test void wrongScopeAndStaleRequestAreRejectedBeforeAnyDatabaseWork(){var f=new F();assertTrue(f.admission.open(new ServerProgressAccess().forVerifiedServerLink(UUID.randomUUID(),f.context.playerId()),null,true,()->true).toCompletableFuture().isCompletedExceptionally());assertTrue(f.admission.open(f.context,null,true,()->false).toCompletableFuture().isCompletedExceptionally());assertEquals(0,f.creates+f.reads);}
}
