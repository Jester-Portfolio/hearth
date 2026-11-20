package io.github.jesterportfolio.hearth.survival.platform;
import java.util.*;
/** 유한 집계만 보존하며 아이템/유저 원문은 로그에 넣지 않는다. */
public final class PlatformMetrics {
    public record Sample(long count,long failed,long totalNanos,long maximumNanos){}
    private final Map<String,Sample> samples=new TreeMap<>();
    public synchronized void record(String key,long nanos,boolean failed){if(!Set.of("upgrade","autosell","reset","config","stat","pulse","job").contains(key)||nanos<0)throw new IllegalArgumentException("METRIC");var old=samples.getOrDefault(key,new Sample(0,0,0,0));samples.put(key,new Sample(Math.addExact(old.count,1),old.failed+(failed?1:0),Math.addExact(old.totalNanos,nanos),Math.max(old.maximumNanos,nanos)));}
    public synchronized Map<String,Sample> snapshot(){return Map.copyOf(samples);}
}
