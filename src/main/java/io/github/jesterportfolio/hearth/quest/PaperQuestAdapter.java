package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.session.*;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import io.github.jesterportfolio.hearth.persistence.QuestStore;
import java.util.*;
import java.util.concurrent.Executor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** 콘텐츠 주민 하나의 격리 어댑터. NPC 플랫폼/퀘스트 엔진을 일반 서버에 설치하지 않습니다. */
public final class PaperQuestAdapter implements Listener,AutoCloseable {
    private final JavaPlugin plugin;
    private final PaperSessionAdapter players;
    private final PersonalQuest quests;
    private final QuestContent content;
    private final Executor owner;
    private final Set<UUID> worldIds;
    private final NamespacedKey tag;
    // World/Entity 참조를 보존하지 않습니다.
    private final Map<UUID,UUID> npcs=new HashMap<>();
    private final Map<UUID,PlayerSessions.Session> conversations=new HashMap<>();
    private boolean closed;
    public PaperQuestAdapter(JavaPlugin plugin,PaperSessionAdapter players,PersonalQuest quests,QuestContent content,Executor owner,Set<UUID> worldIds) {
        this.plugin=plugin;this.players=players;this.quests=quests;this.content=content;this.owner=owner;this.worldIds=Set.copyOf(worldIds);tag=new NamespacedKey(plugin,"quest_demo_npc");
        Bukkit.getPluginManager().registerEvents(this,plugin);players.questCommand(this::command);
        for(World w:Bukkit.getWorlds())ensure(w);
    }
    public void ensure(World w) {
        if(closed || !worldIds.contains(w.getUID()))return;
        dialogueRegion(w);
        w.getChunkAt((int)Math.floor(content.x())>>4,(int)Math.floor(content.z())>>4);
        List<Entity> existing=w.getEntities().stream().filter(this::tagged).toList();
        UUID current=npcs.get(w.getUID());
        if(current!=null && existing.size()==1 && existing.getFirst().getUniqueId().equals(current))return;
        existing.forEach(Entity::remove);
        var npc=w.spawn(new Location(w,content.x(),content.y(),content.z()),Villager.class,v->{
            v.setAI(false);v.setGravity(false);v.setInvulnerable(true);v.setSilent(true);v.setCollidable(false);
            v.setPersistent(false);v.customName(Component.text(content.name()));v.setCustomNameVisible(true);
            v.getPersistentDataContainer().set(tag,PersistentDataType.STRING,content.id());
        });
        npcs.put(w.getUID(),npc.getUniqueId());
    }
    private void dialogueRegion(World w) {
        var manager=Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(w)),"Missing WG manager");
        var point=BlockVector3.at(Math.floor(content.x()),Math.floor(content.y()),Math.floor(content.z()));
        var top=point.add(0,1,0);
        var existing=manager.getRegion("hearth_demo_dialogue");
        if(existing==null) {
            var region=new ProtectedCuboidRegion("hearth_demo_dialogue",point,top);
            region.setPriority(30);region.setFlag(Flags.PASSTHROUGH,StateFlag.State.ALLOW);
            region.setFlag(Flags.INTERACT,StateFlag.State.ALLOW);manager.addRegion(region);
        } else if(!existing.getMinimumPoint().equals(point) || !existing.getMaximumPoint().equals(top)
                || existing.getPriority()!=30 || existing.getFlag(Flags.INTERACT)!=StateFlag.State.ALLOW
                || existing.getFlag(Flags.PASSTHROUGH)!=StateFlag.State.ALLOW)
            throw new IllegalStateException("Existing dialogue region mismatch; preserve original");
    }
    public boolean dialogueAllowed(World w) {
        var manager=Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(w)));
        return manager.getApplicableRegions(BlockVector3.at(content.x(),content.y(),content.z())).queryState(null,Flags.INTERACT)==StateFlag.State.ALLOW;
    }
    @EventHandler public void chunkLoad(ChunkLoadEvent e){
        if(!worldIds.contains(e.getWorld().getUID()) || e.getChunk().getX()!=((int)Math.floor(content.x())>>4) || e.getChunk().getZ()!=((int)Math.floor(content.z())>>4))return;
        UUID id=e.getWorld().getUID();
        Bukkit.getScheduler().runTaskLater(plugin,()->{World w=Bukkit.getWorld(id);if(w!=null && w.isChunkLoaded((int)Math.floor(content.x())>>4,(int)Math.floor(content.z())>>4))ensure(w);},1);
    }
    private boolean tagged(Entity e){return content.id().equals(e.getPersistentDataContainer().get(tag,PersistentDataType.STRING));}
    public int count(World w){return (int)w.getEntities().stream().filter(this::tagged).count();}
    public boolean tracks(UUID world){return npcs.containsKey(world);}
    private boolean near(Player p){UUID id=npcs.get(p.getWorld().getUID());Entity e=id==null?null:p.getWorld().getEntity(id);return e!=null && e.isValid() && p.getLocation().distanceSquared(e.getLocation())<=16;}
    @EventHandler public void load(WorldLoadEvent e){UUID id=e.getWorld().getUID();Bukkit.getScheduler().runTaskLater(plugin,()->{World w=Bukkit.getWorld(id);if(w!=null)ensure(w);},1);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void unload(WorldUnloadEvent e){npcs.remove(e.getWorld().getUID());}
    @EventHandler public void quit(PlayerQuitEvent e){conversations.remove(e.getPlayer().getUniqueId());}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void click(PlayerInteractAtEntityEvent e){
        if(!tagged(e.getRightClicked()))return;
        e.setCancelled(true); // 주민 거래 GUI/기존 아이템 변경을 열지 않습니다.
        if(e.getHand()!=EquipmentSlot.HAND)return;
        Player p=e.getPlayer();var session=players.current(p);
        if(session==null || !near(p))return;
        conversations.put(p.getUniqueId(),session);
        quests.open(session.context(),session,()->players.current(p)==session).whenCompleteAsync((q,error)->{
            if(players.current(p)!=session)return;
            if(error!=null){failure(p,error);return;}
            switch(q.saved().phase()) {
                case NOT_ACCEPTED -> p.sendMessage(Component.text(content.text().get("offer")).append(Component.newline()).append(Component.text(content.text().get("accept")).clickEvent(ClickEvent.runCommand("/hearthquest accept"))));
                case ACTIVE -> p.sendMessage(Component.text(content.text().get("active")));
                case REPORT_READY -> p.sendMessage(Component.text(content.text().get("report-ready")).append(Component.newline()).append(Component.text(content.text().get("report")).clickEvent(ClickEvent.runCommand("/hearthquest report"))));
                case COMPLETED -> p.sendMessage(Component.text(content.text().get("completed")));
            }
        },owner);
    }
    private boolean command(Player p,String command){
        if(!command.equals("/hearthquest accept") && !command.equals("/hearthquest report"))return false;
        var session=players.current(p);
        if(session==null || conversations.get(p.getUniqueId())!=session || !near(p))return true;
        act(p,session,command.endsWith("accept")?QuestStore.Phase.ACTIVE:QuestStore.Phase.COMPLETED);return true;
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void move(PlayerMoveEvent e){
        if(e instanceof PlayerTeleportEvent || e.getTo()==null)return;
        var p=e.getPlayer();var session=players.current(p);var to=e.getTo();
        if(session==null || !worldIds.contains(p.getWorld().getUID()) || !content.contains(to.getX(),to.getY(),to.getZ()))return;
        quests.open(session.context(),session,()->players.current(p)==session).thenAcceptAsync(q->{
            if(players.current(p)==session && !q.busy() && !q.held() && q.saved().phase()==QuestStore.Phase.ACTIVE)act(p,session,QuestStore.Phase.REPORT_READY);
        },owner).exceptionally(error->null);
    }
    private void act(Player p,PlayerSessions.Session session,QuestStore.Phase target){
        quests.open(session.context(),session,()->players.current(p)==session)
            .thenComposeAsync(q->quests.advance(q,target),owner).whenCompleteAsync((saved,error)->{
                if(players.current(p)!=session)return;
                if(error!=null){failure(p,error);return;}
                String key=switch(saved.phase()){case ACTIVE->"active";case REPORT_READY->"report-ready";case COMPLETED->"completed";default->throw new IllegalStateException();};
                p.sendMessage(Component.text(content.text().get(key)));
            },owner);
    }
    private void failure(Player p,Throwable error){p.sendMessage(Component.text(content.text().get("failure")));plugin.getLogger().warning("HEARTH_QUEST_ACTION_REJECTED");}
    @Override public void close(){closed=true;HandlerList.unregisterAll(this);players.questCommand((p,c)->false);conversations.clear();
        for(UUID id:worldIds){World w=Bukkit.getWorld(id);if(w!=null)w.getEntities().stream().filter(this::tagged).forEach(Entity::remove);}npcs.clear();}
}
