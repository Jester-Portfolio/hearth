package io.github.jesterportfolio.hearth.combat;

import java.util.*;

/** 공개 보스 제어 시험의 콘텐츠. 수치와 위치는 운영 정책이 아닌 명시적 시험값입니다. */
public record CombatContent(String id,String title,double healthPerPlayer,double attack,long returnProtectionMillis,Revival revival) {
    public enum Pool { PERSONAL,SHARED }
    public enum LocationMode { DEATH,START,FIXED }
    public record Pose(UUID world,double x,double y,double z,float yaw,float pitch) {
        public Pose {Objects.requireNonNull(world);if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Float.isFinite(yaw)||!Float.isFinite(pitch))throw new IllegalArgumentException("Invalid combat pose");}
    }
    public record Effect(String type,int amplifier,int ticks) {
        public Effect {if(!Set.of("RESISTANCE","REGENERATION","SLOWNESS").contains(type)||amplifier<0||amplifier>4||ticks<1||ticks>1200)throw new IllegalArgumentException("Invalid combat effect");}
    }
    public record Revival(boolean enabled,String condition,Pool pool,int limit,double healthFraction,LocationMode location,FieldContent.Point fixed,List<Effect> effects) {
        public Revival {effects=List.copyOf(effects);Objects.requireNonNull(pool);Objects.requireNonNull(location);if(condition==null||!condition.matches("[a-z][a-z0-9_]{0,63}")||limit<0||limit>32||!Double.isFinite(healthFraction)||healthFraction<=0||healthFraction>1||effects.size()>4||(location==LocationMode.FIXED)!=(fixed!=null))throw new IllegalArgumentException("Invalid combat revival");}
    }
    public CombatContent {Objects.requireNonNull(revival);if(id==null||!id.matches("[a-z][a-z0-9_]{0,63}")||title==null||title.isBlank()||title.length()>80||!Double.isFinite(healthPerPlayer)||healthPerPlayer<1||healthPerPlayer>256||!Double.isFinite(attack)||attack<0||attack>32||returnProtectionMillis<0||returnProtectionMillis>30000)throw new IllegalArgumentException("Invalid combat content");}
    public static CombatContent parse(Map<?,?> root){if(!(root.get("format") instanceof Number f)||f.doubleValue()!=1)throw new IllegalArgumentException("Invalid combat format");var r=map(root.get("revival"));if(!(r.get("enabled") instanceof Boolean enabled))throw new IllegalArgumentException("Invalid combat enabled flag");var effects=new ArrayList<Effect>();if(!(r.get("effects") instanceof List<?> raw))throw new IllegalArgumentException("Invalid combat effects");for(Object e:raw){var m=map(e);effects.add(new Effect(text(m,"type"),integer(m,"amplifier"),integer(m,"ticks")));}FieldContent.Point fixed=null;if(r.containsKey("fixed")){var p=map(r.get("fixed"));fixed=new FieldContent.Point(number(p,"x"),number(p,"y"),number(p,"z"));}return new CombatContent(text(root,"id"),text(root,"title"),number(root,"health-per-player"),number(root,"attack"),integer(root,"return-protection-ms"),new Revival(enabled,text(r,"condition"),Pool.valueOf(text(r,"pool")),integer(r,"limit"),number(r,"health-fraction"),LocationMode.valueOf(text(r,"location")),fixed,effects));}
    private static Map<?,?> map(Object o){if(!(o instanceof Map<?,?> m))throw new IllegalArgumentException("Invalid combat mapping");return m;}
    private static String text(Map<?,?> m,String k){if(!(m.get(k) instanceof String s))throw new IllegalArgumentException("Invalid combat text");return s;}
    private static double number(Map<?,?> m,String k){if(!(m.get(k) instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Invalid combat number");return n.doubleValue();}
    private static int integer(Map<?,?> m,String k){double n=number(m,k);if(n!=(int)n)throw new IllegalArgumentException("Invalid combat integer");return (int)n;}
}
