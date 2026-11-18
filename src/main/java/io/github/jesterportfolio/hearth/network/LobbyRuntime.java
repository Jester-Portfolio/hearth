package io.github.jesterportfolio.hearth.network;

import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.entity.EntityPickupItemEvent;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import net.kyori.adventure.text.Component;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;

/** 검토한 기존 로비의 탐색/서버 선택만 제공합니다. 스토리·경제·개인 슬롯을 복사하거나 초기화하지 않습니다. */
public final class LobbyRuntime implements Listener,AutoCloseable {
    public enum Phase{STARTING,READY,HOLD,CLOSED}
    private record Link(Player player,Object token){}
    private final JavaPlugin plugin;private final Map<UUID,Link> links=new HashMap<>();private final Set<Object> frozen=Collections.newSetFromMap(new IdentityHashMap<>());private final NetworkSettings settings;private final Location entry;private final com.sk89q.worldguard.protection.regions.ProtectedRegion region;private final PaperNetwork network;private volatile Phase phase=Phase.STARTING;
    public LobbyRuntime(JavaPlugin plugin)throws Exception{
        this.plugin=plugin;settings=Objects.requireNonNull(BackendAuthentication.settings(plugin,"lobby"));BackendAuthentication.require(settings);if(!Bukkit.getOnlinePlayers().isEmpty())throw new IllegalStateException("LOBBY_EXISTING_PLAYERS");var c=Objects.requireNonNull(plugin.getConfig().getConfigurationSection("lobby-runtime"));var world=Objects.requireNonNull(Bukkit.getWorld(UUID.fromString(c.getString("world-uuid"))));if(!world.getKey().toString().equals(c.getString("world-key")))throw new IllegalArgumentException("LOBBY_WORLD_IDENTITY");entry=new Location(world,c.getDouble("entry-x"),c.getDouble("entry-y"),c.getDouble("entry-z"),(float)c.getDouble("entry-yaw"),(float)c.getDouble("entry-pitch"));if(!Double.isFinite(entry.getX())||!Double.isFinite(entry.getY())||!Double.isFinite(entry.getZ())||!Float.isFinite(entry.getYaw())||!Float.isFinite(entry.getPitch())||Math.abs(entry.getPitch())>90||!world.getWorldBorder().isInside(entry))throw new IllegalArgumentException("LOBBY_ENTRY");
        var manager=Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world)));region=Objects.requireNonNull(manager.getRegion(Objects.requireNonNull(c.getString("region"))));if(!region.contains(entry.getBlockX(),entry.getBlockY(),entry.getBlockZ())||region.getFlag(com.sk89q.worldguard.protection.flags.Flags.BUILD)!=com.sk89q.worldguard.protection.flags.StateFlag.State.DENY||!world.isChunkGenerated(entry.getBlockX()>>4,entry.getBlockZ()>>4))throw new IllegalArgumentException("LOBBY_REGION_OR_CHUNK");
        Bukkit.getPluginManager().registerEvents(this,plugin);network=new PaperNetwork(plugin,settings,new PaperNetwork.Boundary(){public Object token(Player p){var l=links.get(p.getUniqueId());return l==null||frozen.contains(l.token())?null:l.token();}public boolean current(Player p,Object token){var l=links.get(p.getUniqueId());return phase==Phase.READY&&l!=null&&l.player()==p&&l.token()==token&&p.isOnline()&&Bukkit.getPlayer(p.getUniqueId())==p&&p.getWorld()==entry.getWorld();}public void freeze(Player p,Object token,boolean value){if(value){if(!current(p,token)||!empty(p)||!frozen.add(token))throw new IllegalStateException("LOBBY_TRANSFER_NOT_READY");}else frozen.remove(token);}public CompletionStage<Void> save(Player p,Object token){return current(p,token)&&empty(p)?CompletableFuture.completedFuture(null):CompletableFuture.failedFuture(new IllegalStateException("LOBBY_INVENTORY_PRESERVED_HOLD"));}},action->Bukkit.getScheduler().runTask(plugin,action));
        Objects.requireNonNull(plugin.getCommand("hearthserver")).setExecutor((sender,cmd,label,args)->{if(sender instanceof Player p)network.command(p,label.equals("lobby")?"/lobby":"/hearthserver"+(args.length==1?" "+args[0]:""));else sender.sendMessage("로비 상태="+phase);return true;});
        world.getChunkAtAsync(entry.getBlockX()>>4,entry.getBlockZ()>>4,false).whenComplete((chunk,error)->Bukkit.getScheduler().runTask(plugin,()->{if(phase!=Phase.STARTING)return;if(error!=null||!safe()){phase=Phase.HOLD;plugin.getLogger().warning("HEARTH_LOBBY_HOLD");}else{phase=Phase.READY;plugin.getLogger().info("HEARTH_LOBBY_READY");}}));
    }
    private boolean safe(){var w=entry.getWorld();var floor=w.getBlockAt(entry.getBlockX(),entry.getBlockY()-1,entry.getBlockZ());var feet=w.getBlockAt(entry.getBlockX(),entry.getBlockY(),entry.getBlockZ());var head=w.getBlockAt(entry.getBlockX(),entry.getBlockY()+1,entry.getBlockZ());return io.github.jesterportfolio.hearth.combat.CombatPlayers.safeCells(floor.getType().name(),feet.getType().name(),head.getType().name(),floor.getType().isSolid(),feet.isPassable(),head.isPassable());}
    private boolean empty(Player p){return Arrays.stream(p.getInventory().getContents()).allMatch(i->i==null||i.getType().isAir())&&(p.getItemOnCursor()==null||p.getItemOnCursor().getType().isAir())&&Arrays.stream(p.getEnderChest().getContents()).allMatch(i->i==null||i.getType().isAir());}
    @EventHandler(priority=EventPriority.HIGHEST) public void login(AsyncPlayerPreLoginEvent event){if(phase!=Phase.READY)event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,Component.text("로비 준비 또는 점검 중입니다."));}
    @EventHandler public void spawn(AsyncPlayerSpawnLocationEvent event){event.setSpawnLocation(entry.clone());}
    @EventHandler public void join(PlayerJoinEvent event){var p=event.getPlayer();if(phase!=Phase.READY||!p.isWhitelisted()||!empty(p)||links.containsKey(p.getUniqueId())){p.kick(Component.text("로비 입장 보류 · 기존 아이템/권한은 원본을 확인하세요."));return;}var token=new Object();links.put(p.getUniqueId(),new Link(p,token));Bukkit.getScheduler().runTask(plugin,()->{var l=links.get(p.getUniqueId());if(l!=null&&l.player()==p&&p.isOnline())p.sendMessage(Component.text("/hearthserver · 경제 서버 선택"));});}
    @EventHandler public void quit(PlayerQuitEvent event){var l=links.get(event.getPlayer().getUniqueId());if(l!=null&&l.player()==event.getPlayer()){links.remove(event.getPlayer().getUniqueId(),l);frozen.remove(l.token());}}
    @EventHandler(priority=EventPriority.HIGHEST) public void move(PlayerMoveEvent event){if(event.getTo()==null)return;var p=event.getPlayer();var l=links.get(p.getUniqueId());var to=event.getTo();if(phase!=Phase.READY||l==null||l.player()!=p||frozen.contains(l.token())||to.getWorld()!=entry.getWorld()||!region.contains(to.getBlockX(),to.getBlockY(),to.getBlockZ()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void command(PlayerCommandPreprocessEvent event){boolean denied=event.isCancelled();event.setCancelled(true);if(!denied)network.command(event.getPlayer(),event.getMessage());}
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent event){if(!(event.getWhoClicked() instanceof Player player)||!network.allowed(player,event.getView().getTopInventory()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent event){event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEvent event){event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void drop(PlayerDropItemEvent event){event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void pickup(EntityPickupItemEvent event){if(event.getEntity() instanceof Player)event.setCancelled(true);}
    public Phase phase(){return phase;}
    @Override public void close(){boolean clean=phase==Phase.READY;phase=Phase.CLOSED;network.close();for(var link:links.values())if(link.player().isOnline())link.player().kick(Component.text("로비 종료"));HandlerList.unregisterAll(this);links.clear();frozen.clear();plugin.getLogger().info("HEARTH_LOBBY_DISABLE_CLOSE "+(clean?"PASS":"HOLD"));}
}
