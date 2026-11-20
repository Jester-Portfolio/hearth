package io.github.jesterportfolio.hearth.survival.platform;

import java.util.*;

/** 경제 논리 tick을 사용하므로 기존 offline PAUSE를 그대로 따른다. */
public record JobPolicy(boolean enabled,Map<String,Long> resourceIntervals) {
    public static final Set<String> ROLES=Set.of("wilderness","nether","novice","end");
    public JobPolicy{resourceIntervals=Map.copyOf(resourceIntervals);if(!resourceIntervals.keySet().equals(ROLES)||resourceIntervals.values().stream().anyMatch(n->n<0||n>100_000_000L))throw new IllegalArgumentException("JOB_INTERVAL");}
    public static JobPolicy parse(Properties p,boolean resetEnabled){String enabled=p.getProperty("jobs.enabled","false");if(!Set.of("true","false").contains(enabled))throw new IllegalArgumentException("JOB_ENABLED");var intervals=new TreeMap<String,Long>();for(String role:ROLES)intervals.put(role,Long.parseLong(p.getProperty("jobs.resource."+role+".interval-ticks","0")));for(String key:p.stringPropertyNames())if(key.startsWith("jobs.")&&!key.equals("jobs.enabled")&&ROLES.stream().noneMatch(role->key.equals("jobs.resource."+role+".interval-ticks")))throw new IllegalArgumentException("JOB_SETTING");var policy=new JobPolicy(Boolean.parseBoolean(enabled),intervals);if(policy.enabled&&intervals.values().stream().anyMatch(n->n>0)&&!resetEnabled)throw new IllegalArgumentException("JOBS_REQUIRE_RESOURCE_RESET");return policy;}
    public Map<String,Long> active(){var out=new TreeMap<String,Long>();resourceIntervals.forEach((role,interval)->out.put(role,enabled?interval:0));return Map.copyOf(out);}
    public static boolean dispatchAllowed(boolean platformReady,boolean configuring,boolean rpgReady,boolean worldReady,boolean economyReady){return platformReady&&!configuring&&rpgReady&&worldReady&&economyReady;}
    /** 완료 이후 다시 한 주기를 기다리며 밀린 예약을 폭주 재생하지 않는다. */
    public static long next(long now,long interval){if(now<0||interval<1||interval>100_000_000L)throw new IllegalArgumentException("JOB_CLOCK");return Math.addExact(now,interval);}
}
