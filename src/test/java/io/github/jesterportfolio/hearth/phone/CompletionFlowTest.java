package io.github.jesterportfolio.hearth.phone;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CompletionFlowTest {
    final CompletionFlow flow=new CompletionFlow();final Object session=new Object();
    PhoneCompletion.Choice choice(){return new PhoneCompletion.Choice(PhoneCompletion.Operation.RETRY,"quest","개인 의뢰",1,2);}
    @Test void sharedSurfaceCannotStartAnotherOperationUntilResultSettles(){var request=flow.begin(session,choice());assertEquals(CompletionFlow.Phase.PENDING,request.phase());assertEquals("처리 중",request.title());assertTrue(request.lines().toString().contains("아직 성공으로 확정하지"));assertThrows(IllegalStateException.class,()->flow.begin(session,choice()));assertSame(request,flow.current(session));}
    @Test void onlyFirstCompletionCanRecordResult(){var request=flow.begin(session,choice());assertTrue(flow.complete(session,request,true));assertFalse(flow.complete(session,request,false));assertEquals(CompletionFlow.Phase.SUCCEEDED,request.phase());assertEquals("처리 완료",request.title());}
    @Test void failureStaysFailureWithoutSuccessText(){var request=flow.begin(session,choice());assertTrue(flow.complete(session,request,false));assertEquals(CompletionFlow.Phase.FAILED,request.phase());assertTrue(request.lines().toString().contains("성공으로 기록하지"));assertFalse(flow.complete(session,request,true));}
    @Test void oldCallbackCannotOverwriteLaterOperation(){var old=flow.begin(session,choice());flow.complete(session,old,false);var current=flow.begin(session,choice());assertFalse(flow.complete(session,old,true));assertFalse(flow.current(session,old));assertSame(current,flow.current(session));assertEquals(CompletionFlow.Phase.PENDING,current.phase());}
    @Test void logoutAndNewSessionDiscardLateUiResult(){var old=flow.begin(session,choice());flow.forget(session);var nextSession=new Object();var next=flow.begin(nextSession,choice());assertFalse(flow.complete(session,old,true));assertFalse(flow.complete(nextSession,old,true));assertSame(next,flow.current(nextSession));assertNull(flow.current(session));}
    @Test void identityOwnershipAndIndependentPlayersArePreserved(){var a=new String("same");var b=new String("same");var first=flow.begin(a,choice());var second=flow.begin(b,choice());assertFalse(flow.complete(b,first,true));assertTrue(flow.complete(a,first,true));assertEquals(CompletionFlow.Phase.PENDING,second.phase());flow.clear();assertFalse(flow.complete(b,second,true));}
    @Test void invalidInputsAndReadOnlyResultListsDoNotCreateSuccess(){assertThrows(NullPointerException.class,()->flow.begin(null,choice()));assertThrows(NullPointerException.class,()->flow.begin(session,null));assertNull(flow.current(session));assertFalse(flow.complete(session,null,true));var request=flow.begin(session,choice());assertThrows(UnsupportedOperationException.class,()->request.lines().clear());}
}
