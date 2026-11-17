package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ScienceComparisonTest {
    private static PhoneCalculator.Entry add(PhoneCalculator calculator,ScienceCalculation.Topic topic,String... values){assertTrue(calculator.calculateScience(ScienceCalculation.prepare(topic,List.of(values))));return calculator.history().getLast();}
    private static PhoneCalculator.Entry lever(PhoneCalculator calculator,String arm){return add(calculator,ScienceCalculation.Topic.LEVER,"400","0.5",arm);}

    @Test void changingTheLeverArmShowsExactDifferencesRatiosAndWhichInputChanged(){
        var calculator=new PhoneCalculator();var baseline=lever(calculator,"2");var compared=lever(calculator,"1");var original=calculator.select(baseline);var result=calculator.compare(baseline,compared);
        assertEquals("100",result.baseline().exact());assertEquals("200",result.compared().exact());assertEquals("100",result.difference().exact());assertEquals("2",result.ratio().exact());assertTrue(result.difference().available());assertNull(result.difference().approximation());assertEquals(List.of(false,false,true),result.inputs().stream().map(ScienceComparison.Input::changed).toList());assertEquals("2",result.inputs().getLast().baseline());assertEquals("1",result.inputs().getLast().compared());assertEquals(original,calculator.select(baseline));assertTrue(result.lines().stream().anyMatch(line->line.contains("100 N")));assertTrue(result.lines().stream().anyMatch(line->line.contains("2배")));
    }
    @Test void swappingTheBaselineChangesDifferenceSignAndInvertsTheRatio(){
        var calculator=new PhoneCalculator();var baseline=lever(calculator,"2");var compared=lever(calculator,"1");var result=calculator.compare(compared,baseline);assertEquals("-100",result.difference().exact());assertEquals("0.5",result.ratio().exact());assertEquals("200",result.baseline().exact());assertEquals("100",result.compared().exact());
    }
    @Test void energyAndResistanceUseTheirOwnFormulaInputsAndOutputUnits(){
        var energy=new PhoneCalculator();var first=add(energy,ScienceCalculation.Topic.ENERGY,"10","5","10");var second=add(energy,ScienceCalculation.Topic.ENERGY,"10","10","10");var e=energy.compare(first,second);assertEquals("500",e.difference().exact());assertEquals("2",e.ratio().exact());assertTrue(e.lines().stream().anyMatch(line->line.contains("1000 J")));assertEquals(List.of(false,true,false),e.inputs().stream().map(ScienceComparison.Input::changed).toList());
        var series=new PhoneCalculator();var a=add(series,ScienceCalculation.Topic.SERIES,"2","3");var b=add(series,ScienceCalculation.Topic.SERIES,"2","8");var r=series.compare(a,b);assertEquals("5",r.difference().exact());assertEquals("2",r.ratio().exact());assertTrue(r.lines().stream().anyMatch(line->line.contains("10 Ω")));assertEquals(ScienceReading.SERIES,r.baseline().reading());
    }
    @Test void fractionsAndTheirApproximationAreDistinctAndNoRoundedValueIsUsedForTheRatio(){
        var calculator=new PhoneCalculator();var first=add(calculator,ScienceCalculation.Topic.LEVER,"1","1","3");var second=add(calculator,ScienceCalculation.Topic.LEVER,"2","1","3");var result=calculator.compare(first,second);assertEquals("1/3",result.difference().exact());assertEquals("0.33333333333333333333",result.difference().approximation());assertEquals("2",result.ratio().exact());assertNull(result.ratio().approximation());assertTrue(result.lines().stream().anyMatch(line->line.contains("근삿값")));assertEquals("1/3",calculator.select(first).exact());
    }
    @Test void zeroBaselineNeverCreatesInfinityAndZeroComparedWithNonzeroIsAValidRatio(){
        var calculator=new PhoneCalculator();var zero=add(calculator,ScienceCalculation.Topic.SERIES,"0","0");var other=add(calculator,ScienceCalculation.Topic.SERIES,"2","3");var bothZero=add(calculator,ScienceCalculation.Topic.SERIES,"0","0");
        for(var target:List.of(other,bothZero)){var result=calculator.compare(zero,target);assertFalse(result.ratio().available());assertNull(result.ratio().exact());assertTrue(result.ratio().notice().contains("0"));assertFalse(String.join("\n",result.lines()).contains("Infinity"));}
        var reversed=calculator.compare(other,zero);assertEquals("0",reversed.ratio().exact());assertEquals("-5",reversed.difference().exact());
    }
    @Test void numericallyEqualInputsAreUnchangedEvenWhenSpelledDifferently(){
        var calculator=new PhoneCalculator();var a=add(calculator,ScienceCalculation.Topic.LEVER,"400",".5","2");var b=add(calculator,ScienceCalculation.Topic.LEVER,"4e2","+0.50","2.0");var result=calculator.compare(a,b);assertTrue(result.inputs().stream().noneMatch(ScienceComparison.Input::changed));assertEquals("0",result.difference().exact());assertEquals("1",result.ratio().exact());assertEquals("4e2",result.inputs().getFirst().compared());assertTrue(result.lines().stream().anyMatch(line->line.contains("입력: 0개")));
    }
    @Test void candidateSelectionUsesOwnerIdentityAndTheSameTopicAndAllowsDistinctIdenticalEntries(){
        var calculator=new PhoneCalculator();var plan=ScienceCalculation.prepare(ScienceCalculation.Topic.LEVER,List.of("400",".5","2"));assertTrue(calculator.calculateScience(plan));var a=calculator.history().getLast();assertTrue(calculator.calculateScience(plan));var b=calculator.history().getLast();assertEquals(a,b);assertNotSame(a,b);add(calculator,ScienceCalculation.Topic.SERIES,"2","3");assertTrue(calculator.calculate("100"));var candidates=calculator.comparisons(a);assertEquals(1,candidates.size());assertSame(b,candidates.getFirst());assertEquals("0",calculator.compare(a,b).difference().exact());assertThrows(UnsupportedOperationException.class,candidates::clear);
        var forged=new PhoneCalculator.Entry(a.expression(),a.previous(),a.exact(),a.approximation(),a.science());assertThrows(IllegalStateException.class,()->calculator.compare(a,forged));assertThrows(IllegalArgumentException.class,()->calculator.compare(a,a));
    }
    @Test void crossOwnerManualAndMixedTopicPairsCannotBeComparedAndDoNotMutateTheCalculator(){
        var calculator=new PhoneCalculator();var first=lever(calculator,"2");var different=add(calculator,ScienceCalculation.Topic.SERIES,"2","3");assertTrue(calculator.calculate("100"));var manual=calculator.history().getLast();var other=new PhoneCalculator();var foreign=lever(other,"1");var before=calculator.history();
        assertThrows(IllegalStateException.class,()->calculator.compare(first,foreign));assertThrows(IllegalStateException.class,()->calculator.compare(first,manual));assertThrows(IllegalArgumentException.class,()->calculator.compare(first,different));assertThrows(IllegalStateException.class,()->calculator.comparisons(manual));assertThrows(IllegalStateException.class,()->calculator.comparisons(null));assertEquals(before,calculator.history());
    }
    @Test void removingEitherOriginalEntryOrClosingTheOwnerRejectsOldComparisonSelections(){
        var calculator=new PhoneCalculator();var baseline=lever(calculator,"2");var compared=lever(calculator,"1");for(int i=0;i<3;i++)calculator.calculate("1");assertFalse(calculator.current(baseline));assertTrue(calculator.current(compared));assertThrows(IllegalStateException.class,()->calculator.compare(baseline,compared));assertThrows(IllegalStateException.class,()->calculator.comparisons(baseline));
        var second=new PhoneCalculator();var a=lever(second,"2");var b=lever(second,"1");second.clear();assertThrows(IllegalStateException.class,()->second.compare(a,b));second.close();assertThrows(IllegalStateException.class,()->second.comparisons(a));
    }
    @Test void boundedComparisonFailurePreservesBothOriginalResultsErrorDraftAndPreviousValue(){
        var calculator=new PhoneCalculator();var high=add(calculator,ScienceCalculation.Topic.ENERGY,"1e30","1","1e30");var low=add(calculator,ScienceCalculation.Topic.ENERGY,"1e-30","1","1e-30");var before=calculator.history();assertFalse(calculator.calculate("1/0"));String error=calculator.error();calculator.draft("ans+1");var result=calculator.compare(low,high);
        assertFalse(result.difference().available());assertFalse(result.ratio().available());assertTrue(result.difference().notice().contains("한도"));assertEquals(calculator.select(low),result.baseline());assertEquals(calculator.select(high),result.compared());assertEquals(before,calculator.history());assertEquals(error,calculator.error());assertEquals("ans+1",calculator.draft());assertTrue(calculator.calculate("ans*1"));assertEquals(low.exact(),calculator.history().getLast().exact());
    }
    @Test void readingComparisonDoesNotSendAiDataCancelPendingWorkOrChangeChatDraftHistoryAndCache(){
        var calls=new AtomicInteger();var pending=new CompletableFuture<String>();var chat=new PhoneChat(q->{calls.incrementAndGet();return pending;},Runnable::run);var request=chat.submit("원리를 설명해");var calculator=new PhoneCalculator();var baseline=lever(calculator,"2");var compared=lever(calculator,"1");calculator.comparisons(baseline);calculator.compare(baseline,compared);calculator.compare(compared,baseline);
        assertEquals(1,calls.get());assertTrue(chat.busy());assertFalse(pending.isDone());assertEquals("원리를 설명해",chat.draft());assertTrue(chat.history().isEmpty());assertEquals(0,chat.cacheStats().entries());pending.complete("일반 원리 설명");request.toCompletableFuture().join();assertEquals(1,calls.get());assertEquals(1,chat.history().size());chat.close();
    }
    @Test void comparisonPagesAreCompleteImmutableAndKeepAssumptionsAndMultipleInputWarning(){
        var calculator=new PhoneCalculator();var a=add(calculator,ScienceCalculation.Topic.LEVER,"1","1","3");var b=add(calculator,ScienceCalculation.Topic.LEVER,"2","2","3");var result=calculator.compare(a,b);var detail=PhonePages.text("비교",result.lines());assertEquals(String.join("\n\n",result.lines()),String.join("",detail.pages()));assertTrue(detail.pages().size()>1);assertTrue(result.lines().stream().anyMatch(line->line.contains("공통 가정")));assertTrue(result.lines().stream().anyMatch(line->line.contains("한 조건만의 영향")));assertThrows(UnsupportedOperationException.class,()->result.lines().clear());assertThrows(UnsupportedOperationException.class,()->result.inputs().clear());assertEquals(List.of(true,true,false),result.inputs().stream().map(ScienceComparison.Input::changed).toList());
    }
}
