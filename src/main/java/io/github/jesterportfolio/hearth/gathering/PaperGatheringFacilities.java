package io.github.jesterportfolio.hearth.gathering;

import io.github.jesterportfolio.hearth.session.*;
import io.github.jesterportfolio.hearth.lifecycle.WorldLifecycle;
import io.github.jesterportfolio.hearth.persistence.ServerProgressAccess;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.*;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.function.*;

/** 등록된 현장 버튼만 사용합니다. 스마트폰/지도에서 원격 입퇴장을 제공하지 않습니다. */
public final class PaperGatheringFacilities implements Listener,AutoCloseable{
    @FunctionalInterface public interface Objectives {void open(Player player,BooleanSupplier current,Runnable back);}
    private final Objectives objectives;
    private final JavaPlugin plugin;private final PaperGathering gathering;private final PaperSessionAdapter sessions;private final Map<UUID,WorldLifecycle> worlds;public final GatheringFacilities content;private final BiPredicate<ServerProgressAccess.Context,GatheringFacilities.Site> access;
    private final Map<UUID,Screen> screens=new HashMap<>();private final Set<UUID> opening=new HashSet<>();private boolean closed;private final Consumer<Player> preparation;
    private final class Screen implements InventoryHolder{
        final Player player;final PlayerSessions.Session session;final GatheringFacilities.Site site;final long generation;final Inventory inventory;final GatheringFacilities.Choice choice;final boolean confirmation;boolean used;
        Screen(Player p,PlayerSessions.Session s,GatheringFacilities.Site site,boolean confirmation){player=p;session=s;this.site=site;this.confirmation=confirmation;generation=worlds.get(s.context().storyRunId()).generation();var stamp=gathering.stamp(p);GatheringFacilities.Choice candidate;try{candidate=content.choose(site,stamp,true);}catch(IllegalStateException unavailable){candidate=null;}choice=candidate;inventory=preview(site,gathering.information(p),choice!=null,confirmation);}
        public Inventory getInventory(){return inventory;}
    }
    public PaperGatheringFacilities(JavaPlugin plugin,PaperGathering gathering,PaperSessionAdapter sessions,Map<UUID,WorldLifecycle> worlds,GatheringFacilities content,BiPredicate<ServerProgressAccess.Context,GatheringFacilities.Site> access,Consumer<Player> preparation,Objectives objectives){
        if(!plugin.getConfig().getBoolean("gathering-facility-verification-enabled")||!Boolean.getBoolean("hearth.verification.gatheringFacilities"))throw new IllegalStateException("GATHERING_FACILITY_GATE");this.plugin=plugin;this.gathering=gathering;this.sessions=sessions;this.worlds=worlds;this.content=content;this.access=access;this.preparation=preparation;
        this.objectives=Objects.requireNonNull(objectives);sessions.gatheringInventory((p,i)->i.getHolder() instanceof Screen s&&s.player==p&&valid(s));for(UUID run:worlds.keySet())if(Bukkit.getWorld(worlds.get(run).mapping().physicalId())!=null)ensureRegions(run);Bukkit.getPluginManager().registerEvents(this,plugin);
    }
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("GATHERING_FACILITY_MAIN_THREAD");}
    public static Inventory preview(GatheringFacilities.Site site,List<String> status,boolean available,boolean confirmation){var i=Bukkit.createInventory(null,54,Component.text(site.title()));i.setItem(13,item(Material.MAP,"현재 채집 상태",status));i.setItem(22,item(Material.BOOK,"내 목표·다음 행동",List.of("현재 방문·다음 목표·개인 보고 안내를 확인합니다","입퇴장이나 수락·보고를 실행하지 않습니다")));i.setItem(31,item(available?Material.LIME_DYE:Material.BARRIER,available?(confirmation?"확인: ":"")+(site.action()==GatheringFacilities.Action.ENTER?"채집 입장":"채집 퇴장"):"현재 요청할 수 없습니다",List.of("현장·세션·구역 상태를 다시 확인합니다","필요한 저장 성공 후 기존 이동 경로를 사용합니다")));i.setItem(45,item(Material.ARROW,confirmation?"이전 화면":"다음 플레이 안내",List.of(confirmation?"현장 상태로 다시 조회":"공개 코스의 다음 행동을 확인합니다")));i.setItem(49,item(Material.CLOCK,"상태 새로고침",List.of("입퇴장이나 저장을 실행하지 않습니다")));i.setItem(53,item(Material.BARRIER,"닫기",List.of()));return i;}
    private static ItemStack item(Material material,String title,List<String> lore){var i=new ItemStack(material);i.editMeta(m->{m.displayName(Component.text(title));m.lore(lore.stream().map(Component::text).toList());});return i;}
    private GatheringFacilities.Site find(Block b){return content.sites().stream().filter(s->s.x()==b.getX()&&s.y()==b.getY()&&s.z()==b.getZ()&&s.material().equals(b.getType().name())).findFirst().orElse(null);}
    private boolean permitted(Player p,GatheringFacilities.Site site){var w=WorldGuard.getInstance().getPlatform();var local=WorldGuardPlugin.inst().wrapPlayer(p);return w.getSessionManager().hasBypass(local,BukkitAdapter.adapt(p.getWorld()))||w.getRegionContainer().createQuery().testState(BukkitAdapter.adapt(new Location(p.getWorld(),site.x(),site.y(),site.z())),local,Flags.INTERACT);}
    private boolean eligible(Player p,PlayerSessions.Session s,GatheringFacilities.Site site,long generation){if(closed||sessions.current(p)!=s||!sessions.travelEligible(p,s)||p.isDead()||p.getGameMode()!=GameMode.SURVIVAL||p.isInsideVehicle()||!p.getItemOnCursor().isEmpty())return false;var w=worlds.get(s.context().storyRunId());return w!=null&&w.generation()==generation&&p.getWorld().getUID().equals(w.mapping().physicalId())&&site.near(p.getX(),p.getY(),p.getZ())&&p.getWorld().isChunkLoaded(site.x()>>4,site.z()>>4)&&find(p.getWorld().getBlockAt(site.x(),site.y(),site.z()))==site&&access.test(s.context(),site)&&permitted(p,site);}
    private boolean valid(Screen s){return screens.get(s.player.getUniqueId())==s&&eligible(s.player,s.session,s.site,s.generation);}
    public void ensureRegions(UUID run){main();var w=Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId()));var manager=Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(w)));for(var site:content.sites()){var point=BlockVector3.at(site.x(),site.y(),site.z());String id="hearth_gathering_facility_"+site.id();var r=manager.getRegion(id);if(r==null){r=new ProtectedCuboidRegion(id,point,point);r.setPriority(30);r.setFlag(Flags.INTERACT,StateFlag.State.ALLOW);r.setFlag(Flags.PASSTHROUGH,StateFlag.State.ALLOW);manager.addRegion(r);}else if(!r.getMinimumPoint().equals(point)||!r.getMaximumPoint().equals(point)||r.getPriority()!=30||r.getFlag(Flags.INTERACT)!=StateFlag.State.ALLOW||r.getFlag(Flags.PASSTHROUGH)!=StateFlag.State.ALLOW)throw new IllegalStateException("GATHERING_FACILITY_REGION_CONFLICT");}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interact(PlayerInteractEvent e){if(e.getAction()!=Action.RIGHT_CLICK_BLOCK||e.getClickedBlock()==null)return;var p=e.getPlayer();var session=sessions.current(p);if(session==null)return;var world=worlds.get(session.context().storyRunId());if(world==null||!p.getWorld().getUID().equals(world.mapping().physicalId()))return;var site=find(e.getClickedBlock());if(site==null)return;e.setCancelled(true);if(e.getHand()!=EquipmentSlot.HAND||!opening.add(p.getUniqueId()))return;long generation=worlds.get(session.context().storyRunId()).generation();Bukkit.getScheduler().runTaskLater(plugin,()->{opening.remove(p.getUniqueId());if(eligible(p,session,site,generation))show(p,session,site,false);},1);}
    private void show(Player p,PlayerSessions.Session session,GatheringFacilities.Site site,boolean confirmation){try{var screen=new Screen(p,session,site,confirmation);screens.put(p.getUniqueId(),screen);p.openInventory(screen.inventory);if(p.getOpenInventory().getTopInventory()!=screen.inventory)screens.remove(p.getUniqueId(),screen);}catch(RuntimeException unavailable){p.sendMessage(Component.text("채집 시설을 준비하지 못했습니다. 등록/저장 상태를 확인하세요."));}}
    @EventHandler(priority=EventPriority.HIGHEST) public void clicked(InventoryClickEvent e){if(!(e.getView().getTopInventory().getHolder() instanceof Screen s))return;e.setCancelled(true);if(e.getWhoClicked()!=s.player||!valid(s)||s.used||e.getClickedInventory()!=s.inventory||e.getClick()!=ClickType.LEFT||!Set.of(22,31,45,49,53).contains(e.getSlot())||e.getSlot()==31&&s.choice==null)return;s.used=true;int slot=e.getSlot();Bukkit.getScheduler().runTaskLater(plugin,()->{
        if(!valid(s)||s.player.getOpenInventory().getTopInventory()!=s.inventory)return;
        if(slot==22){s.used=false;objectives.open(s.player,()->eligible(s.player,s.session,s.site,s.generation),()->show(s.player,s.session,s.site,false));return;}if(slot==53){s.player.closeInventory();return;}if(slot==49||slot==45&&s.confirmation){show(s.player,s.session,s.site,false);return;}if(slot==45){s.player.closeInventory();preparation.accept(s.player);return;}
        try{if(s.choice==null)throw new IllegalStateException("GATHERING_UNAVAILABLE");content.require(s.choice,s.site,gathering.stamp(s.player),true);if(!s.confirmation){show(s.player,s.session,s.site,true);return;}s.player.closeInventory();gathering.request(s.player,s.site.action()).whenComplete((v,error)->{if(sessions.prepared(s.player)==s.session)s.player.sendMessage(Component.text(error==null?"채집 "+(s.site.action()==GatheringFacilities.Action.ENTER?"입장":"퇴장")+" 완료":"채집 처리 보류 · 저장 상태 확인 필요"));});}
        catch(RuntimeException rejected){s.player.sendMessage(Component.text("시설 또는 구역 상태가 바뀌었습니다. 다시 확인하세요."));}
    },1);}
    @EventHandler(priority=EventPriority.HIGHEST) public void dragged(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Screen)e.setCancelled(true);}
    @EventHandler public void inventoryClosed(InventoryCloseEvent e){if(e.getInventory().getHolder() instanceof Screen s)screens.remove(e.getPlayer().getUniqueId(),s);}
    @EventHandler public void quit(PlayerQuitEvent e){screens.remove(e.getPlayer().getUniqueId());opening.remove(e.getPlayer().getUniqueId());}
    public void unloaded(UUID run){screens.entrySet().removeIf(e->e.getValue().session.context().storyRunId().equals(run));}
    public void close(){main();if(closed)return;closed=true;HandlerList.unregisterAll(this);sessions.gatheringInventory((p,i)->false);screens.clear();opening.clear();}
}
