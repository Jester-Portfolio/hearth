package io.github.jesterportfolio.hearth.combat;

import io.github.jesterportfolio.hearth.lifecycle.WorldLifecycle;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.nio.file.Files;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import static io.github.jesterportfolio.hearth.combat.CombatContent.*;
import io.github.jesterportfolio.hearth.combat.CombatContent.Pose;
import io.github.jesterportfolio.hearth.combat.CombatContent.Effect;

/** 실제 몹 AI/타이머를 확인하는 격리 제어 어댑터. 인증 플레이어 상태 적용기는 별도 포트입니다. */
public final class PaperCombatTrials implements Listener,AutoCloseable {
    private record Trial(WorldLifecycle world,long generation,Zombie mob,CombatAttempt attempt) {}
    private final Map<UUID,Trial> trials=new HashMap<>();private final Set<UUID> retired=new HashSet<>();private final JavaPlugin plugin;private final Executor owner;private final LongSupplier clock;private final CombatContent content;private final BukkitTask timer;private boolean closed;
    public PaperCombatTrials(JavaPlugin plugin,CombatContent content,Executor owner,LongSupplier clock){
        main();if(!plugin.getConfig().getBoolean("combat-state-verification-enabled")||!Boolean.getBoolean("hearth.verification.combatState"))throw new IllegalStateException("Combat gate missing");
        this.plugin=plugin;this.content=content;this.owner=owner;this.clock=clock;
        try{Files.createFile(plugin.getDataFolder().toPath().resolve("combat-trials.started"));}catch(java.io.IOException e){throw new IllegalStateException("COMBAT_RESTART_REVIEW_REQUIRED",e);}
        Bukkit.getPluginManager().registerEvents(this,plugin);timer=Bukkit.getScheduler().runTaskTimer(plugin,this::poll,1,1);
    }
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Not main thread");}
    public CombatAttempt start(WorldLifecycle lifecycle,List<CombatAttempt.Entrant> roster,CombatAttempt.Port actors){
        main();UUID run=lifecycle.mapping().storyRunId();if(closed||trials.containsKey(run)||retired.contains(run)||lifecycle.state()!=WorldLifecycle.State.ACTIVE)throw new IllegalStateException("COMBAT_TRIAL_NOT_READY");var world=Objects.requireNonNull(Bukkit.getWorld(lifecycle.mapping().physicalId()));
        var unique=new HashSet<UUID>();if(roster.isEmpty()||roster.size()>32||roster.stream().anyMatch(e->!e.context().storyRunId().equals(run)||!e.start().world().equals(world.getUID())||!unique.add(e.context().playerId())))throw new IllegalArgumentException("Invalid combat trial roster");var point=roster.getFirst().start();double health=content.healthPerPlayer()*roster.size();if(health>1024||!world.isChunkLoaded(((int)Math.floor(point.x()))>>4,((int)Math.floor(point.z()))>>4))throw new IllegalStateException("COMBAT_SPAWN_NOT_READY");
        Zombie mob=world.spawn(new Location(world,point.x(),point.y(),point.z()),Zombie.class,CreatureSpawnEvent.SpawnReason.CUSTOM,false,z->{z.setAI(false);z.setPersistent(false);z.setRemoveWhenFarAway(false);z.setCanPickupItems(false);z.setBaby(false);Objects.requireNonNull(z.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(health);z.setHealth(health);Objects.requireNonNull(z.getAttribute(Attribute.ATTACK_DAMAGE)).setBaseValue(content.attack());Objects.requireNonNull(z.getAttribute(Attribute.SPAWN_REINFORCEMENTS)).setBaseValue(0);});
        if(!mob.isValid()){mob.remove();throw new IllegalStateException("COMBAT_SPAWN_REJECTED");}
        try{var attempt=new CombatAttempt(run,world.getUID(),lifecycle.generation(),content,roster,new CombatAttempt.Port(){
            public void freeze(boolean pause){main();if(mob.isValid()){mob.setAI(!pause);if(pause)mob.setTarget(null);}actors.freeze(pause);}
            public void camera(UUID p,UUID target){main();actors.camera(p,target);}
            public boolean safe(Pose pose){main();return pose.world().equals(world.getUID())&&actors.safe(pose);}
            public CompletionStage<Boolean> revive(UUID p,Pose pose,double health,List<Effect> effects,BooleanSupplier current){main();return actors.revive(p,pose,health,effects,()->{main();var trial=trials.get(run);return !closed&&trial!=null&&lifecycle.generation()==trial.generation()&&Bukkit.getWorld(world.getUID())==world&&current.getAsBoolean();});}
        },owner,PaperCombatTrials::main,clock);trials.put(run,new Trial(lifecycle,lifecycle.generation(),mob,attempt));return attempt;}
        catch(RuntimeException e){mob.remove();throw e;}
    }
    public LivingEntity mob(UUID run){main();return Objects.requireNonNull(trials.get(run)).mob();}
    private void poll(){main();if(closed)return;for(var t:List.copyOf(trials.values()))try{if(t.world().generation()!=t.generation()||Bukkit.getWorld(t.world().mapping().physicalId())==null)t.attempt().invalidate();else t.attempt().poll();}catch(RuntimeException e){t.attempt().invalidate();plugin.getLogger().warning("HEARTH_COMBAT_HOLD "+e.getClass().getSimpleName());}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void target(EntityTargetLivingEntityEvent e){if(trials.values().stream().anyMatch(t->t.mob()==e.getEntity()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void damage(EntityDamageEvent e){if(trials.values().stream().anyMatch(t->t.mob()==e.getEntity()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void attack(EntityDamageByEntityEvent e){if(trials.values().stream().anyMatch(t->t.mob()==e.getDamager()))e.setCancelled(true);}
    @EventHandler public void died(EntityDeathEvent e){for(var t:trials.values())if(t.mob()==e.getEntity()){e.getDrops().clear();e.setDroppedExp(0);t.attempt().victory();}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void blocks(EntityChangeBlockEvent e){if(trials.values().stream().anyMatch(t->t.mob()==e.getEntity()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void unload(WorldUnloadEvent e){for(var entry:List.copyOf(trials.entrySet()))if(entry.getValue().world().mapping().physicalId().equals(e.getWorld().getUID()))stop(entry.getKey());}
    public void stop(UUID run){main();var t=trials.remove(run);retired.add(run);if(t!=null){if(t.attempt().snapshot().phase()!=CombatAttempt.Phase.CLOSED)t.attempt().finish(CombatAttempt.Outcome.RETIRED);t.mob().remove();}}
    @Override public void close(){main();if(closed)return;closed=true;timer.cancel();HandlerList.unregisterAll(this);for(UUID run:List.copyOf(trials.keySet()))stop(run);}
}
