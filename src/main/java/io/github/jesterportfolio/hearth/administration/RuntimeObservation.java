package io.github.jesterportfolio.hearth.administration;

import java.util.*;
import java.util.function.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** 기본 비활성인 로그 관측. SQL/HTTP/추론을 실행하지 않고 기존 비동기 로깅만 사용합니다. */
public final class RuntimeObservation implements AutoCloseable {
    public record State(String phase,int worlds,int heldWorlds,boolean aiPreparationRecorded,Map<String,Long> platform){public State(String phase,int worlds,int heldWorlds,boolean ai){this(phase,worlds,heldWorlds,ai,Map.of());}}
    private final JavaPlugin plugin;private final String mode;private final Supplier<State> state;private final UUID boot;private BukkitTask task;private boolean closed;
    private RuntimeObservation(JavaPlugin plugin,String mode,Supplier<State> state,UUID boot){this.plugin=plugin;this.mode=mode;this.state=state;this.boot=boot;}
    public static int interval(Object raw){if(!(raw instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.intValue())throw new IllegalArgumentException("RUNTIME_OBSERVATION_INTERVAL");int seconds=n.intValue();if(seconds!=0&&(seconds<1||seconds>3600))throw new IllegalArgumentException("RUNTIME_OBSERVATION_INTERVAL");return seconds;}
    public static RuntimeObservation start(JavaPlugin plugin,String mode,Supplier<State> state){int seconds=interval(plugin.getConfig().get("runtime-observation-seconds",0));var boot=UUID.fromString(System.getProperty("hearth.runtime.boot",UUID.randomUUID().toString()));var observation=new RuntimeObservation(plugin,mode,state,boot);if(seconds>0){observation.publish(false);observation.task=Bukkit.getScheduler().runTaskTimer(plugin,()->observation.publish(false),seconds*20L,seconds*20L);}return observation;}
    public UUID boot(){return boot;}
    private void publish(boolean stopped){if(closed&&!stopped)return;try{var s=stopped?new State("CLOSED",0,0,false):state.get();var tps=Bukkit.getTPS()[0];var mspt=Bukkit.getAverageTickTime();long heap=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();var report=new RuntimeReport(boot,ProcessHandle.current().pid(),System.currentTimeMillis(),mode,stopped?"CLOSED":s.phase(),s.worlds(),s.heldWorlds(),s.aiPreparationRecorded(),Double.isFinite(tps)&&tps>=0?tps:0,Double.isFinite(mspt)&&mspt>=0?mspt:0,Math.max(0,heap),s.platform());plugin.getLogger().info("HEARTH_RUNTIME_REPORT "+report.json());}catch(RuntimeException error){plugin.getLogger().warning("HEARTH_RUNTIME_REPORT_UNAVAILABLE");}}
    @Override public void close(){if(closed)return;closed=true;if(task!=null){task.cancel();publish(true);}}
}
