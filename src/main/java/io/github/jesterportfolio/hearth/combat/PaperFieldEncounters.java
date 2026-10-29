package io.github.jesterportfolio.hearth.combat;

import io.github.jesterportfolio.hearth.lifecycle.WorldLifecycle;
import io.github.jesterportfolio.hearth.session.PaperSessionAdapter;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import static io.github.jesterportfolio.hearth.combat.FieldContent.*;
import static io.github.jesterportfolio.hearth.combat.FieldEncounters.*;

/** 새 격리 실행에서만 켜는 필드 조우. 실제 플레이어 피해 제한은 유지합니다. */
public final class PaperFieldEncounters implements Listener,AutoCloseable {
    private record MobRef(Binding binding,String zone,LivingEntity entity) {}
    private final JavaPlugin plugin;private final PaperSessionAdapter sessions;private final Map<UUID,WorldLifecycle> worlds;
    private final Map<UUID,MobRef> mobs=new HashMap<>();private final Set<UUID> reported=new HashSet<>();private final FieldEncounters engine;private boolean closed;
    public PaperFieldEncounters(JavaPlugin plugin,PaperSessionAdapter sessions,Map<UUID,WorldLifecycle> worlds,FieldContent content){
        main();if(!plugin.getConfig().getBoolean("field-encounter-verification-enabled")||!Boolean.getBoolean("hearth.verification.field"))throw new IllegalStateException("Field gate missing");
        this.plugin=plugin;this.sessions=sessions;this.worlds=Map.copyOf(worlds);
        // 조우의 프로세스 간 복원/리셋 정책을 발명하지 않습니다. 이전 실행의 표식은 보존하고 자동 재소환을 거부합니다.
        try{Files.createFile(plugin.getDataFolder().toPath().resolve("field-encounters.started"));}catch(IOException e){throw new IllegalStateException("FIELD_RESTART_REVIEW_REQUIRED",e);}
        engine=new FieldEncounters(content,new Port(){
            public UUID spawn(Binding b,Zone zone,Tier tier){main();var world=Bukkit.getWorld(b.world());if(world==null||!live(b))throw new IllegalStateException("FIELD_WORLD_NOT_READY");
                if(!world.isChunkLoaded(((int)Math.floor(zone.spawn().x()))>>4,((int)Math.floor(zone.spawn().z()))>>4))throw new IllegalStateException("FIELD_CHUNK_NOT_READY");
                Zombie entity=world.spawn(new Location(world,zone.spawn().x(),zone.spawn().y(),zone.spawn().z()),Zombie.class,CreatureSpawnEvent.SpawnReason.CUSTOM,false,z->{
                    z.setPersistent(false);z.setRemoveWhenFarAway(false);z.setCanPickupItems(false);z.setBaby(false);z.setSilent(true);
                    Objects.requireNonNull(z.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(tier.health());z.setHealth(tier.health());
                    Objects.requireNonNull(z.getAttribute(Attribute.ATTACK_DAMAGE)).setBaseValue(tier.attack());Objects.requireNonNull(z.getAttribute(Attribute.SPAWN_REINFORCEMENTS)).setBaseValue(0);
                });
                // 다른 플러그인이 소환을 취소했으면 성공 기록을 만들지 않습니다.
                if(!entity.isValid()){entity.remove();throw new IllegalStateException("FIELD_SPAWN_REJECTED");}
                mobs.put(entity.getUniqueId(),new MobRef(b,zone.id(),entity));return entity.getUniqueId();}
            public void remove(UUID id){main();var ref=mobs.get(id);if(ref!=null){ref.entity().remove();mobs.remove(id,ref);}}
        },PaperFieldEncounters::main);
        Bukkit.getPluginManager().registerEvents(this,plugin);sessions.fieldAttack(this::canAttack);
    }
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Not main thread");}
    public FieldEncounters engine(){main();return engine;}
    private boolean live(Binding b){var lifecycle=worlds.get(b.run());return !closed&&lifecycle!=null&&lifecycle.generation()==b.generation()&&lifecycle.mapping().physicalId().equals(b.world())&&lifecycle.state()==WorldLifecycle.State.ACTIVE&&Bukkit.getWorld(b.world())!=null;}
    public Binding binding(UUID run){main();var w=Objects.requireNonNull(worlds.get(run));var b=new Binding(run,w.mapping().physicalId(),w.generation());if(!live(b))throw new IllegalStateException("FIELD_WORLD_NOT_READY");engine.bind(b);return b;}
    public List<String> enter(Binding b,UUID player,Point from,Point to,List<Presence> online){main();if(!live(b))throw new IllegalStateException("FIELD_STALE_GENERATION");return engine.enter(b,player,from,to,online);}
    private static Point point(Location l){return new Point(l.getX(),l.getY(),l.getZ());}
    private boolean participant(Player p,Binding b){var s=sessions.current(p);return live(b)&&engine.current(b)&&s!=null&&s.context().storyRunId().equals(b.run())&&p.getWorld().getUID().equals(b.world());}
    public boolean canAttack(Player p,Entity target){main();var ref=mobs.get(target.getUniqueId());return ref!=null&&target==ref.entity()&&target.isValid()&&participant(p,ref.binding())&&engine.mayAttack(ref.binding(),target.getUniqueId());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void move(PlayerMoveEvent e){
        if(e.getTo()==null||e.getFrom().getWorld()!=e.getTo().getWorld()||e.getFrom().getBlockX()==e.getTo().getBlockX()&&e.getFrom().getBlockY()==e.getTo().getBlockY()&&e.getFrom().getBlockZ()==e.getTo().getBlockZ())return;
        var s=sessions.current(e.getPlayer());if(s==null||reported.contains(s.context().storyRunId()))return;try{var b=binding(s.context().storyRunId());var present=new ArrayList<Presence>();for(var p:e.getPlayer().getWorld().getPlayers())if(participant(p,b))present.add(new Presence(b,p.getUniqueId(),p==e.getPlayer()?point(e.getTo()):point(p.getLocation())));var started=enter(b,e.getPlayer().getUniqueId(),point(e.getFrom()),point(e.getTo()),present);if(!started.isEmpty())e.getPlayer().sendMessage(Component.text("필드 조우 시작 · 소환 시점 난이도 적용"));}
        catch(RuntimeException failure){if(reported.add(s.context().storyRunId()))plugin.getLogger().warning("HEARTH_FIELD_HOLD "+failure.getMessage());}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void target(EntityTargetLivingEntityEvent e){var ref=mobs.get(e.getEntity().getUniqueId());if(ref!=null&&e.getTarget()!=null&&(!(e.getTarget() instanceof Player p)||!participant(p,ref.binding())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void attack(EntityDamageByEntityEvent e){
        var source=mobs.get(e.getDamager().getUniqueId());if(source!=null&&(!(e.getEntity() instanceof Player p)||!participant(p,source.binding())))e.setCancelled(true);
        var target=mobs.get(e.getEntity().getUniqueId());if(target!=null&&(!(e.getDamager() instanceof Player p)||!canAttack(p,e.getEntity())))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void blocks(EntityChangeBlockEvent e){if(mobs.containsKey(e.getEntity().getUniqueId()))e.setCancelled(true);}
    @EventHandler public void died(EntityDeathEvent e){var ref=mobs.remove(e.getEntity().getUniqueId());if(ref!=null){e.getDrops().clear();e.setDroppedExp(0);engine.died(ref.binding(),ref.zone(),e.getEntity().getUniqueId());}}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void unload(WorldUnloadEvent e){for(var w:worlds.values())if(w.mapping().physicalId().equals(e.getWorld().getUID()))retire(w.mapping().storyRunId());}
    public void retire(UUID run){main();engine.retire(run);}
    public int count(UUID run){main();return (int)mobs.values().stream().filter(m->m.binding().run().equals(run)).count();}
    public List<LivingEntity> entities(UUID run){main();return mobs.values().stream().filter(m->m.binding().run().equals(run)).map(MobRef::entity).toList();}
    @Override public void close(){main();if(closed)return;closed=true;HandlerList.unregisterAll(this);sessions.fieldAttack((p,e)->false);for(UUID run:worlds.keySet())retire(run);}
}
