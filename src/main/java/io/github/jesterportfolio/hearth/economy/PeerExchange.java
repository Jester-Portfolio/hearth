package io.github.jesterportfolio.hearth.economy;

import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.session.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.Bukkit;

/** 양쪽 기존 저장을 동결한 다음 하나의 PG 거래를 실행합니다. 실패/불명 결과는 재제출하지 않습니다. */
public final class PeerExchange {
    public static final Set<String> REFUSALS=Set.of("ECON_FUNDS","ECON_ITEMS","ECON_INVENTORY_FULL","ECON_REQUEST_BUSY");
    public record Attempt(EconomyStore.PeerRequest request,InventoryStore.Stored firstBefore,InventoryStore.Stored secondBefore,InventoryPayload firstAfter,InventoryPayload secondAfter){}
    private final EconomyStore store;private final EconomyTrade items;private final Executor owner;private final Map<UUID,Attempt> pending=new LinkedHashMap<>();private final Set<UUID> actors=new HashSet<>();
    public PeerExchange(EconomyStore store,EconomyTrade items,Executor owner){this.store=store;this.items=items;this.owner=owner;}
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("ECON_PEER_MAIN");}
    private InventoryPayload transfer(InventoryPayload before,PeerTrade.Offer outgoing,PeerTrade.Offer incoming){var after=before;for(var entry:new TreeMap<>(outgoing.goods()).entrySet())after=items.change(after,entry.getKey(),entry.getValue(),false);for(var entry:new TreeMap<>(incoming.goods()).entrySet())after=items.change(after,entry.getKey(),entry.getValue(),true);return after;}
    public List<UUID> pending(ServerProgressAccess.Context context){main();store.validate(context);return pending.values().stream().filter(a->a.request().first().storyRunId().equals(context.storyRunId())&&(a.request().first().playerId().equals(context.playerId())||a.request().second().playerId().equals(context.playerId()))).map(a->a.request().id()).toList();}
    public CompletionStage<EconomyStore.PeerResult> execute(PlayerSessions sessions,PlayerSessions.Session first,PlayerSessions.Session second,PeerTrade deal,BooleanSupplier valid,EconomyStore.Probe probe){
        main();store.validate(first.context());store.validate(second.context());var left=first.context();var right=second.context();
        if(deal.state()!=PeerTrade.State.COMMITTING||!deal.first().equals(left.playerId())||!deal.second().equals(right.playerId())||!deal.realm().equals(left.storyRunId())||!deal.realm().equals(right.storyRunId())||pending.size()>=128||actors.size()>=256||pending.containsKey(deal.id())||actors.contains(left.playerId())||actors.contains(right.playerId())||!sessions.playable(first)||!sessions.playable(second)||!valid.getAsBoolean())return CompletableFuture.failedFuture(new StorageFailure("economy","ECON_REQUEST_BUSY"));
        var request=new EconomyStore.PeerRequest(deal.id(),left,right,deal.offer(left.playerId()),deal.offer(right.playerId()));var receipt=new EconomyStore.PeerResult[1];var attempt=new Attempt[1];actors.add(left.playerId());actors.add(right.playerId());
        return sessions.inventoryTransaction(first,firstBefore->{
            if(!valid.getAsBoolean())return CompletableFuture.failedFuture(new StorageFailure("economy","ECON_REQUEST_BUSY"));
            return sessions.inventoryTransaction(second,secondBefore->{
            if(!valid.getAsBoolean())return CompletableFuture.failedFuture(new StorageFailure("economy","ECON_REQUEST_BUSY"));
            var firstAfter=transfer(firstBefore.payload(),request.firstOffer(),request.secondOffer());var secondAfter=transfer(secondBefore.payload(),request.secondOffer(),request.firstOffer());
            attempt[0]=new Attempt(request,firstBefore,secondBefore,firstAfter,secondAfter);pending.put(request.id(),attempt[0]);
            return store.peer(request,firstBefore,secondBefore,firstAfter,secondAfter,valid,probe).thenApplyAsync(result->{receipt[0]=result;return result.second().inventory();},owner);
        }).thenApplyAsync(v->Objects.requireNonNull(receipt[0]).first().inventory(),owner);}).thenApply(v->Objects.requireNonNull(receipt[0])).whenCompleteAsync((result,error)->{
            if(error==null||REFUSALS.contains(InventorySaveSession.code(error))){pending.remove(request.id());actors.remove(left.playerId());actors.remove(right.playerId());}
            else{sessions.inventoryHold(first,"ECON_PEER_REVIEW_REQUIRED");sessions.inventoryHold(second,"ECON_PEER_REVIEW_REQUIRED");}
        },owner);
    }
}
