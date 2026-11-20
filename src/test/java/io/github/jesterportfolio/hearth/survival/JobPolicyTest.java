package io.github.jesterportfolio.hearth.survival;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import io.github.jesterportfolio.hearth.survival.platform.*;
import io.github.jesterportfolio.hearth.economy.JobStore;

class JobPolicyTest {
    @Test void legacyPlatformConfigurationLeavesJobsDisabled(){var p=JobPolicy.parse(new Properties(),false);assertFalse(p.enabled());assertTrue(p.active().values().stream().allMatch(n->n==0));}
    @Test void enabledIntervalsRequireTheExistingResetEngine(){var p=new Properties();p.setProperty("jobs.enabled","true");p.setProperty("jobs.resource.novice.interval-ticks","10");assertThrows(IllegalArgumentException.class,()->JobPolicy.parse(p,false));assertEquals(10L,JobPolicy.parse(p,true).active().get("novice"));}
    @Test void disabledPolicyKeepsDraftIntervalsWithoutRunningThem(){var p=new Properties();p.setProperty("jobs.resource.nether.interval-ticks","10");var policy=JobPolicy.parse(p,false);assertEquals(10L,policy.resourceIntervals().get("nether"));assertEquals(0L,policy.active().get("nether"));}
    @Test void residentialAndUnknownScheduleFieldsAreRejected(){for(String key:List.of("jobs.resource.residence.interval-ticks","jobs.command","jobs.resource.end.interval")){var p=new Properties();p.setProperty(key,"1");assertThrows(IllegalArgumentException.class,()->JobPolicy.parse(p,true));}}
    @Test void malformedFlagsNegativeAndExcessiveIntervalsAreRejected(){for(String value:List.of("yes","1")){var p=new Properties();p.setProperty("jobs.enabled",value);assertThrows(IllegalArgumentException.class,()->JobPolicy.parse(p,true));}for(String value:List.of("-1","100000001","NaN")){var p=new Properties();p.setProperty("jobs.resource.end.interval-ticks",value);assertThrows(IllegalArgumentException.class,()->JobPolicy.parse(p,true));}}
    @Test void pausedClockAndCompletionBasedSchedulingDoNotReplayMissedRuns(){assertEquals(15,JobPolicy.next(10,5));assertEquals(105,JobPolicy.next(100,5));assertThrows(ArithmeticException.class,()->JobPolicy.next(Long.MAX_VALUE,1));assertThrows(IllegalArgumentException.class,()->JobPolicy.next(1,0));}
    @Test void persistedJobArgumentsCannotChangeAfterReservation(){var values=new HashMap<>(Map.of("seed","42"));var job=new JobStore.Job(UUID.randomUUID(),"resource_reset","novice",values,1,true,JobStore.State.RUNNING,0,"");values.put("seed","43");assertEquals("42",job.arguments().get("seed"));assertThrows(UnsupportedOperationException.class,()->job.arguments().put("seed","44"));}
    @Test void invalidOpaqueArgumentsAreRejectedBeforePersistence(){assertThrows(IllegalArgumentException.class,()->new JobStore.Job(UUID.randomUUID(),"command;exec","novice",Map.of(),1,false,JobStore.State.RUNNING,0,""));assertThrows(IllegalArgumentException.class,()->new JobStore.Job(UUID.randomUUID(),"resource_reset","novice",Map.of("seed","x".repeat(257)),1,false,JobStore.State.RUNNING,0,""));}
    @Test void newDispatchRemainsClosedDuringGlobalHoldDrainStartupAndConfiguration(){assertTrue(JobPolicy.dispatchAllowed(true,false,true,true,true));assertFalse(JobPolicy.dispatchAllowed(false,false,true,true,true));assertFalse(JobPolicy.dispatchAllowed(true,true,true,true,true));assertFalse(JobPolicy.dispatchAllowed(true,false,false,true,true));assertFalse(JobPolicy.dispatchAllowed(true,false,true,false,true));assertFalse(JobPolicy.dispatchAllowed(true,false,true,true,false));}
    @Test void snapshotReportsHeldJobsRegardlessOfPastCompletedHistory(){var done=new JobStore.Job(UUID.randomUUID(),"resource_reset","end",Map.of("seed","42"),1,true,JobStore.State.SUCCEEDED,1,"");var hold=new JobStore.Job(UUID.randomUUID(),"resource_reset","novice",Map.of("seed","43"),2,true,JobStore.State.HOLD,1,"RESTART_REVIEW");assertEquals(hold,new JobStore.Snapshot(List.of(),List.of(done,hold)).active().orElseThrow());}
}
