package io.github.jesterportfolio.hearth.features;

import io.github.jesterportfolio.hearth.persistence.ServerProgressAccess;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 개인 장면의 CAS 완료 확인 뒤에만 다음 프레임을 공개합니다. 늦은 응답/불명 결과는 HOLD합니다. */
public final class SceneProgress {
    public interface Repository{CompletionStage<Optional<FeatureStore.Saved>> read(ServerProgressAccess.Context c,String id);CompletionStage<FeatureStore.Saved> write(ServerProgressAccess.Context c,String id,long revision,byte[] payload);}
    public final class Session{public final ServerProgressAccess.Context context;public final Object token;public final SceneContent.Scene scene;private final BooleanSupplier current;private Cutscene.State state;private long revision;private boolean busy,hold;private CompletionStage<?> pending=CompletableFuture.completedFuture(null);Session(ServerProgressAccess.Context c,Object token,SceneContent.Scene scene,BooleanSupplier current,Cutscene.State state,long revision){context=c;this.token=token;this.scene=scene;this.current=current;this.state=state;this.revision=revision;}public Cutscene.State state(){return state;}public long revision(){return revision;}public boolean held(){return hold;}public boolean busy(){return busy;}}
    private record Key(UUID run,UUID player,String scene){}private final Map<Key,Session> sessions=new HashMap<>();private final Repository store;private final Executor owner;private final Runnable check;
    public SceneProgress(Repository store,Executor owner,Runnable check){this.store=store;this.owner=owner;this.check=check;}
    public SceneProgress(FeatureStore store,Executor owner,Runnable check){this(new Repository(){public CompletionStage<Optional<FeatureStore.Saved>> read(ServerProgressAccess.Context c,String id){return store.read(c,"cutscene",id);}public CompletionStage<FeatureStore.Saved> write(ServerProgressAccess.Context c,String id,long r,byte[] p){return store.write(c,"cutscene",id,r,p);}},owner,check);}
    private Key key(Session s){return new Key(s.context.storyRunId(),s.context.playerId(),s.scene.id());}private void require(Session s){check.run();if(sessions.get(key(s))!=s||!s.current.getAsBoolean()||s.busy||s.hold)throw new IllegalStateException("SCENE_STALE_OR_HOLD");}
    private record Opening(Object token,CompletableFuture<Session> result){}private final Map<Key,Opening> opening=new HashMap<>();
    public CompletionStage<Session> open(ServerProgressAccess.Context c,Object token,SceneContent.Scene scene,BooleanSupplier current){
        check.run();if(!current.getAsBoolean())return CompletableFuture.failedFuture(new IllegalStateException("SCENE_STALE_READ"));var key=new Key(c.storyRunId(),c.playerId(),scene.id());var old=sessions.get(key);
        if(old!=null&&old.token==token&&old.current.getAsBoolean()){require(old);return CompletableFuture.completedFuture(old);}
        if(old!=null&&(old.busy||old.hold))return CompletableFuture.failedFuture(new IllegalStateException("SCENE_HOLD"));
        var pending=opening.get(key);if(pending!=null){if(pending.token()!=token)return CompletableFuture.failedFuture(new IllegalStateException("SCENE_READ_PENDING"));return pending.result().thenApplyAsync(value->{if(!current.getAsBoolean())throw new IllegalStateException("SCENE_STALE_READ");return value;},owner);}
        var request=new Opening(token,new CompletableFuture<Session>());opening.put(key,request);
        try{store.read(c,scene.id()).thenApplyAsync(row->{if(!current.getAsBoolean()||opening.get(key)!=request)throw new IllegalStateException("SCENE_STALE_READ");var value=new Session(c,token,scene,current,row.map(r->decode(scene,r.payload())).orElseGet(Cutscene.State::empty),row.map(FeatureStore.Saved::revision).orElse(-1L));sessions.put(key,value);return value;},owner).whenCompleteAsync((value,error)->{opening.remove(key,request);if(error==null)request.result().complete(value);else request.result().completeExceptionally(error);},owner);}catch(RuntimeException error){opening.remove(key,request);request.result().completeExceptionally(error);}
        return request.result();
    }
    public void release(ServerProgressAccess.Context c,Object token){check.run();sessions.entrySet().removeIf(e->e.getKey().run().equals(c.storyRunId())&&e.getKey().player().equals(c.playerId())&&e.getValue().token==token&&!e.getValue().busy&&!e.getValue().hold);}
    public boolean completed(ServerProgressAccess.Context c,String id){var s=sessions.get(new Key(c.storyRunId(),c.playerId(),id));return s!=null&&!s.hold&&s.state.completed();}
    public record Diagnostic(int opened,List<String> held,List<String> pending){public Diagnostic{held=List.copyOf(held);pending=List.copyOf(pending);}}
    public Diagnostic diagnostic(ServerProgressAccess.Context context){check.run();var own=sessions.values().stream().filter(s->s.context.storyRunId().equals(context.storyRunId())&&s.context.playerId().equals(context.playerId())).toList();return new Diagnostic(own.size(),own.stream().filter(s->s.hold).map(s->s.scene.id()).sorted().toList(),own.stream().filter(s->s.busy).map(s->s.scene.id()).sorted().toList());}
    public CompletionStage<Cutscene.State> start(Session s,UUID request,Cutscene.Choice choice){require(s);return save(s,Cutscene.start(s.state,request,choice));}
    public CompletionStage<Cutscene.State> advance(Session s,UUID request,int frame){require(s);return save(s,Cutscene.advance(s.state,request,frame,s.scene.frames().size()));}
    public static byte[] encode(SceneContent.Scene scene,Cutscene.State state){byte[] raw=Cutscene.encode(state),hash=scene.fingerprint();var all=java.util.Arrays.copyOf(hash,hash.length+raw.length);System.arraycopy(raw,0,all,hash.length,raw.length);return all;}
    public static Cutscene.State decode(SceneContent.Scene scene,byte[] payload){if(payload.length<33||!java.security.MessageDigest.isEqual(scene.fingerprint(),java.util.Arrays.copyOf(payload,32)))throw new IllegalArgumentException("SCENE_CONTENT_MISMATCH");return Cutscene.decode(java.util.Arrays.copyOfRange(payload,32,payload.length));}
    private CompletionStage<Cutscene.State> save(Session s,Cutscene.State next){if(next.equals(s.state))return CompletableFuture.completedFuture(next);long before=s.revision;s.busy=true;var task=store.write(s.context,s.scene.id(),before,encode(s.scene,next)).thenApplyAsync(saved->{if(!s.current.getAsBoolean()||sessions.get(key(s))!=s||s.revision!=before)throw new IllegalStateException("SCENE_STALE_COMMIT");s.revision=saved.revision();s.state=next;return next;},owner).whenCompleteAsync((v,error)->{s.busy=false;if(error!=null)s.hold=true;},owner);s.pending=task;return task;}
    public CompletionStage<Void> barrier(UUID run){check.run();if(sessions.values().stream().anyMatch(s->s.context.storyRunId().equals(run)&&s.hold))return CompletableFuture.failedFuture(new IllegalStateException("SCENE_HOLD"));return CompletableFuture.allOf(sessions.values().stream().filter(s->s.context.storyRunId().equals(run)).map(s->s.pending.toCompletableFuture()).toArray(CompletableFuture[]::new));}

}
