package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PhoneChatCacheTest {
    static final class Service implements AssistantService {
        int generated,checked;CompletableFuture<String> confirming;boolean reject;
        public boolean allowsCache(){return true;}
        public CompletionStage<String> ask(String question){generated++;return CompletableFuture.completedFuture("응답");}
        public CompletionStage<String> confirmCached(String answer){checked++;return confirming!=null?confirming:reject?CompletableFuture.failedFuture(new AssistantFailure(AssistantFailure.Reason.MODEL_MISMATCH)):CompletableFuture.completedFuture(answer);}
    }
    void prime(PhoneChat chat){for(int i=0;i<4;i++)chat.submit("같은 질문","현재 자료","diary",()->true,()->CompletableFuture.completedFuture(true),"revision1").toCompletableFuture().join();}
    CompletionStage<Void> again(PhoneChat chat,java.util.function.Supplier<CompletionStage<Boolean>> check){return chat.submit("같은 질문","현재 자료","diary",()->true,check,"revision1");}
    @Test void exactHistoryRepeatUsesCacheButAlwaysRevalidatesIdentityAndAuthorization(){
        var service=new Service();var chat=new PhoneChat(service,Runnable::run);prime(chat);var reads=new AtomicInteger();again(chat,()->{reads.incrementAndGet();return CompletableFuture.completedFuture(true);}).toCompletableFuture().join();assertEquals(4,service.generated);assertEquals(1,service.checked);assertEquals(1,reads.get());assertEquals(1,chat.cacheStats().hits());
        var other=new PhoneChat(service,Runnable::run);other.submit("같은 질문","현재 자료","diary",()->true).toCompletableFuture().join();assertEquals(5,service.generated);assertEquals(0,other.cacheStats().hits());
    }
    @Test void cachedAnswerWaitsForCurrentDatabaseAndFailureDiscardsCacheWithoutHistory(){
        var service=new Service();var chat=new PhoneChat(service,Runnable::run);prime(chat);var before=chat.history();var gate=new CompletableFuture<Boolean>();var result=again(chat,()->gate);assertTrue(chat.busy());assertEquals(before,chat.history());assertEquals("같은 질문",chat.draft());gate.completeExceptionally(new IllegalStateException("PG_READ_FAILED"));assertTrue(result.toCompletableFuture().isCompletedExceptionally());assertEquals(before,chat.history());assertEquals(0,chat.cacheStats().entries());assertTrue(chat.canRetry());assertEquals(4,service.generated);
    }
    @Test void backendMismatchEvictsAnswerAndNeverPretendsCacheIsSuccess(){
        var service=new Service();var chat=new PhoneChat(service,Runnable::run);prime(chat);service.reject=true;assertTrue(again(chat,()->CompletableFuture.completedFuture(true)).toCompletableFuture().isCompletedExceptionally());assertEquals(0,chat.cacheStats().entries());assertTrue(chat.error().contains("모델"));assertEquals("같은 질문",chat.draft());
    }
    @Test void cancellingCacheIdentityReadStopsOnlyThatRequestAndCannotPublishLate(){
        var service=new Service();var chat=new PhoneChat(service,Runnable::run);prime(chat);service.confirming=new CompletableFuture<>();var before=chat.history();var result=again(chat,()->CompletableFuture.completedFuture(true));chat.cancel();assertTrue(service.confirming.isCancelled());assertTrue(result.toCompletableFuture().isDone());assertEquals(before,chat.history());assertEquals(0,chat.cacheStats().entries());
    }
    @Test void scopeRevisionSameTextAndExpiryCannotReuseOldValue(){
        var clock=new AtomicLong();var service=new Service();var chat=new PhoneChat(service,Runnable::run,8000,new PhoneAnswerCache(clock::get));prime(chat);clock.set(59_000_000_000L);again(chat,()->CompletableFuture.completedFuture(true)).toCompletableFuture().join();clock.set(61_000_000_000L);again(chat,()->CompletableFuture.completedFuture(true)).toCompletableFuture().join();assertEquals(5,service.generated);assertEquals(1,service.checked);chat.submit("같은 질문","현재 자료","diary",()->true,()->CompletableFuture.completedFuture(true),"revision2").toCompletableFuture().join();assertEquals(6,service.generated);assertEquals(1,chat.cacheStats().entries());chat.clear();assertEquals(0,chat.cacheStats().entries());assertTrue(chat.history().isEmpty());chat.close();assertEquals(0,chat.cacheStats().bytes());
    }
    @Test void invalidCitationsNeverEnterHistoryOrCacheAndKeepQuestion(){
        var scope=new PhoneGrounding.Scope("diary",List.of(new PhoneGrounding.Identity("diary","internal",new io.github.jesterportfolio.hearth.quest.GameInformation(List.of(),List.of(),List.of(),List.of()),1,-1)));
        var reads=new AtomicInteger();var service=new AssistantService(){public boolean allowsCache(){return true;}public CompletionStage<String> ask(String prompt){return CompletableFuture.completedFuture("만든 근거 [자료 2]");}};
        var chat=new PhoneChat(service,Runnable::run);var result=chat.submit("유지할 질문","[자료 1] 공개 기록","diary",()->true,()->{reads.incrementAndGet();return CompletableFuture.completedFuture(true);},scope);
        assertTrue(result.toCompletableFuture().isCompletedExceptionally());assertEquals(0,reads.get());assertTrue(chat.history().isEmpty());assertEquals(0,chat.cacheStats().entries());assertEquals("유지할 질문",chat.draft());assertTrue(chat.error().contains("참고자료"));
    }
    @Test void currentScopeChangeDuringCacheConfirmationRejectsBeforeDatabaseOrHistory(){
        var service=new Service();var chat=new PhoneChat(service,Runnable::run);prime(chat);var before=chat.history();var live=new AtomicBoolean(true);var reads=new AtomicInteger();service.confirming=new CompletableFuture<>();
        var result=chat.submit("같은 질문","현재 자료","diary",live::get,()->{reads.incrementAndGet();return CompletableFuture.completedFuture(true);},"revision1");live.set(false);service.confirming.complete("응답");
        assertTrue(result.toCompletableFuture().isCompletedExceptionally());assertEquals(0,reads.get());assertEquals(before,chat.history());assertEquals(0,chat.cacheStats().entries());assertEquals("같은 질문",chat.draft());
    }
}
