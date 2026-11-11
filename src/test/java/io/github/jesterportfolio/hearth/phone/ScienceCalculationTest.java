package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.jesterportfolio.hearth.phone.ScienceCalculation.Topic.*;

final class ScienceCalculationTest {
    private static PhoneCalculator.Entry run(PhoneCalculator calculator,ScienceCalculation.Topic topic,String... values){
        assertTrue(calculator.calculateScience(ScienceCalculation.prepare(topic,List.of(values))),calculator::error);return calculator.history().getLast();
    }
    @Test void structuredLeverDistinguishesTheTwoArmsAndStatesAssumptionsAndUnits(){
        var calculator=new PhoneCalculator();var first=run(calculator,LEVER,"400","0.5","2");assertEquals("100",first.exact());assertTrue(first.lines().contains("정확값: 100 N"));assertTrue(first.lines().stream().anyMatch(s->s.contains("수직")));
        assertEquals("200",run(calculator,LEVER,"400","0.5","1").exact());assertEquals("1600",run(calculator,LEVER,"400","2","0.5").exact());
        assertEquals("0",run(calculator,LEVER,"0",".5","2").exact());
    }
    @Test void energyUsesExplicitGravityAndSIInputsAndOnlyHeightIncrease(){
        var calculator=new PhoneCalculator();assertEquals("500",run(calculator,ENERGY,"10","5","10").exact());assertEquals("490",run(calculator,ENERGY,"10","5","9.8").exact());
        assertTrue(calculator.history().getLast().lines().contains("정확값: 490 J"));assertEquals("0",run(calculator,ENERGY,"0","5","10").exact());assertEquals("0",run(calculator,ENERGY,"10","0","10").exact());
        assertThrows(IllegalArgumentException.class,()->ScienceCalculation.prepare(ENERGY,List.of("10","-5","10")));
    }
    @Test void seriesAcceptsNonnegativeResistanceWithExactDecimals(){
        var calculator=new PhoneCalculator();assertEquals("5",run(calculator,SERIES,"2","3").exact());assertTrue(calculator.history().getLast().lines().contains("정확값: 5 Ω"));
        assertEquals("0.3",run(calculator,SERIES,"0.1","0.2").exact());assertEquals("1000",run(calculator,SERIES,"1e3","0").exact());
    }
    @Test void formsRejectMissingZeroDivisorsUnitsExpressionsNegativeOrExcessiveValues(){
        for(var values:List.of(List.of("400",".5","0"),List.of("400","0","2"),List.of("400 N",".5","2"),List.of("400",".5",""),List.of("400",".5","1+1"),List.of("400",".5","-2"),List.of("400",".5","ans"),List.of("400",".5","1e31"),List.of("400",".5","1".repeat(33))))assertThrows(IllegalArgumentException.class,()->ScienceCalculation.prepare(LEVER,values),values.toString());
        assertThrows(IllegalArgumentException.class,()->ScienceCalculation.prepare(ENERGY,List.of("1","2","0")));
        assertThrows(IllegalArgumentException.class,()->ScienceCalculation.prepare(SERIES,List.of("1")));assertThrows(IllegalArgumentException.class,()->ScienceCalculation.prepare(SERIES,Arrays.asList(null,"2")));
        assertThrows(UnsupportedOperationException.class,()->LEVER.fields().clear());
    }
    @Test void formCalculationsKeepManualDraftAndFailureKeepsPreviousResult(){
        var calculator=new PhoneCalculator();calculator.draft("1+2");run(calculator,LEVER,"400",".5","2");assertEquals("1+2",calculator.draft());var before=calculator.history();
        var plan=ScienceCalculation.prepare(ENERGY,List.of("1e30","1e30","1e30"));assertFalse(calculator.calculateScience(plan));assertEquals(before,calculator.history());assertEquals("1+2",calculator.draft());
        assertTrue(calculator.calculate("ans*2"));assertEquals("200",calculator.history().getLast().exact());assertNull(calculator.history().getLast().science());
    }
    @Test void formInputsAreSnapshottedAndNumericResultHasNoImplicitUnitConversion(){
        var values=new ArrayList<>(List.of("1","1","3"));var plan=ScienceCalculation.prepare(LEVER,values);values.set(0,"999");var calculator=new PhoneCalculator();assertTrue(calculator.calculateScience(plan));assertEquals("1/3",calculator.history().getLast().exact());
        assertEquals(LEVER,calculator.history().getLast().science());assertTrue(calculator.calculate("ans*3"));var continued=calculator.history().getLast();assertEquals("1/3",continued.previous());assertNull(continued.science());assertEquals("1",continued.exact());
    }
}
