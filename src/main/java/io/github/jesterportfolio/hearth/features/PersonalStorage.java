package io.github.jesterportfolio.hearth.features;

import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 진행별 개인 27칸. 일반 dirty 저장과 같은 순서에서 41칸/27칸을 원자적으로 확정합니다. */
public final class PersonalStorage implements InventoryStore {
    private record Key(UUID run,UUID player){}private static Key key(ServerProgressAccess.Context c){return new Key(c.storyRunId(),c.playerId());}
    public static final class Account{public final ServerProgressAccess.Context context;public final Object token;private final Runnable dirty;private InventoryPayload payload;private long revision;private boolean saving,hold;Account(ServerProgressAccess.Context c,Object t,Runnable d,InventoryPayload p,long r){context=c;token=t;dirty=d;payload=p;revision=r;}public InventoryPayload payload(){return payload;}public long revision(){return revision;}public boolean held(){return hold;}public boolean saving(){return saving;}}
    private final InventoryStore base;private final FeatureStore features;private final Executor owner;private final Runnable check;private final BiPredicate<InventoryPayload,InventoryPayload> same;private final Map<Key,Account> accounts=new HashMap<>();private final Set<Key> opening=new HashSet<>();
    public PersonalStorage(InventoryStore base,FeatureStore features,Executor owner,Runnable check,BiPredicate<InventoryPayload,InventoryPayload> same){this.base=base;this.features=features;this.owner=owner;this.check=check;this.same=same;}
    public static InventoryPayload empty(){return InventoryPayload.pack(new byte[41][]);}
    public record Diagnostic(boolean loaded,long revision,boolean saving,boolean held) {}
    public Diagnostic diagnostic(ServerProgressAccess.Context context){check.run();var account=accounts.get(key(context));return account==null?new Diagnostic(false,-1,false,false):new Diagnostic(true,account.revision,account.saving,account.hold);}
    public static InventoryPayload validate(InventoryPayload value){var slots=value.slots();for(int i=27;i<41;i++)if(slots[i]!=null)throw new IllegalArgumentException("ENDER_SLOT_LAYOUT");return value;}
    public CompletionStage<Account> open(ServerProgressAccess.Context c,Object token,BooleanSupplier current,Runnable dirty){check.run();var key=key(c);var old=accounts.get(key);if(old!=null){if(old.hold||old.saving||old.token!=token)return CompletableFuture.failedFuture(new IllegalStateException("ENDER_BUSY_OR_HOLD"));return CompletableFuture.completedFuture(old);}if(!current.getAsBoolean()||accounts.size()+opening.size()>=128||!opening.add(key))return CompletableFuture.failedFuture(new IllegalStateException("ENDER_OPEN_BUSY"));
        return features.read(c,"ender","inventory").thenApplyAsync(row->{if(!current.getAsBoolean())throw new IllegalStateException("ENDER_STALE_OPEN");var account=new Account(c,token,dirty,row.map(r->validate(InventoryPayload.parse(r.payload()))).orElseGet(PersonalStorage::empty),row.map(FeatureStore.Saved::revision).orElse(-1L));accounts.put(key,account);return account;},owner).whenCompleteAsync((v,error)->opening.remove(key),owner);
    }
    public void observe(Account a,InventoryPayload value){check.run();if(accounts.get(key(a.context))!=a||a.hold)throw new IllegalStateException("ENDER_STALE_OR_HOLD");validate(value);if(!same.test(a.payload,value)){a.dirty.run();a.payload=value;}}
    public void forget(Account a){check.run();if(a.hold||a.saving)throw new IllegalStateException("ENDER_NOT_SETTLED");accounts.remove(key(a.context),a);}
    public CompletionStage<Stored> createInventory(ServerProgressAccess.Context c,InventoryPayload value){return base.createInventory(c,value);}public CompletionStage<Stored> readInventory(ServerProgressAccess.Context c){return base.readInventory(c);}
    public CompletionStage<Stored> saveInventory(ServerProgressAccess.Context c,long revision,InventoryPayload value){check.run();var account=accounts.get(key(c));if(account==null)return base.saveInventory(c,revision,value);if(account.hold||account.saving)return CompletableFuture.failedFuture(new IllegalStateException("ENDER_HOLD"));account.saving=true;var payload=account.payload;long before=account.revision;
        return features.inventory(c,revision,value,before,payload).thenApplyAsync(saved->{if(accounts.get(key(c))!=account||account.revision!=before)throw new IllegalStateException("ENDER_STALE_COMMIT");account.revision=saved.feature().revision();return saved.inventory();},owner).whenCompleteAsync((saved,error)->{account.saving=false;if(error!=null)account.hold=true;},owner);
    }
}
