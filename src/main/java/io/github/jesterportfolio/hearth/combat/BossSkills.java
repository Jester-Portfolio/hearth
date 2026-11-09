package io.github.jesterportfolio.hearth.combat;

import java.util.*;
import java.util.function.*;

/** 별도 벽시계 타이머 없이 같은 도전 전투 시간으로 연속 타격을 처리합니다. */
public final class BossSkills implements AutoCloseable {
    public record Strike(CombatContent.Pattern pattern,CombatContent.Pose origin,int pulse){}
    private record Pending(Strike strike,long due){}
    private final UUID run,attempt;private final long generation;private final BooleanSupplier current;private final Consumer<Strike> deliver;private final PriorityQueue<Pending> queue=new PriorityQueue<>(Comparator.comparingLong(Pending::due));private long last=-1;private boolean closed;
    public BossSkills(CombatAttempt.Snapshot state,BooleanSupplier current,Consumer<Strike> deliver){run=state.run();attempt=state.attempt();generation=state.generation();this.current=current;this.deliver=deliver;}
    public void cast(CombatContent.Pattern p,CombatContent.Pose origin,long time){if(closed||!current.getAsBoolean()||queue.size()+p.repeats()>16)throw new IllegalStateException("BOSS_SKILL_SCOPE");for(int i=0;i<p.repeats();i++)queue.add(new Pending(new Strike(p,origin,i),Math.addExact(time,Math.multiplyExact(i,p.repeatMillis()))));}
    public void poll(CombatAttempt.Snapshot state){if(closed)return;if(!run.equals(state.run())||!attempt.equals(state.attempt())||generation!=state.generation()||state.combatMillis()<last){close();throw new IllegalStateException("BOSS_SKILL_STALE");}last=state.combatMillis();if(state.phase()==CombatAttempt.Phase.CLOSED||state.phase()==CombatAttempt.Phase.HOLD){close();return;}if(state.phase()!=CombatAttempt.Phase.ACTIVE)return;if(!current.getAsBoolean()){close();throw new IllegalStateException("BOSS_SKILL_SCOPE");}while(!queue.isEmpty()&&queue.peek().due<=last){var next=queue.remove();if(!current.getAsBoolean()){close();throw new IllegalStateException("BOSS_SKILL_SCOPE");}deliver.accept(next.strike);}}
    public int pending(){return queue.size();}public void cancel(){queue.clear();}public void close(){closed=true;queue.clear();}
}
