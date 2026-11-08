package io.github.jesterportfolio.hearth.crafting;

import io.github.jesterportfolio.hearth.inventory.InventoryPayload;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class CraftingActionTest {
    static InventoryPayload payload(int n){byte[][] slots=new byte[41][];slots[0]=new byte[]{(byte)n};return InventoryPayload.pack(slots);}
    static final class Port implements CraftingAction.Port {
        UUID run=UUID.randomUUID(),player=UUID.randomUUID();boolean valid=true,fail;int applied,dirty,held;InventoryPayload value=payload(1),after=payload(2);
        public UUID run(){return run;}public UUID player(){return player;}public boolean valid(){return valid;}public InventoryPayload capture(){return value;}public InventoryPayload plan(InventoryPayload before){return after;}
        public void apply(InventoryPayload after){if(fail)throw new IllegalStateException("apply");value=after;applied++;}public void changed(){dirty++;}public void hold(){held++;}
    }
    final ArrayDeque<Runnable> ticks=new ArrayDeque<>();final CraftingAction actions=new CraftingAction(ticks::add,()->{},InventoryPayload::equals);final Port p=new Port();
    @Test void previewIsReadOnlyAndDuplicateConfirmationAppliesOnceNextTick(){var t=actions.preview(p);assertEquals(0,p.applied);var f=actions.confirm(t).toCompletableFuture();assertSame(f,actions.confirm(t));assertFalse(f.isDone());assertFalse(actions.settled(p.run));var barrier=actions.barrier(p.run).toCompletableFuture();ticks.remove().run();assertTrue(f.isDone());assertFalse(f.isCompletedExceptionally());assertTrue(barrier.isDone());assertEquals(1,p.applied);assertEquals(1,p.dirty);assertEquals(1,ticks.size()+p.applied);assertSame(f,actions.confirm(t));}
    @Test void completionCallbackCanQueueNextCraftAfterOldPendingIsRemoved(){
        var first=actions.confirm(actions.preview(p));var both=first.thenCompose(v->actions.confirm(actions.preview(p))).toCompletableFuture();ticks.remove().run();assertEquals(1,ticks.size());assertFalse(both.isDone());ticks.remove().run();assertFalse(both.isCompletedExceptionally());assertEquals(2,p.applied);assertTrue(actions.settled(p.run));
    }
    @Test void sessionWorldOrFacilityInvalidationRejectsQueuedResult(){var f=actions.confirm(actions.preview(p)).toCompletableFuture();var barrier=actions.barrier(p.run).toCompletableFuture();p.valid=false;ticks.remove().run();assertTrue(f.isCompletedExceptionally());assertTrue(barrier.isDone());assertFalse(barrier.isCompletedExceptionally());assertEquals(0,p.applied);assertEquals(0,p.held);assertTrue(actions.settled(p.run));}
    @Test void inventoryOrRecipeChangesRejectOldQuote(){var f=actions.confirm(actions.preview(p)).toCompletableFuture();p.value=payload(3);ticks.remove().run();assertTrue(f.isCompletedExceptionally());assertEquals(0,p.applied);p.value=payload(1);var g=actions.confirm(actions.preview(p)).toCompletableFuture();p.after=payload(4);ticks.remove().run();assertTrue(g.isCompletedExceptionally());assertEquals(0,p.dirty);}
    @Test void concurrentRequestsAreBoundedPerPlayer(){var first=actions.confirm(actions.preview(p)).toCompletableFuture();var second=actions.confirm(actions.preview(p)).toCompletableFuture();assertTrue(second.isCompletedExceptionally());assertEquals(1,ticks.size());ticks.remove().run();assertFalse(first.isCompletedExceptionally());}
    @Test void partialApplyFailureUsesExistingHoldBoundary(){p.fail=true;var f=actions.confirm(actions.preview(p)).toCompletableFuture();ticks.remove().run();assertTrue(f.isCompletedExceptionally());assertEquals(1,p.held);assertEquals(0,p.dirty);assertEquals(payload(1),p.value);}
    @Test void shutdownCompletesWaiterAndQueuedTaskCannotApply(){var f=actions.confirm(actions.preview(p)).toCompletableFuture();var b=actions.barrier(p.run).toCompletableFuture();actions.close();ticks.remove().run();assertTrue(f.isCompletedExceptionally());assertTrue(b.isDone());assertFalse(b.isCompletedExceptionally());assertTrue(actions.settled(p.run));assertEquals(0,p.applied);}
    @Test void otherPlayerAndRunCanApplyIndependently(){var q=new Port();var f=actions.confirm(actions.preview(p)).toCompletableFuture();var g=actions.confirm(actions.preview(q)).toCompletableFuture();p.valid=false;ticks.remove().run();ticks.remove().run();assertTrue(f.isCompletedExceptionally());assertFalse(g.isCompletedExceptionally());assertEquals(1,q.applied);}
}
