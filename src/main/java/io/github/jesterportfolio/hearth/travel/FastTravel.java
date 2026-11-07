package io.github.jesterportfolio.hearth.travel;

import io.github.jesterportfolio.hearth.exploration.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 기존 개인 발견/저장 경계와 서버 세션을 사용하는 거점 내 이동 조정자. 새 저장 체계가 아닙니다. */
public final class FastTravel implements AutoCloseable {
    public interface Port {
        ServerProgressAccess.Context context();Object session();UUID world();long generation();TravelContent.Point position();
        boolean current();boolean eligible();boolean access(TravelContent.Station s);boolean facility(TravelContent.Station s);void freeze(boolean frozen);
        CompletionStage<ExplorationView> read();CompletionStage<Void> save();CompletionStage<Void> prepare(TravelContent.Station target,BooleanSupplier valid);
        CompletionStage<Boolean> move(TravelContent.Station target,BooleanSupplier valid);void release();
    }
    public enum Phase{CHECKING,SAVING,PREPARING,MOVING,DONE,REJECTED,HOLD,CANCELLED}
    public record Destination(String id,String name,String location){}
    private record Key(UUID run,UUID player){}
    private static final class Job {final Port p;final UUID request,world;final Object session;final long generation;final TravelContent.Station from,to;final CompletableFuture<Void> result=new CompletableFuture<>(),drained=new CompletableFuture<>();Phase phase=Phase.CHECKING;boolean terminal;
        Job(Port p,UUID id,TravelContent.Station from,TravelContent.Station to){this.p=p;request=id;this.from=from;this.to=to;world=p.world();session=p.session();generation=p.generation();}}
    private final TravelContent content;private final Executor owner;private final Runnable check;private final Map<Key,Job> jobs=new HashMap<>();private boolean closed;
    public FastTravel(TravelContent content,Executor owner,Runnable check){this.content=content;this.owner=owner;this.check=check;}
    private static Key key(Port p){return new Key(p.context().storyRunId(),p.context().playerId());}
    public static void unlocked(ExplorationView view,String place){var p=view.place(place).orElseThrow(()->new IllegalStateException("TRAVEL_LOCKED"));if(!p.visited()||!p.discovered()||!p.hubUnlocked())throw new IllegalStateException("TRAVEL_LOCKED");}
    public List<Destination> destinations(ExplorationView view,Port port,String source){check.run();var from=content.get(source);if(!port.current()||!port.eligible()||!port.facility(from)||port.position().distance(from.facility())>16||!port.access(from))throw new IllegalStateException("TRAVEL_FACILITY_REQUIRED");unlocked(view,from.place());var result=new ArrayList<Destination>();
        for(var s:content.stations().values())if(!s.id().equals(source)&&port.access(s))view.place(s.place()).filter(p->p.visited()&&p.discovered()&&p.hubUnlocked()).ifPresent(p->result.add(new Destination(s.id(),p.name(),p.location())));return result.stream().sorted(Comparator.comparing(Destination::id)).toList();}
    public CompletionStage<Void> start(Port p,String source,String target,UUID request){check.run();if(closed)return CompletableFuture.failedFuture(new IllegalStateException("TRAVEL_CLOSED"));var from=content.get(source);var to=content.get(target);var key=key(p);var old=jobs.get(key);
        if(old!=null){if(old.session==p.session()&&old.request.equals(request)&&old.from.equals(from)&&old.to.equals(to))return old.result;if(!old.drained.isDone()||old.session==p.session()&&old.phase==Phase.HOLD)return CompletableFuture.failedFuture(new IllegalStateException("TRAVEL_BUSY_OR_HOLD"));}
        if(source.equals(target)||!p.current()||!p.eligible()||!p.facility(from)||p.position().distance(from.facility())>16||!p.access(from)||!p.access(to))return CompletableFuture.failedFuture(new IllegalStateException("TRAVEL_FACILITY_REQUIRED"));
        if(old==null&&jobs.size()>=128)return CompletableFuture.failedFuture(new IllegalStateException("TRAVEL_CAPACITY"));var j=new Job(p,Objects.requireNonNull(request),from,to);jobs.put(key,j);
        try{p.freeze(true);var work=p.read().thenComposeAsync(view->{require(j);unlocked(view,from.place());unlocked(view,to.place());j.phase=Phase.SAVING;return p.save();},owner)
            .thenComposeAsync(v->{require(j);return p.read();},owner).thenComposeAsync(view->{require(j);unlocked(view,from.place());unlocked(view,to.place());j.phase=Phase.PREPARING;return p.prepare(to,()->current(j));},owner)
            .thenComposeAsync(v->{require(j);j.phase=Phase.MOVING;return p.move(to,()->current(j));},owner).thenAcceptAsync(ok->{require(j);if(!Boolean.TRUE.equals(ok)||p.position().distance(to.arrival())>0.01)throw new IllegalStateException("TRAVEL_MOVE_FAILED");},owner);
            work.whenCompleteAsync((v,error)->{if(!j.terminal)finish(j,error);j.drained.complete(null);},owner).exceptionally(error->{j.result.completeExceptionally(error);j.drained.completeExceptionally(error);return null;});
        }catch(RuntimeException e){finish(j,e);j.drained.complete(null);}return j.result;
    }
    private boolean current(Job j){check.run();return !closed&&!j.terminal&&jobs.get(key(j.p))==j&&j.p.session()==j.session&&j.p.current()&&j.p.eligible()&&j.p.world().equals(j.world)&&j.p.generation()==j.generation&&j.p.access(j.from)&&j.p.access(j.to)&&(j.phase==Phase.MOVING||j.p.facility(j.from)&&j.p.position().distance(j.from.facility())<=16);}
    private void require(Job j){if(!current(j))throw new IllegalStateException("TRAVEL_STALE");}
    private void finish(Job j,Throwable error){check.run();j.terminal=true;try{j.p.release();}catch(RuntimeException release){if(error==null)error=release;}try{j.p.freeze(false);}catch(RuntimeException release){if(error==null)error=release;}
        if(error==null){j.phase=Phase.DONE;j.result.complete(null);}else{if(j.phase!=Phase.CANCELLED)j.phase=j.phase==Phase.CHECKING?Phase.REJECTED:Phase.HOLD;j.result.completeExceptionally(error);}}
    public void cancel(Port p){check.run();var j=jobs.get(key(p));if(j!=null&&j.session==p.session()&&!j.terminal){j.phase=Phase.CANCELLED;finish(j,new IllegalStateException("TRAVEL_CANCELLED"));}}
    public Phase phase(Port p){check.run();var j=jobs.get(key(p));return j==null?null:j.phase;}
    public CompletionStage<Void> barrier(UUID run){check.run();return CompletableFuture.allOf(jobs.entrySet().stream().filter(e->e.getKey().run().equals(run)).map(e->e.getValue().drained).toArray(CompletableFuture[]::new));}
    public boolean settled(UUID run){check.run();return jobs.entrySet().stream().filter(e->e.getKey().run().equals(run)).allMatch(e->e.getValue().drained.isDone());}
    public static boolean pageAvailable(int size,int page){return size>=0&&page>=0&&page<Math.max(1,(size+44L)/45);}
    public static <T> List<T> page(List<T> rows,int page){if(!pageAvailable(rows.size(),page))throw new IllegalArgumentException("TRAVEL_PAGE");return List.copyOf(rows.subList(page*45,Math.min(rows.size(),(page+1)*45)));}
    public void forget(Port p){check.run();var j=jobs.get(key(p));if(j!=null&&j.session==p.session()&&j.drained.isDone())jobs.remove(key(p),j);}
    public void close(){check.run();closed=true;for(var j:List.copyOf(jobs.values()))if(!j.terminal){j.phase=Phase.CANCELLED;finish(j,new IllegalStateException("TRAVEL_CLOSED"));}}
}
