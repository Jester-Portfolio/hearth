package io.github.jesterportfolio.hearth.survival.travel;

import java.util.*;
import java.util.function.LongSupplier;

/** 단일 writer의 30초 태그. quit/death는 태그를 지우지 않는다. */
public final class CombatTags {
    public static final long DURATION=30_000;
    private final LongSupplier clock;private final Map<UUID,Long> deadlines=new java.util.concurrent.ConcurrentHashMap<>();private final Map<UUID,Long> dirty=new HashMap<>();
    private final long startupUntil;
    public CombatTags(LongSupplier clock,boolean startupGuard){this.clock=clock;startupUntil=startupGuard?Math.addExact(clock.getAsLong(),DURATION):0;}
    public void restore(Map<UUID,Long> saved){saved.forEach((id,until)->deadlines.merge(id,until,Math::max));}
    public void hit(UUID attacker,UUID victim){long until=Math.addExact(clock.getAsLong(),DURATION);if(attacker!=null)tag(attacker,until);if(victim!=null)tag(victim,until);}
    private void tag(UUID id,long until){deadlines.merge(id,until,Math::max);dirty.merge(id,until,Math::max);}
    public long deadline(UUID id){return Math.max(startupUntil,deadlines.getOrDefault(id,0L));}
    public boolean blocked(UUID id){return deadline(id)>clock.getAsLong();}
    public long remainingSeconds(UUID id){long left=deadline(id)-clock.getAsLong();return left<=0?0:(left+999)/1000;}
    public Map<UUID,Long> pending(){return Map.copyOf(dirty);}
    public void acknowledge(Map<UUID,Long> saved){saved.forEach((id,until)->dirty.computeIfPresent(id,(k,current)->current<=until?null:current));}
    public void prune(){long now=clock.getAsLong();deadlines.entrySet().removeIf(e->e.getValue()<=now&&!dirty.containsKey(e.getKey()));}
}
