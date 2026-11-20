package io.github.jesterportfolio.hearth.survival;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;

/** 일반 인벤토리와 분리된 가상 선택창입니다. 실제 슬롯/경험치/잔액을 복사하거나 수정하지 않습니다. */
public final class PaperWorldMenu implements Listener,AutoCloseable {
    public interface Boundary {Object token(Player p);boolean current(Player p,Object token);SurvivalWorld world(Player p);boolean available(Player p,SurvivalWorld target);void select(Player p,Object token,SurvivalWorld target);default String reason(Player p,SurvivalWorld target){return "현재 해당 월드로 이동할 수 없습니다.";}default boolean personalAvailable(){return false;}default void personal(Player p){} }
    private final JavaPlugin plugin;private final Boundary boundary;private boolean closed;
    private final class Screen implements InventoryHolder {
        final Player player;final Object token;final Inventory inventory;
        Screen(Player p,Object token){this.player=p;this.token=token;inventory=Bukkit.createInventory(this,27,Component.text("월드 선택"));
            inventory.setContents(items(boundary.world(p),w->boundary.available(p,w),w->boundary.reason(p,w)));if(boundary.personalAvailable()){var house=new ItemStack(Material.OAK_DOOR);house.editMeta(meta->{meta.displayName(Component.text("개인 거주 · 나의 집과 공동 거주"));meta.lore(List.of(Component.text("구매·확장·초대·기본 거주 구획")));});inventory.setItem(22,house);}}

        public Inventory getInventory(){return inventory;}
    }
    public static ItemStack[] items(SurvivalWorld current,java.util.function.Predicate<SurvivalWorld> available){return items(current,available,w->"현재 이동할 수 없습니다.");}
    public static ItemStack[] items(SurvivalWorld current,java.util.function.Predicate<SurvivalWorld> available,java.util.function.Function<SurvivalWorld,String> reason){var items=new ItemStack[27];for(var world:SurvivalWorld.values()){var item=new ItemStack(Material.valueOf(world.material()));boolean here=current==world;item.editMeta(meta->{meta.displayName(Component.text(world.label()+(here?" · 현재 위치":"")));meta.lore(List.of(Component.text(world.description()),Component.text(here?"현재 머무르는 월드입니다.":available.test(world)?"클릭하여 이동":reason.apply(world))));});items[world.slot()]=item;}return items;}
    public PaperWorldMenu(JavaPlugin plugin,Boundary boundary){this.plugin=plugin;this.boundary=boundary;Bukkit.getPluginManager().registerEvents(this,plugin);}
    private boolean current(Player p,Screen s){return !closed&&s.player==p&&boundary.current(p,s.token)&&p.getOpenInventory().getTopInventory()==s.inventory;}
    public void open(Player p){var token=boundary.token(p);if(closed||token==null||!boundary.current(p,token)){p.sendMessage(Component.text("접속 또는 이동 준비가 끝난 뒤 다시 이용하세요."));return;}p.openInventory(new Screen(p,token).inventory);}
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent event){if(!(event.getView().getTopInventory().getHolder() instanceof PaperWorldMenu.Screen screen))return;boolean denied=event.isCancelled();event.setCancelled(true);if(event.getWhoClicked()==screen.player&&current(screen.player,screen)&&!denied&&event.getClickedInventory()==screen.inventory&&event.getSlot()==22&&event.getClick()==ClickType.LEFT&&boundary.personalAvailable()){Bukkit.getScheduler().runTask(plugin,()->{if(current(screen.player,screen))boundary.personal(screen.player);});return;}var target=SurvivalWorld.select(event.getWhoClicked()==screen.player&&current(screen.player,screen),denied,event.getClickedInventory()==screen.inventory,event.getSlot(),event.getClick().name());if(target.isEmpty())return;Bukkit.getScheduler().runTask(plugin,()->{var p=screen.player;if(!current(p,screen))return;var selected=target.orElseThrow();if(boundary.world(p)==selected){p.sendMessage(Component.text("현재 "+selected.label()+"에 있습니다."));return;}if(!boundary.available(p,selected)){p.sendMessage(Component.text(boundary.reason(p,selected)));return;}p.closeInventory();if(boundary.current(p,screen.token))boundary.select(p,screen.token,selected);});}
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent event){if(event.getView().getTopInventory().getHolder() instanceof PaperWorldMenu.Screen)event.setCancelled(true);}
    public boolean owns(Inventory inventory){return inventory.getHolder() instanceof PaperWorldMenu.Screen;}
    @Override public void close(){closed=true;HandlerList.unregisterAll(this);}
}
