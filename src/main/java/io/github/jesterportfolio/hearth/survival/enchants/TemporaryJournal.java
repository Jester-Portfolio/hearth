package io.github.jesterportfolio.hearth.survival.enchants;

import java.nio.file.*;import java.nio.channels.FileChannel;import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;import java.io.IOException;import java.util.concurrent.*;

/** Ordered durable audit. No Bukkit objects cross the worker boundary; segments are never overwritten/deleted. */
public final class TemporaryJournal implements AutoCloseable {
 private final Path directory;private final String prefix;private final int segmentLimit;
 private final ThreadPoolExecutor worker;private FileChannel channel;private int records,segment;private Throwable failure;
 public TemporaryJournal(Path directory,String prefix){this(directory,prefix,20000);}
 public TemporaryJournal(Path directory,String prefix,int segmentLimit){if(!prefix.matches("[A-Za-z0-9_-]+")||segmentLimit<1)throw new IllegalArgumentException("TEMPORARY_JOURNAL");this.directory=directory;this.prefix=prefix;this.segmentLimit=segmentLimit;worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(512),r->{var t=new Thread(r,"hearth-enchant-journal");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy()){@Override protected void terminated(){try{if(channel!=null)channel.close();}catch(IOException ignored){}}};}
 public CompletionStage<Void> append(String record){byte[] bytes=(record+"\n").getBytes(StandardCharsets.UTF_8);if(bytes.length>65536)return CompletableFuture.failedFuture(new IOException("TEMPORARY_RECORD_LIMIT"));return submit(()->{if(channel==null||records>=segmentLimit){if(channel!=null)channel.close();Path file=directory.resolve(prefix+"-"+String.format(java.util.Locale.ROOT,"%06d",segment++)+".jsonl");channel=FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);records=0;try(var parent=FileChannel.open(directory,StandardOpenOption.READ)){parent.force(true);}}var data=ByteBuffer.wrap(bytes);while(data.hasRemaining())channel.write(data);channel.force(true);records++;});}
 @FunctionalInterface private interface Work{void run()throws Exception;}
 private CompletionStage<Void> submit(Work work){var result=new CompletableFuture<Void>();try{worker.execute(()->{try{if(failure!=null)throw new IOException("TEMPORARY_JOURNAL_HELD",failure);work.run();result.complete(null);}catch(Throwable e){failure=e;result.completeExceptionally(e);}});}catch(RejectedExecutionException e){result.completeExceptionally(new IOException("TEMPORARY_JOURNAL_BUSY_OR_CLOSED",e));}return result;}
 public CompletionStage<Void> drain(){return submit(()->{});}
 public void close(){worker.shutdown();}
}
