package io.github.jesterportfolio.hearth.survival;
import io.github.jesterportfolio.hearth.survival.enchants.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.util.*;import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class TemporaryJournalTest{
 @TempDir Path root;
 @Test void rotationPreservesAllDurableEvidence()throws Exception{
  try(var journal=new TemporaryJournal(root,"audit",2)){for(int n=0;n<7;n++)journal.append("record-"+n).toCompletableFuture().get(3,TimeUnit.SECONDS);journal.drain().toCompletableFuture().get(3,TimeUnit.SECONDS);}
  var actual=new ArrayList<String>();try(var files=Files.list(root)){for(var file:files.sorted().toList())actual.addAll(Files.readAllLines(file));}assertEquals(List.of("record-0","record-1","record-2","record-3","record-4","record-5","record-6"),actual);
 }
 @Test void previousEvidenceIsNeverOverwrittenAndFailureStaysHeld()throws Exception{
  Path existing=root.resolve("audit-000000.jsonl");Files.writeString(existing,"preserved");try(var journal=new TemporaryJournal(root,"audit",2)){assertThrows(ExecutionException.class,()->journal.append("new").toCompletableFuture().get(3,TimeUnit.SECONDS));assertThrows(ExecutionException.class,()->journal.append("retry").toCompletableFuture().get(3,TimeUnit.SECONDS));}assertEquals("preserved",Files.readString(existing));
 }
 @Test void concurrentRecordsAreSerializedAndCloseRefusesNewWork()throws Exception{
  var journal=new TemporaryJournal(root,"concurrent",20);var pending=new ArrayList<CompletableFuture<Void>>();for(int i=0;i<100;i++)pending.add(journal.append("line-"+i).toCompletableFuture());CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(10,TimeUnit.SECONDS);journal.close();assertThrows(ExecutionException.class,()->journal.append("late").toCompletableFuture().get());var lines=new ArrayList<String>();try(var files=Files.list(root)){for(var file:files.sorted().toList())lines.addAll(Files.readAllLines(file));}assertEquals(100,new HashSet<>(lines).size());
 }
}
