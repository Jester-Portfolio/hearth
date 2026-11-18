package io.github.jesterportfolio.hearth.survival.economy;

import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.persistence.StorageFailure;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** 거래만 잠시 보호합니다. 일반 플레이 인벤토리는 native 정본이며 PG 그림자를 재접속 때 적용하지 않습니다. */
public final class NativeTransactions implements AutoCloseable {
    public interface Port {UUID player();boolean current();InventoryPayload capture();boolean same(InventoryPayload before);void freeze(boolean value);void saveNative();void validate(InventoryPayload after);void apply(InventoryPayload after);void notice(String code);}
    public record Result(Map<UUID,InventoryPayload> after,Object receipt,boolean replay){public Result{after=Map.copyOf(after);Objects.requireNonNull(receipt);}}
    public interface Operation{CompletionStage<Result> execute(Map<UUID,InventoryPayload> before,BooleanSupplier valid);}
    private static final Set<String> REFUSALS=Set.of("ECON_FUNDS","ECON_ITEMS","ECON_INVENTORY_FULL","ECON_QUOTE_CHANGED","ECON_STOCK","ECON_REQUEST_BUSY");
    private static final class Work{final UUID request;final List<Port> ports;final Map<UUID,InventoryPayload> before;final AtomicBoolean alive=new AtomicBoolean(true);final CompletableFuture<Result> result=new CompletableFuture<>();final NativeTradeJournal.Ticket ticket;final long deadline;boolean hold,settled;Work(UUID id,List<Port> ports,Map<UUID,InventoryPayload> before,NativeTradeJournal.Ticket ticket,long deadline){request=id;this.ports=ports;this.before=before;this.ticket=ticket;this.deadline=deadline;}}
    private final NativeTradeJournal journal;private final Executor owner;private final LongSupplier clock;private final Map<UUID,Work> pending=new LinkedHashMap<>();private boolean closed;
    public NativeTransactions(NativeTradeJournal journal,Executor owner,LongSupplier clock){this.journal=journal;this.owner=owner;this.clock=clock;}
    public CompletionStage<Result> begin(UUID id,String kind,List<Port> ports,Operation operation){
        if(closed||pending.size()>=128||pending.containsKey(id)||ports.isEmpty()||ports.stream().map(Port::player).distinct().count()!=ports.size()||ports.stream().anyMatch(p->!p.current()||busy(p.player())))return CompletableFuture.failedFuture(new StorageFailure("native_trade","ECON_REQUEST_BUSY"));
        var before=new LinkedHashMap<UUID,InventoryPayload>();var frozen=new ArrayList<Port>();NativeTradeJournal.Ticket ticket;
        try{for(var p:ports)before.put(p.player(),p.capture());for(var p:ports){p.freeze(true);frozen.add(p);}ticket=journal.acquire(new NativeTradeJournal.Pending(id,kind,ports.stream().map(Port::player).toList()));}
        catch(Exception failure){for(var p:frozen)if(p.current()&&p.same(before.get(p.player())))p.freeze(false);return CompletableFuture.failedFuture(new StorageFailure("native_trade","ECON_REQUEST_BUSY"));}
        var work=new Work(id,List.copyOf(ports),Map.copyOf(before),ticket,Math.addExact(clock.getAsLong(),30000));pending.put(id,work);
        try{for(var p:work.ports)p.saveNative();operation.execute(work.before,work.alive::get).whenCompleteAsync((result,error)->finish(work,result,error),owner);}catch(Exception failure){finish(work,null,failure);}return work.result;
    }
    private void finish(Work work,Result result,Throwable error){UUID id=work.request;
            work.settled=true;if(closed||work.hold||pending.get(id)!=work){hold(work,"NATIVE_TRADE_STALE");return;}
            if(error!=null){String code=InventorySaveSession.code(error);if(REFUSALS.contains(code)&&unchanged(work)){try{work.ticket.clean();pending.remove(id,work);for(var p:work.ports)p.freeze(false);work.result.completeExceptionally(error);}catch(Exception failure){hold(work,"NATIVE_TRADE_MARKER_FAILED");}}else hold(work,code);return;}
            if(!unchanged(work)||result==null||result.replay()||!result.after().keySet().equals(work.before.keySet())){hold(work,"NATIVE_TRADE_REVIEW_REQUIRED");return;}
            try{for(var p:work.ports)p.validate(result.after().get(p.player()));for(var p:work.ports)p.apply(result.after().get(p.player()));for(var p:work.ports)p.saveNative();work.ticket.clean();pending.remove(id,work);for(var p:work.ports){p.freeze(false);p.notice("NATIVE_TRADE_COMPLETE");}work.result.complete(result);}
            catch(Exception failure){hold(work,"NATIVE_TRADE_APPLY_OR_SAVE_FAILED");}

    }
    private boolean unchanged(Work w){try{return !closed&&!w.hold&&w.alive.get()&&clock.getAsLong()<w.deadline&&w.ports.stream().allMatch(p->p.current()&&p.same(w.before.get(p.player())));}catch(RuntimeException invalid){return false;}}
    private void hold(Work w,String code){w.hold=true;w.alive.set(false);for(var p:w.ports)if(p.current())p.notice("NATIVE_TRADE_HOLD "+w.request);w.result.completeExceptionally(new IllegalStateException(code));}
    public boolean busy(UUID player){return journal.held(player)||pending.values().stream().anyMatch(w->w.ports.stream().anyMatch(p->p.player().equals(player)));}
    public boolean settled(){return pending.values().stream().allMatch(w->w.settled);}
    public void invalidate(UUID player){for(var w:pending.values())if(w.ports.stream().anyMatch(p->p.player().equals(player))&&!w.hold)hold(w,"NATIVE_TRADE_LIFE_CHANGED");}
    public void tick(){for(var w:List.copyOf(pending.values()))if(!w.hold&&(!unchanged(w)||clock.getAsLong()>=w.deadline))hold(w,"NATIVE_TRADE_TIMEOUT_OR_CHANGED");}
    @Override public void close(){closed=true;for(var w:pending.values())if(!w.hold)hold(w,"NATIVE_TRADE_CLOSED");}
}
