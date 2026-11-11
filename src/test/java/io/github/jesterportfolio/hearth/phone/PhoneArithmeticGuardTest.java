package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PhoneArithmeticGuardTest {
    static final String OBSERVED_WRONG="마찰 없이 지렛대에서 균형을 이루려면 힘 × 작용 팔 = 하중 × 하중 팔이므로,\nF × 2 = 400 × 0.5 → F = 200 N\n필요한 힘은 200 N입니다.";
    @Test void observedActualWrongLinearDerivationIsRejectedWithoutHardcodingItsAnswer(){
        var bad=assertThrows(AssistantFailure.class,()->AssistantAnswer.present(AssistantRequest.general("지렛대 질문",List.of()),OBSERVED_WRONG));assertEquals(AssistantFailure.Reason.INVALID_CALCULATION,bad.reason());
        for(String text:List.of("Q * 3 = 900 → Q = 500 N","R / 4 = 7 → R = 12 J","2 + 3 = 6 Ω","1 / 0 = 1"))assertThrows(AssistantFailure.class,()->ArithmeticConsistency.check(text),text);
    }
    @Test void correctDecimalAndLinearChainsRemainUnchanged(){
        for(String text:List.of("F × 2 = 400 × 0.5 → F = (400 × 0.5) / 2 = 100 N","E = 10 * 10 * 5 = 500 J","0.1 + 0.2 = 0.3","1 / 3 = 0.3333333333","Q*3=900 → Q=300 N"))assertDoesNotThrow(()->ArithmeticConsistency.check(text),text);
    }
    @Test void unsupportedNotationAndQuotedCodeAreNotPresentedAsVerifiedArithmetic(){
        for(String text:List.of("x * x = 4","a + b = 10","i = i + 1","1 / 3 ≈ 0.3333","```\n2+2=5\n```","(".repeat(20)+"1"+")".repeat(20)+"=1"))assertDoesNotThrow(()->ArithmeticConsistency.check(text));
    }
    @Test void publicRecordReadingDoesNotBecomeAGeneralCalculator(){
        var request=new AssistantRequest("기록을 읽어줘",List.of(),"제공된 기록",1);assertEquals("2+2=5 [자료 1]",AssistantAnswer.present(request,"2+2=5 [자료 1]"));
    }
    @Test void contradictionNeverEntersHistoryCacheOrFollowupAuthorization(){
        var reads=new AtomicInteger();var chat=new PhoneChat(question->CompletableFuture.completedFuture(OBSERVED_WRONG),Runnable::run);
        var result=chat.submit("지렛대 질문","","none",()->true,()->{reads.incrementAndGet();return CompletableFuture.completedFuture(true);});assertTrue(result.toCompletableFuture().isCompletedExceptionally());assertEquals(0,reads.get());assertTrue(chat.history().isEmpty());assertEquals(0,chat.cacheStats().entries());assertEquals("지렛대 질문",chat.draft());assertTrue(chat.error().contains("수식"));
    }
    @Test void previouslyCachedContradictionIsRecheckedAndEvicted(){
        var cache=new PhoneAnswerCache();var calls=new AtomicInteger();var service=new AssistantService(){public boolean allowsCache(){return true;}public CompletionStage<String> ask(String prompt){calls.incrementAndGet();return CompletableFuture.completedFuture("100 N");}public CompletionStage<String> confirmCached(String answer){return CompletableFuture.completedFuture(answer);}};
        var chat=new PhoneChat(service,Runnable::run,8000,cache);chat.includeHistory(false);chat.submit("질문").toCompletableFuture().join();var before=chat.history();cache.put(AssistantRequest.general("질문",List.of()),OBSERVED_WRONG);assertTrue(chat.submit("질문").toCompletableFuture().isCompletedExceptionally());assertEquals(before,chat.history());assertEquals(0,cache.stats().entries());assertEquals(1,calls.get());
    }
}
