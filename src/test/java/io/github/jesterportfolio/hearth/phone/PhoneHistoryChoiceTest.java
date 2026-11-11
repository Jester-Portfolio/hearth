package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PhoneHistoryChoiceTest {
    static final class Service implements AssistantService {
        final List<AssistantRequest> requests=new ArrayList<>();CompletableFuture<String> pending;int confirmations;
        public boolean allowsCache(){return true;}
        public CompletionStage<String> ask(String prompt){throw new AssertionError();}
        public CompletionStage<String> ask(AssistantRequest request){requests.add(request);return pending==null?CompletableFuture.completedFuture("답변"):pending;}
        public CompletionStage<String> confirmCached(String answer){confirmations++;return CompletableFuture.completedFuture(answer);}
    }
    @Test void turningHistoryOffKeepsDisplayAndDraftButSendsNoneAndEvictsCache(){
        var service=new Service();var chat=new PhoneChat(service,Runnable::run);assertTrue(chat.includeHistory());chat.submit("질문").toCompletableFuture().join();chat.submit("질문").toCompletableFuture().join();var before=chat.history();chat.draft("작성 중");assertTrue(chat.cacheStats().entries()>0);
        chat.includeHistory(false);assertEquals(before,chat.history());assertEquals("작성 중",chat.draft());assertEquals(0,chat.cacheStats().entries());chat.submit("질문").toCompletableFuture().join();assertTrue(service.requests.getLast().history().isEmpty());assertEquals(3,chat.history().size());
        chat.includeHistory(true);assertEquals(0,chat.cacheStats().entries());chat.submit("질문").toCompletableFuture().join();assertEquals(3,service.requests.getLast().history().size());
    }
    @Test void repeatedIndependentQuestionsCanCacheWithoutRestoringOldHistory(){
        var service=new Service();var chat=new PhoneChat(service,Runnable::run);chat.includeHistory(false);chat.submit("질문").toCompletableFuture().join();chat.submit("질문").toCompletableFuture().join();assertEquals(1,service.requests.size());assertEquals(1,service.confirmations);assertEquals(2,chat.history().size());assertFalse(chat.includeHistory());chat.clear();assertFalse(chat.includeHistory());assertTrue(chat.history().isEmpty());
    }
    @Test void busyAndClosedChoicesCannotChangeTheRequestOrBypassCancellation(){
        var service=new Service();service.pending=new CompletableFuture<>();var chat=new PhoneChat(service,Runnable::run);chat.submit("질문");assertThrows(IllegalStateException.class,()->chat.includeHistory(false));assertTrue(chat.includeHistory());chat.cancel();assertTrue(service.pending.isCancelled());chat.includeHistory(false);chat.close();assertThrows(IllegalStateException.class,()->chat.includeHistory(true));
    }
}
