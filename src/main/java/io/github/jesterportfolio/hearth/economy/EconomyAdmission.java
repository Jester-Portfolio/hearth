package io.github.jesterportfolio.hearth.economy;

import io.github.jesterportfolio.hearth.inventory.InventoryPayload;
import io.github.jesterportfolio.hearth.persistence.ServerProgressAccess.Context;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 인증 완료 뒤 기존 인벤토리 소유권을 확인하는 비동기 정상 접속 경계. */
public final class EconomyAdmission {
    public interface Accounts {
        CompletionStage<EconomyStore.Account> existing(Context context);
        CompletionStage<EconomyStore.Account> ensureEmpty(Context context,InventoryPayload empty);
    }
    private final UUID realm;private final Accounts accounts;private final InventoryPayload empty;private final Executor owner;
    public EconomyAdmission(UUID realm,Accounts accounts,InventoryPayload empty,Executor owner){this.realm=Objects.requireNonNull(realm);this.accounts=Objects.requireNonNull(accounts);this.empty=Objects.requireNonNull(empty);this.owner=Objects.requireNonNull(owner);}
    public CompletionStage<EconomyStore.Account> open(Context context,String inventoryOwner,boolean sourceEmpty,BooleanSupplier current){
        try{
            if(!realm.equals(context.storyRunId())||inventoryOwner!=null&&!realm.toString().equals(inventoryOwner))throw new IllegalStateException("ECON_INVENTORY_OWNER_CONFLICT");
            if(inventoryOwner==null&&!sourceEmpty)throw new IllegalStateException("ECON_UNMANAGED_INVENTORY_PRESERVED");
            if(!current.getAsBoolean())throw new IllegalStateException("ECON_ADMISSION_STALE");
            var work=sourceEmpty?accounts.ensureEmpty(context,empty):accounts.existing(context);
            return work.thenApplyAsync(account->{if(!current.getAsBoolean())throw new IllegalStateException("ECON_ADMISSION_STALE");return Objects.requireNonNull(account);},owner);
        }catch(RuntimeException error){return CompletableFuture.failedFuture(error);}
    }
}
