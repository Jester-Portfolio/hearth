package io.github.jesterportfolio.hearth.economy;

import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.session.PlayerSessions;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** UI 입력은 가격/소유권이 아닙니다. 현재 41슬롯의 최신 저장 뒤 경제 트랜잭션을 실행합니다. */
public final class EconomyTrade {
    private final EconomyStore store;private final PaperInventoryCodec codec;private final Executor owner;
    private record Key(UUID realm,UUID request){}
    private static final Set<String> REFUSALS=Set.of("ECON_QUOTE_CHANGED","ECON_STOCK","ECON_FUNDS","ECON_ITEMS","ECON_INVENTORY_FULL");
    private static final class Attempt {
        final PlayerSessions.Session session;final EconomyStore.Request request;final InventoryStore.Stored before;final InventoryPayload after;boolean settled;
        Attempt(PlayerSessions.Session session,EconomyStore.Request request,InventoryStore.Stored before,InventoryPayload after){this.session=session;this.request=request;this.before=before;this.after=after;}
    }
    private final Map<Key,Attempt> pending=new HashMap<>();
    public record Review(UUID request,long revision,EconomyRecovery.Decision decision){}
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("ECON_MAIN_THREAD");}
    public List<UUID> pending(ServerProgressAccess.Context context){main();store.validate(context);return pending.values().stream().filter(a->a.request.context().storyRunId().equals(context.storyRunId())&&a.request.context().playerId().equals(context.playerId())).map(a->a.request.id()).toList();}
    private Attempt require(ServerProgressAccess.Context context,UUID request){main();store.validate(context);var a=pending.get(new Key(context.storyRunId(),request));if(a==null||!a.request.context().playerId().equals(context.playerId())||!a.settled)throw new StorageFailure("economy","ECON_NO_SETTLED_REQUEST");return a;}
    public CompletionStage<Review> inspect(ServerProgressAccess.Context context,UUID request){main();var a=require(context,request);return store.inspect(a.request).thenApplyAsync(row->{if(require(context,request)!=a)throw new StorageFailure("economy","STALE_CONTEXT");return new Review(request,row.account().inventory().revision(),EconomyRecovery.decide(a.request,a.before,a.after,row));},owner);}
    public CompletionStage<Review> recover(PlayerSessions sessions,ServerProgressAccess.Context context,UUID request,long revision,BooleanSupplier offline){
        main();var a=require(context,request);return inspect(context,request).thenApplyAsync(review->{
            if(require(context,request)!=a||!offline.getAsBoolean()||review.revision()!=revision||review.decision()==EconomyRecovery.Decision.REVIEW_REQUIRED)throw new StorageFailure("economy","ECON_RECOVERY_REVIEW_REQUIRED");
            sessions.retireReconciledHold(a.session,a.before.revision());pending.remove(new Key(context.storyRunId(),request),a);return review;
        },owner);
    }
    public EconomyTrade(EconomyStore store,PaperInventoryCodec codec,Executor owner){this.store=store;this.codec=codec;this.owner=owner;var materials=new HashSet<Material>();for(var good:store.content().goods().values())if(Material.matchMaterial(good.material())==null||!materials.add(Material.matchMaterial(good.material())))throw new IllegalArgumentException("ECON_MATERIAL_OR_ALIAS");}
    public EconomyQuote.Capacity capacity(InventoryPayload payload,String good){
        main();var definition=Objects.requireNonNull(store.content().goods().get(good));var plain=new ItemStack(Objects.requireNonNull(Material.matchMaterial(definition.material())));int space=0,items=0;var slots=codec.decode(payload);
        for(int i=0;i<36;i++){var item=slots[i];if(item==null||item.isEmpty())space+=plain.getMaxStackSize();else if(item.isSimilar(plain)){items+=item.getAmount();space+=Math.max(0,item.getMaxStackSize()-item.getAmount());}}return new EconomyQuote.Capacity(space,items);
    }
    public InventoryPayload change(InventoryPayload before,String good,int quantity,boolean buy){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("ECON_ITEMS_MAIN_THREAD");if(quantity<1||quantity>4096)throw new IllegalArgumentException("ECON_QUANTITY");var definition=Objects.requireNonNull(store.content().goods().get(good));var item=new ItemStack(Objects.requireNonNull(Material.matchMaterial(definition.material())));var slots=codec.decode(before);int remaining=quantity;
        if(buy){for(int i=0;i<36&&remaining>0;i++)if(slots[i]!=null&&slots[i].isSimilar(item)){int add=Math.min(remaining,Math.max(0,slots[i].getMaxStackSize()-slots[i].getAmount()));slots[i].setAmount(slots[i].getAmount()+add);remaining-=add;}for(int i=0;i<36&&remaining>0;i++)if(slots[i]==null||slots[i].isEmpty()){int add=Math.min(remaining,item.getMaxStackSize());slots[i]=item.clone();slots[i].setAmount(add);remaining-=add;}}
        else for(int i=0;i<36&&remaining>0;i++)if(slots[i]!=null&&slots[i].isSimilar(item)){int remove=Math.min(remaining,slots[i].getAmount());slots[i].setAmount(slots[i].getAmount()-remove);if(slots[i].getAmount()==0)slots[i]=null;remaining-=remove;}
        if(remaining!=0)throw new StorageFailure("economy",buy?"ECON_INVENTORY_FULL":"ECON_ITEMS");return codec.encode(slots);
    }
    public CompletionStage<EconomyStore.Receipt> execute(PlayerSessions sessions,PlayerSessions.Session session,EconomyStore.Offer offer,int quantity,boolean buy,UUID request,BooleanSupplier fence,EconomyStore.Probe probe){
        main();store.validate(session.context());var key=new Key(session.context().storyRunId(),Objects.requireNonNull(request));
        if(quantity<1||quantity>4096||pending.size()>=128||pending.containsKey(key))return CompletableFuture.failedFuture(new StorageFailure("economy","ECON_REQUEST_BUSY"));
        var result=new AtomicReference<EconomyStore.Receipt>();var attempt=new AtomicReference<Attempt>();
        return sessions.inventoryTransaction(session,before->{
            if(!fence.getAsBoolean())return CompletableFuture.failedFuture(new StorageFailure("economy","STALE_CONTEXT"));
            var after=change(before.payload(),offer.key().good(),quantity,buy);var command=new EconomyStore.Request(session.context(),request,offer.key(),quantity,buy,offer.market().revision());
            if(pending.size()>=128||pending.containsKey(key))return CompletableFuture.failedFuture(new StorageFailure("economy","ECON_REQUEST_BUSY"));
            var a=new Attempt(session,command,before,after);attempt.set(a);pending.put(key,a);
            return store.trade(command,before,after,fence,probe).thenApplyAsync(receipt->{result.set(receipt);return receipt.account().inventory();},owner);
        }).thenApply(v->Objects.requireNonNull(result.get())).whenCompleteAsync((receipt,error)->{
            var a=attempt.get();if(a==null)return;a.settled=true;
            if(error==null||REFUSALS.contains(InventorySaveSession.code(error)))pending.remove(key,a);
        },owner);
    }
}
