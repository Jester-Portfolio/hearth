package io.github.jesterportfolio.hearth.survival.skills;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import io.github.jesterportfolio.hearth.survival.*;
import io.github.jesterportfolio.hearth.survival.housing.HousingRuntime;
import static io.github.jesterportfolio.hearth.survival.skills.WorldAccessPolicy.*;

/** 모든 이동의 최종 접근 경계. 로그인 async 경로에서는 캐시된 공용 위치만 사용한다. */
public final class SurvivalAdmission implements Listener,AutoCloseable {
    public interface Boundary {
        boolean ready(); boolean connected(Player player);
        default boolean travelAllowed(Player player){return true;}
        default String travelReason(Player player){return "현재 이동할 수 없습니다.";}
        default boolean loginPublic(){return false;}
        Map<SurvivalWorld,World> worlds(); HousingRuntime housing();
        Location publicEntry(); void portal(Player player,SurvivalWorld target); void evict(Player player);
    }
    public record Permit(UUID player,UUID world,long housingGeneration){}
    private final JavaPlugin plugin;private final Boundary boundary;private final boolean enabled;
    private final WorldAccessPolicy policy;private final SkillsBridge skills;private final BukkitTask pulse;
    private volatile Location publicEntry;private volatile Set<UUID> common=Set.of();private final Set<UUID> evicting=new HashSet<>();private boolean closed;
    public SurvivalAdmission(JavaPlugin plugin,Boundary boundary,Runnable hold)throws Exception {
        this.plugin=plugin;this.boundary=boundary;enabled=plugin.getConfig().getBoolean("skills.enabled",false);
        if(enabled){
            String name=plugin.getConfig().getString("skills.access-file","skills-access.properties");if(name==null||!name.matches("[A-Za-z0-9_-]+\\.properties"))throw new IllegalArgumentException("SKILLS_ACCESS_PATH");
            Path path=plugin.getDataFolder().toPath().resolve(name).toAbsolutePath();if(!path.equals(path.toRealPath())||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>32768)throw new IllegalArgumentException("SKILLS_ACCESS_PATH");
            var properties=new Properties();try(var reader=Files.newBufferedReader(path)){properties.load(reader);}policy=WorldAccessPolicy.parse(properties);skills=SkillsBridge.enabled(plugin,policy,hold);
        }else{policy=WorldAccessPolicy.unrestricted();skills=SkillsBridge.disabled();}
        Bukkit.getPluginManager().registerEvents(this,plugin);
        pulse=Bukkit.getScheduler().runTaskTimer(plugin,()->{if(!closed&&boundary.ready())for(var player:Bukkit.getOnlinePlayers())if(boundary.connected(player)&&!decision(player.getUniqueId(),player.getLocation()).allowed()&&evicting.add(player.getUniqueId())){boundary.evict(player);Bukkit.getScheduler().runTaskLater(plugin,()->{evicting.remove(player.getUniqueId());if(!closed&&player.isOnline()&&boundary.connected(player)&&!decision(player.getUniqueId(),player.getLocation()).allowed())player.kick(Component.text("입장 조건을 확인할 수 없습니다. 다시 접속해 주세요."));},20);}},20,20);
    }
    public CompletionStage<Void> ready(){return skills.ready();}
    public void activate(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("ADMISSION_ACTIVATE_MAIN_THREAD");publicEntry=boundary.publicEntry().clone();common=boundary.worlds().values().stream().map(World::getUID).collect(java.util.stream.Collectors.toUnmodifiableSet());}
    public boolean enabled(){return enabled;}
    public WorldAccessPolicy policy(){return policy;}
    public Role role(World world){
        if(world==null)return null;
        for(var entry:boundary.worlds().entrySet())if(entry.getValue()==world)return switch(entry.getKey()){case RESIDENCE->Role.PUBLIC_HOME;case WILDERNESS->Role.RESOURCE;case NETHER->Role.NETHER;};
        var housing=boundary.housing();return housing!=null&&housing.house(world).isPresent()?Role.PRIVATE_HOME:null;
    }
    public Decision decision(UUID player,Location target){
        if(closed||!boundary.ready()||target==null||target.getWorld()==null)return new Decision(false,"월드 접근 준비가 끝나지 않았습니다.");
        Role role=role(target.getWorld());var housing=boundary.housing();boolean acl=housing==null?role!=Role.PRIVATE_HOME:housing.allowed(player,target);
        return policy.evaluate(role,acl,role==null||role.residence()?Levels.unavailable():skills.levels(player));
    }
    public boolean allowed(Player player,Location target){return decision(player.getUniqueId(),target).allowed();}
    public String reason(Player player,SurvivalWorld target){var d=decision(player.getUniqueId(),boundary.worlds().get(target).getSpawnLocation());return d.allowed()?"다른 이동·거래 처리가 끝난 뒤 다시 이용하세요.":d.message();}
    public Permit prepare(Player player,Location target){return prepare(player.getUniqueId(),target);}
    public Permit prepare(UUID player,Location target){if(!decision(player,target).allowed())throw new IllegalStateException("WORLD_ACCESS_DENIED");return new Permit(player,target.getWorld().getUID(),generation());}
    public boolean commit(Permit permit,Player player,Location target){return commit(permit,player.getUniqueId(),target);}
    public boolean commit(Permit permit,UUID player,Location target){return permit!=null&&permit.player().equals(player)&&target!=null&&target.getWorld()!=null&&permit.world().equals(target.getWorld().getUID())&&permit.housingGeneration()==generation()&&decision(player,target).allowed();}
    private long generation(){var h=boundary.housing();return h==null?0:h.generation();}
    @EventHandler(priority=EventPriority.HIGHEST) public void login(AsyncPlayerSpawnLocationEvent event){var entry=publicEntry;if(entry!=null&&(enabled||boundary.loginPublic()||!common.contains(event.getSpawnLocation().getWorld().getUID())))event.setSpawnLocation(entry.clone());}
    @EventHandler(priority=EventPriority.HIGHEST) public void respawn(PlayerRespawnEvent event){if(publicEntry!=null&&!allowed(event.getPlayer(),event.getRespawnLocation()))event.setRespawnLocation(publicEntry.clone());}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void teleport(PlayerTeleportEvent event){if(!boundary.connected(event.getPlayer())||!boundary.travelAllowed(event.getPlayer())||!allowed(event.getPlayer(),event.getTo())){event.setCancelled(true);event.getPlayer().sendMessage(Component.text(!boundary.travelAllowed(event.getPlayer())?boundary.travelReason(event.getPlayer()):decision(event.getPlayer().getUniqueId(),event.getTo()).message()));}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void portal(PlayerPortalEvent event){
        event.setCancelled(true);var p=event.getPlayer();if(!boundary.connected(p))return;if(!boundary.travelAllowed(p)){p.sendMessage(Component.text(boundary.travelReason(p)));return;}
        if(event.getCause()!=PlayerTeleportEvent.TeleportCause.NETHER_PORTAL){p.sendMessage(Component.text("이 차원은 아직 열리지 않았습니다."));return;}
        var target=p.getWorld().getEnvironment()==World.Environment.NETHER?SurvivalWorld.WILDERNESS:SurvivalWorld.NETHER;
        Bukkit.getScheduler().runTask(plugin,()->{if(!closed&&boundary.connected(p))boundary.portal(p,target);});
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void move(PlayerMoveEvent event){
        if(event instanceof PlayerTeleportEvent||event.getTo()==null)return;
        if(event.getFrom().getWorld()==event.getTo().getWorld()&&event.getFrom().getBlockX()==event.getTo().getBlockX()&&event.getFrom().getBlockY()==event.getTo().getBlockY()&&event.getFrom().getBlockZ()==event.getTo().getBlockZ())return;
        if(!allowed(event.getPlayer(),event.getTo()))event.setCancelled(true);
    }
    @EventHandler public void quit(PlayerQuitEvent event){evicting.remove(event.getPlayer().getUniqueId());}
    public void close(){closed=true;pulse.cancel();skills.close();evicting.clear();HandlerList.unregisterAll(this);}
}
