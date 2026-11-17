package io.github.jesterportfolio.hearth.verification;

import java.lang.management.ManagementFactory;
import java.util.*;

/** 격리 시험의 유한한 실제 tick-duration 표본. 운영 SLO나 플레이어 부하를 추정하지 않습니다. */
public final class TickObservation {
    public enum Phase { PREPARATION,BASELINE,REQUESTS,AFTER }
    public record Window(Phase phase,long wallMillis,int ticks,double meanMillis,double maxMillis,double p95Millis,long gcCount,long gcMillis){}
    private static final class Acc {final long started=System.nanoTime();long ended;final long[] gc=gc();long[] endGc;final List<Double> samples=new ArrayList<>();}
    private final Map<Phase,Acc> data=new LinkedHashMap<>();private Phase phase;private int total;private boolean closed;
    public void phase(Phase next){if(closed||data.containsKey(next))throw new IllegalStateException("TICK_PHASE_REUSED");finish();phase=next;data.put(next,new Acc());}
    private void finish(){if(phase!=null){var a=data.get(phase);if(a.ended==0){a.ended=System.nanoTime();a.endGc=gc();}}}
    private static long[] gc(){long count=0,time=0;for(var bean:ManagementFactory.getGarbageCollectorMXBeans()){count+=Math.max(0,bean.getCollectionCount());time+=Math.max(0,bean.getCollectionTime());}return new long[]{count,time};}
    public void tick(double milliseconds){if(closed||phase==null)return;if(!Double.isFinite(milliseconds)||milliseconds<0)throw new IllegalArgumentException("INVALID_TICK_DURATION");if(total>=12000)throw new IllegalStateException("TICK_SAMPLE_LIMIT");total++;data.get(phase).samples.add(milliseconds);}
    public List<Window> close(){if(!closed){finish();closed=true;}var result=new ArrayList<Window>();for(var row:data.entrySet()){var a=row.getValue();var sorted=a.samples.stream().sorted().toList();int n=sorted.size();result.add(new Window(row.getKey(),Math.max(0,(a.ended-a.started)/1_000_000),n,n==0?0:sorted.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),n==0?0:sorted.getLast(),n==0?0:sorted.get((int)Math.ceil(n*.95)-1),Math.max(0,a.endGc[0]-a.gc[0]),Math.max(0,a.endGc[1]-a.gc[1])));}return List.copyOf(result);}
}
