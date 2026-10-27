package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** 기존 bounded DB worker/owner를 사용합니다. DB 결과 불명/실패는 자동 재시도하지 않습니다. */
public final class PersonalQuest {
    private record Key(UUID run,UUID player,String quest) {}
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
    public CompletionStage<QuestStore.Saved> advance(Session s,QuestStore.Phase target) {
        check.run();
        if(!current(s))return rejected("STALE_QUEST");
        if(s.busy || s.hold || s.saved==null)return rejected("QUEST_BUSY_OR_HOLD");
        if(!QuestStore.follows(s.saved.phase(),target))return rejected("QUEST_ORDER");
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
    public CompletionStage<Void> barrier(UUID run){check.run();return CompletableFuture.allOf(sessions.values().stream()
        .filter(s->s.key.run().equals(run)).map(s->s.io.toCompletableFuture()).toArray(CompletableFuture[]::new));}
    public boolean settled(UUID run){check.run();return sessions.values().stream().filter(s->s.key.run().equals(run)).allMatch(s->!s.hold && s.io.toCompletableFuture().isDone() && !s.io.toCompletableFuture().isCompletedExceptionally());}
    public void release(UUID run){check.run();if(!settled(run))throw new IllegalStateException("QUEST_HOLD");sessions.entrySet().removeIf(e->e.getKey().run().equals(run));}
    public CompletionStage<Void> shutdown(){check.run();closed=true;return CompletableFuture.allOf(sessions.values().stream().map(s->s.io.toCompletableFuture()).toArray(CompletableFuture[]::new));}
}
