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
    public interface Access {default boolean target(UUID run,Entity target){return false;}default boolean hit(UUID run,Entity source,Entity target){return false;}default void notice(UUID run,LivingEntity target,String text){if(target instanceof Player p)p.sendMessage(net.kyori.adventure.text.Component.text(text));}
        default void preview(UUID run,LivingEntity target,List<Pose> points){if(target instanceof Player p)for(var point:points){if(!point.world().equals(p.getWorld().getUID()))throw new IllegalStateException("BOSS_PREVIEW_WORLD_CHANGED");p.spawnParticle(Particle.FLAME,point.x(),point.y()+0.15,point.z(),1,0,0,0,0);}}}
    private Access access=new Access(){};
    public void access(Access access){main();this.access=Objects.requireNonNull(access);}
    private record Trial(WorldLifecycle world,long generation,Zombie mob,CombatAttempt attempt,boolean path,BossPatterns patterns,BossSkills skills) {}
    private record Replacement(CombatRetryVote.Receipt receipt,CombatAttempt attempt){}
    private final Map<UUID,Replacement> replacements=new HashMap<>();
    private final Map<UUID,Trial> trials=new HashMap<>();private final Set<UUID> retired=new HashSet<>();private final JavaPlugin plugin;private final Executor owner;private final LongSupplier clock;private final CombatContent content;private final BukkitTask timer;private boolean closed;
    public PaperCombatTrials(JavaPlugin plugin,CombatContent content,Executor owner,LongSupplier clock){
        main();if(!plugin.getConfig().getBoolean("combat-state-verification-enabled")||!Boolean.getBoolean("hearth.verification.combatState"))throw new IllegalStateException("Combat gate missing");
        this.plugin=plugin;this.content=content;this.owner=owner;this.clock=clock;
        var marker=plugin.getDataFolder().toPath().resolve("combat-trials.started");
        try{Files.createFile(marker);}catch(java.nio.file.FileAlreadyExistsException e){if(!plugin.getConfig().getBoolean("s4-features-enabled")||!Boolean.getBoolean("hearth.verification.s4Features")||content.exit()==null||!Files.isRegularFile(marker,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("COMBAT_RESTART_REVIEW_REQUIRED",e);}catch(java.io.IOException e){throw new IllegalStateException("COMBAT_RESTART_REVIEW_REQUIRED",e);}
        Bukkit.getPluginManager().registerEvents(this,plugin);timer=Bukkit.getScheduler().runTaskTimer(plugin,this::poll,1,1);
    }
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Not main thread");}
    public CombatAttempt start(WorldLifecycle lifecycle,List<CombatAttempt.Entrant> roster,CombatAttempt.Port actors){
        main();UUID run=lifecycle.mapping().storyRunId();if(closed||trials.containsKey(run)||retired.contains(run))throw new IllegalStateException("COMBAT_TRIAL_NOT_READY");var trial=create(lifecycle,roster,actors);trials.put(run,trial);return trial.attempt();
    }
    public CombatAttempt startPath(WorldLifecycle lifecycle,List<CombatAttempt.Entrant> roster,CombatAttempt.Port actors){
        main();UUID run=lifecycle.mapping().storyRunId();if(content.path()==null||closed||trials.containsKey(run)||retired.contains(run))throw new IllegalStateException("COMBAT_PATH_NOT_READY");
        var t=create(lifecycle,roster,actors,content.path().combat(),true);trials.put(run,t);return t.attempt();
    }
    public void freezePath(UUID run,UUID attempt){main();var t=trials.get(run);if(t==null||!t.path()||!t.attempt().snapshot().attempt().equals(attempt)||!CombatGathering.sourceReady(t.attempt().snapshot()))throw new IllegalStateException("COMBAT_PATH_NOT_READY");if(t.mob().isValid()){t.mob().setAI(false);t.mob().setTarget(null);}}
    public CombatAttempt enterBoss(WorldLifecycle lifecycle,UUID pathAttempt,UUID request,List<CombatAttempt.Entrant> roster,CombatAttempt.Port actors){
        main();UUID run=lifecycle.mapping().storyRunId();var old=trials.get(run);var duplicate=replacements.get(run);var receipt=new CombatRetryVote.Receipt(run,pathAttempt,request);
        if(duplicate!=null&&duplicate.receipt().equals(receipt)){if(old==null||old.attempt()!=duplicate.attempt()||old.generation()!=lifecycle.generation()||!old.attempt().onlineSessions().equals(roster.stream().collect(java.util.stream.Collectors.toMap(e->e.context().playerId(),CombatAttempt.Entrant::session))))throw new IllegalStateException("COMBAT_GATHERING_REPLACEMENT_CONFLICT");return old.attempt();}
        if(closed||retired.contains(run)||old==null||old.world()!=lifecycle||!old.path()||!old.attempt().snapshot().attempt().equals(pathAttempt)||old.generation()!=lifecycle.generation()||!CombatGathering.sourceReady(old.attempt().snapshot())||!old.attempt().onlineSessions().equals(roster.stream().collect(java.util.stream.Collectors.toMap(e->e.context().playerId(),CombatAttempt.Entrant::session))))throw new IllegalStateException("COMBAT_GATHERING_REPLACEMENT_REJECTED");
        var next=create(lifecycle,roster,actors);
        try{old.attempt().finish(CombatAttempt.Outcome.RETIRED);old.skills().close();if(old.patterns()!=null)old.patterns().close();old.mob().remove();if(old.mob().isValid())throw new IllegalStateException("COMBAT_PATH_MOB_NOT_REMOVED");trials.put(run,next);replacements.put(run,new Replacement(receipt,next.attempt()));return next.attempt();}
        catch(RuntimeException error){next.attempt().invalidate();next.skills().close();if(next.patterns()!=null)next.patterns().close();next.mob().remove();throw error;}
    }
    public void resumePath(UUID run,UUID attempt){main();var t=trials.get(run);if(t!=null&&t.path()&&t.attempt().snapshot().attempt().equals(attempt)&&t.mob().isValid())t.mob().setAI(t.attempt().snapshot().phase()==CombatAttempt.Phase.ACTIVE);}
    public CombatContent content(){main();return content;}
    /** 기존 RETRY 도전만 교체합니다. 초기화/운영 월드 복제나 다른 도전의 재생성은 하지 않습니다. */
    public CombatAttempt replace(WorldLifecycle lifecycle,CombatRetryVote.Receipt receipt,List<CombatAttempt.Entrant> roster,CombatAttempt.Port actors){return replace(lifecycle,receipt,roster,actors,content.retry(),false);}
    public CombatAttempt restartPath(WorldLifecycle lifecycle,CombatRetryVote.Receipt receipt,List<CombatAttempt.Entrant> roster,CombatAttempt.Port actors){return replace(lifecycle,receipt,roster,actors,content.path()==null?null:content.path().retry(),true);}
    private CombatAttempt replace(WorldLifecycle lifecycle,CombatRetryVote.Receipt receipt,List<CombatAttempt.Entrant> roster,CombatAttempt.Port actors,CombatContent.Retry policy,boolean pathFailure){
        main();UUID run=lifecycle.mapping().storyRunId();var old=trials.get(run);var duplicate=replacements.get(run);
        if(closed||policy==null||receipt==null||receipt.request()==null||retired.contains(run)||old==null||old.world()!=lifecycle||old.generation()!=lifecycle.generation())throw new IllegalStateException("COMBAT_REPLACEMENT_NOT_READY");
        boolean toPath=policy.scope().equals("FROM_PATH");
        if(duplicate!=null&&duplicate.receipt().equals(receipt)){
            if(old.attempt()!=duplicate.attempt()||old.path()!=toPath||!old.attempt().onlineSessions().equals(roster.stream().collect(java.util.stream.Collectors.toMap(e->e.context().playerId(),CombatAttempt.Entrant::session))))throw new IllegalStateException("COMBAT_REPLACEMENT_CONFLICT");
            return duplicate.attempt();
        }
        if(old.path()!=pathFailure||!run.equals(receipt.run())||!old.attempt().snapshot().attempt().equals(receipt.attempt())||old.attempt().snapshot().phase()!=CombatAttempt.Phase.CLOSED||old.attempt().snapshot().outcome()!=(pathFailure?CombatAttempt.Outcome.REQUIRED_FAILURE:CombatAttempt.Outcome.RETRY)||old.attempt().snapshot().error()!=null||toPath&&content.path()==null||pathFailure&&!toPath)throw new IllegalStateException("COMBAT_REPLACEMENT_NOT_READY");
        var next=create(lifecycle,roster,actors,toPath?content.path().combat():content,toPath);
        try{old.skills().close();if(old.patterns()!=null)old.patterns().close();old.mob().remove();if(old.mob().isValid())throw new IllegalStateException("COMBAT_OLD_MOB_NOT_REMOVED");trials.put(run,next);replacements.put(run,new Replacement(receipt,next.attempt()));return next.attempt();}
        catch(RuntimeException error){next.attempt().invalidate();next.skills().close();if(next.patterns()!=null)next.patterns().close();next.mob().remove();throw error;}
    }
    private Trial create(WorldLifecycle lifecycle,List<CombatAttempt.Entrant> roster,CombatAttempt.Port actors){return create(lifecycle,roster,actors,content,false);}
    private Trial create(WorldLifecycle lifecycle,List<CombatAttempt.Entrant> roster,CombatAttempt.Port actors,CombatContent definition,boolean path){
        main();UUID run=lifecycle.mapping().storyRunId();if(lifecycle.state()!=WorldLifecycle.State.ACTIVE)throw new IllegalStateException("COMBAT_TRIAL_NOT_READY");var world=Objects.requireNonNull(Bukkit.getWorld(lifecycle.mapping().physicalId()));
        var unique=new HashSet<UUID>();if(roster.isEmpty()||roster.size()>32||roster.stream().anyMatch(e->!e.context().storyRunId().equals(run)||!e.start().world().equals(world.getUID())||!unique.add(e.context().playerId())))throw new IllegalArgumentException("Invalid combat trial roster");var point=roster.getFirst().start();double health=definition.healthPerPlayer()*roster.size();if(health>1024||!world.isChunkLoaded(((int)Math.floor(point.x()))>>4,((int)Math.floor(point.z()))>>4))throw new IllegalStateException("COMBAT_SPAWN_NOT_READY");
        Zombie mob=world.spawn(new Location(world,point.x(),point.y(),point.z()),Zombie.class,CreatureSpawnEvent.SpawnReason.CUSTOM,false,z->{z.setAI(false);z.setPersistent(false);z.setRemoveWhenFarAway(false);z.setCanPickupItems(false);z.setBaby(false);Objects.requireNonNull(z.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(health);z.setHealth(health);Objects.requireNonNull(z.getAttribute(Attribute.ATTACK_DAMAGE)).setBaseValue(definition.attack());Objects.requireNonNull(z.getAttribute(Attribute.SPAWN_REINFORCEMENTS)).setBaseValue(0);});
        if(!mob.isValid()){mob.remove();throw new IllegalStateException("COMBAT_SPAWN_REJECTED");}
        try{var attempt=new CombatAttempt(run,world.getUID(),lifecycle.generation(),definition,roster,new CombatAttempt.Port(){
            public void freeze(boolean pause){main();if(mob.isValid()){mob.setAI(!pause);if(pause)mob.setTarget(null);}actors.freeze(pause);}
            public void camera(UUID p,UUID target){main();actors.camera(p,target);}
            public boolean safe(Pose pose){main();return pose.world().equals(world.getUID())&&actors.safe(pose);}
            public CompletionStage<Boolean> revive(UUID p,Pose pose,double health,List<Effect> effects,BooleanSupplier current){main();return actors.revive(p,pose,health,effects,()->{main();var trial=trials.get(run);return !closed&&trial!=null&&trial.mob()==mob&&lifecycle.generation()==trial.generation()&&Bukkit.getWorld(world.getUID())==world&&current.getAsBoolean();});}
        },owner,PaperCombatTrials::main,clock);
            var skills=new BossSkills(attempt.snapshot(),()->patternCurrent(lifecycle,attempt,mob),strike->{var phase=strike.pattern();var origin=strike.origin();for(var target:patternTargets(run,mob,phase,origin,true)){if(!patternCurrent(lifecycle,attempt,mob))return;if(access.target(run,target)&&access.hit(run,mob,target)&&BossPatternArea.contains(phase,origin,patternPose(target))){target.damage(phase.damage(),mob);if(!access.target(run,target)||!patternCurrent(lifecycle,attempt,mob))continue;applyEffects(phase,target,origin);}}});
            var anchor=new BossPatternArea.Anchor();BossPatterns patterns=definition.patterns().isEmpty()?null:new BossPatterns(attempt.snapshot(),definition.patterns(),new BossPatterns.Port(){
                public void phase(Pattern phase){skills.cancel();anchor.clear();patternNotice(lifecycle,attempt,mob,phase,patternPose(mob),"보스 패턴: "+phase.title(),false);}
                public void telegraph(Pattern phase){anchor.begin(patternPose(mob));patternNotice(lifecycle,attempt,mob,phase,anchor.origin(phase,patternPose(mob)),"공격 예고: "+phase.title()+" · 표시 범위 밖으로 이동하세요.",true);}
                public void strike(Pattern phase){main();skills.cast(phase,anchor.origin(phase,patternPose(mob)),attempt.snapshot().combatMillis());}
            },()->patternCurrent(lifecycle,attempt,mob),PaperCombatTrials::main);
            return new Trial(lifecycle,lifecycle.generation(),mob,attempt,path,patterns,skills);}
        catch(RuntimeException e){mob.remove();throw e;}
    }
    public static void applyEffects(Pattern phase,LivingEntity target,Pose origin){if(phase.technique()==Technique.SLOW)target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS,phase.effectTicks(),0));if(phase.technique()==Technique.REPEL){var direction=target.getLocation().toVector().subtract(new org.bukkit.util.Vector(origin.x(),target.getY(),origin.z()));if(direction.lengthSquared()<.001)direction=new org.bukkit.util.Vector(0,0,1);target.setVelocity(direction.normalize().multiply(phase.strength()).setY(.2));}}
    private boolean patternCurrent(WorldLifecycle world,CombatAttempt attempt,LivingEntity mob){
        main();var trial=trials.get(world.mapping().storyRunId());return !closed&&trial!=null&&trial.attempt()==attempt&&trial.mob()==mob&&world.generation()==trial.generation()&&Bukkit.getWorld(world.mapping().physicalId())==mob.getWorld()&&mob.isValid()&&!mob.isDead()&&attempt.snapshot().phase()==CombatAttempt.Phase.ACTIVE;
    }
    private static Pose patternPose(Entity entity){var p=entity.getLocation();return new Pose(entity.getWorld().getUID(),p.getX(),p.getY(),p.getZ(),p.getYaw(),p.getPitch());}
    private List<LivingEntity> patternTargets(UUID run,LivingEntity mob,Pattern phase,Pose origin,boolean attack){
        main();var world=mob.getWorld();if(!world.getUID().equals(origin.world()))throw new IllegalStateException("BOSS_AREA_WRONG_WORLD");double extent=BossPatternArea.extent(phase);var center=new Location(world,origin.x(),origin.y(),origin.z());
        return world.getNearbyEntities(center,extent,extent,extent).stream().filter(LivingEntity.class::isInstance).map(LivingEntity.class::cast).filter(e->e!=mob&&e.isValid()&&!e.isDead()&&e.getWorld()==world&&(attack||phase.shape()==Shape.SPHERE?BossPatternArea.contains(phase,origin,patternPose(e)):true)&&access.target(run,e)&&access.hit(run,mob,e)).limit(32).toList();
    }
    private void patternNotice(WorldLifecycle world,CombatAttempt attempt,LivingEntity mob,Pattern phase,Pose origin,String text,boolean preview){
        main();var points=preview?BossPatternArea.boundary(phase,origin):List.<Pose>of();for(var target:patternTargets(world.mapping().storyRunId(),mob,phase,origin,false)){if(!patternCurrent(world,attempt,mob))return;access.notice(world.mapping().storyRunId(),target,text);if(preview&&patternCurrent(world,attempt,mob)&&access.target(world.mapping().storyRunId(),target)&&access.hit(world.mapping().storyRunId(),mob,target))access.preview(world.mapping().storyRunId(),target,points);}
    }
    public Optional<BossPatterns.View> patterns(UUID run){main();var trial=trials.get(run);return trial==null||trial.patterns()==null?Optional.empty():Optional.of(trial.patterns().view());}
    public LivingEntity mob(UUID run){main();return Objects.requireNonNull(trials.get(run)).mob();}
    private final Set<UUID> durableFrozen=new HashSet<>();
    public void durableFreeze(UUID run,boolean frozen){main();var t=trials.get(run);if(t==null)throw new IllegalStateException("COMBAT_DURABLE_SCOPE");if(frozen)durableFrozen.add(run);else durableFrozen.remove(run);t.mob().setAI(!frozen&&t.attempt().snapshot().phase()==CombatAttempt.Phase.ACTIVE);}
    private void poll(){main();if(closed)return;for(var t:List.copyOf(trials.values()))try{if(t.world().generation()!=t.generation()||Bukkit.getWorld(t.world().mapping().physicalId())==null)t.attempt().invalidate();else t.attempt().poll();if(!durableFrozen.contains(t.world().mapping().storyRunId())&&t.patterns()!=null)t.patterns().poll(t.attempt().snapshot(),t.mob().isDead()?0:t.mob().getHealth()/Objects.requireNonNull(t.mob().getAttribute(Attribute.MAX_HEALTH)).getValue());if(!durableFrozen.contains(t.world().mapping().storyRunId()))t.skills().poll(t.attempt().snapshot());}catch(RuntimeException e){t.attempt().invalidate();t.skills().close();if(t.patterns()!=null)t.patterns().close();plugin.getLogger().warning("HEARTH_COMBAT_HOLD "+e.getClass().getSimpleName());}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void target(EntityTargetLivingEntityEvent e){for(var entry:trials.entrySet())if(entry.getValue().mob()==e.getEntity()&&e.getTarget()!=null&&!access.target(entry.getKey(),e.getTarget()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void damage(EntityDamageEvent e){for(var entry:trials.entrySet())if(entry.getValue().mob()==e.getEntity()&&(!(e instanceof EntityDamageByEntityEvent hit)||!access.hit(entry.getKey(),hit.getDamager(),e.getEntity())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void attack(EntityDamageByEntityEvent e){for(var entry:trials.entrySet())if(entry.getValue().mob()==e.getDamager()&&!access.hit(entry.getKey(),e.getDamager(),e.getEntity()))e.setCancelled(true);}
    @EventHandler public void died(EntityDeathEvent e){for(var t:trials.values())if(t.mob()==e.getEntity()){e.getDrops().clear();e.setDroppedExp(0);t.attempt().victory();}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void blocks(EntityChangeBlockEvent e){if(trials.values().stream().anyMatch(t->t.mob()==e.getEntity()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void unload(WorldUnloadEvent e){for(var entry:List.copyOf(trials.entrySet()))if(entry.getValue().world().mapping().physicalId().equals(e.getWorld().getUID()))stop(entry.getKey());}
    /** 같은 JVM의 정상 종료 도전만 정리합니다. 언로드로 퇴역한 진행은 다시 열지 않습니다. */
    public void releaseEnded(UUID run,UUID attempt){main();var t=trials.get(run);
        if(closed||retired.contains(run)||t==null||!t.attempt().snapshot().attempt().equals(attempt)||!CombatReturn.ended(t.attempt().snapshot())||t.world().generation()!=t.generation())throw new IllegalStateException("COMBAT_RELEASE_NOT_READY");
        t.skills().close();if(t.patterns()!=null)t.patterns().close();t.mob().remove();if(t.mob().isValid())throw new IllegalStateException("COMBAT_RELEASE_MOB_HOLD");
        durableFrozen.remove(run);trials.remove(run,t);replacements.remove(run);
    }
    public void stop(UUID run){main();durableFrozen.remove(run);var t=trials.remove(run);replacements.remove(run);retired.add(run);if(t!=null){t.skills().close();if(t.patterns()!=null)t.patterns().close();if(t.attempt().snapshot().phase()!=CombatAttempt.Phase.CLOSED)t.attempt().finish(CombatAttempt.Outcome.RETIRED);t.mob().remove();}}
    @Override public void close(){main();if(closed)return;closed=true;timer.cancel();HandlerList.unregisterAll(this);for(UUID run:List.copyOf(trials.keySet()))stop(run);}
}
