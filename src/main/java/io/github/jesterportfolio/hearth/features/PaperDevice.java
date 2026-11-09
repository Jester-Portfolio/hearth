package io.github.jesterportfolio.hearth.features;

import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.session.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** 최초 지급 표시와 인벤토리의 단일 commit. 분실 후에는 명시적 재발급만 허용합니다. */
public final class PaperDevice implements Listener,AutoCloseable {
    private final JavaPlugin plugin;private final PaperSessionAdapter players;private final PlayerSessions sessions;private final FeatureStore store;private final PaperInventoryCodec codec;private final Executor owner;private final Consumer<Player> home;private final NamespacedKey ownerKey,runKey,device;
    public PaperDevice(JavaPlugin plugin,PaperSessionAdapter players,PlayerSessions sessions,FeatureStore store,PaperInventoryCodec codec,Executor owner,Consumer<Player> home){this.plugin=plugin;this.players=players;this.sessions=sessions;this.store=store;this.codec=codec;this.owner=owner;this.home=home;ownerKey=new NamespacedKey(plugin,"phone_owner");runKey=new NamespacedKey(plugin,"phone_run");device=new NamespacedKey(plugin,"dev_phone");Bukkit.getPluginManager().registerEvents(this,plugin);}
    public boolean tagged(ItemStack item){return item!=null&&!item.isEmpty()&&item.hasItemMeta()&&item.getItemMeta().getPersistentDataContainer().has(ownerKey,PersistentDataType.STRING);}
    public boolean owned(ItemStack item,ServerProgressAccess.Context c){return tagged(item)&&c.playerId().toString().equals(item.getItemMeta().getPersistentDataContainer().get(ownerKey,PersistentDataType.STRING))&&c.storyRunId().toString().equals(item.getItemMeta().getPersistentDataContainer().get(runKey,PersistentDataType.STRING));}
    public InventoryPayload plan(ServerProgressAccess.Context c,InventoryPayload source){var slots=codec.decode(source);int count=0,empty=-1;for(int i=0;i<41;i++){if(owned(slots[i],c)){count+=slots[i].getAmount();if(slots[i].getAmount()!=1)throw new IllegalStateException("DEVICE_DUPLICATE");}if(i<36&&(slots[i]==null||slots[i].isEmpty())&&empty<0)empty=i;}if(count>1)throw new IllegalStateException("DEVICE_DUPLICATE");if(count==1)return source;if(empty<0)throw new IllegalStateException("DEVICE_SPACE_REQUIRED");var item=new ItemStack(Material.PAPER);item.editMeta(meta->{meta.displayName(Component.text("Hearth 개인 스마트폰"));meta.setItemModel(new NamespacedKey("hearth","phone"));meta.setMaxStackSize(1);meta.getPersistentDataContainer().set(ownerKey,PersistentDataType.STRING,c.playerId().toString());meta.getPersistentDataContainer().set(runKey,PersistentDataType.STRING,c.storyRunId().toString());meta.getPersistentDataContainer().set(device,PersistentDataType.BYTE,(byte)1);});slots[empty]=item;return codec.encode(slots);}
    public CompletionStage<Void> issue(Player p,boolean explicit){var s=players.current(p);if(s==null)return CompletableFuture.completedFuture(null);var source=codec.capture(p.getInventory(),p.getItemOnCursor());InventoryPayload planned;try{planned=plan(s.context(),source);}catch(RuntimeException full){p.sendMessage(Component.text("스마트폰 지급을 보류했습니다. 빈 보관 슬롯과 소유 상태를 확인하세요."));return CompletableFuture.completedFuture(null);}if(codec.equivalent(source,planned))return CompletableFuture.completedFuture(null);
        return store.read(s.context(),"device","phone").thenComposeAsync(row->{if(players.current(p)!=s)throw new IllegalStateException("DEVICE_STALE");if(!DeviceRules.issue(row.isPresent(),explicit)){p.sendMessage(Component.text("기존 스마트폰이 보이지 않습니다. /hearthphone device 로 본인 저장 상태를 확인해 재발급하세요."));return CompletableFuture.completedFuture(null);}return sessions.inventoryTransaction(s,before->{var next=plan(s.context(),before.payload());if(codec.equivalent(next,before.payload()))return CompletableFuture.completedFuture(before);return store.device(s.context(),before.revision(),next,row.map(FeatureStore.Saved::revision).orElse(-1L)).thenApply(FeatureStore.InventoryCommit::inventory);});},owner);
    }
    public boolean click(Player p,InventoryClickEvent e){var slot=PaperInventoryWindow.slot(e.getView(),e.getClickedInventory(),e.getSlot());var cursor=p.getItemOnCursor();var current=e.getCurrentItem();ItemStack hotbar=e.getClick()==ClickType.SWAP_OFFHAND?p.getInventory().getItemInOffHand():e.getHotbarButton()>=0&&e.getHotbarButton()<9?p.getInventory().getItem(e.getHotbarButton()):null;
        if(slot==InventoryWindowRules.Slot.RESULT&&e.getView().getTopInventory() instanceof CraftingInventory c&&Arrays.stream(c.getMatrix()).anyMatch(this::tagged))return false;
        if(tagged(current)&&(e.getClick()==ClickType.DROP||e.getClick()==ClickType.CONTROL_DROP||slot==InventoryWindowRules.Slot.CONTAINER||e.isShiftClick()&&e.getView().getTopInventory().getHolder() instanceof PrivateInventory||e.isShiftClick()&&!(e.getView().getTopInventory() instanceof CraftingInventory)))return false;
        if((tagged(cursor)||tagged(hotbar))&&slot!=InventoryWindowRules.Slot.STORAGE&&slot!=InventoryWindowRules.Slot.EQUIPMENT)return false;return true;
    }
    public boolean drag(InventoryDragEvent e){for(var entry:e.getNewItems().entrySet())if(tagged(entry.getValue())){var slot=PaperInventoryWindow.slot(e.getView(),e.getView().getInventory(entry.getKey()),e.getView().convertSlot(entry.getKey()));if(slot!=InventoryWindowRules.Slot.STORAGE)return false;}return true;}
    @EventHandler(priority=EventPriority.LOWEST) public void use(PlayerInteractEvent e){if(!tagged(e.getItem())||!e.getAction().isLeftClick()&&!e.getAction().isRightClick())return;e.setCancelled(true);if(e.getHand()==EquipmentSlot.HAND&&e.getAction().isRightClick())return;var p=e.getPlayer();var s=players.current(p);if(s!=null&&owned(e.getItem(),s.context()))Bukkit.getScheduler().runTask(plugin,()->{if(players.current(p)==s)home.accept(p);});}
    @EventHandler(priority=EventPriority.LOWEST) public void entity(PlayerInteractEntityEvent e){var item=e.getHand()==EquipmentSlot.HAND?e.getPlayer().getInventory().getItemInMainHand():e.getPlayer().getInventory().getItemInOffHand();if(tagged(item)){e.setCancelled(true);var p=e.getPlayer();var s=players.current(p);if(s!=null&&owned(item,s.context()))Bukkit.getScheduler().runTask(plugin,()->{if(players.current(p)==s)home.accept(p);});}}
    @EventHandler(priority=EventPriority.LOWEST) public void attack(org.bukkit.event.entity.EntityDamageByEntityEvent e){if(e.getDamager() instanceof Player p&&tagged(p.getInventory().getItemInMainHand())){e.setCancelled(true);var s=players.current(p);if(s!=null&&owned(p.getInventory().getItemInMainHand(),s.context()))Bukkit.getScheduler().runTask(plugin,()->{if(players.current(p)==s)home.accept(p);});}}
    @EventHandler(priority=EventPriority.HIGHEST) public void drop(PlayerDropItemEvent e){if(tagged(e.getItemDrop().getItemStack()))e.setCancelled(true);}
    public void close(){HandlerList.unregisterAll(this);}
}
