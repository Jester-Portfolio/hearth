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
    private final QuestStore store;
    private final Set<String> ids;
    private final Executor owner;
    private final Runnable check;
    private final Map<Key,Session> sessions=new HashMap<>();
    private final Map<Reader,Query> queries=new HashMap<>();
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
        check.run();if(closed)return rejected("CLOSED");
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
    /** 목표 도달만 automatic의 보고 생략을 허용합니다. 일반 보고 명령으로 목표를 건너뛸 수 없습니다. */
    public CompletionStage<QuestStore.Saved> reach(Session s,QuestContent content,double x,double y,double z) {
        check.run();
        if(!current(s))return rejected("STALE_QUEST");
        if(!s.key.quest().equals(content.id()) || !content.contains(x,y,z) || s.saved==null || s.saved.phase()!=QuestStore.Phase.ACTIVE)return rejected("QUEST_GOAL");
        boolean automatic=content.completionMode()==QuestContent.CompletionMode.AUTOMATIC;
        return advance(s,automatic?QuestStore.Phase.COMPLETED:QuestStore.Phase.REPORT_READY,automatic);
    }
    private CompletionStage<QuestStore.Saved> advance(Session s,QuestStore.Phase target,boolean automatic) {
        check.run();
        if(!current(s))return rejected("STALE_QUEST");
        if(s.busy || s.hold || s.saved==null)return rejected("QUEST_BUSY_OR_HOLD");
        if(!QuestStore.follows(s.saved.phase(),target) || (s.saved.phase()==QuestStore.Phase.ACTIVE && target==QuestStore.Phase.COMPLETED && !automatic))return rejected("QUEST_ORDER");
        s.busy=true;var result=new CompletableFuture<QuestStore.Saved>();
        s.io=store.advanceQuest(s.context,s.key.quest(),s.saved,target).thenAcceptAsync(row->{
            if(current(s))s.saved=row;
        },owner);
        s.io.whenCompleteAsync((v,e)->{
            s.busy=false;
            if(e!=null){s.hold=true;result.completeExceptionally(e);}
            else if(current(s))result.complete(s.saved);
            else result.completeExceptionally(new IllegalStateException("STALE_QUEST"));
        },owner);
        return result;
    }
    public QuestStore.Saved requireDialogue(Session s){
        require(s);if(s.busy || s.hold || s.saved==null)throw new IllegalStateException("QUEST_BUSY_OR_HOLD");return s.saved;
    }
    /** DB를 읽기만 합니다. 조회로 Session/open/수락 행을 만들거나 상태를 덮어쓰지 않습니다. */
    public CompletionStage<Map<String,QuestStore.Saved>> list(ServerProgressAccess.Context context,Object token,BooleanSupplier valid) {
        check.run();Reader reader=new Reader(context.storyRunId(),context.playerId());
        if(closed || token==null || !valid.getAsBoolean())return rejected("STALE_QUEST_LIST");
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
        for(String id:ids){Session s=sessions.get(new Key(context.storyRunId(),context.playerId(),id));
            if(s==null)continue;
            if(s.hold || s.io.toCompletableFuture().isCompletedExceptionally())throw new IllegalStateException("QUEST_LIST_HOLD");
            if(s.busy || !s.io.toCompletableFuture().isDone())throw new IllegalStateException("QUEST_LIST_BUSY");
        }
    }
    public CompletionStage<Void> barrier(UUID run){check.run();var tasks=new ArrayList<CompletableFuture<?>>();
        sessions.values().stream().filter(s->s.key.run().equals(run)).forEach(s->tasks.add(s.io.toCompletableFuture()));
        queries.forEach((reader,q)->{if(reader.run().equals(run))tasks.add(q.drained);});
        return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));}
    public boolean settled(UUID run){check.run();return queries.keySet().stream().noneMatch(reader->reader.run().equals(run)) && sessions.values().stream().filter(s->s.key.run().equals(run)).allMatch(s->!s.hold && s.io.toCompletableFuture().isDone() && !s.io.toCompletableFuture().isCompletedExceptionally());}
    public void release(UUID run){check.run();if(!settled(run))throw new IllegalStateException("QUEST_HOLD");sessions.entrySet().removeIf(e->e.getKey().run().equals(run));}
    public CompletionStage<Void> shutdown(){check.run();closed=true;var tasks=new ArrayList<CompletableFuture<?>>();
        sessions.values().forEach(s->tasks.add(s.io.toCompletableFuture()));queries.values().forEach(q->tasks.add(q.drained));
        return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));}
}
