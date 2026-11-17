package io.github.jesterportfolio.hearth.verification;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class TickObservationTest {
    @Test void phaseSamplesAreIndependentAndP95UsesActualObservedTicks(){var o=new TickObservation();o.phase(TickObservation.Phase.BASELINE);for(int i=1;i<=20;i++)o.tick(i);o.phase(TickObservation.Phase.REQUESTS);o.tick(100);var rows=o.close();assertEquals(2,rows.size());assertEquals(20,rows.getFirst().ticks());assertEquals(10.5,rows.getFirst().meanMillis());assertEquals(19,rows.getFirst().p95Millis());assertEquals(20,rows.getFirst().maxMillis());assertEquals(100,rows.getLast().meanMillis());assertEquals(rows,o.close());assertThrows(UnsupportedOperationException.class,()->rows.clear());}
    @Test void closedOrUnstartedTrackerDoesNotKeepGrowingOrInventTicks(){var empty=new TickObservation();empty.tick(25);assertTrue(empty.close().isEmpty());var o=new TickObservation();o.phase(TickObservation.Phase.BASELINE);var first=o.close();o.tick(1000);assertEquals(first,o.close());assertEquals(0,first.getFirst().ticks());assertEquals(0,first.getFirst().p95Millis());assertThrows(IllegalStateException.class,()->o.phase(TickObservation.Phase.AFTER));}
    @Test void invalidSamplesRepeatedPhasesAndMoreThan600SecondsAreRejected(){var o=new TickObservation();o.phase(TickObservation.Phase.PREPARATION);for(double bad:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->o.tick(bad));assertThrows(IllegalStateException.class,()->o.phase(TickObservation.Phase.PREPARATION));for(int i=0;i<12000;i++)o.tick(1);assertThrows(IllegalStateException.class,()->o.tick(1));assertEquals(12000,o.close().getFirst().ticks());}
}
