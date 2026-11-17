package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import java.util.function.LongSupplier;

/** 확인한 전송부터 서버의 응답 수용까지 유한 집계만 보관합니다. 사용자/내용/주소·개별 기록은 보관하지 않습니다. */
public final class PhoneTelemetry {
    public enum Phase { TOTAL, PREFLIGHT, ANSWER_WAIT, VALIDATION }
    public enum Outcome { ACCEPTED, CACHED, BUSY, INPUT_REJECTED, PREVIEW_REJECTED, PREFLIGHT_FAILED, PREFLIGHT_REJECTED, BACKEND_FAILED, ANSWER_REJECTED, VALIDATION_FAILED, STALE, CANCELLED, CLOSED }
    public record Timing(Phase phase,long started,long completed,long active,long totalMillis,long maxMillis,List<Long> buckets){public Timing{buckets=List.copyOf(buckets);}}
    public record Snapshot(List<Timing> timings,Map<Outcome,Long> outcomes){public Snapshot{timings=List.copyOf(timings);outcomes=Map.copyOf(outcomes);}}
    private static final class Counter {long started,completed,active,total,max;final long[] buckets=new long[6];}
    public final class Flow {
        private final PhoneTelemetry owner=PhoneTelemetry.this;private final long[] began=new long[Phase.values().length];private final byte[] state=new byte[Phase.values().length];private boolean ended,claimed;
        private Flow(){start(Phase.TOTAL);}
        public boolean claimable(){synchronized(owner){return !ended&&!claimed;}}
        public boolean claim(){synchronized(owner){if(ended||claimed)return false;claimed=true;return true;}}
        public boolean started(Phase phase){synchronized(owner){return state[phase.ordinal()]!=0;}}
        public boolean active(){synchronized(owner){return !ended;}}
        public void start(Phase phase){synchronized(owner){if(ended||state[phase.ordinal()]!=0)return;state[phase.ordinal()]=1;began[phase.ordinal()]=clock.getAsLong();var c=counters.get(phase);c.started=add(c.started,1);c.active=add(c.active,1);}}
        public void finish(Phase phase){synchronized(owner){if(ended||phase==Phase.TOTAL)return;finishPhase(phase,clock.getAsLong());}}
        private void finishPhase(Phase phase,long at){int n=phase.ordinal();if(state[n]!=1)return;state[n]=2;var c=counters.get(phase);c.completed=add(c.completed,1);c.active=Math.max(0,c.active-1);long ms=Math.max(0,(at-began[n])/1_000_000);c.total=add(c.total,ms);c.max=Math.max(c.max,ms);int bucket=ms<=100?0:ms<=500?1:ms<=1000?2:ms<=3000?3:ms<=6000?4:5;c.buckets[bucket]=add(c.buckets[bucket],1);}
        public void complete(Outcome outcome){Objects.requireNonNull(outcome);synchronized(owner){if(ended)return;long at=clock.getAsLong();for(var phase:Phase.values())finishPhase(phase,at);ended=true;outcomes.put(outcome,add(outcomes.get(outcome),1));}}
    }
    private final LongSupplier clock;private final EnumMap<Phase,Counter> counters=new EnumMap<>(Phase.class);private final EnumMap<Outcome,Long> outcomes=new EnumMap<>(Outcome.class);
    public PhoneTelemetry(){this(System::nanoTime);}PhoneTelemetry(LongSupplier clock){this.clock=Objects.requireNonNull(clock);for(var phase:Phase.values())counters.put(phase,new Counter());for(var outcome:Outcome.values())outcomes.put(outcome,0L);}
    public Flow begin(){return new Flow();}public boolean owns(Flow flow){return flow!=null&&flow.owner==this;}
    private static long add(long left,long right){return left>Long.MAX_VALUE-right?Long.MAX_VALUE:left+right;}
    public synchronized Snapshot snapshot(){var list=new ArrayList<Timing>();for(var phase:Phase.values()){var c=counters.get(phase);list.add(new Timing(phase,c.started,c.completed,c.active,c.total,c.max,Arrays.stream(c.buckets).boxed().toList()));}return new Snapshot(list,outcomes);}
    public static List<String> lines(Snapshot snapshot){var lines=new ArrayList<String>();lines.add("AI 서버 수용 집계 · 확인한 전송부터 수용/거부까지, 실제 클라이언트 표시·선택 미리보기 시간은 미판정");for(var row:snapshot.timings()){lines.add("flow "+row.phase()+" started="+row.started()+" completed="+row.completed()+" active="+row.active()+" avg_ms="+(row.completed()==0?0:row.totalMillis()/row.completed())+" max_ms="+row.maxMillis());lines.add("flow "+row.phase()+" latency_ms[<=100,<=500,<=1000,<=3000,<=6000,>6000]="+row.buckets());}for(var outcome:Outcome.values())if(snapshot.outcomes().getOrDefault(outcome,0L)>0)lines.add("flow outcome="+outcome+" count="+snapshot.outcomes().get(outcome));return List.copyOf(lines);}
}
