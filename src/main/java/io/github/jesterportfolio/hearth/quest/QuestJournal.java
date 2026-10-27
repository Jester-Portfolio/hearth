package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 기존 quest list/worker/owner에 연결한 작은 개인 UI 조정자입니다. */
public final class QuestJournal {
    public record View(Map<String,QuestStore.Saved> states,QuestJournalStore.Journal journal){public View{states=Map.copyOf(states);}}
    private record Key(UUID run,UUID player){}
    private final QuestJournalStore store;private final PersonalQuest quests;private final QuestCatalog catalog;private final Executor owner;private final Runnable check;
    private final Map<Key,CompletableFuture<Void>> pending=new HashMap<>();private final Set<Key> holds=new HashSet<>();private boolean closed;private Consumer<ServerProgressAccess.Context> changed=c->{};
    public void changed(Consumer<ServerProgressAccess.Context> listener){check.run();changed=listener;}
    public QuestJournal(QuestJournalStore store,PersonalQuest quests,QuestCatalog catalog,Executor owner,Runnable check){this.store=store;this.quests=quests;this.catalog=catalog;this.owner=owner;this.check=check;}
    private void valid(BooleanSupplier valid){check.run();if(closed || !valid.getAsBoolean())throw new IllegalStateException("STALE_JOURNAL");}
    private CompletionStage<View> read(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid){
        return quests.list(ctx,token,valid).thenCompose(rows->store.readJournal(ctx).thenApplyAsync(data->{valid(valid);quests.requireListDisplay(ctx,rows);return new View(rows,data);},owner));
    }
    private CompletionStage<View> perform(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid,Function<View,CompletionStage<Void>> write){
        check.run();var key=new Key(ctx.storyRunId(),ctx.playerId());
        try{valid(valid);if(holds.contains(key))throw new IllegalStateException("JOURNAL_HOLD");if(pending.containsKey(key))throw new IllegalStateException("JOURNAL_BUSY");}catch(RuntimeException e){return CompletableFuture.failedFuture(e);}
        var result=new CompletableFuture<View>();var drained=new CompletableFuture<Void>();pending.put(key,drained);var writing=new java.util.concurrent.atomic.AtomicBoolean();var committed=new java.util.concurrent.atomic.AtomicBoolean();
        read(ctx,token,valid).thenComposeAsync(view->{
            valid(valid);quests.requireListDisplay(ctx,view.states());
            if(write==null)return CompletableFuture.completedFuture(view);
            var operation=write.apply(view);writing.set(true);return operation.thenComposeAsync(v->{committed.set(true);return read(ctx,token,valid);},owner);
        },owner).whenCompleteAsync((view,error)->{
            pending.remove(key,drained);
            if(error!=null && writing.get() && !committed.get()){
                Throwable t=error;while(t instanceof CompletionException && t.getCause()!=null)t=t.getCause();
                if(t instanceof StorageFailure failure && !Set.of("STALE_OR_UNACCEPTED","REVISION_CONFLICT","MISSING_NOTICE").contains(failure.code()))holds.add(key);
            }
            if(committed.get())try{changed.accept(ctx);}catch(RuntimeException ignored){}
            // 실제 저장 실패는 해당 UI 범위 HOLD로 남기고 종료 장벽에도 전달합니다.
            if(holds.contains(key))drained.completeExceptionally(error);else drained.complete(null);
            if(error==null)result.complete(view);else result.completeExceptionally(error);
        },owner);return result;
    }
    public CompletionStage<View> load(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid){return perform(ctx,token,valid,null);}
    public CompletionStage<View> track(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid,String quest,boolean enabled){return perform(ctx,token,valid,v->{
        var row=v.states().get(quest);if(row==null || row.phase()==QuestStore.Phase.NOT_ACCEPTED || enabled && row.phase()==QuestStore.Phase.COMPLETED)throw new IllegalArgumentException("Untrackable quest");
        return store.track(ctx,quest,row,enabled);
    });}
    public CompletionStage<View> hud(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid,boolean enabled){return perform(ctx,token,valid,v->store.hud(ctx,v.journal().preferences(),enabled));}
    public CompletionStage<View> readAll(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid){return perform(ctx,token,valid,v->store.readAllNotices(ctx));}
    public CompletionStage<View> readNotice(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid,String quest,long revision){return perform(ctx,token,valid,v->{
        if(v.journal().notices().stream().noneMatch(n->n.quest().equals(quest)&&n.revision()==revision) || QuestListView.detail(catalog,v.states(),quest).isEmpty())throw new IllegalArgumentException("Notice unavailable");
        return store.readNotice(ctx,quest,revision);
    });}
    public List<String> hudLines(View view){
        if(!view.journal().preferences().hudEnabled())return List.of();var lines=new ArrayList<String>();
        for(String id:new TreeSet<>(view.journal().tracked())){
            var state=view.states().get(id);if(state==null)throw new IllegalStateException("Unknown tracked quest");
            if(state.phase()==QuestStore.Phase.ACTIVE || state.phase()==QuestStore.Phase.REPORT_READY)lines.add(catalog.quest(id).title()+" — "+catalog.quest(id).guidance(state));
        }return List.copyOf(lines);
    }
    public CompletionStage<Void> barrier(UUID run){check.run();var work=new ArrayList<CompletableFuture<?>>();pending.forEach((key,value)->{if(key.run().equals(run))work.add(value);});if(holds.stream().anyMatch(k->k.run().equals(run)))return CompletableFuture.failedFuture(new IllegalStateException("JOURNAL_HOLD"));return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));}
    public boolean settled(UUID run){check.run();return pending.keySet().stream().noneMatch(k->k.run().equals(run)) && holds.stream().noneMatch(k->k.run().equals(run));}
    public CompletionStage<Void> shutdown(){check.run();closed=true;return CompletableFuture.allOf(pending.values().toArray(CompletableFuture[]::new));}
}
