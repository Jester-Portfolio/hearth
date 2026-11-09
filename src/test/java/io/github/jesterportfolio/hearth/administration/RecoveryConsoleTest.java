package io.github.jesterportfolio.hearth.administration;

import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.features.PersonalStorage;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RecoveryConsoleTest {
    final ServerProgressAccess access=new ServerProgressAccess();
    final List<ServerProgressAccess.Context> links=new ArrayList<>();
    final AtomicLong clock=new AtomicLong();final AtomicInteger reads=new AtomicInteger();
    final List<String> output=new ArrayList<>();
    final RecoveryInspection.Runtime runtime=new RecoveryInspection.Runtime(UUID.randomUUID(),Map.of("session.state","ACTIVE"),Set.of(),Set.of());
    ServerProgressAccess.Context context(){var c=access.forVerifiedServerLink(UUID.randomUUID(),UUID.randomUUID());links.add(c);return c;}
    RecoveryInspection.Report prepare(ServerProgressAccess.Context c,UUID request,RecoveryInspection.Runtime before) {
        var bytes=PersonalStorage.empty().bytes();
        return RecoveryInspection.build(request,c,new InspectionStore.Snapshot(Instant.EPOCH,List.of(new InspectionStore.Row("player","progress",0,0,null),new InspectionStore.Row("run","progress",0,0,null),new InspectionStore.Row("inventory","inventory",0,bytes.length,bytes)),false),before,before,Map.of());
    }
    RecoveryConsole console(){return new RecoveryConsole((c,id,b,s)->{reads.incrementAndGet();return CompletableFuture.completedFuture(prepare(c,id,b));},()->List.copyOf(links),c->runtime,Map.of(),Runnable::run,()->{},clock::get);}
    String[] inspect(ServerProgressAccess.Context c){return new String[]{"inspect",c.storyRunId().toString(),c.playerId().toString()};}
    UUID id(){String line=output.stream().filter(s->s.startsWith("HEARTH_RECOVERY_REPORT")).reduce((a,b)->b).orElseThrow();return UUID.fromString(line.split("report=",2)[1].split(" ")[0]);}
    @Test void playerRequestsAndUnknownVerifiedLinksCannotReachTheReader() {
        var c=context();var console=console();assertTrue(console.command(false,inspect(c),output::add).toCompletableFuture().isCompletedExceptionally());
        assertTrue(console.command(true,new String[]{"inspect",UUID.randomUUID().toString(),c.playerId().toString()},output::add).toCompletableFuture().isCompletedExceptionally());assertEquals(0,reads.get());assertTrue(output.isEmpty());
    }
    @Test void mutatingCommandsAndNoncanonicalIdsAreRejectedBeforeAnyQuery() {
        var console=console();
        for(var args:List.of(new String[]{"reset"},new String[]{"repair"},new String[]{"claim"},new String[]{"inspect","1-1-1-1-1",UUID.randomUUID().toString()}))assertTrue(console.command(true,args,output::add).toCompletableFuture().isCompletedExceptionally());
        assertEquals(0,reads.get());
    }
    @Test void inspectAndCompareReuseOnlyTheSameVerifiedContext() {
        var c=context();var console=console();console.command(true,inspect(c),output::add).toCompletableFuture().join();UUID first=id();
        console.command(true,new String[]{"compare",first.toString()},output::add).toCompletableFuture().join();
        assertEquals(2,reads.get());assertTrue(output.getLast().contains("changed=[] unconfirmed=[]"));assertTrue(console.report(first).isPresent());
        links.clear();assertTrue(console.command(true,new String[]{"compare",first.toString()},output::add).toCompletableFuture().isCompletedExceptionally());assertEquals(2,reads.get());
    }
    @Test void receiptsExpireAndDoNotBecomeNewAuthorityAfterTenMinutes() {
        var c=context();var console=console();console.command(true,inspect(c),output::add).toCompletableFuture().join();var id=id();clock.set(600_000_000_000L);
        assertTrue(console.report(id).isEmpty());assertTrue(console.command(true,new String[]{"compare",id.toString()},output::add).toCompletableFuture().isCompletedExceptionally());assertEquals(1,reads.get());
    }
    @Test void cacheEvictsOldReceiptsAndReplacementLinksCannotReuseTheirAuthority() {
        var c=context();var console=console();console.command(true,inspect(c),output::add).toCompletableFuture().join();var first=id();
        for(int i=0;i<16;i++)console.command(true,inspect(c),output::add).toCompletableFuture().join();
        assertTrue(console.report(first).isEmpty());var last=id();assertTrue(console.report(last).isPresent());
        links.clear();links.add(access.forVerifiedServerLink(c.storyRunId(),c.playerId()));
        assertTrue(console.command(true,new String[]{"compare",last.toString()},output::add).toCompletableFuture().isCompletedExceptionally());assertEquals(17,reads.get());
    }
    @Test void listIsBoundedToOnePageAndReadsEachContextSequentially() {
        for(int i=0;i<7;i++)context();var console=console();console.command(true,new String[]{"list"},output::add).toCompletableFuture().join();assertEquals(6,reads.get());
        console.command(true,new String[]{"list","2"},output::add).toCompletableFuture().join();assertEquals(7,reads.get());
        assertTrue(console.command(true,new String[]{"list","3"},output::add).toCompletableFuture().isCompletedExceptionally());assertEquals(7,reads.get());
    }
    @Test void pendingReadRejectsDuplicatesAndCloseSuppressesItsLateReport() {
        var c=context();var gate=new CompletableFuture<RecoveryInspection.Report>();var request=new AtomicReference<UUID>();
        var console=new RecoveryConsole((ctx,id,b,s)->{reads.incrementAndGet();request.set(id);return gate;},()->List.copyOf(links),ctx->runtime,Map.of(),Runnable::run,()->{},clock::get);
        var pending=console.command(true,inspect(c),output::add);assertTrue(console.command(true,inspect(c),output::add).toCompletableFuture().isCompletedExceptionally());assertEquals(1,reads.get());
        console.close();gate.complete(prepare(c,request.get(),runtime));assertTrue(pending.toCompletableFuture().isCompletedExceptionally());assertTrue(output.isEmpty());assertTrue(console.report(request.get()).isEmpty());assertDoesNotThrow(()->console.barrier().toCompletableFuture().join());
    }
    @Test void databaseFailureDoesNotReplaceAnExistingReceiptWithAnEmptySuccess() {
        var c=context();var fail=new AtomicBoolean();
        var console=new RecoveryConsole((ctx,id,b,s)->{reads.incrementAndGet();return fail.get()?CompletableFuture.failedFuture(new StorageFailure("read","42P01")):CompletableFuture.completedFuture(prepare(ctx,id,b));},()->List.copyOf(links),ctx->runtime,Map.of(),Runnable::run,()->{},clock::get);
        console.command(true,inspect(c),output::add).toCompletableFuture().join();var first=id();var before=console.report(first).orElseThrow();int lines=output.size();fail.set(true);
        var failed=console.command(true,new String[]{"compare",first.toString()},output::add);assertTrue(failed.toCompletableFuture().isCompletedExceptionally());assertSame(before,console.report(first).orElseThrow());assertEquals(lines,output.size());
        assertEquals("42P01",RecoveryConsole.errorCode(new CompletionException(new StorageFailure("read","42P01"))));
    }
    @Test void errorOutputDoesNotEchoPrivateExceptionText() {
        assertEquals("IllegalArgumentException",RecoveryConsole.errorCode(new IllegalArgumentException("private payload or database URL")));
        assertEquals("UNKNOWN_VERIFIED_LINK",RecoveryConsole.errorCode(new SecurityException("UNKNOWN_VERIFIED_LINK")));
    }
}
