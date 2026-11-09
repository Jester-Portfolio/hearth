package io.github.jesterportfolio.hearth.combat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static io.github.jesterportfolio.hearth.combat.CombatContent.*;
import static org.junit.jupiter.api.Assertions.*;
final class BossSkillsTest {
    final UUID run=UUID.randomUUID(),attempt=UUID.randomUUID(),world=UUID.randomUUID();
    CombatAttempt.Snapshot state(CombatAttempt.Phase phase,long clock){return new CombatAttempt.Snapshot(attempt,run,1,0,phase,CombatAttempt.Outcome.NONE,1,0,0,clock,null,List.of());}
    Pattern wave(){return new Pattern("wave","공개 연속 타격",1,1000,100,6,2,Shape.RING,2,0,4,Origin.LOCKED,Technique.REPEL,.4,0,3,200);}
    @Test void repeatsUseOnlySameAttemptCombatClock(){var hits=new ArrayList<BossSkills.Strike>();var skills=new BossSkills(state(CombatAttempt.Phase.ACTIVE,0),()->true,hits::add);skills.cast(wave(),new Pose(world,0,64,0,0,0),100);skills.poll(state(CombatAttempt.Phase.ACTIVE,100));assertEquals(1,hits.size());skills.poll(state(CombatAttempt.Phase.ACTIVE,299));assertEquals(1,hits.size());skills.poll(state(CombatAttempt.Phase.ACTIVE,300));skills.poll(state(CombatAttempt.Phase.ACTIVE,500));assertEquals(3,hits.size());assertEquals(0,skills.pending());}
    @Test void pauseCloseAndPhaseChangeDoNotReplayOldAttacks(){var hits=new ArrayList<BossSkills.Strike>();var skills=new BossSkills(state(CombatAttempt.Phase.ACTIVE,0),()->true,hits::add);skills.cast(wave(),new Pose(world,0,64,0,0,0),0);skills.poll(state(CombatAttempt.Phase.PAUSED,0));assertTrue(hits.isEmpty());skills.cancel();skills.poll(state(CombatAttempt.Phase.ACTIVE,500));assertTrue(hits.isEmpty());skills.cast(wave(),new Pose(world,0,64,0,0,0),500);skills.poll(state(CombatAttempt.Phase.CLOSED,500));assertEquals(0,skills.pending());assertTrue(hits.isEmpty());}
    @Test void staleSourceOrWorldOwnershipRejectsAndClearsQueue(){var live=new AtomicBoolean(true);var skills=new BossSkills(state(CombatAttempt.Phase.ACTIVE,0),live::get,s->fail());skills.cast(wave(),new Pose(world,0,64,0,0,0),0);live.set(false);assertThrows(IllegalStateException.class,()->skills.poll(state(CombatAttempt.Phase.ACTIVE,0)));assertEquals(0,skills.pending());}
    @Test void ringHasInnerSafeZoneAndBoundedPublishedBoundary(){var p=wave();var origin=new Pose(world,0,64,0,0,0);assertFalse(BossPatternArea.contains(p,origin,new Pose(world,1,64,0,0,0)));assertTrue(BossPatternArea.contains(p,origin,new Pose(world,3,64,0,0,0)));assertFalse(BossPatternArea.contains(p,origin,new Pose(world,7,64,0,0,0)));assertEquals(24,BossPatternArea.boundary(p,origin).size());}
    @Test void malformedPulseOrAreaConfigurationIsRejected(){assertThrows(IllegalArgumentException.class,()->new Pattern("wave","연속",1,1000,100,6,2,Shape.RING,6,0,4,Origin.LOCKED,Technique.STRIKE,0,0,3,200));assertThrows(IllegalArgumentException.class,()->new Pattern("wave","연속",1,1000,100,6,2,Shape.RING,2,0,4,Origin.LOCKED,Technique.STRIKE,0,0,5,200));}
}
