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

/** 초기 검증한 콘텐츠만 사용하는 격리 어댑터. 일반 서버에는 활성화하지 않습니다. */
public final class PaperQuestAdapter implements Listener,AutoCloseable {
    private final JavaPlugin plugin;
    private final PaperSessionAdapter players;
    private final PersonalQuest quests;
    private final QuestCatalog catalog;
    private final QuestDialogue dialogues;
    private final Executor owner;
    private final Set<UUID> worldIds;
    private final NamespacedKey tag;
    // World/Entity 객체 대신 물리 식별자만 보관합니다. 개인 저장 소유권은 논리 진행 키입니다.
    private final Map<UUID,Map<String,UUID>> npcs=new HashMap<>();
    private boolean closed;
    public PaperQuestAdapter(JavaPlugin plugin,PaperSessionAdapter players,PersonalQuest quests,QuestCatalog catalog,Executor owner,Set<UUID> worldIds) {
        this.plugin=plugin;this.players=players;this.quests=quests;this.catalog=catalog;dialogues=new QuestDialogue(catalog);
        this.owner=owner;this.worldIds=Set.copyOf(worldIds);tag=new NamespacedKey(plugin,"quest_demo_npc");
        Bukkit.getPluginManager().registerEvents(this,plugin);players.questCommand(this::command);
        for(World w:Bukkit.getWorlds())ensure(w);
    }
    public void ensure(World w) {
        if(closed || !worldIds.contains(w.getUID()))return;
        for(QuestContent content:catalog.npcs())dialogueRegion(w,content);
        var tracked=npcs.computeIfAbsent(w.getUID(),id->new HashMap<>());
        for(QuestContent content:catalog.npcs()) {
            w.getChunkAt(chunk(content.x()),chunk(content.z()));
            List<Entity> existing=w.getEntities().stream().filter(e->content.npcId().equals(npcId(e))).toList();
            UUID current=tracked.get(content.npcId());
            if(current!=null && existing.size()==1 && existing.getFirst().getUniqueId().equals(current))continue;
            existing.forEach(Entity::remove);
            var npc=w.spawn(new Location(w,content.x(),content.y(),content.z()),Villager.class,v->{
                v.setAI(false);v.setGravity(false);v.setInvulnerable(true);v.setSilent(true);v.setCollidable(false);
                v.setPersistent(false);v.customName(Component.text(content.name()));v.setCustomNameVisible(true);
                v.getPersistentDataContainer().set(tag,PersistentDataType.STRING,content.npcId());
            });
            tracked.put(content.npcId(),npc.getUniqueId());
        }
    }
    private static int chunk(double coordinate){return (int)Math.floor(coordinate)>>4;}
    private static String regionId(QuestContent c){return c.npcId().equals("survey_guide")?"hearth_demo_dialogue":"hearth_demo_dialogue_"+c.npcId();}
    private void dialogueRegion(World w,QuestContent c) {
        var manager=Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(w)),"Missing WG manager");
        var point=BlockVector3.at(Math.floor(c.x()),Math.floor(c.y()),Math.floor(c.z()));var top=point.add(0,1,0);
        var existing=manager.getRegion(regionId(c));
        if(existing==null) {
            var region=new ProtectedCuboidRegion(regionId(c),point,top);region.setPriority(30);
            region.setFlag(Flags.PASSTHROUGH,StateFlag.State.ALLOW);region.setFlag(Flags.INTERACT,StateFlag.State.ALLOW);manager.addRegion(region);
        } else if(!existing.getMinimumPoint().equals(point) || !existing.getMaximumPoint().equals(top)
                || existing.getPriority()!=30 || existing.getFlag(Flags.INTERACT)!=StateFlag.State.ALLOW
                || existing.getFlag(Flags.PASSTHROUGH)!=StateFlag.State.ALLOW)
            throw new IllegalStateException("Existing dialogue region mismatch; preserve original");
    }
    public boolean dialogueAllowed(World w,QuestContent c) {
        var manager=Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(w)));
        return manager.getApplicableRegions(BlockVector3.at(c.x(),c.y(),c.z())).queryState(null,Flags.INTERACT)==StateFlag.State.ALLOW;
    }
    @EventHandler public void chunkLoad(ChunkLoadEvent e){
        if(!worldIds.contains(e.getWorld().getUID()) || catalog.all().stream().noneMatch(c->e.getChunk().getX()==chunk(c.x()) && e.getChunk().getZ()==chunk(c.z())))return;
        UUID id=e.getWorld().getUID();int x=e.getChunk().getX(),z=e.getChunk().getZ();
        Bukkit.getScheduler().runTaskLater(plugin,()->{World w=Bukkit.getWorld(id);if(w!=null && w.isChunkLoaded(x,z))ensure(w);},1);
    }
    private String npcId(Entity e){return e.getPersistentDataContainer().get(tag,PersistentDataType.STRING);}
    public int count(World w,String npc){return (int)w.getEntities().stream().filter(e->npc.equals(npcId(e))).count();}
    public boolean tracks(UUID world){return npcs.containsKey(world);}
    private Entity entity(World w,String npc){var map=npcs.get(w.getUID());UUID id=map==null?null:map.get(npc);return id==null?null:w.getEntity(id);}
    private boolean near(Player p,String npc,UUID id){Entity e=entity(p.getWorld(),npc);return e!=null && e.isValid() && e.getUniqueId().equals(id) && p.getLocation().distanceSquared(e.getLocation())<=16;}
    private boolean current(Player p,PlayerSessions.Session session,QuestDialogue.Ticket t){return dialogues.current(t) && players.current(p)==session && p.getWorld().getUID().equals(t.world()) && near(p,t.npc(),t.entity());}
    @EventHandler public void load(WorldLoadEvent e){UUID id=e.getWorld().getUID();Bukkit.getScheduler().runTaskLater(plugin,()->{World w=Bukkit.getWorld(id);if(w!=null)ensure(w);},1);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void unload(WorldUnloadEvent e){npcs.remove(e.getWorld().getUID());dialogues.clearWorld(e.getWorld().getUID());}
    @EventHandler public void quit(PlayerQuitEvent e){dialogues.clear(e.getPlayer().getUniqueId());}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void click(PlayerInteractAtEntityEvent e){
        String npc=npcId(e.getRightClicked());if(npc==null)return;
        e.setCancelled(true);if(e.getHand()!=EquipmentSlot.HAND)return;
        Player p=e.getPlayer();var session=players.current(p);UUID entity=e.getRightClicked().getUniqueId();
        if(session==null || !near(p,npc,entity))return;
        for(Component message:selectionMessages(catalog,dialogues,dialogues.options(p.getUniqueId(),session,session.context().storyRunId(),p.getWorld().getUID(),entity,npc)))p.sendMessage(message);
    }
    public static List<Component> selectionMessages(QuestCatalog catalog,QuestDialogue dialogues,List<QuestDialogue.Ticket> tickets){
        return tickets.stream().map(t->(Component)Component.text("["+catalog.quest(t.quest()).title()+"]").clickEvent(ClickEvent.runCommand(dialogues.command(t,"select")))).toList();
    }
    /** 인증된 선택의 현재 상태만 표시합니다. 선택용 nonce를 수락/보고에 재사용하지 않습니다. */
    public static List<Component> dialogueMessages(QuestContent c,QuestStore.Saved saved,QuestDialogue dialogues,QuestDialogue.Ticket selected){
        if(!dialogues.current(selected) || !c.id().equals(selected.quest()))throw new IllegalArgumentException("Stale dialogue response");
        String text=switch(saved.phase()){case NOT_ACCEPTED->"offer";case ACTIVE->"active";case REPORT_READY->"report-ready";case COMPLETED->"completed";};
        Component message=Component.text(c.text().get(text));
        if(saved.phase()==QuestStore.Phase.NOT_ACCEPTED || saved.phase()==QuestStore.Phase.REPORT_READY){
            String action=saved.phase()==QuestStore.Phase.NOT_ACCEPTED?"accept":"report";
            var next=dialogues.action(selected,action);
            message=message.append(Component.newline()).append(Component.text(c.text().get(action)).clickEvent(ClickEvent.runCommand(dialogues.command(next,action))));
        }
        return List.of(message);
    }
    private boolean command(Player p,String command){
        if(!command.startsWith("/hearthquest"))return false;
        var session=players.current(p);if(session==null)return true;
        if(command.equals("/hearthquest list")){read(p,session,null);return true;}
        if(command.equals("/hearthquest info") || command.startsWith("/hearthquest info ")) {
            var id=QuestListView.infoId(command);
            if(id.isEmpty())p.sendMessage(Component.text(QuestListView.UNAVAILABLE));else read(p,session,id.get());
            return true;
        }
        final QuestDialogue.Request request;final QuestDialogue.Ticket ticket;
        try {
            request=QuestDialogue.Request.parse(command);
            Entity e=entity(p.getWorld(),request.npc());
            if(e==null || !near(p,request.npc(),e.getUniqueId()))throw new IllegalArgumentException("NPC unavailable");
            ticket=dialogues.authorize(p.getUniqueId(),session,session.context().storyRunId(),p.getWorld().getUID(),e.getUniqueId(),request);
        } catch(IllegalArgumentException error){plugin.getLogger().info("HEARTH_QUEST_REQUEST_REJECTED");return true;}
        QuestContent content=catalog.quest(ticket.quest());
        if(request.action().equals("select")) {
            quests.open(session.context(),content.id(),session,()->players.current(p)==session).whenCompleteAsync((q,error)->{
                if(!current(p,session,ticket))return;
                if(error!=null){failure(p,content);return;}
                try {for(Component message:dialogueMessages(content,quests.requireDialogue(q),dialogues,ticket))p.sendMessage(message);}
                catch(RuntimeException invalid){failure(p,content);}
            },owner);
        } else quests.open(session.context(),content.id(),session,()->players.current(p)==session)
            .thenComposeAsync(q->{if(!current(p,session,ticket))throw new IllegalStateException("STALE_DIALOGUE");return quests.advance(q,request.action().equals("accept")?QuestStore.Phase.ACTIVE:QuestStore.Phase.COMPLETED);},owner)
            .whenCompleteAsync((saved,error)->{
                if(!current(p,session,ticket))return;
                if(error!=null){failure(p,content);return;}
                p.sendMessage(Component.text(content.text().get(saved.phase()==QuestStore.Phase.ACTIVE?"active":"completed")));
            },owner);
        return true;
    }
    public static List<Component> listMessages(QuestCatalog catalog,Map<String,QuestStore.Saved> states) {
        var rows=QuestListView.entries(catalog,states);
        if(rows.isEmpty())return List.of(Component.text(QuestListView.lines(rows).getFirst()));
        return rows.stream().map(row->(Component)Component.text("["+row.status()+"] "+row.title()).clickEvent(ClickEvent.runCommand(QuestListView.infoCommand(row)))).toList();
    }
    public static List<Component> infoMessages(QuestCatalog catalog,Map<String,QuestStore.Saved> states,String id) {
        return QuestListView.detailLines(QuestListView.detail(catalog,states,id)).stream().map(line->(Component)Component.text(line)).toList();
    }
    private void read(Player p,PlayerSessions.Session session,String questId) {
        quests.list(session.context(),session,()->players.current(p)==session).whenCompleteAsync((states,error)->{
            if(players.current(p)!=session)return;
            if(error!=null){p.sendMessage(Component.text("퀘스트 정보를 확인할 수 없습니다. 저장 처리 또는 오류 상태를 확인해 주세요."));plugin.getLogger().info("HEARTH_QUEST_READ_REJECTED");return;}
            try {quests.requireListDisplay(session.context(),states);for(Component message:questId==null?listMessages(catalog,states):infoMessages(catalog,states,questId))p.sendMessage(message);}
            catch(RuntimeException failure){p.sendMessage(Component.text("퀘스트 정보를 확인할 수 없습니다."));plugin.getLogger().warning("HEARTH_QUEST_READ_INVALID_RESPONSE");}
        },owner);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void move(PlayerMoveEvent e){
        if(e instanceof PlayerTeleportEvent || e.getTo()==null)return;
        var p=e.getPlayer();var session=players.current(p);var to=e.getTo();
        if(session==null || !worldIds.contains(p.getWorld().getUID()))return;
        for(QuestContent c:catalog.all())if(c.contains(to.getX(),to.getY(),to.getZ())) {
            quests.open(session.context(),c.id(),session,()->players.current(p)==session).thenComposeAsync(q->{
                if(players.current(p)==session && !q.busy() && !q.held() && q.saved().phase()==QuestStore.Phase.ACTIVE)
                    return quests.reach(q,c,to.getX(),to.getY(),to.getZ()).thenAcceptAsync(saved->{if(players.current(p)==session)p.sendMessage(Component.text(c.text().get(saved.phase()==QuestStore.Phase.COMPLETED?"completed":"report-ready")));},owner);
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            },owner).exceptionally(error->null);
        }
    }
    private void failure(Player p,QuestContent content){p.sendMessage(Component.text(content.text().get("failure")));plugin.getLogger().warning("HEARTH_QUEST_ACTION_REJECTED");}
    @Override public void close(){closed=true;HandlerList.unregisterAll(this);players.questCommand((p,c)->false);dialogues.clear();
        for(UUID id:worldIds){World w=Bukkit.getWorld(id);if(w!=null)w.getEntities().stream().filter(e->npcId(e)!=null).forEach(Entity::remove);}npcs.clear();}
}
