package io.github.jesterportfolio.hearth.phone;

import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PhoneChatTest {
    @Test void mockKeepsOrderedBoundedPersonalHistory(){
        var one=new PhoneChat(LocalAssistant.mock(),Runnable::run);var two=new PhoneChat(LocalAssistant.mock(),Runnable::run);
        for(int i=0;i<7;i++)one.submit("질문 "+i).toCompletableFuture().join();
        assertEquals(4,one.history().size());assertEquals("질문 3",one.history().getFirst().question());assertTrue(one.history().getLast().answer().contains("Mock"));assertTrue(two.history().isEmpty());
    }
    @Test void pendingRejectsDuplicatesAndCloseDiscardsLateResponse(){
        var result=new CompletableFuture<String>();var chat=new PhoneChat(q->result,Runnable::run);
        var first=chat.submit("첫 질문");assertTrue(chat.busy());assertEquals("첫 질문",chat.question());assertTrue(chat.submit("중복").toCompletableFuture().isCompletedExceptionally());chat.close();result.complete("늦은 응답");assertTrue(first.toCompletableFuture().isCompletedExceptionally());assertTrue(chat.history().isEmpty());
    }
    @Test void invalidInputErrorsAndLongOutputAreNotSuccessOrUnlimited(){
        var chat=new PhoneChat(q->CompletableFuture.completedFuture("가".repeat(1500)),Runnable::run);
        for(String input:new String[]{""," ","가".repeat(257)})assertTrue(chat.submit(input).toCompletableFuture().isCompletedExceptionally());
        chat.submit("질문").toCompletableFuture().join();assertTrue(chat.history().getFirst().answer().length()<1250);
        var failed=new PhoneChat(q->CompletableFuture.failedFuture(new IllegalStateException()),Runnable::run);assertTrue(failed.submit("실패").toCompletableFuture().isCompletedExceptionally());assertNotNull(failed.error());assertTrue(failed.history().isEmpty());
    }
    @Test void timeoutReleasesBusyWithoutInventingAnswer()throws Exception{
        var chat=new PhoneChat(q->new CompletableFuture<>(),Runnable::run,20);assertThrows(ExecutionException.class,()->chat.submit("대기").toCompletableFuture().get(1,TimeUnit.SECONDS));assertFalse(chat.busy());assertNotNull(chat.error());assertTrue(chat.history().isEmpty());
    }
}
