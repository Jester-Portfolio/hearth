package io.github.jesterportfolio.hearth.survival.economy;

import io.github.jesterportfolio.hearth.economy.*;
import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;

/** 기존 가격/재고/화폐/영수증 트랜잭션을 재사용합니다. 최신 native 슬롯만 CAS로 준비합니다. */
public final class NativeEconomyLedger {
    private final EconomyStore store;private final EconomyTrade items;
    public NativeEconomyLedger(EconomyStore store,EconomyTrade items){this.store=store;this.items=items;}
    private CompletionStage<InventoryStore.Stored> shadow(ServerProgressAccess.Context context,InventoryPayload before){return store.account(context).thenCompose(account->store.saveInventory(context,account.inventory().revision(),before));}
    public NativeTransactions.Operation shop(ServerProgressAccess.Context context,EconomyStore.Offer offer,int quantity,boolean buy,UUID id,EconomyStore.Probe probe){return (before,valid)->{
        var payload=Objects.requireNonNull(before.get(context.playerId()));var after=items.change(payload,offer.key().good(),quantity,buy);
        var request=new EconomyStore.Request(context,id,offer.key(),quantity,buy,offer.market().revision());
        return shadow(context,payload).thenCompose(stored->store.trade(request,stored,after,valid,probe)).thenApply(receipt->new NativeTransactions.Result(Map.of(context.playerId(),after),receipt,receipt.replay()));
    };}
    public NativeTransactions.Operation peer(EconomyStore.PeerRequest request,EconomyStore.Probe probe){return (before,valid)->{
        var first=request.first();var second=request.second();var left=transfer(before.get(first.playerId()),request.firstOffer(),request.secondOffer());var right=transfer(before.get(second.playerId()),request.secondOffer(),request.firstOffer());
        return shadow(first,before.get(first.playerId())).thenCompose(a->shadow(second,before.get(second.playerId())).thenCompose(b->store.peer(request,a,b,left,right,valid,probe))).thenApply(receipt->new NativeTransactions.Result(Map.of(first.playerId(),left,second.playerId(),right),receipt,receipt.replay()));
    };}
    private InventoryPayload transfer(InventoryPayload before,PeerTrade.Offer outgoing,PeerTrade.Offer incoming){var result=before;for(var e:new TreeMap<>(outgoing.goods()).entrySet())result=items.change(result,e.getKey(),e.getValue(),false);for(var e:new TreeMap<>(incoming.goods()).entrySet())result=items.change(result,e.getKey(),e.getValue(),true);return result;}
}
