package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.story.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OneBlockEffectTest {
    static OneBlockEffect.Definition definition(UUID run){return new OneBlockEffect.Definition(1,"synthetic-restart-v1",run,"hearth_verify:restart_a","open",0,64,0,"GOLD_BLOCK","EMERALD_BLOCK",Set.of("private_area"));}
    static OneBlockEffect.Target target(UUID run){return new OneBlockEffect.Target(run,new UUID(7,1),"hearth_verify:restart_a","/tmp/owned-world",1,"synthetic-restart-v1",1);}
    static StoryStore.Snapshot confirmed(){return new StoryStore.Snapshot(new ExplorationStore.Story("open","공개",true,2),Set.of("arrival","vote"),Map.of());}
    static final class Block implements OneBlockEffect.Block{String type="GOLD_BLOCK";int writes;public String read(){return type;}public void write(String next){type=next;writes++;}}
    @Test void convergesOnlyExpectedOrAlreadyTargetAndPreservesConflict(){UUID run=UUID.randomUUID();var d=definition(run);var t=target(run);var b=new Block();assertTrue(OneBlockEffect.reconcile(d,run,confirmed(),t,t,b,()->true));assertTrue(OneBlockEffect.reconcile(d,run,confirmed(),t,t,b,()->true));assertEquals(1,b.writes);b.type="DIAMOND_BLOCK";assertThrows(IllegalStateException.class,()->OneBlockEffect.reconcile(d,run,confirmed(),t,t,b,()->true));assertEquals("DIAMOND_BLOCK",b.type);assertEquals(1,b.writes);}
    @Test void rejectsUnconfirmedWrongRunWorldGenerationAndVersionBeforeWrite(){UUID run=UUID.randomUUID();var d=definition(run);var expected=target(run);var b=new Block();
        assertThrows(IllegalStateException.class,()->OneBlockEffect.reconcile(d,run,confirmed(),expected,expected,b,()->false));assertThrows(IllegalStateException.class,()->OneBlockEffect.reconcile(d,run,SharedStoryTest.empty(),expected,expected,b,()->true));
        for(var actual:List.of(target(UUID.randomUUID()),new OneBlockEffect.Target(run,UUID.randomUUID(),expected.world(),expected.directory(),1,expected.template(),1),new OneBlockEffect.Target(run,expected.physical(),"hearth_verify:restart_b",expected.directory(),1,expected.template(),1),new OneBlockEffect.Target(run,expected.physical(),expected.world(),"/tmp/other",1,expected.template(),1),new OneBlockEffect.Target(run,expected.physical(),expected.world(),expected.directory(),2,expected.template(),1),new OneBlockEffect.Target(run,expected.physical(),expected.world(),expected.directory(),1,expected.template(),2)))assertThrows(IllegalStateException.class,()->OneBlockEffect.reconcile(d,run,confirmed(),expected,actual,b,()->true));
        assertEquals(0,b.writes);assertEquals("GOLD_BLOCK",b.type);
    }
    @Test void invalidContentCoordinatesAndVersionsFailBeforeActivation(){UUID run=UUID.randomUUID();assertThrows(IllegalArgumentException.class,()->new OneBlockEffect.Definition(2,"synthetic-restart-v1",run,"hearth_verify:restart_a","open",0,64,0,"GOLD_BLOCK","EMERALD_BLOCK",Set.of("private_area")));assertThrows(IllegalArgumentException.class,()->new OneBlockEffect.Definition(1,"wrong",run,"hearth_verify:restart_a","open",0,64,0,"GOLD_BLOCK","EMERALD_BLOCK",Set.of("private_area")));assertThrows(IllegalArgumentException.class,()->new OneBlockEffect.Definition(1,"synthetic-restart-v1",run,"hearth_verify:restart_a","open",2000,64,0,"GOLD_BLOCK","EMERALD_BLOCK",Set.of("private_area")));}
    static final class Port implements SharedStory.Effects{
        final SharedStoryTest.F f;final Block block=new Block();final OneBlockEffect.Definition d;final OneBlockEffect.Target t;java.util.function.BooleanSupplier permit;int calls;boolean delayed;CompletableFuture<Boolean> work;io.github.jesterportfolio.hearth.persistence.StoryStore.Snapshot saved;
        Port(SharedStoryTest.F f){this.f=f;d=definition(f.a);t=target(f.a);}
        public boolean required(UUID run,StoryStore.Snapshot saved){return d.required(run,saved);}
        public Set<String> regions(){return d.regions();}
        public CompletionStage<Boolean> apply(UUID run,UUID request,StoryStore.Snapshot saved){calls++;this.saved=saved;permit=f.story.effectPermit(run,saved);work=new CompletableFuture<>();if(!delayed)finish();return work;}
        void finish(){try{work.complete(OneBlockEffect.reconcile(d,f.a,saved,t,t,block,permit));}catch(Exception e){work.completeExceptionally(e);}}
    }
    static void vote(SharedStoryTest.F f){var v=f.ok(f.story.openVote(f.c,f.t1,()->true));f.ok(f.story.cast(f.c,f.t1,()->true,v.id(),"one"));f.ok(f.story.cast(f.access.forVerifiedServerLink(f.a,f.p2),f.t2,()->true,v.id(),"one"));}
    @Test void pendingAndConflictOnlyCloseDependentRegionAndNeverRollbackDB(){var f=new SharedStoryTest.F();f.readyVote();var port=new Port(f);port.delayed=true;f.story.effects(port);vote(f);var access=new StoryAccess(f.story);assertEquals("PENDING",f.story.effectStatus(f.a));assertEquals("open",f.story.cached(f.a).story().stage());assertFalse(access.entry(f.a,21,64,21));assertTrue(access.entry(f.a,1,64,1));assertFalse(f.story.barrier(f.a).toCompletableFuture().isDone());port.finish();f.pump();assertEquals("APPLIED",f.story.effectStatus(f.a));assertTrue(access.entry(f.a,21,64,21));
        port.delayed=false;port.block.type="DIAMOND_BLOCK";assertFalse(f.ok(f.story.reconcile(f.c)));assertEquals("EFFECT_CONFLICT",f.story.effectStatus(f.a));assertEquals("open",f.story.cached(f.a).story().stage());assertFalse(access.entry(f.a,21,64,21));assertTrue(access.entry(f.a,1,64,1));assertEquals(1,port.block.writes);
    }
    @Test void duplicateCallsSharePendingWorkButCompletedCallsVerifyAgain(){var f=new SharedStoryTest.F();f.readyVote();var port=new Port(f);f.story.effects(port);vote(f);int calls=port.calls;var first=f.story.reconcile(f.c);var duplicate=f.story.reconcile(f.c);assertSame(first,duplicate);assertTrue(f.ok(first));assertEquals(calls+1,port.calls);assertEquals(1,port.block.writes);}
    @Test void DBFailureAndUnknownNeverInvokeBlockEffect(){for(boolean unknown:List.of(false,true)){var f=new SharedStoryTest.F();f.readyVote();var port=new Port(f);f.story.effects(port);f.store.fail=!unknown;f.store.unknown=unknown;var v=f.ok(f.story.openVote(f.c,f.t1,()->true));f.ok(f.story.cast(f.c,f.t1,()->true,v.id(),"one"));f.bad(f.story.cast(f.access.forVerifiedServerLink(f.a,f.p2),f.t2,()->true,v.id(),"one"));assertEquals(0,port.calls);assertEquals(0,port.block.writes);assertNotEquals("APPLIED",f.story.effectStatus(f.a));}}
    @Test void stalePendingResultDoesNotSetAppliedAndFalseIsNotSuccess(){var f=new SharedStoryTest.F();f.readyVote();var port=new Port(f);port.delayed=true;f.story.effects(port);vote(f);var pending=f.story.reconcile(f.c);f.story.invalidate(f.a);port.finish();f.pump();assertFalse(pending.toCompletableFuture().join());assertNotEquals("APPLIED",f.story.effectStatus(f.a));assertEquals(0,port.block.writes);
        f.story.effects(new SharedStory.Effects(){public Set<String> regions(){return Set.of("private_area");}public CompletionStage<Boolean> apply(UUID r,UUID id,StoryStore.Snapshot saved){return CompletableFuture.completedFuture(false);}});assertFalse(f.ok(f.story.reconcile(f.c)));assertEquals("NOT_IMPLEMENTED",f.story.effectStatus(f.a));assertFalse(new StoryAccess(f.story).entry(f.a,21,64,21));}
    @Test void sameStageOtherRunNeverInvokesEffectOrLosesAccessWhenTargetConflicts(){
        var f=new SharedStoryTest.F();f.readyVote();var port=new Port(f);f.story.effects(port);vote(f);
        f.store.snapshots.put(f.b,f.store.snapshots.get(f.a));f.ok(f.story.refresh(f.d));
        var before=f.store.snapshots.get(f.b);int calls=port.calls;
        assertFalse(f.ok(f.story.reconcile(f.d)));assertEquals(calls,port.calls);
        assertEquals("NOT_REQUIRED",f.story.effectStatus(f.b));
        var access=new StoryAccess(f.story);assertTrue(access.entry(f.b,21,64,21));assertTrue(access.content(f.b,21,64,21));assertTrue(f.story.effectAllows(f.b,"private_area"));
        port.block.type="DIAMOND_BLOCK";assertFalse(f.ok(f.story.reconcile(f.c)));
        assertEquals("EFFECT_CONFLICT",f.story.effectStatus(f.a));assertFalse(access.entry(f.a,21,64,21));
        calls=port.calls;assertFalse(f.ok(f.story.reconcile(f.d)));assertEquals(calls,port.calls);
        assertTrue(access.entry(f.b,21,64,21));assertTrue(access.content(f.b,21,64,21));assertSame(before,f.store.snapshots.get(f.b));
        assertFalse(port.d.required(f.b,confirmed()));assertTrue(port.d.required(f.a,confirmed()));
    }
    @Test void freshLoadChecksActualBlockInsteadOfPreviousAppliedMemory(){var f=new SharedStoryTest.F();f.readyVote();vote(f);var block=new Block();var holder=new SharedStory[1];var d=definition(f.a);var target=target(f.a);var port=new SharedStory.Effects(){public Set<String> regions(){return d.regions();}public CompletionStage<Boolean> apply(UUID r,UUID id,StoryStore.Snapshot saved){return CompletableFuture.completedFuture(OneBlockEffect.reconcile(d,r,saved,target,target,block,()->holder[0].healthy(r)&&holder[0].cached(r)==saved));}};
        var fresh=new SharedStory(f.store,f.story.content(),f.queue::add,()->{},c->Map.of(f.p1,f.t1),f.time::get,java.time.Duration.ofSeconds(30));holder[0]=fresh;fresh.effects(port);f.ok(fresh.load(f.c));assertEquals("APPLIED",fresh.effectStatus(f.a));assertEquals("EMERALD_BLOCK",block.type);assertEquals(1,block.writes);
    }
}
