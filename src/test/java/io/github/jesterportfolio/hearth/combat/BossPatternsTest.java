package io.github.jesterportfolio.hearth.combat;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BossPatternsTest {
    static final List<CombatContent.Pattern> PHASES=List.of(new CombatContent.Pattern("opening","기본 공격",1,1000,200,4,2),new CombatContent.Pattern("pressure","집중 공격",0.5,500,100,5,3));
    static final class Fixture {
        final CombatAttemptTest.Fixture combat=new CombatAttemptTest.Fixture();final List<String> events=new ArrayList<>();final AtomicBoolean valid=new AtomicBoolean(true);boolean failStrike,invalidateOnWarning;double health=1;
        final BossPatterns engine=new BossPatterns(combat.fight.snapshot(),PHASES,new BossPatterns.Port(){
            public void phase(CombatContent.Pattern p){events.add("phase:"+p.id());}
            public void telegraph(CombatContent.Pattern p){events.add("warning:"+p.id());if(invalidateOnWarning)valid.set(false);}
            public void strike(CombatContent.Pattern p){events.add("strike:"+p.id());if(failStrike)throw new IllegalStateException("APPLY_FAILED");}
        },valid::get,()->{});
        void poll(){combat.fight.poll();engine.poll(combat.fight.snapshot(),health);}
        void advance(long elapsed){combat.clock.addAndGet(elapsed);poll();}
    }
    @Test void warnsBeforeStrikeAndSameTickNeverRepeatsIt(){var f=new Fixture();f.poll();assertEquals(List.of("phase:opening"),f.events);f.advance(1000);assertTrue(f.engine.view().winding());assertEquals(0,f.engine.view().strikes());f.advance(199);assertEquals(0,f.engine.view().strikes());f.advance(1);assertEquals(1,f.engine.view().strikes());f.poll();f.poll();assertEquals(1,f.engine.view().strikes());assertEquals("strike:opening",f.events.getLast());}
    @Test void healthBandChangeCancelsOldWarningAndSchedulesNewDefinition(){var f=new Fixture();f.poll();f.advance(1000);f.health=0.5;f.poll();assertEquals("pressure",f.engine.view().phase());assertFalse(f.engine.view().winding());f.advance(200);assertEquals(0,f.engine.view().strikes());f.advance(300);assertTrue(f.engine.view().winding());f.advance(100);assertEquals(1,f.engine.view().strikes());assertEquals("strike:pressure",f.events.getLast());f.health=0.75;f.poll();assertEquals("opening",f.engine.view().phase());}
    @Test void pausedFightAndReturnProtectionDoNotAdvancePendingAttack(){var f=new Fixture();f.poll();f.advance(1000);long combatTime=f.combat.fight.snapshot().combatMillis();f.combat.deadP();f.combat.fight.disconnect(f.combat.q,f.combat.two,f.combat.pose);f.advance(10000);assertEquals(BossPatterns.State.PAUSED,f.engine.view().state());assertEquals(combatTime,f.combat.fight.snapshot().combatMillis());assertEquals(0,f.engine.view().strikes());var fresh=new Object();f.combat.fight.reconnect(f.combat.q,fresh);f.combat.fight.returned(f.combat.q,fresh);f.advance(3000);assertEquals(0,f.engine.view().strikes());f.advance(200);assertEquals(1,f.engine.view().strikes());}
    @Test void victoryAndHoldCancelPendingAttackWithoutChangingBattleResult(){for(boolean hold:List.of(false,true)){var f=new Fixture();f.poll();f.advance(1000);if(hold)f.combat.fight.invalidate();else f.combat.fight.victory();var snapshot=f.combat.fight.snapshot();if(hold)assertThrows(IllegalStateException.class,f::poll);else f.poll();f.advance(10000);assertFalse(f.engine.view().winding());assertEquals(0,f.engine.view().strikes());assertEquals(snapshot.outcome(),f.combat.fight.snapshot().outcome());}}
    @Test void authorizationLostDuringWarningPreventsStrikeAndHoldsOnlyThisEngine(){var f=new Fixture();var other=new Fixture();f.poll();other.poll();f.invalidateOnWarning=true;assertThrows(IllegalStateException.class,()->f.advance(1000));assertEquals(BossPatterns.State.HOLD,f.engine.view().state());assertEquals(0,f.engine.view().strikes());f.advance(10000);assertEquals(0,f.engine.view().strikes());other.advance(1200);other.advance(200);assertEquals(1,other.engine.view().strikes());}
    @Test void foreignAttemptGenerationAndReversedClockCannotDriveCurrentEngine(){for(int kind=0;kind<3;kind++){var f=new Fixture();f.poll();var s=f.combat.fight.snapshot();var invalid=new CombatAttempt.Snapshot(kind==0?UUID.randomUUID():s.attempt(),s.run(),kind==1?s.generation()+1:s.generation(),s.revision(),s.phase(),s.outcome(),s.difficultyPlayers(),s.sharedUsed(),s.reserved(),kind==2?-1:s.combatMillis(),s.error(),s.members());assertThrows(IllegalStateException.class,()->f.engine.poll(invalid,1));assertEquals(BossPatterns.State.HOLD,f.engine.view().state());assertEquals(0,f.engine.view().strikes());}}
    @Test void delayedTickDoesNotBurstAndCloseDiscardsPendingCallbacks(){var f=new Fixture();f.poll();f.advance(100000);f.advance(200);assertEquals(1,f.engine.view().strikes());f.poll();assertEquals(1,f.engine.view().strikes());f.advance(1000);assertTrue(f.engine.view().winding());f.engine.close();f.advance(10000);assertEquals(BossPatterns.State.CLOSED,f.engine.view().state());assertEquals(1,f.engine.view().strikes());}
    @Test void applicationFailureNeverRetriesAndInvalidHealthNeverStartsAttack(){var f=new Fixture();f.poll();f.advance(1000);f.failStrike=true;assertThrows(IllegalStateException.class,()->f.advance(200));int count=f.events.size();f.advance(10000);assertEquals(count,f.events.size());assertEquals(0,f.engine.view().strikes());for(double value:new double[]{Double.NaN,-1,1.1}){var invalid=new Fixture();invalid.health=value;assertThrows(IllegalArgumentException.class,invalid::poll);assertTrue(invalid.events.isEmpty());}}
}
