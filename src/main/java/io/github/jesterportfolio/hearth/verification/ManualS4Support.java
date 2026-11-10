package io.github.jesterportfolio.hearth.verification;

import io.github.jesterportfolio.hearth.lifecycle.WorldLifecycle;
import io.github.jesterportfolio.hearth.session.PaperSessionAdapter;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.entity.Enemy;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.*;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;

/** 명시적으로 요청한 실제 인증 시험의 새 격리 월드 시설/관측. 행동 PASS를 자동 판정하지 않습니다. */
final class ManualS4Support implements Listener,AutoCloseable {
    private final JavaPlugin plugin;private final PaperSessionAdapter players;private final Executor owner;
    private final Set<UUID> worlds=new HashSet<>();
    private final Map<UUID,Difficulty> difficulties=new HashMap<>();private boolean combatMode;
    ManualS4Support(JavaPlugin plugin,PaperSessionAdapter players,Executor owner){
        if(!Boolean.getBoolean("hearth.verification.manualS4")||!Boolean.getBoolean("hearth.verification.sessionManual"))throw new IllegalStateException("MANUAL_S4_GATE");
        this.plugin=plugin;this.players=players;this.owner=owner;Bukkit.getPluginManager().registerEvents(this,plugin);
    }
    void prepare(WorldLifecycle.Mapping mapping,Map<BlockVector3,Material> facilities){
        World world=Objects.requireNonNull(Bukkit.getWorld(mapping.physicalId()));
        if(!world.getKey().toString().equals(mapping.worldKey()))throw new IllegalStateException("MANUAL_S4_WORLD");
        try{if(!difficulties.containsKey(world.getUID()))difficulties.put(world.getUID(),Difficulty.valueOf(ManualEnvironmentState.load(mapping.directory().resolve("hearth-manual-environment.properties"),mapping.storyRunId(),mapping.physicalId(),world.getDifficulty().name())));}catch(java.io.IOException error){throw new IllegalStateException("CLIENT_ENVIRONMENT_ORIGINAL_HOLD",error);}worlds.add(world.getUID());environment(world);
        var file=mapping.directory().resolve("hearth-manual-s4.properties");
        var manager=Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world)));
        String region="hearth_manual_s4_lab";
        Material[] types={Material.ENDER_CHEST,Material.CHEST,Material.FURNACE,Material.BLAST_FURNACE,Material.SMOKER,Material.HOPPER,Material.DROPPER,Material.DISPENSER,Material.BREWING_STAND,Material.BLUE_SHULKER_BOX};
        try{
            if(Files.isSymbolicLink(file))throw new IllegalStateException("MANUAL_S4_METADATA_LINK");
            if(Files.exists(file)){
                var saved=new Properties();try(var in=Files.newInputStream(file)){saved.load(in);}
                if(!mapping.storyRunId().toString().equals(saved.getProperty("run"))||!world.getUID().toString().equals(saved.getProperty("world"))||!"1".equals(saved.getProperty("version"))||manager.getRegion(region)==null)throw new IllegalStateException("MANUAL_S4_METADATA_CONFLICT");
            }else{
                if(manager.getRegion(region)!=null)throw new IllegalStateException("MANUAL_S4_REGION_CONFLICT");
                for(var point:facilities.keySet())if(!world.getBlockAt(point.x(),point.y(),point.z()).isEmpty())throw new IllegalStateException("MANUAL_S4_FACILITY_CONFLICT");
                for(int i=0;i<types.length;i++)if(!world.getBlockAt(3+i%5*2,64,14+i/5*2).isEmpty())throw new IllegalStateException("MANUAL_S4_BLOCK_CONFLICT");
                for(int i=0;i<types.length;i++)world.getBlockAt(3+i%5*2,64,14+i/5*2).setType(types[i],false);
                for(var entry:facilities.entrySet()){var point=entry.getKey();var block=world.getBlockAt(point.x(),point.y(),point.z());if(entry.getValue()==Material.STONE_BUTTON){var button=(org.bukkit.block.data.type.Switch)Material.STONE_BUTTON.createBlockData();button.setAttachedFace(org.bukkit.block.data.FaceAttachable.AttachedFace.FLOOR);block.setBlockData(button,false);}else block.setType(entry.getValue(),false);}
                if(world.getBlockAt(3,63,16).getType()!=Material.STONE||!world.getBlockAt(3,65,16).isEmpty())throw new IllegalStateException("MANUAL_S4_HOPPER_CONFLICT");
                world.getBlockAt(3,63,16).setType(Material.CHEST,false);world.getBlockAt(3,65,16).setType(Material.CHEST,false);
                var chest=((InventoryHolder)world.getBlockAt(5,64,14).getState()).getInventory();
                chest.addItem(new ItemStack(Material.IRON_SWORD),new ItemStack(Material.IRON_PICKAXE),new ItemStack(Material.BREAD,32),new ItemStack(Material.IRON_HELMET),new ItemStack(Material.IRON_CHESTPLATE),new ItemStack(Material.IRON_LEGGINGS),new ItemStack(Material.IRON_BOOTS),new ItemStack(Material.COAL,32),new ItemStack(Material.RAW_IRON,8),new ItemStack(Material.BEEF,8),new ItemStack(Material.GLASS_BOTTLE,3),new ItemStack(Material.POTION,3),new ItemStack(Material.BLAZE_POWDER,3),new ItemStack(Material.NETHER_WART,3),new ItemStack(Material.OAK_LOG,8),new ItemStack(Material.COBBLESTONE,16));
                var lab=new ProtectedCuboidRegion(region,BlockVector3.at(1,63,12),BlockVector3.at(14,68,18));lab.setPriority(30);
                for(var flag:List.of(Flags.CHEST_ACCESS,Flags.INTERACT,Flags.USE,Flags.BUILD,Flags.BLOCK_BREAK,Flags.BLOCK_PLACE))lab.setFlag(flag,StateFlag.State.ALLOW);
                for(var flag:List.of(Flags.CREEPER_EXPLOSION,Flags.OTHER_EXPLOSION,Flags.TNT))lab.setFlag(flag,StateFlag.State.DENY);
                manager.addRegion(lab);
                for(int i=0;i<types.length;i++)if(world.getBlockAt(3+i%5*2,64,14+i/5*2).getType()!=types[i])throw new IllegalStateException("CLIENT_LAB_BLOCK_NOT_APPLIED");
                for(var entry:facilities.entrySet()){var point=entry.getKey();if(world.getBlockAt(point.x(),point.y(),point.z()).getType()!=entry.getValue())throw new IllegalStateException("CLIENT_FACILITY_NOT_APPLIED");}
                log("LAB_VERIFIED world="+world.getUID()+" containers="+types.length+" facilities="+facilities.size()+" creeper_explosion="+lab.getFlag(Flags.CREEPER_EXPLOSION));
                Files.writeString(file,"version=1\nrun="+mapping.storyRunId()+"\nworld="+world.getUID()+"\n",StandardOpenOption.CREATE_NEW);
            }
            log("LAB_READY run="+mapping.storyRunId()+" world="+world.getUID());
        }catch(java.io.IOException error){throw new IllegalStateException("MANUAL_S4_PREPARATION",error);}
    }
    private void environment(World world){
        if(!difficulties.containsKey(world.getUID()))throw new IllegalStateException("CLIENT_ENVIRONMENT_ORIGINAL_MISSING");
        world.setDifficulty(combatMode?difficulties.get(world.getUID()):Difficulty.PEACEFUL);
        for(var rule:List.of(GameRules.ADVANCE_TIME,GameRules.ADVANCE_WEATHER,GameRules.SPAWN_MOBS,GameRules.MOB_GRIEFING,GameRules.SPAWN_PHANTOMS,GameRules.SPAWN_PATROLS))if(!world.setGameRule(rule,false)||!Boolean.FALSE.equals(world.getGameRuleValue(rule)))throw new IllegalStateException("CLIENT_ENVIRONMENT_RULE");
        world.setTime(6000);world.setStorm(false);world.setThundering(false);world.setClearWeatherDuration(Integer.MAX_VALUE);
        int removed=0;for(var entity:world.getEntities())if(entity instanceof Enemy&&ManualClientEnvironment.blocksSpawn(entity.getEntitySpawnReason().name())&&entity.getPersistentDataContainer().getKeys().stream().noneMatch(key->key.getNamespace().equals("hearth"))){entity.remove();removed++;}
        if(world.getTime()!=6000||world.hasStorm()||world.isThundering()||!combatMode&&world.getDifficulty()!=Difficulty.PEACEFUL)throw new IllegalStateException("CLIENT_ENVIRONMENT_NOT_APPLIED");
        log("ENV_READY world="+world.getUID()+" key="+world.getKey()+" time="+world.getTime()+" difficulty="+world.getDifficulty()+" storm="+world.hasStorm()+" natural_spawn="+world.getGameRuleValue(GameRules.SPAWN_MOBS)+" griefing="+world.getGameRuleValue(GameRules.MOB_GRIEFING)+" removed_natural_hostiles="+removed);
    }
    void environment(boolean combat){combatMode=combat;for(UUID id:worlds){var world=Bukkit.getWorld(id);if(world!=null)environment(world);}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void naturalSpawn(CreatureSpawnEvent event){if(worlds.contains(event.getLocation().getWorld().getUID())&&ManualClientEnvironment.blocksSpawn(event.getSpawnReason().name()))event.setCancelled(true);}
    void pack(String profile){
        var player=Objects.requireNonNull(Bukkit.getPlayerExact(profile),"CLIENT_NOT_CONNECTED");var session=Objects.requireNonNull(players.prepared(player),"CLIENT_NOT_PREPARED");
        Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{
            try{
                Path metadata=plugin.getDataFolder().toPath().resolve("betterhud-pack.json"),pack=plugin.getDataFolder().toPath().resolveSibling("BetterHud").resolve("build.zip");
                if(Files.isSymbolicLink(metadata)||Files.isSymbolicLink(pack))throw new IllegalStateException("CLIENT_PACK_LINK");
                var match=Pattern.compile("\\\"pack_id\\\":\\\"([0-9a-f-]{36})\\\"").matcher(Files.readString(metadata));
                if(!match.find())throw new IllegalStateException("CLIENT_PACK_NOT_READY");
                UUID id=UUID.fromString(match.group(1));byte[] hash=MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(pack));
                owner.execute(()->{if(Bukkit.getPlayerExact(profile)!=player||players.prepared(player)!=session){log("PACK_REJECTED STALE_CLIENT");return;}player.addResourcePack(id,"http://127.0.0.1:25588/hearth-s4.zip",hash,"Hearth S4 격리 시험: 스마트폰·지도·HUD 팩",false);log("PACK_SENT player="+player.getUniqueId()+" pack="+id);});
            }catch(Exception error){owner.execute(()->log("PACK_UNAVAILABLE "+error.getClass().getSimpleName()));}
        });
    }
    private boolean linked(Player player){return worlds.contains(player.getWorld().getUID())&&players.linked(player)!=null;}
    private void log(String message){plugin.getLogger().info("HEARTH_S4_CLIENT_"+message);}
    @EventHandler(priority=EventPriority.MONITOR) public void teleport(PlayerTeleportEvent event){if(players.linked(event.getPlayer())!=null)log("TELEPORT cause="+event.getCause()+" cancelled="+event.isCancelled()+" target_world="+(event.getTo()==null?"NONE":event.getTo().getWorld().getUID()));}
    @EventHandler(priority=EventPriority.MONITOR) public void packStatus(PlayerResourcePackStatusEvent event){if(linked(event.getPlayer()))log("PACK_STATUS player="+event.getPlayer().getUniqueId()+" pack="+event.getID()+" status="+event.getStatus());}
    @EventHandler(priority=EventPriority.MONITOR) public void open(InventoryOpenEvent event){if(event.getPlayer() instanceof Player p&&linked(p))log("OPEN player="+p.getUniqueId()+" type="+event.getInventory().getType()+" cancelled="+event.isCancelled());}
    @EventHandler(priority=EventPriority.MONITOR) public void click(InventoryClickEvent event){if(event.getWhoClicked() instanceof Player p&&linked(p))log("CLICK player="+p.getUniqueId()+" type="+event.getView().getTopInventory().getType()+" action="+event.getAction()+" click="+event.getClick()+" slot="+event.getRawSlot()+" cancelled="+event.isCancelled());}
    @EventHandler(priority=EventPriority.MONITOR) public void drag(InventoryDragEvent event){if(event.getWhoClicked() instanceof Player p&&linked(p))log("DRAG player="+p.getUniqueId()+" slots="+event.getRawSlots()+" cancelled="+event.isCancelled());}
    @EventHandler(priority=EventPriority.MONITOR) public void drop(PlayerDropItemEvent event){if(linked(event.getPlayer()))log("DROP player="+event.getPlayer().getUniqueId()+" type="+event.getItemDrop().getItemStack().getType()+" cancelled="+event.isCancelled());}
    @EventHandler(priority=EventPriority.MONITOR) public void smelt(FurnaceSmeltEvent event){if(worlds.contains(event.getBlock().getWorld().getUID()))log("SMELT type="+event.getResult().getType()+" cancelled="+event.isCancelled());}
    @Override public void close(){HandlerList.unregisterAll(this);worlds.clear();}
}
