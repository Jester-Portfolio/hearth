package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MeasuredAssistantTest {
    static final class Backend implements AssistantService {
        final List<CompletableFuture<String>> work=new ArrayList<>();AssistantRequest request;int strings,confirms,closes;
        public CompletionStage<String> ask(String value){strings++;return next();}
        public CompletionStage<String> ask(AssistantRequest value){request=value;return next();}
        private CompletableFuture<String> next(){var f=new CompletableFuture<String>();work.add(f);return f;}
        public boolean allowsCache(){return true;}
        public CompletionStage<String> confirmCached(String answer){confirms++;return next();}
        public void close(){closes++;}
    }
    static MeasuredAssistant.Usage row(MeasuredAssistant measured,MeasuredAssistant.Kind kind){return measured.snapshot().usage().stream().filter(u->u.kind()==kind).findFirst().orElseThrow();}
    @Test void structuredRequestsCacheCapabilityAndResultsAreForwardedWithoutStoringContent(){
        var backend=new Backend();var clock=new AtomicLong();var measured=new MeasuredAssistant(backend,clock::get);var request=new AssistantRequest("private_question",List.of(),"private_context",-1);
        var answer=measured.ask(request).toCompletableFuture();assertSame(request,backend.request);assertTrue(measured.allowsCache());assertEquals(1,row(measured,MeasuredAssistant.Kind.GENERATION).active());
        clock.set(250_000_000L);backend.work.getFirst().complete("private_answer");assertEquals("private_answer",answer.join());
        var cached=measured.confirmCached("private_answer").toCompletableFuture();clock.addAndGet(20_000_000L);backend.work.getLast().complete("private_answer");assertEquals("private_answer",cached.join());
        var generated=row(measured,MeasuredAssistant.Kind.GENERATION);assertEquals(1,generated.succeeded());assertEquals(250,generated.totalMillis());assertEquals(List.of(0L,1L,0L,0L,0L,0L),generated.latencyBuckets());
        assertEquals(1,row(measured,MeasuredAssistant.Kind.CACHE_CONFIRMATION).succeeded());assertEquals(1,backend.confirms);assertEquals(0,backend.strings);
        assertFalse(measured.snapshot().toString().contains("private_"));assertThrows(UnsupportedOperationException.class,()->generated.failures().put(AssistantFailure.Reason.BUSY,3L));
    }
    @Test void cancelCloseAndLateCallbacksCountOnceAndCancelOriginalSources(){
        var backend=new Backend();var measured=new MeasuredAssistant(backend);var one=measured.ask("one").toCompletableFuture();var two=measured.confirmCached("two").toCompletableFuture();
        assertTrue(one.cancel(true));assertTrue(backend.work.getFirst().isCancelled());measured.close();measured.close();assertTrue(backend.work.getLast().isCancelled());assertTrue(two.isCompletedExceptionally());
        assertEquals(1,row(measured,MeasuredAssistant.Kind.GENERATION).cancelled());assertEquals(0,row(measured,MeasuredAssistant.Kind.GENERATION).active());
        assertEquals(Map.of(AssistantFailure.Reason.CLOSED,1L),row(measured,MeasuredAssistant.Kind.CACHE_CONFIRMATION).failures());assertEquals(1,backend.closes);
        assertThrows(CompletionException.class,()->measured.ask("late").toCompletableFuture().join());assertEquals(1,row(measured,MeasuredAssistant.Kind.GENERATION).started());
    }
    @Test void upstreamCancellationAndSynchronousFailuresUseBoundedReasonCounts(){
        var backend=new Backend();var measured=new MeasuredAssistant(backend);measured.ask("cancel");backend.work.getFirst().cancel(false);
        assertEquals(1,row(measured,MeasuredAssistant.Kind.GENERATION).cancelled());
        var broken=new MeasuredAssistant(question->{throw new IllegalStateException("secret backend detail");});assertThrows(CompletionException.class,()->broken.ask("private").toCompletableFuture().join());
        assertEquals(Map.of(AssistantFailure.Reason.UNAVAILABLE,1L),row(broken,MeasuredAssistant.Kind.GENERATION).failures());assertFalse(broken.snapshot().toString().contains("secret"));
    }
    @Test void everyCompletionBucketAndReasonAreMeasuredWithoutARequestLog(){
        var backend=new Backend();var clock=new AtomicLong();var measured=new MeasuredAssistant(backend,clock::get);
        for(long millis:new long[]{100,500,1000,3000,6000,6001}){measured.ask("question");clock.addAndGet(millis*1_000_000);backend.work.getLast().completeExceptionally(new AssistantFailure(AssistantFailure.Reason.TIMEOUT));}
        var row=row(measured,MeasuredAssistant.Kind.GENERATION);assertEquals(List.of(1L,1L,1L,1L,1L,1L),row.latencyBuckets());assertEquals(6,row.failed());assertEquals(6001,row.maxMillis());assertEquals(16601,row.totalMillis());assertEquals(Map.of(AssistantFailure.Reason.TIMEOUT,6L),row.failures());
    }
    @Test void concurrentCompletionsSnapshotsAndCancellationPreserveAccounting()throws Exception{
        var backend=new Backend();var measured=new MeasuredAssistant(backend);var results=new ArrayList<CompletableFuture<String>>();for(int i=0;i<120;i++)results.add(measured.ask("question").toCompletableFuture());
        try(var pool=Executors.newFixedThreadPool(2)){var futures=new ArrayList<Future<?>>();for(int i=0;i<120;i++){int n=i;futures.add(pool.submit(()->{if(n%3==0)results.get(n).cancel(true);else backend.work.get(n).complete("answer");measured.snapshot();}));}for(var f:futures)f.get(2,TimeUnit.SECONDS);}
        var row=row(measured,MeasuredAssistant.Kind.GENERATION);assertEquals(120,row.started());assertEquals(80,row.succeeded());assertEquals(40,row.cancelled());assertEquals(0,row.active());assertEquals(120,row.latencyBuckets().stream().mapToLong(Long::longValue).sum());
    }
    @Test void snapshotIsDetachedAndClockRollbackNeverCreatesNegativeDurations(){
        var backend=new Backend();var clock=new AtomicLong(10_000_000);var measured=new MeasuredAssistant(backend,clock::get);var before=measured.snapshot();measured.ask("question");clock.set(0);backend.work.getFirst().complete("answer");
        assertEquals(0,before.usage().getFirst().started());assertEquals(0,row(measured,MeasuredAssistant.Kind.GENERATION).totalMillis());assertThrows(UnsupportedOperationException.class,()->before.usage().clear());
    }
    @Test void completionAndCancellationRaceMatchesTheReturnedOutcomeBeforeObserversRun()throws Exception{
        var backend=new Backend();var measured=new MeasuredAssistant(backend);int cancelled=0,success=0;
        try(var pool=Executors.newFixedThreadPool(2)){
            for(int i=0;i<200;i++){
                var result=measured.ask("question").toCompletableFuture();var source=backend.work.getLast();var start=new CountDownLatch(1);
                var a=pool.submit(()->{start.await();result.cancel(true);return null;});var b=pool.submit(()->{start.await();source.complete("answer");return null;});start.countDown();a.get(2,TimeUnit.SECONDS);b.get(2,TimeUnit.SECONDS);
                if(result.isCancelled())cancelled++;else{assertEquals("answer",result.join());success++;}
                var row=row(measured,MeasuredAssistant.Kind.GENERATION);assertEquals(success,row.succeeded());assertEquals(cancelled,row.cancelled());assertEquals(0,row.active());
            }
        }
    }
    @Test void downstreamCompletionObserversSeeTheAlreadyFinalUsage(){
        var backend=new Backend();var measured=new MeasuredAssistant(backend);
        var observed=measured.ask("question").thenApply(answer->{var row=row(measured,MeasuredAssistant.Kind.GENERATION);assertEquals(1,row.succeeded());assertEquals(0,row.active());return answer;}).toCompletableFuture();
        backend.work.getFirst().complete("answer");assertEquals("answer",observed.join());
    }
}
