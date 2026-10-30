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
    @Test void retryUsesOnlyFailedQuestionAndClearIsSessionLocal(){
        var answers=new java.util.ArrayList<String>();var calls=new java.util.concurrent.atomic.AtomicInteger();
        var chat=new PhoneChat(q->{answers.add(q);return calls.getAndIncrement()==0?CompletableFuture.failedFuture(new IllegalStateException()):CompletableFuture.completedFuture("성공");},Runnable::run);
        var other=new PhoneChat(LocalAssistant.mock(),Runnable::run);other.submit("다른 개인").toCompletableFuture().join();
        assertTrue(chat.submit("실패한 질문").toCompletableFuture().isCompletedExceptionally());assertTrue(chat.canRetry());chat.retry().toCompletableFuture().join();
        assertEquals(java.util.List.of("실패한 질문","실패한 질문"),answers);assertFalse(chat.canRetry());chat.clear();assertTrue(chat.history().isEmpty());assertNull(chat.question());assertFalse(other.history().isEmpty());
    }
    @Test void clearAndRetryCannotInterruptPendingOrClosedChat(){
        var pending=new CompletableFuture<String>();var chat=new PhoneChat(q->pending,Runnable::run);chat.submit("대기");assertThrows(IllegalStateException.class,chat::clear);assertTrue(chat.retry().toCompletableFuture().isCompletedExceptionally());
        pending.completeExceptionally(new IllegalStateException());assertTrue(chat.canRetry());chat.close();assertFalse(chat.canRetry());assertThrows(IllegalStateException.class,chat::clear);
    }

    @Test void invalidInputCannotRetryAnEarlierSuccessfulQuestion(){
        var chat=new PhoneChat(LocalAssistant.mock(),Runnable::run);chat.submit("이전 성공").toCompletableFuture().join();
        assertTrue(chat.submit("").toCompletableFuture().isCompletedExceptionally());assertFalse(chat.canRetry());assertTrue(chat.retry().toCompletableFuture().isCompletedExceptionally());assertEquals(1,chat.history().size());
    }

    @Test void contextChangeRejectsLateAnswerWithoutSavingHistory(){
        var result=new CompletableFuture<String>();var valid=new java.util.concurrent.atomic.AtomicBoolean(true);var chat=new PhoneChat(q->result,Runnable::run);
        var request=chat.submit("현재 목표?","허용 정보","quests",valid::get);valid.set(false);result.complete("오래된 응답");assertTrue(request.toCompletableFuture().isCompletedExceptionally());assertTrue(chat.history().isEmpty());assertTrue(chat.canRetry());assertTrue(chat.retry().toCompletableFuture().isCompletedExceptionally());
    }
    @Test void cancellationDoesNotAllowOldResponseToOverwriteNextRequest(){
        var outputs=new java.util.ArrayList<CompletableFuture<String>>();var chat=new PhoneChat(q->{var f=new CompletableFuture<String>();outputs.add(f);return f;},Runnable::run);
        var old=chat.submit("이전");chat.cancel();assertFalse(chat.busy());var next=chat.submit("다음");outputs.getFirst().complete("늦음");assertTrue(chat.busy());outputs.getLast().complete("새 응답");assertTrue(old.toCompletableFuture().isCompletedExceptionally());assertTrue(next.toCompletableFuture().isDone());assertEquals("다음",chat.history().getFirst().question());
    }
    @Test void gameDerivedHistoryIsNotSentWhenSwitchingToGeneralQuestions(){
        var requests=new java.util.ArrayList<AssistantRequest>();var service=new AssistantService(){public CompletionStage<String> ask(String q){throw new AssertionError();}public CompletionStage<String> ask(AssistantRequest r){requests.add(r);return CompletableFuture.completedFuture("허용된 인물 정보");}};
        var chat=new PhoneChat(service,Runnable::run);chat.submit("인물?","개인 기록","diary",()->true).toCompletableFuture().join();chat.submit("일반 질문").toCompletableFuture().join();assertTrue(requests.get(1).context().isEmpty());assertTrue(requests.get(1).history().isEmpty());
        chat.submit("다시 인물?","개인 기록","diary",()->true).toCompletableFuture().join();assertEquals(1,requests.get(2).history().size());assertEquals("인물?",requests.get(2).history().getFirst().question());
        chat.submit("변경 뒤?","최신 기록","diary",()->true).toCompletableFuture().join();assertTrue(requests.get(3).history().isEmpty());
    }

    @Test void draftSurvivesFailureAndCancelButConfirmedAnswerOnlyClearsMatchingDraft(){
        var results=new java.util.ArrayList<CompletableFuture<String>>();var chat=new PhoneChat(q->{var f=new CompletableFuture<String>();results.add(f);return f;},Runnable::run);
        chat.draft("미리보기 질문");assertEquals("미리보기 질문",chat.draft());chat.submit("오류 질문");results.getFirst().completeExceptionally(new IllegalStateException());assertEquals("오류 질문",chat.draft());
        chat.submit("취소 질문");chat.cancel();assertEquals("취소 질문",chat.draft());chat.submit("완료 질문");chat.draft("새로 편집한 질문");results.getLast().complete("응답");assertEquals("새로 편집한 질문",chat.draft());chat.submit("마지막 질문");results.getLast().complete("응답");assertEquals("",chat.draft());
        chat.draft("정리 대상");chat.clear();assertEquals("",chat.draft());assertThrows(IllegalArgumentException.class,()->chat.draft("가".repeat(257)));
    }

}
