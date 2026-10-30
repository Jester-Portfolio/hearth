package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.exploration.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;
import java.time.Duration;

/** run 단위 owner 순서화. DB worker에는 불변 요청과 atomic fence만 전달합니다. */
public final class SharedStory {
    public enum Phase{READY,CHECKING,OPEN,INPUT_CLOSED,PERSISTING,HOLD,RESULT_UNKNOWN}
    public record ChoiceView(String id,String label){}
    public record VoteView(UUID id,String state,List<ChoiceView> choices,int targets,long remainingSeconds){}
    public interface Effects{
        CompletionStage<Boolean> apply(UUID run,UUID request,StoryStore.Snapshot confirmed);
        default boolean required(UUID run,StoryStore.Snapshot saved){return true;}
        default Set<String> regions(){return Set.of();}
        default String notAppliedStatus(){return "NOT_IMPLEMENTED";}
    }
    private static final class Vote{
        final UUID id=UUID.randomUUID();final String stage;final Map<UUID,Object> roster;final long deadline;final Map<UUID,String> votes=new HashMap<>();String state="OPEN";
        Vote(String stage,Map<UUID,Object> roster,long deadline){this.stage=stage;this.roster=Map.copyOf(roster);this.deadline=deadline;}
    }
    private static final class Run{
        StoryStore.Snapshot saved;Phase phase=Phase.READY;Map<UUID,Object> online=Map.of();final AtomicLong rosterVersion=new AtomicLong(),generation=new AtomicLong();
        CompletableFuture<Void> pending;CompletionStage<Void> effectWork=CompletableFuture.completedFuture(null);Vote vote;StoryStore.Request unresolved;CommunityBoundary.Condition condition;String effect="NOT_IMPLEMENTED";long applied=-1,effectEpoch,effectGeneration;StoryStore.Snapshot effectSnapshot;CompletableFuture<Boolean> effectResult;
    }
    private final StoryStore store;private final StoryContent content;private final Executor owner;private final Runnable check;
    private final Function<ServerProgressAccess.Context,Map<UUID,Object>> online;private final LongSupplier clock;private final long voteNanos;
    private final Map<UUID,Run> runs=new HashMap<>();private boolean closed;
    private Consumer<ServerProgressAccess.Context> changed=c->{};private BiConsumer<UUID,String> notice=(r,s)->{};private Effects effects=(r,id,s)->CompletableFuture.completedFuture(false);
    public SharedStory(StoryStore store,StoryContent content,Executor owner,Runnable check,Function<ServerProgressAccess.Context,Map<UUID,Object>> online,LongSupplier clock,Duration voteTime){
        this.store=store;this.content=content;this.owner=owner;this.check=check;this.online=online;this.clock=clock;voteNanos=voteTime.toNanos();if(voteNanos<=0)throw new IllegalArgumentException("Invalid vote duration");
    }
    public StoryContent content(){return content;}
    public void changed(Consumer<ServerProgressAccess.Context> listener){changed=listener;}
    public void notice(BiConsumer<UUID,String> listener){notice=listener;}
    public void effects(Effects port){check.run();effects=Objects.requireNonNull(port);runs.values().forEach(this::resetEffect);}
    private void resetEffect(Run r){r.effectEpoch++;r.applied=-1;r.effect="PENDING";r.effectSnapshot=null;}
    public BooleanSupplier effectPermit(UUID id,StoryStore.Snapshot saved){var r=run(id);long epoch=r.effectEpoch,gen=r.generation.get();return ()->{check.run();return r.saved==saved&&r.effectEpoch==epoch&&r.generation.get()==gen&&healthy(id);};}
    public void worldUnloaded(UUID id){resetEffect(run(id));}
    public boolean effectAllows(UUID id,String region){var r=run(id);return !effects.regions().contains(region)||r.saved!=null&&!effects.required(id,r.saved)||healthy(id)&&r.effect.equals("APPLIED")&&r.applied==r.saved.story().revision();}
    public CompletionStage<Boolean> reconcile(ServerProgressAccess.Context ctx){
        var r=run(ctx.storyRunId());if(!healthy(ctx.storyRunId()))return CompletableFuture.completedFuture(false);
        var saved=r.saved;long gen=r.generation.get();
        if(r.effectResult!=null&&!r.effectResult.isDone()&&r.effectSnapshot==saved&&r.effectGeneration==gen)return r.effectResult;
        var prior=r.effectWork;long epoch=++r.effectEpoch;r.effect="PENDING";r.applied=-1;r.effectSnapshot=saved;r.effectGeneration=gen;var result=new CompletableFuture<Boolean>();r.effectResult=result;
        r.effectWork=prior.handle((v,e)->null).thenComposeAsync(v->{
            if(r.effectEpoch!=epoch||r.saved!=saved||r.generation.get()!=gen||!healthy(ctx.storyRunId()))throw new IllegalStateException("EFFECT_STALE");
            if(!effects.required(ctx.storyRunId(),saved)){r.effect="NOT_REQUIRED";return CompletableFuture.completedFuture(false);}
            UUID request=saved.branches().values().stream().map(StoryStore.Branch::request).findFirst().orElse(new UUID(0,0));
            return effects.apply(ctx.storyRunId(),request,saved);
        },owner).handleAsync((ok,error)->{
            boolean current=r.effectEpoch==epoch&&r.saved==saved&&r.generation.get()==gen&&healthy(ctx.storyRunId());boolean applied=current&&error==null&&Boolean.TRUE.equals(ok);
            if(current){if(applied){r.applied=saved.story().revision();r.effect="APPLIED";}else if(error!=null){var e=cause(error);r.effect="EFFECT_CONFLICT".equals(e.getMessage())?"EFFECT_CONFLICT":"EFFECT_ERROR";}else if(!r.effect.equals("NOT_REQUIRED"))r.effect=effects.notAppliedStatus();try{changed.accept(ctx);}catch(RuntimeException ignored){}}
            result.complete(applied);return (Void)null;
        },owner);return result;
    }
    private Run run(UUID id){check.run();return runs.computeIfAbsent(id,x->new Run());}
    public StoryStore.Snapshot cached(UUID id){return run(id).saved;}
    public boolean known(UUID id){return cached(id)!=null;}
    public boolean healthy(UUID id){var r=run(id);return r.saved!=null&&r.phase!=Phase.HOLD&&r.phase!=Phase.RESULT_UNKNOWN;}
    public Phase phase(UUID id){return run(id).phase;}
    public String effectStatus(UUID id){var r=run(id);return r.phase==Phase.HOLD||r.phase==Phase.RESULT_UNKNOWN?"PENDING":r.effect;}
    private void notify(UUID id,String text){try{notice.accept(id,text);}catch(RuntimeException ignored){}}
    private void validate(StoryStore.Snapshot s){if(s.story().stage()!=null)content.stage(s.story().stage());for(String id:s.completed())content.stage(id);for(var b:s.branches().values())if(content.stage(b.stage()).choices().stream().noneMatch(c->c.id().equals(b.choice())))throw new IllegalStateException("UNREGISTERED_BRANCH");}
    private Map<UUID,Object> roster(ServerProgressAccess.Context ctx){var r=run(ctx.storyRunId());var latest=Map.copyOf(online.apply(ctx));if(!r.online.equals(latest)){r.online=latest;r.rosterVersion.incrementAndGet();r.condition=null;}return latest;}
    public void rosterChanged(ServerProgressAccess.Context ctx){var r=run(ctx.storyRunId());var before=r.rosterVersion.get();roster(ctx);if(before!=r.rosterVersion.get()&&r.phase==Phase.OPEN){
        r.vote.state="CANCELLED";r.phase=Phase.READY;notify(ctx.storyRunId(),"접속 대상 변경으로 투표를 취소했습니다. 조건 확인 후 재투표합니다.");
        if(!r.online.isEmpty())startVote(ctx,null,()->true,true).exceptionally(e->{notify(ctx.storyRunId(),"재투표 조건을 확인하지 못했습니다. 다시 요청해 주세요.");return null;});
    }}
    public void poll(ServerProgressAccess.Context ctx){rosterChanged(ctx);var r=run(ctx.storyRunId());if(r.phase==Phase.OPEN&&clock.getAsLong()>=r.vote.deadline){r.vote.state="UNDECIDED";r.phase=Phase.READY;notify(ctx.storyRunId(),"시간 만료·과반수 미달로 분기를 확정하지 않았습니다. 재투표할 수 있습니다.");}}
    private static Throwable cause(Throwable e){while(e instanceof CompletionException&&e.getCause()!=null)e=e.getCause();return e;}
    private <T> CompletionStage<T> start(ServerProgressAccess.Context ctx,Supplier<CompletionStage<T>> action,boolean resolving){
        var r=run(ctx.storyRunId());if(closed||r.pending!=null||!resolving&&(r.phase==Phase.HOLD||r.phase==Phase.RESULT_UNKNOWN))return CompletableFuture.failedFuture(new IllegalStateException("STORY_BUSY_OR_HOLD"));
        var result=new CompletableFuture<T>();var drain=new CompletableFuture<Void>();r.pending=drain;
        CompletionStage<T> work;try{work=action.get();}catch(RuntimeException e){work=CompletableFuture.failedFuture(e);}
        work.whenCompleteAsync((value,error)->{
            if(error!=null){var e=cause(error);if(r.phase==Phase.RESULT_UNKNOWN){}else if(e instanceof StorageFailure f&&!Set.of("CONDITIONS_UNMET","STALE_ROSTER").contains(f.code()))r.phase=Phase.HOLD;else if(r.phase==Phase.CHECKING||r.phase==Phase.PERSISTING||r.phase==Phase.INPUT_CLOSED)r.phase=Phase.READY;}
            r.pending=null;if(r.phase==Phase.HOLD||r.phase==Phase.RESULT_UNKNOWN)drain.completeExceptionally(error==null?new IllegalStateException("STORY_HOLD"):error);else drain.complete(null);
            if(error==null)result.complete(value);else result.completeExceptionally(error);
        },owner);return result;
    }
    public CompletionStage<StoryStore.Snapshot> load(ServerProgressAccess.Context ctx){var r=run(ctx.storyRunId());if(r.saved!=null&&r.phase!=Phase.HOLD&&r.phase!=Phase.RESULT_UNKNOWN)return CompletableFuture.completedFuture(r.saved);
        if(r.saved==null&&r.pending!=null)return r.pending.thenComposeAsync(v->load(ctx),owner);
        return start(ctx,()->{long gen=r.generation.get();r.phase=Phase.CHECKING;return store.readStory(ctx).thenApplyAsync(s->{try{if(r.generation.get()!=gen)throw new IllegalStateException("STALE_STORY_GENERATION");validate(s);r.saved=s;resetEffect(r);r.phase=Phase.READY;return s;}catch(RuntimeException e){r.phase=Phase.HOLD;throw e;}},owner).thenComposeAsync(s->reconcile(ctx).thenApply(ok->s),owner);},false);
    }
    public CompletionStage<StoryStore.Snapshot> refresh(ServerProgressAccess.Context ctx){var r=run(ctx.storyRunId());if(r.phase==Phase.RESULT_UNKNOWN)return CompletableFuture.failedFuture(new IllegalStateException("RESULT_UNKNOWN"));return start(ctx,()->store.readStory(ctx).thenApplyAsync(s->{validate(s);r.saved=s;resetEffect(r);r.phase=Phase.READY;changed.accept(ctx);return s;},owner).thenComposeAsync(s->reconcile(ctx).thenApply(ok->s),owner),true);}
    private StoryContent.Stage current(Run r){if(r.saved==null)throw new IllegalStateException("STORY_NOT_READY");return r.saved.story().stage()==null?null:content.stage(r.saved.story().stage());}
    private void actor(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid){if(!valid.getAsBoolean()||roster(ctx).get(ctx.playerId())!=token)throw new IllegalStateException("STALE_STORY_ACTOR");}
    private Set<String> required(Run r,StoryContent.Stage target){return content.conditions(current(r),target);}
    private CompletionStage<Map<UUID,Boolean>> evaluate(ServerProgressAccess.Context ctx,StoryContent.Stage target,boolean requireAll){
        var r=run(ctx.storyRunId());var targets=roster(ctx);long version=r.rosterVersion.get(),gen=r.generation.get(),revision=r.saved.story().revision();var ids=required(r,target);
        if(targets.isEmpty())return CompletableFuture.failedFuture(new IllegalStateException("NO_ONLINE_TARGETS"));
        return store.storyConditions(ctx,targets.keySet(),ids).thenApplyAsync(met->{
            roster(ctx);if(version!=r.rosterVersion.get()||gen!=r.generation.get()||revision!=r.saved.story().revision())throw new IllegalStateException("STALE_STORY_CONDITIONS");
            if(!met.keySet().equals(targets.keySet()))throw new IllegalStateException("INVALID_CONDITION_TARGETS");r.condition=new CommunityBoundary.Condition(r.saved.story().stage(),met);
            if(requireAll&&met.values().stream().anyMatch(v->!v))throw new IllegalStateException("CONDITIONS_UNMET");return met;
        },owner);
    }
    public CompletionStage<Void> updateConditions(ServerProgressAccess.Context ctx){var r=run(ctx.storyRunId());if(!healthy(ctx.storyRunId())||r.pending!=null||r.phase!=Phase.READY||r.saved.story().stage()==null)return CompletableFuture.completedFuture(null);
        var c=current(r);var target=content.target(c,c.choices().isEmpty()?null:c.choices().getFirst().id());
        if(c.complete()==null||target.entry()==null||r.saved.completed().contains(c.id())){r.condition=null;return CompletableFuture.completedFuture(null);}
        return start(ctx,()->evaluate(ctx,target,false).thenAccept(x->{}),false);
    }
    public Optional<CommunityBoundary.Condition> condition(ServerProgressAccess.Context ctx,Set<UUID> ids){var r=run(ctx.storyRunId());rosterChanged(ctx);return healthy(ctx.storyRunId())&&r.condition!=null&&r.condition.met().keySet().equals(ids)?Optional.of(r.condition):Optional.empty();}
    private StoryStore.Request request(ServerProgressAccess.Context ctx,UUID id,String choice,Map<UUID,Object> targets){var r=run(ctx.storyRunId());var c=current(r);var target=content.target(c,choice);var facts=new ArrayList<ExplorationContent.Fact>();if(target.fact()!=null)facts.add(target.fact());String label=null;
        if(choice!=null){var selected=c.choices().stream().filter(x->x.id().equals(choice)).findFirst().orElseThrow();label=selected.label();if(selected.fact()!=null)facts.add(selected.fact());}
        return new StoryStore.Request(id,r.saved.story().revision(),c==null?null:c.id(),target.id(),target.title(),Boolean.TRUE.equals(r.saved.story().explorationOpen())||target.opens(),choice,label,targets.keySet(),required(r,target),facts);
    }
    private CompletionStage<StoryStore.Snapshot> persist(ServerProgressAccess.Context ctx,StoryStore.Request request,boolean frozen){var r=run(ctx.storyRunId());long rosterVersion=r.rosterVersion.get(),gen=r.generation.get();r.phase=Phase.PERSISTING;r.unresolved=request;
        return store.commitStory(ctx,request,()->r.generation.get()==gen&&(frozen||r.rosterVersion.get()==rosterVersion)).handleAsync((saved,error)->{
            if(error!=null){var e=cause(error);if(e instanceof StorageFailure f&&(f.code().equals("COMMIT_UNKNOWN")||f.code().startsWith("08")||f.code().equals("57P01")))r.phase=Phase.RESULT_UNKNOWN;else{r.phase=e instanceof StorageFailure f&&Set.of("STALE_ROSTER","CONDITIONS_UNMET").contains(f.code())?Phase.READY:Phase.HOLD;r.unresolved=null;}throw new CompletionException(error);}
            if(r.generation.get()!=gen){r.phase=Phase.HOLD;throw new IllegalStateException("STALE_STORY_GENERATION");}
            confirm(ctx,saved,request);return saved;
        },owner);
    }
    private void confirm(ServerProgressAccess.Context ctx,StoryStore.Snapshot saved,StoryStore.Request request){var r=run(ctx.storyRunId());validate(saved);r.saved=saved;r.phase=Phase.READY;r.unresolved=null;r.condition=null;if(r.vote!=null&&r.vote.id.equals(request.id()))r.vote.state="COMMITTED";try{changed.accept(ctx);}catch(RuntimeException ignored){}
        notify(ctx.storyRunId(),request.choice()==null?"공유 단계 저장을 확인했습니다.":"공동체 분기를 저장·확정했습니다: "+request.label());
        reconcile(ctx);
    }
    /** 현재 화면의 요청을 기존 전이 경로로만 연결합니다. 대상·조건은 실행 시 재검사합니다. */
    public CompletionStage<?> act(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid,StoryGuide.Action action){
        try{StoryGuide.require(content,ctx.storyRunId(),cached(ctx.storyRunId()),phase(ctx.storyRunId()),action);
            return action.command().equals("/hearthstory next")?advance(ctx,token,valid):openVote(ctx,token,valid);
        }catch(RuntimeException rejected){return CompletableFuture.failedFuture(rejected);}
    }
    public CompletionStage<StoryStore.Snapshot> advance(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid){if(run(ctx.storyRunId()).phase!=Phase.READY)return CompletableFuture.failedFuture(new IllegalStateException("STORY_BUSY_OR_HOLD"));return start(ctx,()->{
        var r=run(ctx.storyRunId());actor(ctx,token,valid);var c=current(r);if(c!=null&&!c.choices().isEmpty())throw new IllegalStateException("VOTE_REQUIRED");if(c!=null&&r.saved.completed().contains(c.id()))throw new IllegalStateException("STORY_FINISHED");r.phase=Phase.CHECKING;var target=content.target(c,null);
        return evaluate(ctx,target,true).thenComposeAsync(v->{actor(ctx,token,valid);return persist(ctx,request(ctx,UUID.randomUUID(),null,r.online),false);},owner);
    },false);}
    public CompletionStage<VoteView> openVote(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid){return startVote(ctx,token,valid,false);}
    private CompletionStage<VoteView> startVote(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid,boolean automatic){return start(ctx,()->{
        var r=run(ctx.storyRunId());if(r.phase==Phase.OPEN)throw new IllegalStateException("VOTE_ALREADY_OPEN");if(!automatic)actor(ctx,token,valid);var c=current(r);if(c==null||c.choices().isEmpty()||r.saved.branches().containsKey(c.id()))throw new IllegalStateException("NO_CURRENT_BRANCH");r.phase=Phase.CHECKING;
        return evaluate(ctx,content.target(c,c.choices().getFirst().id()),true).thenApplyAsync(v->{if(!automatic)actor(ctx,token,valid);r.vote=new Vote(c.id(),r.online,clock.getAsLong()+voteNanos);r.phase=Phase.OPEN;notify(ctx.storyRunId(),"스토리 분기 투표가 시작됐습니다. 30초 안에 채팅 선택지를 선택하세요.");return vote(ctx.storyRunId()).orElseThrow();},owner);
    },false);}
    public Optional<VoteView> vote(UUID run){var r=run(run);if(r.vote==null)return Optional.empty();var c=content.stage(r.vote.stage);return Optional.of(new VoteView(r.vote.id,r.vote.state,c.choices().stream().map(x->new ChoiceView(x.id(),x.label())).toList(),r.vote.roster.size(),Math.max(0,(r.vote.deadline-clock.getAsLong()+999999999L)/1000000000L)));}
    public CompletionStage<StoryStore.Snapshot> cast(ServerProgressAccess.Context ctx,Object token,BooleanSupplier valid,UUID vote,String choice){var r=run(ctx.storyRunId());
        try{poll(ctx);actor(ctx,token,valid);if(r.phase!=Phase.OPEN||r.vote==null||!r.vote.id.equals(vote)||r.vote.roster.get(ctx.playerId())!=token)throw new IllegalStateException("STALE_VOTE");var c=content.stage(r.vote.stage);
            if(!choice.equals("abstain")&&c.choices().stream().noneMatch(x->x.id().equals(choice)))throw new IllegalArgumentException("Unknown vote choice");if(r.vote.votes.putIfAbsent(ctx.playerId(),choice)!=null)throw new IllegalStateException("DUPLICATE_VOTE");
            long count=r.vote.votes.values().stream().filter(choice::equals).count();if(choice.equals("abstain")||count<=r.vote.roster.size()/2)return CompletableFuture.completedFuture(r.saved);
            r.vote.state="INPUT_CLOSED";r.phase=Phase.INPUT_CLOSED;var request=request(ctx,r.vote.id,choice,r.vote.roster);notify(ctx.storyRunId(),"과반수로 투표 입력을 마감했습니다. DB 저장 확인 전에는 분기가 확정되지 않습니다.");return start(ctx,()->persist(ctx,request,true),false);
        }catch(RuntimeException e){return CompletableFuture.failedFuture(e);}
    }
    public CompletionStage<StoryStore.Snapshot> resolve(ServerProgressAccess.Context ctx){var r=run(ctx.storyRunId());if(r.phase!=Phase.RESULT_UNKNOWN||r.unresolved==null)return CompletableFuture.failedFuture(new IllegalStateException("NO_UNKNOWN_REQUEST"));var request=r.unresolved;
        return start(ctx,()->store.storyReceipt(ctx,request.id()).thenComposeAsync(found->{if(found.isEmpty()||!found.get().equals(StoryStore.fingerprint(request))){r.phase=Phase.RESULT_UNKNOWN;throw new IllegalStateException("RESULT_STILL_UNKNOWN");}return store.readStory(ctx);},owner).thenApplyAsync(saved->{confirm(ctx,saved,request);return saved;},owner),true);
    }
    public void invalidate(UUID run){var r=run(run);r.generation.incrementAndGet();resetEffect(r);}
    public CompletionStage<Void> barrier(UUID id){var r=run(id);if(r.phase==Phase.HOLD||r.phase==Phase.RESULT_UNKNOWN)return CompletableFuture.failedFuture(new IllegalStateException("STORY_HOLD"));return r.pending==null?r.effectWork:CompletableFuture.allOf(r.pending,r.effectWork.toCompletableFuture());}
    public boolean settled(UUID id){var r=run(id);return r.pending==null&&r.effectWork.toCompletableFuture().isDone()&&r.phase!=Phase.HOLD&&r.phase!=Phase.RESULT_UNKNOWN;}
    public CompletionStage<Void> shutdown(){check.run();closed=true;var tasks=new ArrayList<CompletableFuture<?>>();for(var id:runs.keySet())tasks.add(barrier(id).toCompletableFuture());return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));}
}
