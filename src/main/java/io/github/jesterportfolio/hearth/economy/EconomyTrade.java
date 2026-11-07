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
    public EconomyTrade(EconomyStore store,PaperInventoryCodec codec,Executor owner){this.store=store;this.codec=codec;this.owner=owner;var materials=new HashSet<Material>();for(var good:store.content().goods().values())if(Material.matchMaterial(good.material())==null||!materials.add(Material.matchMaterial(good.material())))throw new IllegalArgumentException("ECON_MATERIAL_OR_ALIAS");}
    public InventoryPayload change(InventoryPayload before,String good,int quantity,boolean buy){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("ECON_ITEMS_MAIN_THREAD");if(quantity<1||quantity>4096)throw new IllegalArgumentException("ECON_QUANTITY");var definition=Objects.requireNonNull(store.content().goods().get(good));var item=new ItemStack(Objects.requireNonNull(Material.matchMaterial(definition.material())));var slots=codec.decode(before);int remaining=quantity;
        if(buy){for(int i=0;i<36&&remaining>0;i++)if(slots[i]!=null&&slots[i].isSimilar(item)){int add=Math.min(remaining,Math.max(0,slots[i].getMaxStackSize()-slots[i].getAmount()));slots[i].setAmount(slots[i].getAmount()+add);remaining-=add;}for(int i=0;i<36&&remaining>0;i++)if(slots[i]==null||slots[i].isEmpty()){int add=Math.min(remaining,item.getMaxStackSize());slots[i]=item.clone();slots[i].setAmount(add);remaining-=add;}}
        else for(int i=0;i<36&&remaining>0;i++)if(slots[i]!=null&&slots[i].isSimilar(item)){int remove=Math.min(remaining,slots[i].getAmount());slots[i].setAmount(slots[i].getAmount()-remove);if(slots[i].getAmount()==0)slots[i]=null;remaining-=remove;}
        if(remaining!=0)throw new StorageFailure("economy",buy?"ECON_INVENTORY_FULL":"ECON_ITEMS");return codec.encode(slots);
    }
    public CompletionStage<EconomyStore.Receipt> execute(PlayerSessions sessions,PlayerSessions.Session session,EconomyStore.Offer offer,int quantity,boolean buy,UUID request,BooleanSupplier fence,EconomyStore.Probe probe){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("ECON_MAIN_THREAD");var result=new AtomicReference<EconomyStore.Receipt>();
        return sessions.inventoryTransaction(session,before->{if(!fence.getAsBoolean())return CompletableFuture.failedFuture(new StorageFailure("economy","STALE_CONTEXT"));var after=change(before.payload(),offer.key().good(),quantity,buy);var command=new EconomyStore.Request(session.context(),request,offer.key(),quantity,buy,offer.market().revision());return store.trade(command,before,after,fence,probe).thenApplyAsync(receipt->{result.set(receipt);return receipt.account().inventory();},owner);}).thenApply(v->Objects.requireNonNull(result.get()));
    }
}
