package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.session.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** 개발 모드 전용 슬롯 GUI/독립 BossBar. E키와 하단 인벤토리는 대체하지 않습니다. */
public final class PaperQuestUI implements Listener,AutoCloseable {
    public static final class Screen implements InventoryHolder {
        final UUID player;final Object session;final String page,quest;final int offset;final Map<Integer,QuestScreens.Button> buttons;final Inventory inventory;
        public Screen(UUID player,Object session,List<QuestScreens.Button> rows,String page,String quest,int offset){
            this.page=page;this.quest=quest;this.offset=offset;
            this.player=player;this.session=session;var map=new HashMap<Integer,QuestScreens.Button>();rows.forEach(b->map.put(b.slot(),b));buttons=Map.copyOf(map);
            inventory=Bukkit.createInventory(this,54,Component.text("Hearth 개발 스마트폰"));
            for(var b:rows){var item=new ItemStack(Material.PAPER);var meta=item.getItemMeta();meta.displayName(Component.text(b.label()));item.setItemMeta(meta);inventory.setItem(b.slot(),item);}
        }
        @Override public Inventory getInventory(){return inventory;}
    }
    private final JavaPlugin plugin;private final PaperSessionAdapter players;private final PersonalQuest quests;private final QuestCatalog catalog;private final QuestJournal journal;private final Executor owner;
    private final Map<UUID,List<BossBar>> bars=new HashMap<>();private final Map<UUID,Object> attempted=new HashMap<>();private final Set<UUID> dirty=new HashSet<>();
    private final Map<UUID,Long> requests=new HashMap<>();private final BukkitTask task;private boolean closed;private long requestId;
    public PaperQuestUI(JavaPlugin plugin,PaperSessionAdapter players,PersonalQuest quests,QuestCatalog catalog,QuestJournal journal,Executor owner){
        this.plugin=plugin;this.players=players;this.quests=quests;this.catalog=catalog;this.journal=journal;this.owner=owner;
        Bukkit.getPluginManager().registerEvents(this,plugin);players.developerInventory(this::allowed);
        quests.changed(ctx->dirty.add(ctx.playerId()));journal.changed(ctx->dirty.add(ctx.playerId()));
        task=Bukkit.getScheduler().runTaskTimer(plugin,()->{for(Player p:Bukkit.getOnlinePlayers()){
            var s=players.current(p);if(s==null){clearBars(p);continue;}
            if(!requests.containsKey(p.getUniqueId()) && (attempted.get(p.getUniqueId())!=s || dirty.remove(p.getUniqueId()))){attempted.put(p.getUniqueId(),s);load(p,s,v->{if(p.getOpenInventory().getTopInventory().getHolder() instanceof Screen screen && screen.session==s)open(p,s,v,screen.page,screen.quest,screen.offset);});}
        }},20,20);
    }
    public boolean allowed(Player p,Inventory inventory){return inventory.getHolder() instanceof Screen s && s.player.equals(p.getUniqueId()) && players.current(p)==s.session && !closed;}
    private void clearBars(Player p){var old=bars.remove(p.getUniqueId());if(old!=null)old.forEach(p::hideBossBar);}
    public static List<BossBar> hudBars(List<String> lines){return lines.stream().map(line->BossBar.bossBar(Component.text(line),0,BossBar.Color.WHITE,BossBar.Overlay.PROGRESS)).toList();}
    private void showHud(Player p,QuestJournal.View view){clearBars(p);var list=hudBars(journal.hudLines(view));list.forEach(p::showBossBar);bars.put(p.getUniqueId(),list);}
    private void error(Player p){clearBars(p);p.sendMessage(Component.text("개인 퀘스트 정보를 처리할 수 없습니다."));}
    private void load(Player p,PlayerSessions.Session session,Consumer<QuestJournal.View> show){request(p,session,journal.load(session.context(),session,()->players.current(p)==session),show);}
    private void request(Player p,PlayerSessions.Session session,CompletionStage<QuestJournal.View> work,Consumer<QuestJournal.View> show){
        long id=++requestId;requests.put(p.getUniqueId(),id);
        work.whenCompleteAsync((view,failure)->{
            if(!Objects.equals(requests.get(p.getUniqueId()),id))return;
            requests.remove(p.getUniqueId());if(closed || players.current(p)!=session)return;if(failure!=null){error(p);return;}
            try{quests.requireListDisplay(session.context(),view.states());showHud(p,view);show.accept(view);}catch(RuntimeException e){error(p);}
        },owner);
    }
    private void open(Player p,PlayerSessions.Session s,QuestJournal.View view,String page,String quest,int offset){
        if(players.current(p)!=s || !p.getItemOnCursor().isEmpty())throw new IllegalStateException("GUI unavailable");
        p.openInventory(new Screen(p.getUniqueId(),s,QuestScreens.screen(catalog,view,page,quest,Math.max(0,offset)),page,quest,Math.max(0,offset)).inventory);
    }
    public boolean command(Player p,String command){
        var s=players.current(p);if(s==null)return false;
        if(command.equals("/hearthphone")){load(p,s,v->open(p,s,v,"home",null,0));return true;}
        String[] args=command.split(" ",-1);
        if(args.length==2 && args[0].equals("/hearthquest") && args[1].equals("tracked")){
            load(p,s,v->{var lines=journal.hudLines(new QuestJournal.View(v.states(),new io.github.jesterportfolio.hearth.persistence.QuestJournalStore.Journal(v.journal().tracked(),new io.github.jesterportfolio.hearth.persistence.QuestJournalStore.Preferences(true,0),v.journal().notices())));if(lines.isEmpty())p.sendMessage(Component.text("추적 중인 퀘스트가 없습니다."));else lines.forEach(x->p.sendMessage(Component.text(x)));});return true;
        }
        if(args.length==3 && args[0].equals("/hearthquest") && Set.of("track","untrack").contains(args[1]) && args[2].matches("[a-z][a-z0-9_]{0,63}")){
            request(p,s,journal.track(s.context(),s,()->players.current(p)==s,args[2],args[1].equals("track")),v->p.sendMessage(Component.text("퀘스트 추적 설정을 저장했습니다.")));return true;
        }return false;
    }
    @EventHandler(priority=EventPriority.MONITOR) public void click(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p) || !(e.getView().getTopInventory().getHolder() instanceof Screen screen))return;
        e.setCancelled(true);if(e.getClickedInventory()!=screen.inventory || e.getClick()!=ClickType.LEFT || !allowed(p,screen.inventory))return;
        var button=screen.buttons.get(e.getRawSlot());if(button==null || button.action().equals("none"))return;var s=players.current(p);
        Bukkit.getScheduler().runTaskLater(plugin,()->{
            if(players.current(p)!=s || p.getOpenInventory().getTopInventory()!=screen.inventory)return;
            String action=button.action();
            if(action.equals("track"))request(p,s,journal.track(s.context(),s,()->players.current(p)==s,button.quest(),button.revision()==1),v->open(p,s,v,"detail",button.quest(),0));
            else if(action.equals("hud"))request(p,s,journal.hud(s.context(),s,()->players.current(p)==s,button.revision()==1),v->open(p,s,v,"settings",null,0));
            else if(action.equals("read-all"))request(p,s,journal.readAll(s.context(),s,()->players.current(p)==s),v->open(p,s,v,"notices",null,0));
            else if(action.equals("notice"))request(p,s,journal.readNotice(s.context(),s,()->players.current(p)==s,button.quest(),button.revision()),v->open(p,s,v,"detail",button.quest(),0));
            else load(p,s,v->open(p,s,v,action,button.quest(),(int)button.revision()));
        },1);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Screen)e.setCancelled(true);}
    @EventHandler public void closed(InventoryCloseEvent e){if(e.getInventory().getHolder() instanceof Screen)requests.remove(e.getPlayer().getUniqueId());}
    @EventHandler public void quit(PlayerQuitEvent e){clearBars(e.getPlayer());requests.remove(e.getPlayer().getUniqueId());attempted.remove(e.getPlayer().getUniqueId());dirty.remove(e.getPlayer().getUniqueId());}
    @Override public void close(){closed=true;task.cancel();HandlerList.unregisterAll(this);players.developerInventory((p,i)->false);quests.changed(c->{});journal.changed(c->{});for(Player p:Bukkit.getOnlinePlayers()){clearBars(p);if(p.getOpenInventory().getTopInventory().getHolder() instanceof Screen)p.closeInventory();}requests.clear();attempted.clear();dirty.clear();}
}
