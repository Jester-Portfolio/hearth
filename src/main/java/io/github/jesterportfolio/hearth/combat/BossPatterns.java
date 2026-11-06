package io.github.jesterportfolio.hearth.combat;

import java.util.*;
import java.util.function.BooleanSupplier;

/** 기존 도전의 전용 전투 시간만 사용합니다. 별도 틱 예약이나 DB 조회는 하지 않습니다. */
public final class BossPatterns implements AutoCloseable {
    public enum State { RUNNING,PAUSED,CLOSED,HOLD }
    public record View(String phase,State state,boolean winding,long strikes,long nextMillis,long strikeMillis) {}
    public interface Port {
        void phase(CombatContent.Pattern phase);
        void telegraph(CombatContent.Pattern phase);
        void strike(CombatContent.Pattern phase);
    }
    private final UUID run,attempt;private final long generation;private final List<CombatContent.Pattern> phases;private final Port port;private final BooleanSupplier current;private final Runnable check;
    private State state=State.RUNNING;private int selected=-1;private boolean winding;private long strikes,next,strike,last=-1;
    public BossPatterns(CombatAttempt.Snapshot source,List<CombatContent.Pattern> phases,Port port,BooleanSupplier current,Runnable check){
        this.run=source.run();this.attempt=source.attempt();this.generation=source.generation();this.phases=List.copyOf(phases);this.port=Objects.requireNonNull(port);this.current=Objects.requireNonNull(current);this.check=check;
        if(phases.isEmpty()||phases.size()>8||phases.getFirst().healthBelow()!=1||phases.stream().map(CombatContent.Pattern::id).distinct().count()!=phases.size())throw new IllegalArgumentException("Invalid boss phases");for(int i=1;i<phases.size();i++)if(phases.get(i).healthBelow()>=phases.get(i-1).healthBelow())throw new IllegalArgumentException("Unordered boss phases");
    }
    private void require(){if(!current.getAsBoolean())throw new IllegalStateException("BOSS_PATTERN_STALE");}
    public void poll(CombatAttempt.Snapshot source,double healthFraction){
        check.run();if(state==State.CLOSED||state==State.HOLD)return;
        try{
            if(!run.equals(source.run())||!attempt.equals(source.attempt())||generation!=source.generation()||source.combatMillis()<last)throw new IllegalStateException("BOSS_PATTERN_SOURCE");
            last=source.combatMillis();if(source.phase()==CombatAttempt.Phase.CLOSED){close();return;}if(source.phase()==CombatAttempt.Phase.HOLD||source.error()!=null)throw new IllegalStateException("BOSS_PATTERN_SOURCE_HOLD");
            if(source.phase()!=CombatAttempt.Phase.ACTIVE){state=State.PAUSED;return;}require();state=State.RUNNING;
            if(!Double.isFinite(healthFraction)||healthFraction<0||healthFraction>1)throw new IllegalArgumentException("BOSS_PATTERN_HEALTH");
            int phase=0;for(int i=1;i<phases.size();i++)if(healthFraction<=phases.get(i).healthBelow())phase=i;
            long now=source.combatMillis();if(phase!=selected){selected=phase;winding=false;strike=0;next=Math.addExact(now,phases.get(phase).intervalMillis());port.phase(phases.get(phase));require();}
            var definition=phases.get(selected);
            if(!winding&&now>=next){winding=true;strike=Math.addExact(now,definition.windupMillis());port.telegraph(definition);require();}
            if(winding&&now>=strike){require();winding=false;next=Math.addExact(now,definition.intervalMillis());port.strike(definition);strikes++;}
        }catch(RuntimeException failure){state=State.HOLD;winding=false;throw failure;}
    }
    public View view(){check.run();return new View(selected<0?"":phases.get(selected).id(),state,winding,strikes,next,strike);}
    @Override public void close(){check.run();if(state!=State.HOLD)state=State.CLOSED;winding=false;next=0;strike=0;}
}
