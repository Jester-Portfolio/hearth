package io.github.jesterportfolio.hearth.features;

import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.session.*;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldedit.bukkit.BukkitAdapter;

/** 순정 개인 엔더 상자는 읽거나 수정하지 않습니다. 발급 문맥의 별도 27칸만 엽니다. */
public final class PaperEnderStorage implements Listener,AutoCloseable {
    private final JavaPlugin plugin;private final PaperSessionAdapter players;private final PersonalStorage store;private final PaperInventoryCodec codec;private final Executor owner;private final Map<Player,Screen> screens=new IdentityHashMap<>();private final Set<Player> opening=Collections.newSetFromMap(new IdentityHashMap<>());private boolean closed;
    public final class Screen implements PrivateInventory{final Player player;final PlayerSessions.Session session;final Block block;final PersonalStorage.Account account;final Inventory inventory;Screen(Player p,PlayerSessions.Session s,Block b,PersonalStorage.Account account){player=p;session=s;block=b;this.account=account;inventory=Bukkit.createInventory(this,27,Component.text("이 진행의 개인 엔더 보관함"));inventory.setContents(Arrays.copyOf(codec.decode(account.payload()),27));}public Inventory getInventory(){return inventory;}}
    public PaperEnderStorage(JavaPlugin plugin,PaperSessionAdapter players,PersonalStorage store,PaperInventoryCodec codec,Executor owner){this.plugin=plugin;this.players=players;this.store=store;this.codec=codec;this.owner=owner;Bukkit.getPluginManager().registerEvents(this,plugin);}
    public boolean facility(Player p,Block b){if(players.current(p)==null||b.getType()!=Material.ENDER_CHEST||!b.getWorld().equals(p.getWorld())||b.getLocation().distanceSquared(p.getLocation())>64)return false;var platform=WorldGuard.getInstance().getPlatform();var local=WorldGuardPlugin.inst().wrapPlayer(p);var regions=platform.getRegionContainer().get(BukkitAdapter.adapt(p.getWorld()));return platform.getSessionManager().hasBypass(local,BukkitAdapter.adapt(p.getWorld()))||regions!=null&&regions.getApplicableRegions(com.sk89q.worldedit.math.BlockVector3.at(b.getX(),b.getY(),b.getZ())).testState(local,Flags.CHEST_ACCESS,Flags.INTERACT);}
    public boolean allowed(Player p,Inventory inventory){if(!(inventory.getHolder() instanceof Screen s))return false;return !closed&&s.player==p&&screens.get(p)==s&&players.current(p)==s.session&&!s.account.held()&&facility(p,s.block);}
    public void observe(Player p){var s=screens.get(p);if(s==null||s.account.held())return;store.observe(s.account,codec.encode(Arrays.copyOf(s.inventory.getContents(),41)));}
    public boolean command(Player p,String line){if(!line.equals("/hearthender"))return false;var b=p.getTargetBlockExact(4);if(b==null||!facility(p,b)){p.sendMessage(Component.text("접근 가능한 엔더 상자 시설에서 개인 보관함을 여세요."));return true;}open(p,b);return true;}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interact(PlayerInteractEvent e){if(e.getHand()!=EquipmentSlot.HAND||e.getAction()!=Action.RIGHT_CLICK_BLOCK||e.getClickedBlock()==null||e.getClickedBlock().getType()!=Material.ENDER_CHEST)return;e.setCancelled(true);if(facility(e.getPlayer(),e.getClickedBlock()))Bukkit.getScheduler().runTask(plugin,()->open(e.getPlayer(),e.getClickedBlock()));}
    private void open(Player p,Block b){observe(p);var session=players.current(p);if(session==null||closed||!facility(p,b)||!opening.add(p))return;store.open(session.context(),session,()->players.current(p)==session&&facility(p,b),()->session.save().markDirty()).whenCompleteAsync((account,error)->{opening.remove(p);if(players.current(p)!=session)return;if(error!=null){p.sendMessage(Component.text("개인 보관함 저장 상태를 확인하지 못했습니다. 원본을 유지합니다."));return;}try{var s=new Screen(p,session,b,account);screens.put(p,s);p.openInventory(s.inventory);}catch(RuntimeException malformed){players.craftingHold(session);}},owner);}
    @EventHandler(priority=EventPriority.LOWEST) public void quit(PlayerQuitEvent e){var s=screens.get(e.getPlayer());if(s==null)return;observe(e.getPlayer());s.session.closed().whenCompleteAsync((v,error)->{if(error==null){store.forget(s.account);screens.remove(e.getPlayer(),s);}},owner);}
    public void close(){closed=true;for(var p:List.copyOf(screens.keySet())){observe(p);if(p.getOpenInventory().getTopInventory().getHolder() instanceof Screen)p.closeInventory();}HandlerList.unregisterAll(this);opening.clear();}
}
