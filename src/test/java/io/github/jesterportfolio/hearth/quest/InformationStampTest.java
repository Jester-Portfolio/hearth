package io.github.jesterportfolio.hearth.quest;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class InformationStampTest {
    @Test void readDoesNotChangeStampButCommittedMutationDoesAndOtherContextStaysIndependent(){
        var f=new NpcTopicsTest.Fixture();assertEquals(0,f.journal.informationStamp(f.ctx));var read=f.journal.load(f.ctx,f.token,()->true);f.f.pump();assertFalse(read.toCompletableFuture().isCompletedExceptionally());assertEquals(0,f.journal.informationStamp(f.ctx));var write=f.journal.hud(f.ctx,f.token,()->true,false);f.f.pump();assertFalse(write.toCompletableFuture().isCompletedExceptionally());assertEquals(1,f.journal.informationStamp(f.ctx));assertEquals(0,f.journal.informationStamp(f.f.ctx(f.f.a,f.f.p2)));assertEquals(0,f.journal.informationStamp(f.f.ctx(f.f.b,f.f.p)));
    }
    @Test void pendingAndFailedOrClosedStorageCannotIssueAuthorizationProof(){
        var f=new NpcTopicsTest.Fixture();f.ui.delay=true;var pending=f.journal.load(f.ctx,f.token,()->true);f.f.pump();assertThrows(IllegalStateException.class,()->f.journal.informationStamp(f.ctx));f.ui.pending.complete(f.ui.get(f.ctx));f.f.pump();assertFalse(pending.toCompletableFuture().isCompletedExceptionally());assertEquals(0,f.journal.informationStamp(f.ctx));f.ui.delay=false;f.ui.fail=true;var write=f.journal.hud(f.ctx,f.token,()->true,false);f.f.pump();assertTrue(write.toCompletableFuture().isCompletedExceptionally());assertThrows(IllegalStateException.class,()->f.journal.informationStamp(f.ctx));assertTrue(f.ui.rows.isEmpty());
        var closed=new NpcTopicsTest.Fixture();closed.journal.shutdown();assertThrows(IllegalStateException.class,()->closed.journal.informationStamp(closed.ctx));
    }
}
