package io.github.jesterportfolio.hearth.administration;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RuntimeReportTest {
    private RuntimeReport report(String phase,int held){return new RuntimeReport(new UUID(0,1),17,100,"story",phase,2,held,true,20,3.5,1024);}
    @Test void readinessDependsOnControllerAndHeldWorlds(){assertTrue(report("READY",0).controllerReady());assertFalse(report("READY",1).controllerReady());for(var phase:List.of("STARTING","HOLD","DRAINING","DRAINED","CLOSED","REFUSED"))assertFalse(report(phase,0).controllerReady());}
    @Test void schemaContainsOnlyAggregateMetricsAndProcessNonce(){var json=JsonParser.parseString(report("READY",0).json()).getAsJsonObject();assertEquals(Set.of("format","boot","pid","observed_ms","mode","phase","controller_ready","worlds","held_worlds","ai_preparation_recorded","tps","mspt","heap_used_bytes"),json.keySet());assertEquals(1,json.get("format").getAsInt());assertEquals(17,json.get("pid").getAsLong());assertTrue(json.get("ai_preparation_recorded").getAsBoolean());}
    @Test void invalidMetricsOrUnboundedLabelsAreRefused(){assertThrows(IllegalArgumentException.class,()->report("private arbitrary text",0));assertThrows(IllegalArgumentException.class,()->report("READY",3));assertThrows(IllegalArgumentException.class,()->new RuntimeReport(UUID.randomUUID(),1,1,"story","READY",0,0,false,Double.NaN,0,0));assertThrows(IllegalArgumentException.class,()->new RuntimeReport(UUID.randomUUID(),1,1,"story","READY",0,0,false,20,0,-1));}
}
