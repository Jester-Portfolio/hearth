package io.github.jesterportfolio.hearth.phone;

import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PhoneLearningTest {
    @Test void returnIsOneShotAndDoesNotInvokeTheCallbackBeforeTheClick(){
        var flow=new PhoneLearning();var session=new Object();var calls=new AtomicInteger();var origin=flow.begin(session,"의뢰 상세로",()->true,calls::incrementAndGet);
        assertSame(origin,flow.current(session));assertEquals(0,calls.get());assertEquals("의뢰 상세로",origin.label());assertTrue(flow.back(session,origin));assertEquals(1,calls.get());assertFalse(flow.back(session,origin));assertNull(flow.current(session));
    }
    @Test void replacementDoesNotAllowAnOldButtonToConsumeTheNewDestination(){
        var flow=new PhoneLearning();var session=new Object();var oldCalls=new AtomicInteger();var newCalls=new AtomicInteger();var old=flow.begin(session,"기존",()->true,oldCalls::incrementAndGet);var next=flow.begin(session,"새 안내",()->true,newCalls::incrementAndGet);
        assertFalse(flow.back(session,old));assertSame(next,flow.current(session));assertTrue(flow.back(session,next));assertEquals(0,oldCalls.get());assertEquals(1,newCalls.get());
    }
    @Test void equalValuedSessionObjectsAndOtherPeopleCannotBorrowTheOrigin(){
        record Session(int id){}var a=new Session(1);var b=new Session(1);var flow=new PhoneLearning();var calls=new AtomicInteger();var origin=flow.begin(a,"준비 안내로",()->true,calls::incrementAndGet);
        assertFalse(flow.back(b,origin));assertSame(origin,flow.current(a));assertTrue(flow.back(a,origin));assertEquals(1,calls.get());
    }
    @Test void changedFacilityGenerationLocationOrReadFailureNeverRunsTheReturnCallback(){
        var flow=new PhoneLearning();var session=new Object();var live=new AtomicBoolean(true);var calls=new AtomicInteger();var origin=flow.begin(session,"작업대로",live::get,calls::incrementAndGet);live.set(false);
        assertFalse(flow.back(session,origin));assertNull(flow.current(session));assertEquals(0,calls.get());
        var reads=new AtomicInteger();var failure=flow.begin(session,"작업대로",()->{if(reads.incrementAndGet()>1)throw new IllegalStateException();return true;},calls::incrementAndGet);assertFalse(flow.back(session,failure));assertEquals(0,calls.get());
    }
    @Test void failedOpenAndForgetClearDoNotCrossSessions(){
        var flow=new PhoneLearning();var a=new Object();var b=new Object();var first=flow.begin(a,"의뢰",()->true,()->{});var second=flow.begin(b,"작업대",()->true,()->{});
        assertThrows(IllegalStateException.class,()->flow.begin(a,"거부",()->false,()->{}));assertSame(first,flow.current(a));flow.forget(a);assertFalse(flow.back(a,first));assertSame(second,flow.current(b));flow.clear();assertFalse(flow.back(b,second));
    }
    @Test void callbackFailureConsumesOnlyItsOwnOrigin(){
        var flow=new PhoneLearning();var session=new Object();var origin=flow.begin(session,"의뢰",()->true,()->{throw new IllegalStateException("read failed");});assertThrows(IllegalStateException.class,()->flow.back(session,origin));assertNull(flow.current(session));assertFalse(flow.back(session,origin));
    }
}
