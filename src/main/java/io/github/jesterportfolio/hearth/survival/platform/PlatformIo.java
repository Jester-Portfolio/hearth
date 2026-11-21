package io.github.jesterportfolio.hearth.survival.platform;

import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicInteger;

/** 순서 보장·bounded 파일 작업. Paper 객체를 이 worker로 넘기지 않는다. */
public final class PlatformIo implements AutoCloseable {
    @FunctionalInterface public interface Work<T>{T run()throws Exception;}
    private final ThreadPoolExecutor executor;private final AtomicInteger pending=new AtomicInteger();
    public PlatformIo(){executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{var t=new Thread(r,"hearth-platform-files");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());}
    public <T>CompletionStage<T> submit(Work<T> work){var result=new CompletableFuture<T>();pending.incrementAndGet();try{executor.execute(()->{try{result.complete(work.run());}catch(Throwable e){result.completeExceptionally(e);}finally{pending.decrementAndGet();}});}catch(RejectedExecutionException e){pending.decrementAndGet();result.completeExceptionally(new IllegalStateException("PLATFORM_IO_BUSY_OR_CLOSED"));}return result;}
    public int pending(){return pending.get();}
    public void close(){executor.shutdown();}
}
