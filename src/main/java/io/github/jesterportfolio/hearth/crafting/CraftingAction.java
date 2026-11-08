package io.github.jesterportfolio.hearth.crafting;

import io.github.jesterportfolio.hearth.inventory.InventoryPayload;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 기존 저장 체계에 불변 제작 결과만 전달하는 일회용 확인 경계입니다. DB를 따로 쓰지 않습니다. */
public final class CraftingAction implements AutoCloseable {
    public interface Port {
        UUID run();UUID player();boolean valid();InventoryPayload capture();InventoryPayload plan(InventoryPayload before);
        void apply(InventoryPayload after);void changed();void hold();
    }
    public static final class Ticket {
        final Port port;final InventoryPayload before,after;CompletableFuture<Void> result;
        private Ticket(Port port,InventoryPayload before,InventoryPayload after){this.port=port;this.before=before;this.after=after;}
    }
    private final Consumer<Runnable> nextTick;private final Runnable check;private final BiPredicate<InventoryPayload,InventoryPayload> same;
    private final Map<UUID,Ticket> pending=new HashMap<>();private boolean closed;
    public CraftingAction(Consumer<Runnable> nextTick,Runnable check,BiPredicate<InventoryPayload,InventoryPayload> same){this.nextTick=nextTick;this.check=check;this.same=same;}
    public Ticket preview(Port port){check.run();if(closed||!port.valid())throw new IllegalStateException("CRAFT_STALE");var before=port.capture();return new Ticket(port,before,port.plan(before));}
    public CompletionStage<Void> confirm(Ticket ticket){
        check.run();if(ticket.result!=null)return ticket.result;
        var result=ticket.result=new CompletableFuture<>();var port=ticket.port;
        if(closed||!port.valid()){result.completeExceptionally(new IllegalStateException("CRAFT_STALE"));return result;}
        if(pending.putIfAbsent(port.player(),ticket)!=null){result.completeExceptionally(new IllegalStateException("CRAFT_BUSY"));return result;}
        try{nextTick.accept(()->{
            check.run();if(result.isDone())return;
            try{if(closed||!port.valid())throw new IllegalStateException("CRAFT_STALE");var current=port.capture();if(!same.test(current,ticket.before)||!same.test(port.plan(current),ticket.after))throw new IllegalStateException("CRAFT_CHANGED");
                try{port.apply(ticket.after);port.changed();}catch(RuntimeException error){port.hold();throw error;}
                pending.remove(port.player(),ticket);result.complete(null);
            }catch(RuntimeException error){pending.remove(port.player(),ticket);result.completeExceptionally(error);}
        });}catch(RuntimeException error){pending.remove(port.player(),ticket);result.completeExceptionally(error);}
        return result;
    }
    public boolean settled(UUID run){check.run();return pending.values().stream().noneMatch(t->t.port.run().equals(run));}
    public CompletionStage<Void> barrier(UUID run){check.run();return CompletableFuture.allOf(pending.values().stream().filter(t->t.port.run().equals(run)).map(t->t.result.handle((v,error)->(Void)null)).toArray(CompletableFuture[]::new));}
    public void close(){check.run();closed=true;var jobs=List.copyOf(pending.values());pending.clear();for(var t:jobs)t.result.completeExceptionally(new IllegalStateException("CRAFT_CLOSED"));}
}
