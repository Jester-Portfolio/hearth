package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 현재 서버 수명의 백엔드 집계만 보관합니다. 질문/응답/개인/주소는 기록하지 않습니다. */
public final class MeasuredAssistant implements AssistantService {
    public enum Kind { GENERATION, CACHE_CONFIRMATION }
    public record Usage(Kind kind,long started,long succeeded,long failed,long cancelled,int active,long totalMillis,long maxMillis,List<Long> latencyBuckets,Map<AssistantFailure.Reason,Long> failures){
        public Usage{latencyBuckets=List.copyOf(latencyBuckets);failures=Map.copyOf(failures);}
    }
    public record Snapshot(boolean closed,List<Usage> usage){public Snapshot{usage=List.copyOf(usage);}}
    private static final class Totals {
        long started,succeeded,failed,cancelled,totalMillis,maxMillis;int active;
        final long[] buckets=new long[6];final EnumMap<AssistantFailure.Reason,Long> failures=new EnumMap<>(AssistantFailure.Reason.class);
    }
    private final class Work {
        final Kind kind;final long began;final CompletableFuture<String> result=new CompletableFuture<>(){
            @Override public boolean cancel(boolean interrupt){synchronized(MeasuredAssistant.this){return super.cancel(interrupt);}}
        };CompletableFuture<String> source;
        Work(Kind kind,long began){this.kind=kind;this.began=began;}
    }
    private final AssistantService delegate;private final LongSupplier clock;private final EnumMap<Kind,Totals> totals=new EnumMap<>(Kind.class);
    private final Set<Work> pending=new HashSet<>();private boolean closed;
    public MeasuredAssistant(AssistantService delegate){this(delegate,System::nanoTime);}
    MeasuredAssistant(AssistantService delegate,LongSupplier clock){this.delegate=Objects.requireNonNull(delegate);this.clock=Objects.requireNonNull(clock);for(var kind:Kind.values())totals.put(kind,new Totals());}
    @Override public CompletionStage<String> ask(String question){return measure(Kind.GENERATION,()->delegate.ask(question));}
    @Override public CompletionStage<String> ask(AssistantRequest request){return measure(Kind.GENERATION,()->delegate.ask(request));}
    @Override public boolean allowsCache(){return delegate.allowsCache();}
    @Override public CompletionStage<String> confirmCached(String answer){return measure(Kind.CACHE_CONFIRMATION,()->delegate.confirmCached(answer));}
    private synchronized CompletionStage<String> measure(Kind kind,Supplier<CompletionStage<String>> start){
        if(closed)return CompletableFuture.failedFuture(new AssistantFailure(AssistantFailure.Reason.CLOSED));
        var work=new Work(kind,clock.getAsLong());pending.add(work);var count=totals.get(kind);count.started++;count.active++;
        work.result.whenComplete((value,error)->{if(work.result.isCancelled())finish(work,error,true);});
        try{work.source=Objects.requireNonNull(start.get()).toCompletableFuture();work.source.whenComplete((value,error)->complete(work,value,error));}
        catch(RuntimeException error){complete(work,null,error);}
        return work.result;
    }
    private synchronized void complete(Work work,String value,Throwable error){
        if(!finish(work,error,error instanceof CancellationException))return;
        if(error==null)work.result.complete(value);else work.result.completeExceptionally(error);
    }
    private synchronized boolean finish(Work work,Throwable error,boolean cancelled){
        if(!pending.remove(work))return false;
        var count=totals.get(work.kind);count.active--;long millis=Math.max(0,(clock.getAsLong()-work.began)/1_000_000);
        count.totalMillis=count.totalMillis>Long.MAX_VALUE-millis?Long.MAX_VALUE:count.totalMillis+millis;count.maxMillis=Math.max(count.maxMillis,millis);
        int bucket=millis<=100?0:millis<=500?1:millis<=1000?2:millis<=3000?3:millis<=6000?4:5;count.buckets[bucket]++;
        if(cancelled)count.cancelled++;
        else if(error!=null){count.failed++;count.failures.merge(AssistantFailure.reason(error),1L,Long::sum);}
        else count.succeeded++;
        if(error!=null&&work.source!=null)work.source.cancel(true);
        return true;
    }
    public synchronized Snapshot snapshot(){
        var rows=new ArrayList<Usage>();for(var kind:Kind.values()){var t=totals.get(kind);rows.add(new Usage(kind,t.started,t.succeeded,t.failed,t.cancelled,t.active,t.totalMillis,t.maxMillis,Arrays.stream(t.buckets).boxed().toList(),t.failures));}
        return new Snapshot(closed,rows);
    }
    @Override public void close(){
        List<Work> closing;synchronized(this){if(closed)return;closed=true;closing=List.copyOf(pending);}
        try{for(var work:closing)complete(work,null,new AssistantFailure(AssistantFailure.Reason.CLOSED));}finally{delegate.close();}
    }
}
