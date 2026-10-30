package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class QuestDialogueReadTest {
    @Test void foregroundWaitsBackgroundQueryWithoutDuplicateReadOrWrite(){
        var f=new PersonalQuestTest.Fixture();var ctx=f.ctx(f.a,f.p);Object token=new Object();
        f.store.deferReads=true;
        var background=f.quests.list(ctx,token,()->true);f.pump();
        var foreground=f.quests.listAfterPending(ctx,token,()->true);f.pump();
        assertEquals(1,f.store.reads);assertFalse(foreground.toCompletableFuture().isDone());
        f.store.readResults.getFirst().complete(f.store.readSnapshots.getFirst());f.pump();
        assertTrue(background.toCompletableFuture().isDone());assertEquals(2,f.store.reads);
        f.store.readResults.get(1).complete(f.store.readSnapshots.get(1));f.pump();
        assertEquals(background.toCompletableFuture().join(),foreground.toCompletableFuture().join());
        assertTrue(f.store.rows.isEmpty());assertTrue(f.store.pending.isEmpty());
    }
    @Test void changedBackgroundSnapshotDoesNotPoisonNewForegroundRead(){
        var f=new PersonalQuestTest.Fixture();var ctx=f.ctx(f.a,f.p);Object token=new Object();f.store.deferReads=true;
        var background=f.quests.list(ctx,token,()->true);f.pump();
        var opening=f.quests.open(ctx,token,()->true);f.pump();
        var foreground=f.quests.listAfterPending(ctx,token,()->true);f.pump();
        f.store.readResults.get(1).complete(f.store.readSnapshots.get(1));f.pump();
        f.store.readResults.getFirst().complete(f.store.readSnapshots.getFirst());f.pump();
        assertTrue(background.toCompletableFuture().isCompletedExceptionally());assertTrue(opening.toCompletableFuture().isDone());
        assertFalse(foreground.toCompletableFuture().isDone());assertEquals(3,f.store.reads);
        f.store.readResults.get(2).complete(f.store.readSnapshots.get(2));f.pump();
        assertEquals(QuestStore.Phase.NOT_ACCEPTED,foreground.toCompletableFuture().join().get("survey_marker").phase());assertTrue(f.store.rows.isEmpty());
    }
    @Test void foregroundWaitsWriteAndReturnsConfirmedRevision(){
        var f=new PersonalQuestTest.Fixture();var ctx=f.ctx(f.a,f.p);Object token=new Object();var q=f.open(ctx,token);
        f.quests.advance(q,QuestStore.Phase.ACTIVE);
        var foreground=f.quests.listAfterPending(ctx,token,()->true);f.pump();
        assertFalse(foreground.toCompletableFuture().isDone());assertEquals(1,f.store.reads);
        f.commit(0);assertEquals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,0),foreground.toCompletableFuture().join().get("survey_marker"));
        assertEquals(1,f.store.pending.size());
    }
    @Test void failedWritePreservesHoldAndDoesNotReadOrClaimSuccess(){
        var f=new PersonalQuestTest.Fixture();var ctx=f.ctx(f.a,f.p);Object token=new Object();var q=f.open(ctx,token);
        f.quests.advance(q,QuestStore.Phase.ACTIVE);var read=f.quests.listAfterPending(ctx,token,()->true);
        f.store.pending.getFirst().completeExceptionally(new StorageFailure("quest","08006"));f.pump();
        assertTrue(read.toCompletableFuture().isCompletedExceptionally());assertEquals(1,f.store.reads);
        assertEquals(QuestStore.Phase.NOT_ACCEPTED,q.saved().phase());assertFalse(f.quests.settled(f.a));
    }
    @Test void sessionOrConversationInvalidationWhileWaitingRejectsLateDisplay(){
        var f=new PersonalQuestTest.Fixture();var ctx=f.ctx(f.a,f.p);f.store.deferReads=true;
        var background=f.quests.list(ctx,new Object(),()->true);f.pump();var valid=new AtomicBoolean(true);
        var foreground=f.quests.listAfterPending(ctx,new Object(),valid::get);f.pump();valid.set(false);
        f.store.readResults.getFirst().complete(f.store.readSnapshots.getFirst());f.pump();
        assertTrue(background.toCompletableFuture().isDone());assertTrue(foreground.toCompletableFuture().isCompletedExceptionally());assertEquals(1,f.store.reads);
    }
    @Test void unrelatedPlayerAndRunDoNotWaitForBlockedQuery(){
        var f=new PersonalQuestTest.Fixture();f.store.deferReads=true;
        f.quests.list(f.ctx(f.a,f.p),new Object(),()->true);f.pump();f.store.deferReads=false;
        for(var ctx:List.of(f.ctx(f.a,f.p2),f.ctx(f.b,f.p))){var read=f.quests.listAfterPending(ctx,new Object(),()->true);f.pump();assertEquals(QuestStore.Phase.NOT_ACCEPTED,read.toCompletableFuture().join().get("survey_marker").phase());}
        assertEquals(1,f.store.readResults.size());assertTrue(f.store.rows.isEmpty());
    }
    @Test void foregroundDatabaseErrorRemainsErrorAndShutdownRejectsWaitingRequest(){
        var f=new PersonalQuestTest.Fixture();var ctx=f.ctx(f.a,f.p);f.store.deferReads=true;
        var read=f.quests.listAfterPending(ctx,new Object(),()->true);f.pump();
        f.store.readResults.getFirst().completeExceptionally(new StorageFailure("read_quest","08006"));f.pump();assertTrue(read.toCompletableFuture().isCompletedExceptionally());
        var background=f.quests.list(ctx,new Object(),()->true);f.pump();var pending=f.quests.listAfterPending(ctx,new Object(),()->true);f.quests.shutdown();
        f.store.readResults.get(1).complete(f.store.readSnapshots.get(1));f.pump();
        assertTrue(background.toCompletableFuture().isCompletedExceptionally());assertTrue(pending.toCompletableFuture().isCompletedExceptionally());assertEquals(2,f.store.reads);
    }
}
