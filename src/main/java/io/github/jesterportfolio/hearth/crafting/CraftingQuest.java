package io.github.jesterportfolio.hearth.crafting;

import io.github.jesterportfolio.hearth.quest.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 실제 제작 성공과 기존 개인 목표를 잇습니다. 제작 전 상태를 고정하고 source 저장 뒤 목표를 저장합니다. */
public final class CraftingQuest implements AutoCloseable {
    private record Key(UUID run,UUID player){}
    private record Expected(QuestContent content,PersonalQuest.Session session,QuestStore.Saved state){}
    public final class Prepared {
        final ServerProgressAccess.Context context;final String reference;final BooleanSupplier connected,valid;final CraftingAction.Ticket ticket;final Predicate<String> allowed;
        final List<Expected> targets=new ArrayList<>();CompletableFuture<List<String>> result;
        Prepared(ServerProgressAccess.Context context,String reference,BooleanSupplier connected,BooleanSupplier valid,CraftingAction.Ticket ticket,Predicate<String> allowed){this.context=context;this.reference=reference;this.connected=connected;this.valid=valid;this.ticket=ticket;this.allowed=allowed;}
        public boolean active(){check.run();return !targets.isEmpty();}
    }
    private final PersonalQuest quests;private final QuestCatalog catalog;private final CraftContent content;private final Executor owner;private final Runnable check;
    private final Map<Key,CompletableFuture<?>> pending=new HashMap<>();private final Set<Key> holds=new HashSet<>();private boolean closed;
    public CraftingQuest(PersonalQuest quests,QuestCatalog catalog,CraftContent content,Executor owner,Runnable check){
        this.quests=quests;this.catalog=catalog;this.content=content;this.owner=owner;this.check=check;
        for(var c:catalog.all())for(var goal:c.goals())if(goal.interaction()!=null&&goal.interaction().startsWith("craft:")){
            var parts=goal.interaction().split(":",-1);var f=parts.length==3?content.facilities().get(parts[1]):null;
            if(f==null||!f.recipes().contains(parts[2])||!goal.area().contains(f.x(),f.y(),f.z()))throw new IllegalArgumentException("CRAFT_QUEST_REFERENCE");
        }
    }
    public CompletionStage<Prepared> prepare(ServerProgressAccess.Context ctx,String facility,String recipe,CraftingAction.Ticket ticket,BooleanSupplier connected,BooleanSupplier valid,Predicate<String> allowed){
        check.run();var key=new Key(ctx.storyRunId(),ctx.playerId());var f=content.facilities().get(facility);
        if(ticket.result!=null||closed||holds.contains(key)||!connected.getAsBoolean()||!valid.getAsBoolean()||f==null||!f.recipes().contains(recipe)||!ticket.port.run().equals(ctx.storyRunId())||!ticket.port.player().equals(ctx.playerId())||!ticket.port.source().equals("craft:"+facility+":"+recipe))return rejected("CRAFT_QUEST_STALE");
        if(pending.containsKey(key))return rejected("CRAFT_QUEST_BUSY");var result=new CompletableFuture<Prepared>();pending.put(key,result);
        var prepared=new Prepared(ctx,"craft:"+facility+":"+recipe,connected,valid,ticket,allowed);Object token=new Object();CompletionStage<Void> work=CompletableFuture.completedFuture(null);
        for(var c:catalog.all())if(c.goals().stream().anyMatch(g->prepared.reference.equals(g.interaction())))work=work.thenComposeAsync(v->{
            require(prepared);if(!allowed.test(c.id()))return CompletableFuture.completedFuture(null);
            return quests.open(ctx,c.id(),token,connected).thenAcceptAsync(q->{require(prepared);if(!allowed.test(c.id()))throw new IllegalStateException("CRAFT_QUEST_STALE");var row=quests.requireDialogue(q);if(row.phase()==QuestStore.Phase.ACTIVE&&prepared.reference.equals(c.goal(row).interaction()))prepared.targets.add(new Expected(c,q,row));},owner);
        },owner);
        work.whenCompleteAsync((v,error)->{pending.remove(key,result);if(error==null){try{require(prepared);result.complete(prepared);}catch(RuntimeException failure){result.completeExceptionally(failure);}}else result.completeExceptionally(error);},owner);
        return result;
    }
    private void require(Prepared p){check.run();if(closed||holds.contains(new Key(p.context.storyRunId(),p.context.playerId()))||!p.connected.getAsBoolean()||!p.valid.getAsBoolean())throw new IllegalStateException("CRAFT_QUEST_STALE");}
    private void requireExpected(Prepared p,Expected expected){require(p);if(!p.allowed.test(expected.content().id()))throw new IllegalStateException("CRAFT_QUEST_STALE");if(!quests.requireDialogue(expected.session()).equals(expected.state()))throw new IllegalStateException("CRAFT_QUEST_CHANGED");}
    public boolean valid(Prepared p){check.run();try{require(p);for(var target:p.targets)requireExpected(p,target);return true;}catch(RuntimeException error){return false;}}
    public CompletionStage<List<String>> complete(Prepared p,Supplier<CompletionStage<Void>> saveInventory){
        check.run();if(p.result!=null)return p.result;p.result=new CompletableFuture<>();var result=p.result;var key=new Key(p.context.storyRunId(),p.context.playerId());
        try{if(!valid(p)||p.ticket.result==null||!p.ticket.result.isDone()||p.ticket.result.isCompletedExceptionally())throw new IllegalStateException("CRAFT_QUEST_NOT_APPLIED");if(pending.putIfAbsent(key,result)!=null)throw new IllegalStateException("CRAFT_QUEST_BUSY");}
        catch(RuntimeException error){result.completeExceptionally(error);return result;}
        var messages=new ArrayList<String>();CompletionStage<Void> work;
        try{work=p.active()?saveInventory.get():CompletableFuture.completedFuture(null);}catch(RuntimeException error){work=CompletableFuture.failedFuture(error);}
        work=work.thenRunAsync(()->{require(p);for(var target:p.targets)requireExpected(p,target);},owner);
        for(var target:p.targets)work=work.thenComposeAsync(v->{requireExpected(p,target);return quests.interact(target.session(),target.content(),p.reference).thenAcceptAsync(row->{require(p);messages.add(target.content().title()+" · "+target.content().guidance(row));},owner);},owner);
        work.whenCompleteAsync((v,error)->{
            pending.remove(key,result);if(error==null)result.complete(List.copyOf(messages));else{Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();String code=Objects.toString(cause.getMessage(),"");if(!Set.of("CRAFT_QUEST_STALE","CRAFT_QUEST_CHANGED","STALE_QUEST","STALE_SESSION").contains(code))holds.add(key);result.completeExceptionally(error);}
        },owner);return result;
    }
    private static <T> CompletionStage<T> rejected(String code){return CompletableFuture.failedFuture(new IllegalStateException(code));}
    public boolean held(ServerProgressAccess.Context ctx){check.run();return holds.contains(new Key(ctx.storyRunId(),ctx.playerId()));}
    public boolean settled(UUID run){check.run();return pending.keySet().stream().noneMatch(k->k.run().equals(run))&&holds.stream().noneMatch(k->k.run().equals(run));}
    public CompletionStage<Void> barrier(UUID run){check.run();if(holds.stream().anyMatch(k->k.run().equals(run)))return rejected("CRAFT_QUEST_HOLD");return CompletableFuture.allOf(pending.entrySet().stream().filter(e->e.getKey().run().equals(run)).map(e->e.getValue().handle((v,error)->(Void)null)).toArray(CompletableFuture[]::new)).thenRunAsync(()->{if(holds.stream().anyMatch(k->k.run().equals(run)))throw new IllegalStateException("CRAFT_QUEST_HOLD");},owner);}
    public void close(){check.run();closed=true;for(var f:List.copyOf(pending.values()))f.completeExceptionally(new IllegalStateException("CRAFT_QUEST_CLOSED"));pending.clear();}
}
