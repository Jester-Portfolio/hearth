package io.github.jesterportfolio.hearth.gathering;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 명령과 현장 GUI가 공유하는 입퇴장 순서/장벽. 채집 초기화와 인벤토리 저장기는 기존 구현을 사용합니다. */
public final class GatheringVisit implements AutoCloseable{
    public interface Port extends GatheringDungeon.Member{UUID run();long worldGeneration();boolean allowed();void freeze(boolean value);CompletionStage<Void> save();CompletionStage<Boolean> exit(BooleanSupplier valid);void hold();}
    private record Job(Port port,long worldGeneration,long generation,CompletableFuture<Void> result){}
    private final Map<UUID,Job> jobs=new HashMap<>();private final Executor owner;private final Runnable check;private boolean closed;
    public GatheringVisit(Executor owner,Runnable check){this.owner=owner;this.check=check;}
    public boolean busy(UUID player){check.run();return jobs.containsKey(player);}
    private boolean valid(Job job,GatheringDungeon dungeon){return !closed&&jobs.get(job.port().player())==job&&!job.result().isDone()&&job.port().current()&&job.port().worldGeneration()==job.worldGeneration()&&dungeon.snapshot().generation()==job.generation()&&dungeon.snapshot().phase()==GatheringStore.Phase.OPEN;}
    public CompletionStage<Void> request(Port port,GatheringDungeon dungeon,GatheringFacilities.Action action){
        check.run();if(closed||!port.current()||!port.allowed()||jobs.containsKey(port.player())||!port.run().equals(dungeon.snapshot().run())||dungeon.snapshot().phase()!=GatheringStore.Phase.OPEN)return CompletableFuture.failedFuture(new IllegalStateException("GATHERING_VISIT_BUSY_OR_HOLD"));
        if(action==GatheringFacilities.Action.LEAVE&&!dungeon.canGather(port,dungeon.snapshot().generation()))return CompletableFuture.failedFuture(new IllegalStateException("GATHERING_NOT_PARTICIPATING"));
        var job=new Job(port,port.worldGeneration(),dungeon.snapshot().generation(),new CompletableFuture<>());jobs.put(port.player(),job);
        CompletionStage<Void> work;
        try{if(action==GatheringFacilities.Action.ENTER)work=dungeon.enter(port);else{port.freeze(true);work=port.save().thenComposeAsync(v->{if(!valid(job,dungeon))throw new IllegalStateException("GATHERING_EXIT_STALE");return port.exit(()->valid(job,dungeon));},owner).thenComposeAsync(ok->{if(!Boolean.TRUE.equals(ok)||!valid(job,dungeon))throw new IllegalStateException("GATHERING_EXIT_FAILED");return dungeon.leave(port);},owner);}}
        catch(RuntimeException e){work=CompletableFuture.failedFuture(e);}
        work.toCompletableFuture().orTimeout(30,TimeUnit.SECONDS).whenCompleteAsync((v,error)->{
            if(job.result().isDone())return;
            Throwable failure=error;
            if(failure==null&&(!port.current()||port.worldGeneration()!=job.worldGeneration()))failure=new IllegalStateException("GATHERING_VISIT_STALE");
            try{port.freeze(false);}catch(RuntimeException release){if(failure==null)failure=release;}
            if(failure==null){jobs.remove(port.player(),job);job.result().complete(null);}else{if(action==GatheringFacilities.Action.ENTER)try{dungeon.leave(port);}catch(RuntimeException ignored){}try{port.hold();}catch(RuntimeException ignored){}job.result().completeExceptionally(failure);}
        },owner);return job.result();
    }
    public void cancel(UUID player,Object session){check.run();var job=jobs.get(player);if(job!=null&&job.port().session()==session&&!job.result().isDone()){jobs.remove(player,job);try{job.port().freeze(false);}catch(RuntimeException ignored){}job.result().completeExceptionally(new IllegalStateException("GATHERING_VISIT_CANCELLED"));}}
    public boolean settled(UUID run){check.run();return jobs.values().stream().noneMatch(j->j.port().run().equals(run));}
    public CompletionStage<Void> barrier(UUID run){check.run();return CompletableFuture.allOf(jobs.values().stream().filter(j->j.port().run().equals(run)).map(Job::result).toArray(CompletableFuture[]::new));}
    /** 성공 처리 없이 미완료 결과만 무효화합니다. */
    public void close(){check.run();if(closed)return;closed=true;for(var job:jobs.values())if(!job.result().isDone()){try{job.port().freeze(false);}catch(RuntimeException ignored){}job.result().completeExceptionally(new IllegalStateException("GATHERING_VISIT_CLOSED"));}}
}
