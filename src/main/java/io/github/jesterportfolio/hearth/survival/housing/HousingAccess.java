package io.github.jesterportfolio.hearth.survival.housing;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.InventoryHolder;import org.bukkit.inventory.Inventory;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import io.github.jesterportfolio.hearth.survival.SurvivalWorld;
import net.kyori.adventure.text.Component;

/** WG의 일반 보호와 별개인 개인 월드 접근/권한 세대와 이동 우회만 닫습니다. */
public final class HousingAccess implements Listener,AutoCloseable {
 private final HousingRuntime h;private final Location publicEntry;private final Set<UUID> common;
 public HousingAccess(HousingRuntime h){this.h=h;publicEntry=h.boundary.entry(SurvivalWorld.RESIDENCE);common=h.boundary.worlds().values().stream().map(World::getUID).collect(java.util.stream.Collectors.toUnmodifiableSet());Bukkit.getPluginManager().registerEvents(this,h.plugin);}
 @EventHandler public void loginSpawn(AsyncPlayerSpawnLocationEvent e){if(!common.contains(e.getSpawnLocation().getWorld().getUID()))e.setSpawnLocation(publicEntry.clone());}
 @EventHandler(priority=EventPriority.HIGHEST) public void respawn(PlayerRespawnEvent e){if(!h.allowed(e.getPlayer().getUniqueId(),e.getRespawnLocation()))e.setRespawnLocation(publicEntry.clone());}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void teleport(PlayerTeleportEvent e){if(e.getTo()==null||!h.allowed(e.getPlayer().getUniqueId(),e.getTo()))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST) public void portal(PlayerPortalEvent e){e.setCancelled(true);var p=e.getPlayer();if(e.getCause()!=PlayerTeleportEvent.TeleportCause.NETHER_PORTAL){p.sendMessage(Component.text("이 차원은 아직 열리지 않았습니다."));return;}if(!h.ready(p))return;var role=p.getWorld().getEnvironment()==World.Environment.NETHER?SurvivalWorld.WILDERNESS:SurvivalWorld.NETHER;Bukkit.getScheduler().runTask(h.plugin,()->{if(h.ready(p))h.boundary.move(p,h.boundary.token(p),role.label(),()->java.util.concurrent.CompletableFuture.completedFuture(h.boundary.entry(role)));});}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void move(PlayerMoveEvent e){if(e.getTo()!=null&&!h.allowed(e.getPlayer().getUniqueId(),e.getTo()))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void place(BlockPlaceEvent e){if(!h.writable(e.getPlayer()))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){if(!h.writable(e.getPlayer()))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interact(PlayerInteractEvent e){if(!h.writable(e.getPlayer()))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void entity(PlayerInteractEntityEvent e){if(!h.writable(e.getPlayer()))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void armor(PlayerArmorStandManipulateEvent e){if(!h.writable(e.getPlayer()))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void bucket(PlayerBucketEmptyEvent e){if(!h.writable(e.getPlayer()))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void bucketFill(PlayerBucketFillEvent e){if(!h.writable(e.getPlayer()))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void damage(EntityDamageByEntityEvent e){var source=e.getDamager() instanceof Projectile projectile?projectile.getShooter():e.getDamager();if(source instanceof Player p&&!h.writable(p))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drop(PlayerDropItemEvent e){if(!h.writable(e.getPlayer()))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void pickup(EntityPickupItemEvent e){if(e.getEntity() instanceof Player p&&!h.writable(p))e.setCancelled(true);}
 private boolean physical(InventoryHolder holder){return holder instanceof org.bukkit.block.BlockState||holder instanceof Entity&&!(holder instanceof Player);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void open(InventoryOpenEvent e){if(e.getPlayer() instanceof Player p&&!h.writable(p)&&(physical(e.getInventory().getHolder())||Set.of(InventoryType.WORKBENCH,InventoryType.ANVIL,InventoryType.ENCHANTING,InventoryType.GRINDSTONE,InventoryType.SMITHING,InventoryType.STONECUTTER).contains(e.getInventory().getType())))e.setCancelled(true);}
 private boolean container(Inventory inv){return physical(inv.getHolder())||Set.of(InventoryType.WORKBENCH,InventoryType.ANVIL,InventoryType.ENCHANTING,InventoryType.GRINDSTONE,InventoryType.SMITHING,InventoryType.STONECUTTER).contains(inv.getType());}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void click(InventoryClickEvent e){if(e.getWhoClicked() instanceof Player p&&!h.writable(p)&&container(e.getView().getTopInventory()))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof Player p&&!h.writable(p)&&container(e.getView().getTopInventory()))e.setCancelled(true);}
 @Override public void close(){HandlerList.unregisterAll(this);}
}
