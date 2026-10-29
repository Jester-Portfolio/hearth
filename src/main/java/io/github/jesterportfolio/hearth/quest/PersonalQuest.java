package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** 기존 bounded DB worker/owner를 사용합니다. DB 결과 불명/실패는 자동 재시도하지 않습니다. */
public final class PersonalQuest {
    private record Key(UUID run,UUID player,String quest) {}
    private record Reader(UUID run,UUID player) {}
    private final class Query {
        final CompletableFuture<Map<String,QuestStore.Saved>> result=new CompletableFuture<>();
        final CompletableFuture<Void> drained=new CompletableFuture<>();
    }
    public final class Session {
        final ServerProgressAccess.Context context;
        final Key key;
        final Object token;
        final BooleanSupplier valid;
        QuestStore.Saved saved;
        CompletionStage<Void> io=CompletableFuture.completedFuture(null);
        final CompletableFuture<Session> ready=new CompletableFuture<>();
        boolean busy=true,hold;
        Session(ServerProgressAccess.Context c,String quest,Object token,BooleanSupplier valid) {
            context=c;key=new Key(c.storyRunId(),c.playerId(),quest);this.token=token;this.valid=valid;
        }
        public QuestStore.Saved saved(){check.run();return saved;}
        public boolean held(){check.run();return hold;}
        public boolean busy(){check.run();return busy;}
    }
    private java.util.function.Function<UUID,CompletionStage<Void>> beforeChapter=r->CompletableFuture.completedFuture(null);
    public void beforeChapter(java.util.function.Function<UUID,CompletionStage<Void>> hook){check.run();beforeChapter=hook;}
    private Set<String> requiredRules=Set.of();
    public void requireRules(Set<String> ids){check.run();if(!sessions.isEmpty())throw new IllegalStateException("Rules already active");requiredRules=Set.copyOf(ids);}
    private QuestRules rules=QuestRules.empty();private java.util.function.LongSupplier clock=System::currentTimeMillis;
    private final Map<Reader,CompletableFuture<Void>> chapterJobs=new HashMap<>();private final Set<Reader> chapterHolds=new HashSet<>();
    public void rules(QuestRules definitions){check.run();if(!sessions.isEmpty())throw new IllegalStateException("Rules already active");rules=definitions;}
    public void clock(java.util.function.LongSupplier source){check.run();if(!sessions.isEmpty())throw new IllegalStateException("Clock already active");clock=source;}
    private java.util.function.BiConsumer<ServerProgressAccess.Context,String> completed=(ctx,id)->{};
    public void completed(java.util.function.BiConsumer<ServerProgressAccess.Context,String> handler){check.run();completed=handler;}
    private boolean chapterBusy(ServerProgressAccess.Context ctx){var key=new Reader(ctx.storyRunId(),ctx.playerId());return chapterJobs.containsKey(key)||chapterHolds.contains(key);}
    private long deadline(String id){long duration=rules.rule(id).timeoutMillis();return duration==0?0:Math.addExact(clock.getAsLong(),duration);}
    private final QuestStore store;
    private final Set<String> ids;
    private final Executor owner;
    private final Runnable check;
    private final Map<Key,Session> sessions=new HashMap<>();
    private final Map<Reader,Query> queries=new HashMap<>();
    private final Map<Key,CompletableFuture<Void>> movements=new HashMap<>(),failureJobs=new HashMap<>();
    private java.util.function.Consumer<ServerProgressAccess.Context> changed=c->{};
    public void changed(java.util.function.Consumer<ServerProgressAccess.Context> listener){check.run();changed=listener;}
    private void notifyChanged(Session s){try{changed.accept(s.context);}catch(RuntimeException ignored){}if(s.saved.phase()==QuestStore.Phase.COMPLETED)try{completed.accept(s.context,s.key.quest());}catch(RuntimeException ignored){}}
    private boolean closed;
    public PersonalQuest(QuestStore store,String id,Executor owner,Runnable check){this(store,Set.of(id),owner,check);}
    public PersonalQuest(QuestStore store,Collection<String> ids,Executor owner,Runnable check){
        this.store=store;this.ids=Set.copyOf(ids);this.owner=owner;this.check=check;
        if(this.ids.isEmpty() || this.ids.stream().anyMatch(id->!id.matches("[a-z][a-z0-9_]{0,63}")))throw new IllegalArgumentException("Invalid quest ids");
    }
    public CompletionStage<Session> open(ServerProgressAccess.Context context,Object token,BooleanSupplier valid) {
        if(ids.size()!=1)return rejected("AMBIGUOUS_QUEST");
        return open(context,ids.iterator().next(),token,valid);
    }
    public CompletionStage<Session> open(ServerProgressAccess.Context context,String quest,Object token,BooleanSupplier valid) {
        check.run();if(closed)return rejected("CLOSED");if(chapterBusy(context))return rejected("CHAPTER_BUSY_OR_HOLD");
        if(!ids.contains(quest))return rejected("UNKNOWN_QUEST");
        Key key=new Key(context.storyRunId(),context.playerId(),quest);Session previous=sessions.get(key);
        if(previous!=null && previous.token==token)return previous.ready;
        Session s=new Session(context,quest,token,valid);sessions.put(key,s);
        // 이전 저장은 콜백 유효성과 별도로 끝까지 기다립니다. 실패 HOLD도 새 세션으로 지우지 않습니다.
        s.io=(previous==null ? CompletableFuture.<Void>completedFuture(null) : previous.io)
            .thenComposeAsync(v->{require(s);return store.readQuest(context,quest);},owner)
            .thenAcceptAsync(row->{s.saved=row;},owner);
        s.io.whenCompleteAsync((v,e)->{
            s.busy=false;
            if(e!=null){s.hold=true;s.ready.completeExceptionally(e);}
            else if(!current(s))s.ready.completeExceptionally(new IllegalStateException("STALE_QUEST"));
            else s.ready.complete(s);
        },owner);
        return s.ready;
    }
    private boolean current(Session s){return !closed && sessions.get(s.key)==s && s.valid.getAsBoolean();}
    private void require(Session s){check.run();if(!current(s))throw new IllegalStateException("STALE_QUEST");}
    private static <T> CompletionStage<T> rejected(String code){return CompletableFuture.failedFuture(new IllegalStateException(code));}
    public CompletionStage<QuestStore.Saved> advance(Session s,QuestStore.Phase target) { return advance(s,target,false); }
    public CompletionStage<QuestStore.Saved> accept(Session s,QuestContent c){
        check.run();if(!s.key.quest().equals(c.id()) || c.startMode()!=QuestContent.StartMode.NPC)return rejected("QUEST_START_MODE");return begin(s,c);
    }
    public CompletionStage<QuestStore.Saved> start(Session s,QuestContent c,double x,double y,double z){
        check.run();if(!s.key.quest().equals(c.id()) || !c.startsAt(x,y,z) || s.saved==null || s.saved.phase()!=QuestStore.Phase.NOT_ACCEPTED)return rejected("QUEST_START_AREA");return begin(s,c);
    }
    private void dependencyReady(Session dependency){
        if(dependency!=null && (dependency.busy || dependency.hold || dependency.io.toCompletableFuture().isCompletedExceptionally()))throw new IllegalStateException("QUEST_PREREQUISITE_BUSY");
    }
    /** 선행 조회부터 시작 INSERT까지 같은 세션의 busy/저장 장벽을 유지합니다. */
    private CompletionStage<QuestStore.Saved> begin(Session s,QuestContent c){
        if(requiredRules.contains(c.id())&&!rules.rules().containsKey(c.id()))return rejected("QUEST_RULES_NOT_READY");
        check.run();if(!current(s))return rejected("STALE_QUEST");
        if(chapterBusy(s.context)||s.busy || s.hold || s.saved==null)return rejected("QUEST_BUSY_OR_HOLD");
        if(s.saved.phase()!=QuestStore.Phase.NOT_ACCEPTED)return rejected("QUEST_ORDER");
        if(c.requiresCompleted()==null)return advance(s,QuestStore.Phase.ACTIVE);
        if(!ids.contains(c.requiresCompleted()))return rejected("UNKNOWN_PREREQUISITE");
        Key key=new Key(s.key.run(),s.key.player(),c.requiresCompleted());var dependency=sessions.get(key);
        try {dependencyReady(dependency);}catch(RuntimeException error){return CompletableFuture.failedFuture(error);}
        var dependencyIO=dependency==null?null:dependency.io;var expected=s.saved;
        var drain=new CompletableFuture<Void>();var result=new CompletableFuture<QuestStore.Saved>();s.busy=true;s.io=drain;
        store.readQuest(s.context,c.requiresCompleted()).thenComposeAsync(row->{
            require(s);if(s.hold || s.io!=drain || s.saved!=expected)throw new IllegalStateException("QUEST_PREREQUISITE_CHANGED");
            if(sessions.get(key)!=dependency || dependency!=null && dependency.io!=dependencyIO)throw new IllegalStateException("QUEST_PREREQUISITE_CHANGED");
            dependencyReady(dependency);
            if(!row.satisfies())throw new IllegalStateException("QUEST_LOCKED");
            return store.advanceQuest(s.context,c.id(),expected,QuestStore.Phase.ACTIVE,expected.objectiveIndex(),deadline(c.id()));
        },owner).whenCompleteAsync((row,error)->{
            s.busy=false;Throwable cause=error;while(cause instanceof CompletionException && cause.getCause()!=null)cause=cause.getCause();
            boolean rejected=cause instanceof StorageFailure f&&Set.of("QUEST_BRANCH_LOCKED","QUEST_RULES_NOT_READY").contains(f.code())||cause instanceof IllegalStateException && Set.of("QUEST_LOCKED","QUEST_PREREQUISITE_BUSY","QUEST_PREREQUISITE_CHANGED","STALE_QUEST").contains(Objects.toString(cause.getMessage(),""));
            if(error!=null && !rejected){s.hold=true;drain.completeExceptionally(error);}else drain.complete(null);
            if(error!=null)result.completeExceptionally(error);
            else if(current(s)){s.saved=row;notifyChanged(s);result.complete(row);}else result.completeExceptionally(new IllegalStateException("STALE_QUEST"));
        },owner);
        return result;
    }
    /** 블록 이동·구역 진입에만 읽기를 요청하고 진행 중 요청은 합칩니다. */
    public CompletionStage<Optional<QuestStore.Saved>> enter(ServerProgressAccess.Context context,QuestContent c,Object token,BooleanSupplier valid,
            double fx,double fy,double fz,double tx,double ty,double tz){
        check.run();if(closed || !valid.getAsBoolean())return rejected("STALE_QUEST");
        if(Math.floor(fx)==Math.floor(tx) && Math.floor(fy)==Math.floor(ty) && Math.floor(fz)==Math.floor(tz))return CompletableFuture.completedFuture(Optional.empty());
        boolean start=c.startMode()==QuestContent.StartMode.AUTOMATIC && c.startArea().entered(fx,fy,fz,tx,ty,tz);
        boolean goal=c.goals().stream().anyMatch(g->g.interaction()==null&&g.area().contains(tx,ty,tz));
        if(!start && !goal)return CompletableFuture.completedFuture(Optional.empty());
        Key key=new Key(context.storyRunId(),context.playerId(),c.id());
        if(movements.containsKey(key))return CompletableFuture.completedFuture(Optional.empty());
        var drain=new CompletableFuture<Void>();movements.put(key,drain);var result=new CompletableFuture<Optional<QuestStore.Saved>>();
        open(context,c.id(),token,valid).thenComposeAsync(q->{
            require(q);if(q.hold)throw new IllegalStateException("QUEST_HOLD");if(q.busy)return CompletableFuture.completedFuture(Optional.<QuestStore.Saved>empty());
            // 시작과 목표가 겹쳐도 이 진입은 시작만 수행합니다.
            if(start && q.saved.phase()==QuestStore.Phase.NOT_ACCEPTED)return start(q,c,tx,ty,tz).thenApply(Optional::of);
            return goal && q.saved.phase()==QuestStore.Phase.ACTIVE && c.goal(q.saved).interaction()==null && c.goal(q.saved).area().contains(tx,ty,tz)?reach(q,c,tx,ty,tz).thenApply(Optional::of):CompletableFuture.completedFuture(Optional.<QuestStore.Saved>empty());
        },owner).whenCompleteAsync((saved,error)->{
            movements.remove(key,drain);
            Throwable cause=error;while(cause instanceof CompletionException && cause.getCause()!=null)cause=cause.getCause();
            boolean locked=cause instanceof IllegalStateException && Set.of("QUEST_LOCKED","QUEST_PREREQUISITE_BUSY","QUEST_PREREQUISITE_CHANGED").contains(Objects.toString(cause.getMessage(),""));
            if(cause==null || locked || cause instanceof IllegalStateException && "STALE_QUEST".equals(cause.getMessage()))drain.complete(null);else drain.completeExceptionally(error);
            if(error==null)result.complete(saved);else if(locked)result.complete(Optional.empty());else result.completeExceptionally(error);
        },owner);
        return result;
    }
    /** 목표 도달만 automatic의 보고 생략을 허용합니다. 일반 보고 명령으로 목표를 건너뛸 수 없습니다. */
    public CompletionStage<QuestStore.Saved> reach(Session s,QuestContent content,double x,double y,double z) {
        check.run();
        if(!current(s))return rejected("STALE_QUEST");
        if(s.saved!=null&&s.saved.deadlineMillis()>0&&clock.getAsLong()>=s.saved.deadlineMillis())return rejected("QUEST_TIMEOUT_PENDING");
        if(!s.key.quest().equals(content.id()) || s.saved==null || s.saved.phase()!=QuestStore.Phase.ACTIVE || content.goal(s.saved).interaction()!=null || !content.goal(s.saved).area().contains(x,y,z))return rejected("QUEST_GOAL");
        return finishObjective(s,content);
    }
    public CompletionStage<QuestStore.Saved> interact(Session s,QuestContent content,String reference){
        check.run();if(!current(s))return rejected("STALE_QUEST");if(s.saved!=null&&s.saved.deadlineMillis()>0&&clock.getAsLong()>=s.saved.deadlineMillis())return rejected("QUEST_TIMEOUT_PENDING");if(!s.key.quest().equals(content.id())||s.saved==null||s.saved.phase()!=QuestStore.Phase.ACTIVE||reference==null||!reference.equals(content.goal(s.saved).interaction()))return rejected("QUEST_INTERACTION");return finishObjective(s,content);
    }
    private CompletionStage<QuestStore.Saved> finishObjective(Session s,QuestContent content){
        if(s.saved.objectiveIndex()+1<content.goals().size())return advance(s,QuestStore.Phase.ACTIVE,false,s.saved.objectiveIndex()+1);
        boolean automatic=content.completionMode()==QuestContent.CompletionMode.AUTOMATIC;
        return advance(s,automatic?QuestStore.Phase.COMPLETED:QuestStore.Phase.REPORT_READY,automatic);
    }
    private CompletionStage<QuestStore.Saved> advance(Session s,QuestStore.Phase target,boolean automatic){return advance(s,target,automatic,s.saved==null?0:s.saved.objectiveIndex());}
    private CompletionStage<QuestStore.Saved> advance(Session s,QuestStore.Phase target,boolean automatic,int index) {
        check.run();
        if(!current(s))return rejected("STALE_QUEST");
        if(chapterBusy(s.context)||s.busy || s.hold || s.saved==null)return rejected("QUEST_BUSY_OR_HOLD");
        if(s.saved.phase()==QuestStore.Phase.ACTIVE&&s.saved.deadlineMillis()>0&&clock.getAsLong()>=s.saved.deadlineMillis())return rejected("QUEST_TIMEOUT_PENDING");
        if(!(s.saved.phase()==QuestStore.Phase.ACTIVE && target==QuestStore.Phase.ACTIVE && index==s.saved.objectiveIndex()+1) && !QuestStore.follows(s.saved.phase(),target) || (s.saved.phase()==QuestStore.Phase.ACTIVE && target==QuestStore.Phase.COMPLETED && !automatic))return rejected("QUEST_ORDER");
        return persist(s,target,index,target==QuestStore.Phase.ACTIVE?(s.saved.phase()==QuestStore.Phase.NOT_ACCEPTED?deadline(s.key.quest()):s.saved.deadlineMillis()):0);
    }
    private CompletionStage<QuestStore.Saved> persist(Session s,QuestStore.Phase target,int index,long deadline){
        var expected=s.saved;var drained=new CompletableFuture<Void>();var result=new CompletableFuture<QuestStore.Saved>();s.busy=true;s.io=drained;
        store.advanceQuest(s.context,s.key.quest(),expected,target,index,deadline).whenCompleteAsync((row,error)->{
            s.busy=false;
            if(error==null&&(row==null||row.phase()!=target||row.objectiveIndex()!=index||row.revision()!=expected.revision()+1||row.deadlineMillis()!=deadline))error=new StorageFailure("quest","INVALID_SAVE_RESULT");
            if(error!=null){Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();if(cause instanceof StorageFailure f&&Set.of("QUEST_BRANCH_LOCKED","QUEST_RULES_NOT_READY").contains(f.code()))drained.complete(null);else{s.hold=true;drained.completeExceptionally(error);}result.completeExceptionally(error);}
            else{boolean live=current(s);if(live)s.saved=row;drained.complete(null);if(live){notifyChanged(s);result.complete(row);}else result.completeExceptionally(new IllegalStateException("STALE_QUEST"));}
        },owner).exceptionally(error->{drained.completeExceptionally(error);result.completeExceptionally(error);return null;});return result;
    }
    public CompletionStage<QuestStore.Saved> fail(Session s,QuestContent c,String condition){return fail(s,c,condition,false);}
    private CompletionStage<QuestStore.Saved> fail(Session s,QuestContent c,String condition,boolean queued){
        check.run();if(!current(s)||!queued&&chapterBusy(s.context))return rejected("STALE_QUEST");var rule=rules.rule(c.id());
        if(!s.key.quest().equals(c.id())||s.busy||s.hold||s.saved==null||s.saved.phase()!=QuestStore.Phase.ACTIVE||!rule.failures().contains(condition))return rejected("QUEST_FAILURE_NOT_ALLOWED");
        if(condition.equals("TIMEOUT")&&(s.saved.deadlineMillis()==0||clock.getAsLong()<s.saved.deadlineMillis()))return rejected("QUEST_TIMEOUT_NOT_REACHED");
        int index=s.saved.objectiveIndex();if(rule.restart()==QuestRules.Restart.BEGINNING)index=0;
        else if(rule.restart()==QuestRules.Restart.CHECKPOINT){int current=index;index=rule.checkpoints().stream().filter(i->i<=current).max(Integer::compareTo).orElse(0);}
        return control(s,QuestStore.Phase.FAILED,index,0);
    }
    public CompletionStage<QuestStore.Saved> retry(Session s,QuestContent c){
        check.run();if(!current(s)||chapterBusy(s.context)||!s.key.quest().equals(c.id())||s.busy||s.hold||s.saved==null||s.saved.phase()!=QuestStore.Phase.FAILED)return rejected("QUEST_RETRY_NOT_ALLOWED");
        return control(s,QuestStore.Phase.ACTIVE,s.saved.objectiveIndex(),deadline(c.id()));
    }
    private CompletionStage<QuestStore.Saved> control(Session s,QuestStore.Phase target,int index,long deadline){return persist(s,target,index,deadline);}
    public CompletionStage<Void> restore(ServerProgressAccess.Context ctx,Object token,java.util.function.BooleanSupplier valid,Map<String,QuestStore.Saved> states){check.run();var work=new ArrayList<CompletableFuture<?>>();for(var row:states.entrySet())if(row.getValue().phase()==QuestStore.Phase.ACTIVE&&!rules.rule(row.getKey()).failures().isEmpty())work.add(open(ctx,row.getKey(),token,valid).toCompletableFuture());return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));}
    public CompletionStage<Void> trigger(ServerProgressAccess.Context ctx,String condition,QuestCatalog catalog){
        check.run();if(chapterBusy(ctx))return rejected("CHAPTER_BUSY_OR_HOLD");var work=new ArrayList<CompletableFuture<?>>();for(var s:List.copyOf(sessions.values())){
            if(!s.context.storyRunId().equals(ctx.storyRunId())||!s.context.playerId().equals(ctx.playerId())||!current(s)||s.hold||s.saved==null||s.saved.phase()!=QuestStore.Phase.ACTIVE||!rules.rule(s.key.quest()).failures().contains(condition))continue;
            var pending=failureJobs.get(s.key);if(pending!=null){work.add(pending);continue;}var job=new CompletableFuture<Void>();failureJobs.put(s.key,job);work.add(job);
            s.io.thenComposeAsync(v->{if(!current(s))return rejected("STALE_QUEST");if(s.saved.phase()!=QuestStore.Phase.ACTIVE)return CompletableFuture.completedFuture(null);return fail(s,catalog.quest(s.key.quest()),condition,true);},owner).whenCompleteAsync((v,error)->{failureJobs.remove(s.key,job);if(error==null)job.complete(null);else job.completeExceptionally(error);},owner);
        }return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));
    }
    public CompletionStage<Void> expire(QuestCatalog catalog){check.run();var pending=new ArrayList<CompletableFuture<?>>();for(var s:List.copyOf(sessions.values()))if(current(s)&&!chapterBusy(s.context)&&!s.busy&&!s.hold&&s.saved!=null&&s.saved.phase()==QuestStore.Phase.ACTIVE&&s.saved.deadlineMillis()>0&&clock.getAsLong()>=s.saved.deadlineMillis())pending.add(fail(s,catalog.quest(s.key.quest()),"TIMEOUT").toCompletableFuture());return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));}
    public CompletionStage<QuestCompletionStore.ChapterState> skip(ServerProgressAccess.Context ctx,Object token,java.util.function.BooleanSupplier valid,QuestCompletionStore storage,QuestRules.Chapter chapter){
        check.run();var key=new Reader(ctx.storyRunId(),ctx.playerId());if(closed||token==null||!valid.getAsBoolean()||chapterBusy(ctx)||!chapter.equals(rules.chapters().get(chapter.id())))return rejected("CHAPTER_BUSY_OR_INVALID");
        var prior=CompletableFuture.allOf(barrier(ctx.storyRunId()).toCompletableFuture(),beforeChapter.apply(ctx.storyRunId()).toCompletableFuture());var drained=new CompletableFuture<Void>();var result=new CompletableFuture<QuestCompletionStore.ChapterState>();chapterJobs.put(key,drained);
        prior.thenComposeAsync(v->{if(!valid.getAsBoolean()||closed)throw new IllegalStateException("STALE_CHAPTER");return storage.skipChapter(ctx,chapter);},owner).whenCompleteAsync((row,error)->{
            chapterJobs.remove(key,drained);if(error==null){sessions.keySet().removeIf(k->k.run().equals(ctx.storyRunId())&&k.player().equals(ctx.playerId()));drained.complete(null);try{changed.accept(ctx);}catch(RuntimeException ignored){}if(valid.getAsBoolean())result.complete(row);else result.completeExceptionally(new IllegalStateException("STALE_CHAPTER"));}
            else{Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();boolean known=cause instanceof StorageFailure f&&Set.of("CHAPTER_NOT_PASSED","CHAPTER_BRANCH_NOT_CONFIRMED","UNKNOWN_CHAPTER").contains(f.code())||cause instanceof IllegalStateException&&"STALE_CHAPTER".equals(cause.getMessage());if(!known){chapterHolds.add(key);drained.completeExceptionally(error);}else drained.complete(null);result.completeExceptionally(error);}
        },owner);return result;
    }
    public QuestStore.Saved requireDialogue(Session s){
        require(s);if(s.busy || s.hold || s.saved==null)throw new IllegalStateException("QUEST_BUSY_OR_HOLD");return s.saved;
    }
    /** DB를 읽기만 합니다. 조회로 Session/open/수락 행을 만들거나 상태를 덮어쓰지 않습니다. */
    public CompletionStage<Map<String,QuestStore.Saved>> list(ServerProgressAccess.Context context,Object token,BooleanSupplier valid) {
        check.run();Reader reader=new Reader(context.storyRunId(),context.playerId());
        if(closed || chapterBusy(context) || token==null || !valid.getAsBoolean())return rejected("STALE_QUEST_LIST");
        if(queries.containsKey(reader))return rejected("QUEST_LIST_BUSY");
        var before=new HashMap<Key,CompletionStage<Void>>();
        try { listable(context); } catch(RuntimeException error){return CompletableFuture.failedFuture(error);}
        for(String id:ids){Key key=new Key(reader.run(),reader.player(),id);Session s=sessions.get(key);before.put(key,s==null?null:s.io);}
        var query=new Query();queries.put(reader,query);
        CompletionStage<Map<String,QuestStore.Saved>> read=CompletableFuture.completedFuture(new LinkedHashMap<>());
        // 같은 bounded worker를 순차 사용해 목록 조회가 큐를 한꺼번에 채우지 않게 합니다.
        for(String id:new TreeSet<>(ids))read=read.thenComposeAsync(rows->{
            if(closed || !valid.getAsBoolean())throw new IllegalStateException("STALE_QUEST_LIST");
            return store.readQuest(context,id).thenApply(row->{rows.put(id,row);return rows;});
        },owner);
        read.whenCompleteAsync((rows,error)->{
            Map<String,QuestStore.Saved> value=null;RuntimeException failed=null;
            try {
                if(closed || queries.get(reader)!=query || !valid.getAsBoolean())throw new IllegalStateException("STALE_QUEST_LIST");
                if(error!=null)throw new CompletionException(error);
                listable(context);
                for(var entry:before.entrySet()) {
                    Session s=sessions.get(entry.getKey());
                    if((s==null?null:s.io)!=entry.getValue())throw new IllegalStateException("QUEST_LIST_CHANGED");
                }
                value=Map.copyOf(rows);
            } catch(RuntimeException failure){failed=failure;}
            queries.remove(reader,query);query.drained.complete(null);
            if(failed==null)query.result.complete(value);else query.result.completeExceptionally(failed);
        },owner);
        return query.result;
    }
    /** 조회 완료와 실제 표시 사이에도 쓰기/HOLD·새 revision이 끼어들지 않았는지 owner에서 확인합니다. */
    public void requireListDisplay(ServerProgressAccess.Context context,Map<String,QuestStore.Saved> rows) {
        check.run();if(closed || !rows.keySet().equals(ids))throw new IllegalStateException("STALE_QUEST_LIST");
        listable(context);
        for(String id:ids){Session s=sessions.get(new Key(context.storyRunId(),context.playerId(),id));
            if(s!=null && s.valid.getAsBoolean() && s.saved!=null && !s.saved.equals(rows.get(id)))throw new IllegalStateException("QUEST_LIST_CHANGED");
        }
    }
    private void listable(ServerProgressAccess.Context context) {
        if(chapterBusy(context))throw new IllegalStateException("CHAPTER_BUSY_OR_HOLD");
        for(String id:ids){Session s=sessions.get(new Key(context.storyRunId(),context.playerId(),id));
            if(s==null)continue;
            if(s.hold || s.io.toCompletableFuture().isCompletedExceptionally())throw new IllegalStateException("QUEST_LIST_HOLD");
            if(s.busy || !s.io.toCompletableFuture().isDone())throw new IllegalStateException("QUEST_LIST_BUSY");
        }
    }
    public CompletionStage<Void> barrier(UUID run){check.run();if(chapterHolds.stream().anyMatch(k->k.run().equals(run)))return rejected("CHAPTER_HOLD");var tasks=new ArrayList<CompletableFuture<?>>();chapterJobs.forEach((k,v)->{if(k.run().equals(run))tasks.add(v);});
        sessions.values().stream().filter(s->s.key.run().equals(run)).forEach(s->tasks.add(s.io.toCompletableFuture()));
        queries.forEach((reader,q)->{if(reader.run().equals(run))tasks.add(q.drained);});
        movements.forEach((key,drain)->{if(key.run().equals(run))tasks.add(drain);});failureJobs.forEach((key,drain)->{if(key.run().equals(run))tasks.add(drain);});
        return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));}
    public boolean settled(UUID run){check.run();return failureJobs.keySet().stream().noneMatch(k->k.run().equals(run))&&chapterHolds.stream().noneMatch(k->k.run().equals(run))&&chapterJobs.keySet().stream().noneMatch(k->k.run().equals(run))&& movements.keySet().stream().noneMatch(key->key.run().equals(run)) && queries.keySet().stream().noneMatch(reader->reader.run().equals(run)) && sessions.values().stream().filter(s->s.key.run().equals(run)).allMatch(s->!s.busy && !s.hold && s.io.toCompletableFuture().isDone() && !s.io.toCompletableFuture().isCompletedExceptionally());}
    public void release(UUID run){check.run();if(!settled(run))throw new IllegalStateException("QUEST_HOLD");sessions.entrySet().removeIf(e->e.getKey().run().equals(run));}
    public CompletionStage<Void> shutdown(){check.run();closed=true;var tasks=new ArrayList<CompletableFuture<?>>();
        tasks.addAll(failureJobs.values());tasks.addAll(chapterJobs.values());if(!chapterHolds.isEmpty())tasks.add(CompletableFuture.failedFuture(new IllegalStateException("CHAPTER_HOLD")));sessions.values().forEach(s->tasks.add(s.io.toCompletableFuture()));queries.values().forEach(q->tasks.add(q.drained));movements.values().forEach(tasks::add);
        return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));}
}
