package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.exploration.*;
import io.github.jesterportfolio.hearth.story.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 기존 quest list/worker/owner에 연결한 작은 개인 UI 조정자입니다. */
public final class QuestJournal {
    public record View(Map<String,QuestStore.Saved> states,QuestJournalStore.Journal journal,ExplorationView exploration){
        public View(Map<String,QuestStore.Saved> states,QuestJournalStore.Journal journal){this(states,journal,ExplorationView.empty());}
        public View{states=Map.copyOf(states);}
    }
    private record Key(UUID run,UUID player){}
    private final QuestJournalStore store;private final PersonalQuest quests;private final QuestCatalog catalog;private final Executor owner;private final Runnable check;
    private final Map<Key,CompletableFuture<Void>> pending=new HashMap<>();private final Set<Key> holds=new HashSet<>();private boolean closed;private Consumer<ServerProgressAccess.Context> changed=c->{};
    private Consumer<UUID> sharedChanged=r->{};
    public void sharedChanged(Consumer<UUID> listener){check.run();sharedChanged=listener;}
    private SharedStory story;private StoryAccess storyAccess;
    public void sharedStory(SharedStory story,StoryAccess access){check.run();this.story=story;storyAccess=access;story.changed(ctx->{changed.accept(ctx);sharedChanged.accept(ctx.storyRunId());});}
    public boolean questAllowed(ServerProgressAccess.Context ctx,String id){return storyAccess==null||storyAccess.quest(ctx.storyRunId(),catalog.quest(id));}
    public Set<String> blocked(ServerProgressAccess.Context ctx){var result=new HashSet<String>();for(String id:catalog.questIds())if(!questAllowed(ctx,id))result.add(id);return Set.copyOf(result);}
    private ExplorationView explorationView(ServerProgressAccess.Context ctx,ExplorationStore.Data raw,List<CommunityBoundary.Member> members){
        if(story==null)return ExplorationView.build(content,raw,members,community.status(ctx,raw.story(),members));
        var snapshot=story.cached(ctx.storyRunId());if(!story.healthy(ctx.storyRunId())||snapshot==null||snapshot.story().revision()!=raw.story().revision())throw new IllegalStateException("STORY_CACHE_UNCONFIRMED");
        var branches=snapshot.branches().values().stream().sorted(Comparator.comparing(StoryStore.Branch::stage)).map(b->b.label()).toList();
        return ExplorationView.build(content,raw,members,community.status(ctx,raw.story(),members),p->storyAccess.region(ctx.storyRunId(),p.region())&&storyAccess.area(ctx.storyRunId(),p.bounds()),r->storyAccess.status(ctx.storyRunId(),r),blocked(ctx),branches).withEffectStatus(story.effectStatus(ctx.storyRunId()));
    }
    private ExplorationStore explorationStore;private ExplorationContent content;private CommunityBoundary community;
    private record DiscoveryKey(UUID run,UUID player,String place,boolean discover){}
    private record DiscoveryJob(Object token,CompletableFuture<View> result){}
    private final Map<DiscoveryKey,DiscoveryJob> discoveries=new HashMap<>();
    public void exploration(ExplorationStore store,ExplorationContent definitions,CommunityBoundary boundary){check.run();explorationStore=store;content=definitions;community=boundary;}
    public void requireView(ServerProgressAccess.Context ctx,View view){
        check.run();quests.requireListDisplay(ctx,view.states());
        if(story!=null&&(!story.healthy(ctx.storyRunId())||story.cached(ctx.storyRunId()).story().revision()!=view.exploration().story().revision()||!blocked(ctx).equals(view.exploration().blockedQuests())))throw new IllegalStateException("STALE_STORY_VIEW");
        if(community!=null && (!community.members(ctx).equals(view.exploration().members()) || !community.status(ctx,view.exploration().story(),view.exploration().members()).equals(view.exploration().conditions())))throw new IllegalStateException("STALE_COMMUNITY");
    }
    public void changed(Consumer<ServerProgressAccess.Context> listener){check.run();changed=listener;}
    public QuestJournal(QuestJournalStore store,PersonalQuest quests,QuestCatalog catalog,Executor owner,Runnable check){this.store=store;this.quests=quests;this.catalog=catalog;this.owner=owner;this.check=check;}
    private void valid(BooleanSupplier valid){check.run();if(closed || !valid.getAsBoolean())throw new IllegalStateException("STALE_JOURNAL");}
    private CompletionStage<View> read(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid){
        CompletionStage<?> prepared=story==null?CompletableFuture.completedFuture(null):story.load(ctx).thenComposeAsync(s->story.updateConditions(ctx),owner);
        return prepared.thenComposeAsync(v->quests.list(ctx,token,valid),owner).thenCompose(rows->store.readJournal(ctx).thenCompose(data->{
            if(content==null)return CompletableFuture.completedFuture(new View(rows,data));
            return explorationStore.readExploration(ctx).thenApplyAsync(raw->{valid(valid);var members=community.members(ctx);return new View(rows,data,explorationView(ctx,raw,members));},owner);
        })).thenApplyAsync(view->{valid(valid);requireView(ctx,view);return view;},owner);
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
        var row=v.states().get(quest);if(!questAllowed(ctx,quest))throw new IllegalArgumentException("Restricted quest");if(row==null || row.phase()==QuestStore.Phase.NOT_ACCEPTED || enabled && row.phase()==QuestStore.Phase.COMPLETED)throw new IllegalArgumentException("Untrackable quest");
        return store.track(ctx,quest,row,enabled);
    });}
    public CompletionStage<View> hud(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid,boolean enabled){return perform(ctx,token,valid,v->store.hud(ctx,v.journal().preferences(),enabled));}
    public CompletionStage<View> readAll(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid){return perform(ctx,token,valid,v->store.readAllNotices(ctx));}
    public CompletionStage<View> readNotice(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid,String quest,long revision){return perform(ctx,token,valid,v->{
        if(v.journal().notices().stream().noneMatch(n->n.type().equals("QUEST")&&n.quest().equals(quest)&&n.revision()==revision) || QuestListView.detail(catalog,v.states(),quest).isEmpty())throw new IllegalArgumentException("Notice unavailable");
        return store.readNotice(ctx,quest,revision);
    });}
    public CompletionStage<View> setting(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid,String key,boolean enabled){return perform(ctx,token,valid,v->{v.journal().preferences().flag(key);return store.setting(ctx,v.journal().preferences(),key,enabled);});}
    public CompletionStage<View> readPlaceNotice(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid,String place){return perform(ctx,token,valid,v->{
        if(v.exploration().place(place).filter(ExplorationView.Place::discovered).isEmpty() || v.journal().notices().stream().noneMatch(n->n.type().equals("PLACE")&&n.quest().equals(place)))throw new IllegalArgumentException("Notice unavailable");return store.readPlaceNotice(ctx,place);
    });}
    private CompletionStage<View> afterCurrent(ServerProgressAccess.Context ctx,BooleanSupplier valid,Supplier<CompletionStage<View>> operation){
        valid(valid);var pendingWork=pending.get(new Key(ctx.storyRunId(),ctx.playerId()));
        if(pendingWork==null){
            if(!quests.settled(ctx.storyRunId()))return quests.barrier(ctx.storyRunId()).thenComposeAsync(v->afterCurrent(ctx,valid,operation),owner);
            return operation.get();
        }
        return pendingWork.handle((v,e)->null).thenComposeAsync(v->afterCurrent(ctx,valid,operation),owner);
    }
    private CompletionStage<View> observe(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid,ExplorationContent.Place place,boolean discover){
        check.run();var key=new DiscoveryKey(ctx.storyRunId(),ctx.playerId(),place.id(),discover);var existing=discoveries.get(key);
        if(existing!=null){if(existing.token()==token)return existing.result();return existing.result().handle((v,e)->null).thenComposeAsync(v->observe(ctx,token,valid,place,discover),owner);}
        var result=new CompletableFuture<View>();var job=new DiscoveryJob(token,result);discoveries.put(key,job);
        CompletionStage<View> work;
        try{work=CompletableFuture.completedFuture(null).thenComposeAsync(v->afterCurrent(ctx,valid,()->perform(ctx,token,valid,view->explorationStore.recordPlace(ctx,place,place.fact()==null?null:content.facts().get(place.fact()),discover,true).thenAccept(first->{}))),owner);}
        catch(RuntimeException e){work=CompletableFuture.failedFuture(e);}
        work.whenCompleteAsync((view,error)->{discoveries.remove(key,job);if(error==null)result.complete(view);else result.completeExceptionally(error);},owner);
        return result;
    }
    public CompletionStage<Void> enterPlaces(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid,double fx,double fy,double fz,double tx,double ty,double tz){
        check.run();if(content==null || Math.floor(fx)==Math.floor(tx)&&Math.floor(fy)==Math.floor(ty)&&Math.floor(fz)==Math.floor(tz))return CompletableFuture.completedFuture(null);
        var work=new ArrayList<CompletableFuture<?>>();
        for(var place:content.places().values())if((storyAccess==null||storyAccess.region(ctx.storyRunId(),place.region())&&storyAccess.area(ctx.storyRunId(),place.bounds())&&storyAccess.content(ctx.storyRunId(),tx,ty,tz))&&place.bounds().entered(fx,fy,fz,tx,ty,tz))work.add(observe(ctx,token,valid,place,place.discovery()==ExplorationContent.Discovery.ENTRY).toCompletableFuture());
        return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));
    }
    public CompletionStage<Void> interactPlace(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid,String reference,double x,double y,double z){
        check.run();if(content==null)return CompletableFuture.completedFuture(null);var work=new ArrayList<CompletableFuture<?>>();
        for(var place:content.places().values())if((storyAccess==null||storyAccess.region(ctx.storyRunId(),place.region())&&storyAccess.area(ctx.storyRunId(),place.bounds())&&storyAccess.content(ctx.storyRunId(),x,y,z))&&place.discovery()==ExplorationContent.Discovery.INTERACTION && Objects.equals(reference,place.interaction()) && place.bounds().contains(x,y,z))work.add(observe(ctx,token,valid,place,true).toCompletableFuture());
        return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));
    }
    public List<String> hudLines(View view){
        if(!view.journal().preferences().hudEnabled())return List.of();var lines=new ArrayList<String>();
        for(String id:new TreeSet<>(view.journal().tracked())){
            var state=view.states().get(id);if(state==null)throw new IllegalStateException("Unknown tracked quest");
            if(!view.exploration().blockedQuests().contains(id)&&(state.phase()==QuestStore.Phase.ACTIVE || state.phase()==QuestStore.Phase.REPORT_READY))lines.add(catalog.quest(id).title()+" — "+catalog.quest(id).guidance(state));
        }return List.copyOf(lines);
    }
    private CompletableFuture<Void> discoveryDrain(DiscoveryJob job){return job.result().handle((view,error)->{
        if(error==null)return (Void)null;Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();
        if(cause instanceof IllegalStateException && Set.of("STALE_JOURNAL","STALE_QUEST_LIST","STALE_COMMUNITY","QUEST_LIST_BUSY","JOURNAL_BUSY").contains(Objects.toString(cause.getMessage(),"")))return null;
        throw new CompletionException(error);
    });}
    public CompletionStage<Void> barrier(UUID run){check.run();var work=new ArrayList<CompletableFuture<?>>();pending.forEach((key,value)->{if(key.run().equals(run))work.add(value);});discoveries.forEach((key,job)->{if(key.run().equals(run))work.add(discoveryDrain(job));});if(holds.stream().anyMatch(k->k.run().equals(run)))return CompletableFuture.failedFuture(new IllegalStateException("JOURNAL_HOLD"));return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));}
    public boolean settled(UUID run){check.run();return discoveries.keySet().stream().noneMatch(k->k.run().equals(run)) && pending.keySet().stream().noneMatch(k->k.run().equals(run)) && holds.stream().noneMatch(k->k.run().equals(run));}
    public CompletionStage<Void> shutdown(){check.run();closed=true;var work=new ArrayList<CompletableFuture<?>>();work.addAll(pending.values());discoveries.values().forEach(job->work.add(discoveryDrain(job)));return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).thenRun(()->{if(!holds.isEmpty())throw new IllegalStateException("JOURNAL_HOLD");});}
}
