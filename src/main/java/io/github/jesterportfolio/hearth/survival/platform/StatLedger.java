package io.github.jesterportfolio.hearth.survival.platform;

import java.util.*;

/** 출처 ID는 장비 복사/반복 갱신에도 불변. Aura 밖의 순수 합산/진단 모델. */
public final class StatLedger {
    public record Contribution(String stat,double amount){public Contribution{if(stat==null||!stat.matches("[a-z][a-z0-9_]+/[a-z][a-z0-9_]*")||!Double.isFinite(amount)||Math.abs(amount)>1e6)throw new IllegalArgumentException("STAT_CONTRIBUTION");}}
    public record View(Map<String,Contribution> sources,Map<String,Double> totals){}
    private final Map<UUID,Map<String,Contribution>> players=new HashMap<>();
    private String stacking="ADD";private double cap=1e6;
    public void policy(String mode,double bound){if(!Set.of("ADD","MAX_ABSOLUTE").contains(mode)||!Double.isFinite(bound)||bound<=0||bound>1e6)throw new IllegalArgumentException("STAT_POLICY");stacking=mode;cap=bound;}
    public View replace(UUID player,String namespace,Map<String,Contribution> values){
        if(namespace==null||!namespace.matches("[a-z][a-z0-9_]{0,30}")||values.size()>512||values.keySet().stream().anyMatch(k->k.isBlank()||k.length()>128))throw new IllegalArgumentException("STAT_SOURCE");
        if(!players.containsKey(player)&&players.size()>=4096)throw new IllegalStateException("STAT_CAPACITY");
        var validated=Map.copyOf(values);var sources=players.computeIfAbsent(player,x->new TreeMap<>());sources.keySet().removeIf(k->k.startsWith(namespace+"/"));validated.forEach((k,v)->sources.put(namespace+"/"+k,v));if(sources.isEmpty())players.remove(player);
        return view(player);
    }
    public View view(UUID player){var sources=players.getOrDefault(player,Map.of());var totals=new TreeMap<String,Double>();sources.values().forEach(v->totals.merge(v.stat,v.amount,(a,b)->stacking.equals("ADD")?a+b:Math.abs(a)>=Math.abs(b)?a:b));totals.replaceAll((k,v)->Math.clamp(v,-cap,cap));return new View(Map.copyOf(sources),Map.copyOf(totals));}
    public void clear(UUID player){players.remove(player);}public int playerCount(){return players.size();}
}
