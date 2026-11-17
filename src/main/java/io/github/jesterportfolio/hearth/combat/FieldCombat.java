package io.github.jesterportfolio.hearth.combat;

import java.util.*;

/** 필드도 기존 인던의 한 고정 명단·사망/관전·부활/복귀 정책으로 처리합니다. */
public final class FieldCombat {
    private FieldCombat(){}
    public static CombatContent definition(FieldContent.Zone first,CombatContent dungeon){
        if(dungeon.exit()==null)throw new IllegalStateException("FIELD_COMBAT_EXIT_REQUIRED");
        var tier=first.tiers().getFirst();
        return new CombatContent(first.id(),first.title(),tier.health(),tier.attack(),dungeon.returnProtectionMillis(),dungeon.revival(),null,dungeon.exit());
    }
    public static Set<UUID> roster(FieldContent content,FieldEncounters.Binding binding,List<String> started,List<FieldEncounters.Presence> online){
        if(started.isEmpty()||started.stream().distinct().count()!=started.size())throw new IllegalArgumentException("FIELD_COMBAT_ZONES_REQUIRED");
        var zones=started.stream().map(id->content.zones().stream().filter(z->z.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("FIELD_COMBAT_UNKNOWN_ZONE"))).toList();
        var selected=new HashSet<UUID>();
        for(var p:online)if(binding.equals(p.binding())&&zones.stream().anyMatch(z->z.population().contains(p.location())))selected.add(p.player());
        if(selected.isEmpty()||selected.size()>32)throw new IllegalStateException("FIELD_COMBAT_ROSTER_REQUIRED");
        return Set.copyOf(selected);
    }
    public static boolean joinable(CombatAttempt battle,FieldEncounters.Binding binding,Set<UUID> players){
        var s=battle.snapshot();var roster=new HashSet<UUID>();s.members().forEach(m->roster.add(m.player()));
        return s.run().equals(binding.run())&&battle.worldId().equals(binding.world())&&s.generation()==binding.generation()&&s.phase()==CombatAttempt.Phase.ACTIVE&&!players.isEmpty()&&roster.containsAll(players);
    }
    public static boolean cleared(FieldEncounters engine,FieldEncounters.Binding binding,Set<String> zones){
        if(!engine.current(binding)||zones.isEmpty())throw new IllegalStateException("FIELD_COMBAT_SOURCE_HOLD");
        boolean all=true;for(String id:zones){var phase=engine.state(binding.run(),id).orElseThrow(()->new IllegalStateException("FIELD_COMBAT_SOURCE_MISSING")).phase();if(phase!=FieldEncounters.Phase.ACTIVE&&phase!=FieldEncounters.Phase.CLEARED)throw new IllegalStateException("FIELD_COMBAT_SOURCE_HOLD");all&=phase==FieldEncounters.Phase.CLEARED;}return all;
    }
}
