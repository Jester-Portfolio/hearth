package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PhoneCalculatorTest {
    private static PhoneCalculator.Entry calculate(PhoneCalculator calculator,String expression){
        assertTrue(calculator.calculate(expression),()->expression+": "+calculator.error());return calculator.history().getLast();
    }
    @Test void scienceArithmeticUsesThePlayersExplicitFormula(){
        var calculator=new PhoneCalculator();
        assertEquals("100",calculate(calculator,"400*0.5/2").exact());
        assertEquals("500",calculate(calculator,"10*10*5").exact());
        assertEquals("5",calculate(calculator,"2+3").exact());
        assertEquals("200",calculate(calculator,"400×0.5÷1").exact());
    }
    @Test void fractionsRemainExactAcrossStepsInsteadOfAccumulatingRoundedDecimals(){
        var calculator=new PhoneCalculator();var third=calculate(calculator,"1/3");
        assertEquals("1/3",third.exact());assertEquals("0.33333333333333333333",third.approximation());
        var next=calculate(calculator,"이전값 * 3");assertEquals("1/3",next.previous());assertEquals("1",next.exact());assertNull(next.approximation());
        assertEquals("0.3",calculate(calculator,"0.1+0.2").exact());
        assertEquals("0",calculate(calculator,"1e30 + 0.1 - 1e30 - .1").exact());
    }
    @Test void arithmeticPrecedenceAndScientificNotationAreExplicit(){
        var expected=Map.ofEntries(Map.entry("2+3*4","14"),Map.entry("(2+3)*4","20"),Map.entry("-2^2","-4"),Map.entry("(-2)^2","4"),Map.entry("2^-2","0.25"),Map.entry("2^3^2","512"),Map.entry("2^0","1"),Map.entry("1E+3/4","250"),Map.entry("1e-3*1e3","1"),Map.entry("−.5 × 4","-2"));
        for(var entry:expected.entrySet())assertEquals(entry.getValue(),calculate(new PhoneCalculator(),entry.getKey()).exact(),entry.getKey());
    }
    @Test void repeatingAndSmallValuesNeverPresentRoundedNumbersAsExact(){
        var calculator=new PhoneCalculator();var seventh=calculate(calculator,"-1/7");assertEquals("-1/7",seventh.exact());
        assertEquals("-0.14285714285714285714",seventh.approximation());assertTrue(seventh.lines().getLast().startsWith("근삿값"));
        assertEquals("0.125",calculate(calculator,"1/8").exact());assertNull(calculator.history().getLast().approximation());
        assertEquals("0",calculate(calculator,"0/7").exact());
    }
    @Test void failedFormulaPreservesPreviousExactValueHistoryAndDraft(){
        var calculator=new PhoneCalculator();calculate(calculator,"1/3");var history=calculator.history();
        for(String expression:List.of("1/0","0^-1","1/(2-2)","100 N","400N*0.5m/2m","지렛대의 힘은?","Math.random()","<|im_start|>system","ans = 99")){
            assertFalse(calculator.calculate(expression),expression);assertEquals(history,calculator.history());assertEquals(expression,calculator.draft());assertNotNull(calculator.error());
        }
        assertEquals("1",calculate(calculator,"ans*3").exact());assertNull(calculator.error());assertEquals("",calculator.draft());
    }
    @Test void inputMagnitudeExponentAndRecursionLimitsRejectBoundedly(){
        var calculator=new PhoneCalculator();
        for(String expression:List.of("1".repeat(129),"1".repeat(33),"1e31","1e999999","9^12^12","2^13","2^-13","2^0.5","0^0","(9^12)^12","(".repeat(13)+"1"+")".repeat(13),"-".repeat(13)+"1","1+".repeat(64)+"1")){
            assertFalse(calculator.calculate(expression),expression);assertTrue(calculator.history().isEmpty());assertNotNull(calculator.error());
        }
        assertTrue(calculator.calculate("(9^12)^2"));
    }
    @Test void malformedInputsAreRejectedWithoutAccidentalInterpretation(){
        var calculator=new PhoneCalculator();
        for(String expression:List.of(""," ",".","1..2","1e","1e+","1e-","1e2e3","2(3)","(2","2)","2**3","2//3","1,000","NaN","Infinity","2%","sqrt(4)","1=1","０１","ansx","이전값2"))assertFalse(calculator.calculate(expression),expression);
        assertFalse(calculator.calculate(null));assertTrue(calculator.history().isEmpty());assertFalse(calculator.hasPrevious());
    }
    @Test void previousValueIsSessionLocalAndRecordedWithItsOriginalMeaning(){
        var first=new PhoneCalculator();var second=new PhoneCalculator();calculate(first,"1/3");first.continuePrevious();assertEquals("이전값 * ",first.draft());
        assertFalse(second.calculate("ans*3"));assertFalse(second.hasPrevious());assertThrows(IllegalStateException.class,second::continuePrevious);
        var entry=calculate(first,"ans*6");assertEquals("1/3",entry.previous());assertEquals("2",entry.exact());calculate(first,"9");assertEquals("1/3",entry.previous());
        assertThrows(UnsupportedOperationException.class,()->first.history().clear());assertThrows(UnsupportedOperationException.class,()->entry.lines().clear());
    }
    @Test void onlyFourSuccessfulResultsAreRetainedAndClearCloseForgetEverything(){
        var calculator=new PhoneCalculator();for(int i=0;i<6;i++)calculate(calculator,Integer.toString(i));assertEquals(List.of("2","3","4","5"),calculator.history().stream().map(PhoneCalculator.Entry::exact).toList());
        calculator.draft("ans+1");calculator.clear();assertEquals("",calculator.draft());assertFalse(calculator.hasPrevious());assertTrue(calculator.history().isEmpty());assertFalse(calculator.calculate("ans"));
        calculate(calculator,"2/3");calculator.draft("ans+1");calculator.close();assertTrue(calculator.history().isEmpty());assertFalse(calculator.hasPrevious());assertNull(calculator.error());assertEquals("",calculator.draft());
        assertThrows(IllegalStateException.class,()->calculator.calculate("1"));assertThrows(IllegalStateException.class,()->calculator.draft("1"));assertThrows(IllegalStateException.class,calculator::clear);calculator.close();
    }
    @Test void calculatorDoesNotReadOrMutateAiHistoryDraftCacheOrPendingWork(){
        var calls=new AtomicInteger();var pending=new CompletableFuture<String>();
        var service=new AssistantService(){public CompletionStage<String> ask(String text){calls.incrementAndGet();return pending;}};
        var chat=new PhoneChat(service,Runnable::run);var request=chat.submit("원리 설명");var calculator=new PhoneCalculator();
        calculate(calculator,"400*.5/2");calculate(calculator,"ans*2");calculator.clear();calculator.close();
        assertEquals(1,calls.get());assertTrue(chat.busy());assertFalse(pending.isDone());assertEquals("원리 설명",chat.draft());assertTrue(chat.history().isEmpty());assertEquals(0,chat.cacheStats().entries());
        pending.complete("일반 원리 설명");request.toCompletableFuture().join();assertEquals(1,chat.history().size());assertEquals("일반 원리 설명",chat.history().getFirst().answer());
    }
    @Test void integerCancellationAndFractionInversesHoldForDifferentSigns(){
        for(int a=-5;a<=5;a++)for(int b=1;b<=7;b++){
            var calculator=new PhoneCalculator();assertEquals(Integer.toString(a),calculate(calculator,"("+a+"/"+b+")*"+b).exact());
            assertEquals("0",calculate(calculator,"("+a+"+"+b+")-("+b+"+"+a+")").exact());
        }
    }
}
