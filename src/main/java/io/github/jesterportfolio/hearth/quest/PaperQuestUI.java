package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.session.*;
import io.github.jesterportfolio.hearth.exploration.*;
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
        final BooleanSupplier dialogueValid;final UUID player;final Object session;final String page,quest;final int offset;final Map<Integer,QuestScreens.Button> buttons;final Inventory inventory;
        public Screen(UUID player,Object session,List<QuestScreens.Button> rows,String page,String quest,int offset){this(player,session,rows,page,quest,offset,()->true);}
        public Screen(UUID player,Object session,List<QuestScreens.Button> rows,String page,String quest,int offset,BooleanSupplier dialogueValid){
            this.dialogueValid=dialogueValid;
            this.page=page;this.quest=quest;this.offset=offset;
            this.player=player;this.session=session;var map=new HashMap<Integer,QuestScreens.Button>();rows.forEach(b->map.put(b.slot(),b));buttons=Map.copyOf(map);
            inventory=Bukkit.createInventory(this,54,Component.text("Hearth 개발 스마트폰"));
            for(var b:rows){var item=new ItemStack(Set.of("detail","book","npc-select").contains(b.action())?Material.BOOK:Material.PAPER);var meta=item.getItemMeta();meta.displayName(Component.text(b.label()));if(Set.of("detail","book","npc-select").contains(b.action()))meta.lore(List.of(Component.text("클릭하여 상세 내용을 책으로 읽기")));item.setItemMeta(meta);inventory.setItem(b.slot(),item);}
        }
        @Override public Inventory getInventory(){return inventory;}
    }
    private Consumer<Player> storyGuide=p->{};
    private Predicate<String> storyNpc=npc->false;
    public void storyNpcs(Predicate<String> registered){storyNpc=registered;}
    private Playthrough playthrough;
    private io.github.jesterportfolio.hearth.story.SharedStory sharedStory;
    public void playthrough(Playthrough content,io.github.jesterportfolio.hearth.story.SharedStory story){playthrough=content;sharedStory=story;}
    public void journey(Player p,BooleanSupplier current,List<Component> back){var session=players.current(p);if(session!=null)journey(p,session,null,current,back);}
    private void journey(Player p,PlayerSessions.Session session,String talk){journey(p,session,talk,()->true,List.of());}
    private QuestJourneyView.Page journeyView(PlayerSessions.Session session,QuestJournal.View view){return QuestJourneyView.build(playthrough,catalog,view,sharedStory.cached(session.context().storyRunId()),sharedStory.phase(session.context().storyRunId()));}
    private void journey(Player p,PlayerSessions.Session session,String talk,BooleanSupplier current,List<Component> back){
        if(playthrough==null||sharedStory==null){p.sendMessage(Component.text("현재 콘텐츠에 플레이 안내가 등록되지 않았습니다."));return;}
        if(requests.containsKey(p.getUniqueId())){p.sendMessage(Component.text("기존 조회를 처리 중입니다."));return;}
        load(p,session,view->{
            if(!current.getAsBoolean())return;var page=journeyView(session,view);var guide=page.guide();
            if(talk!=null){if(!talk.equals(guide.talkQuest()))throw new IllegalArgumentException("STALE_JOURNEY_NPC");npcDialogue.accept(p,talk);return;}
            var actions=new ArrayList<Component>();for(var link:page.links())actions.add(Component.text(link.label()).clickEvent(net.kyori.adventure.text.event.ClickEvent.callback(audience->{
                if(!(audience instanceof Player who)||!who.getUniqueId().equals(p.getUniqueId()))return;
                Bukkit.getScheduler().runTaskLater(plugin,()->{if(players.current(p)!=session||!current.getAsBoolean())return;
                    load(p,session,fresh->{if(!current.getAsBoolean())return;QuestJourneyView.require(journeyView(session,fresh),page,link);journeyAction(p,session,fresh,link,current,back);});
                },1);
            },net.kyori.adventure.text.event.ClickCallback.Options.builder().uses(1).lifetime(java.time.Duration.ofMinutes(2)).build())));
            actions.addAll(back);actions.add(Component.text("안내 새로고침").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/hearthjourney")));
            showBook(p,session,new QuestBookView(guide.title(),QuestBookView.paginate(guide.lines())),actions);
        });
    }
    private void journeyAction(Player p,PlayerSessions.Session session,QuestJournal.View view,QuestJourneyView.Link link,BooleanSupplier current,List<Component> back){
        switch(link.action()){
            case DETAIL->acceptedBook(p,session,view,link.quest());
            case TALK->npcDialogue.accept(p,link.quest());
            case RETRY->questAction.accept(p,"/hearthquest retry "+link.quest());
            case TRACK,UNTRACK->request(p,session,journal.track(session.context(),session,()->players.current(p)==session&&current.getAsBoolean(),link.quest(),link.action()==QuestJourneyView.Action.TRACK),saved->{if(current.getAsBoolean())journey(p,session,null,current,back);});
            case SHARED->storyGuide.accept(p);
            case MAP,DIARY,NOTICES,REWARDS->open(p,session,view,link.action().name().toLowerCase(java.util.Locale.ROOT),null,0);
        }
    }
    @FunctionalInterface public interface NpcSelection{void select(Player player,String npc,String quest,Long revision);}
    private NpcSelection npcSelection=(p,n,q,v)->{};
    public void npcSelection(NpcSelection select){npcSelection=select;}
    @FunctionalInterface public interface NpcNavigation{void open(Player player,String npc,String action,String topic);}
    private NpcNavigation npcNavigation=(p,n,a,t)->{};
    public void npcNavigation(NpcNavigation handler){npcNavigation=handler;}
    public void openNpc(Player p,PlayerSessions.Session session,String npc,Map<String,io.github.jesterportfolio.hearth.persistence.QuestStore.Saved> rows){openNpc(p,session,npc,rows,"npc");}
    public void openNpc(Player p,PlayerSessions.Session session,String npc,Map<String,io.github.jesterportfolio.hearth.persistence.QuestStore.Saved> rows,String page){openNpc(p,session,npc,rows,page,()->true);}
    public void openNpc(Player p,PlayerSessions.Session session,String npc,Map<String,io.github.jesterportfolio.hearth.persistence.QuestStore.Saved> rows,String page,BooleanSupplier valid){
        if(closed||players.current(p)!=session||!p.getItemOnCursor().isEmpty())throw new IllegalStateException("GUI unavailable");
        quests.requireListDisplay(session.context(),rows);
        var buttons=page.equals("npc-topics")?QuestScreens.npcTopics(catalog,npc,rows,c->journal.questAllowed(session.context(),c.id()),0):QuestScreens.npc(catalog,npc,rows,c->journal.questAllowed(session.context(),c.id()),0);
        if(!valid.getAsBoolean())throw new IllegalStateException("STALE_NPC_MENU");
        if(page.equals("npc")){buttons=QuestScreens.npcGuide(buttons,storyNpc.test(npc));buttons=QuestScreens.npcJourney(buttons,playthrough!=null);}
        p.openInventory(new Screen(p.getUniqueId(),session,buttons,page,npc,0,valid).inventory);
    }
    public static net.kyori.adventure.inventory.Book book(QuestBookView view,List<Component> actions){
        var pages=new ArrayList<Component>();view.pages().forEach(page->pages.add(Component.text(page)));
        for(int start=0;start<actions.size();start+=3){Component page=Component.text("대화 및 진행\n\n");for(var action:actions.subList(start,Math.min(actions.size(),start+3)))page=page.append(action).append(Component.newline()).append(Component.newline());pages.add(page);}
        if(pages.size()>100)throw new IllegalArgumentException("QUEST_BOOK_TOO_LONG");
        return net.kyori.adventure.inventory.Book.book(Component.text(view.title()),Component.text("Hearth"),pages);
    }
    public void showBook(Player p,PlayerSessions.Session session,QuestBookView view,List<Component> actions){
        if(closed||players.current(p)!=session||!p.getItemOnCursor().isEmpty())throw new IllegalStateException("STALE_QUEST_BOOK");
        p.closeInventory();p.openBook(book(view,actions));
    }
    private void acceptedBook(Player p,PlayerSessions.Session session,QuestJournal.View view,String id){
        journal.requireView(session.context(),view);
        var detail=QuestBookView.accepted(catalog,view.states(),view.exploration().blockedQuests(),id);
        if(detail.isEmpty()){p.sendMessage(Component.text(QuestListView.UNAVAILABLE));return;}
        showBook(p,session,detail.get(),List.of(Component.text("[진행 관리]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/hearthquest manage "+id)),Component.text("[퀘스트 목록]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/hearthquest list"))));
    }
    private java.util.function.BiConsumer<Player,String> questAction=(p,c)->{};
    public void questAction(java.util.function.BiConsumer<Player,String> handler){questAction=handler;}
    private java.util.function.BiConsumer<Player,String> npcContact=(p,id)->{};
    public void npcContact(java.util.function.BiConsumer<Player,String> open){npcContact=open;}
    private java.util.function.BiConsumer<Player,String> npcDialogue=(p,id)->{};
    public void npcDialogue(java.util.function.BiConsumer<Player,String> open){npcDialogue=open;}
    private BiConsumer<Player,QuestJournal.View> projection=(p,v)->{};
    private Consumer<Player> clearProjection=p->{};
    private Predicate<Player> projectionTick=p->true;
    private Predicate<Player> dialogueBusy=p->false;
    public void dialogueBusy(Predicate<Player> busy){dialogueBusy=busy;}
    public void projection(BiConsumer<Player,QuestJournal.View> show,Consumer<Player> clear,Predicate<Player> tick){projection=show;clearProjection=clear;projectionTick=tick;}
    public void storyGuide(Consumer<Player> guide){storyGuide=guide;}
    private void dirtyRun(UUID run){for(Player player:Bukkit.getOnlinePlayers()){var session=players.prepared(player);if(session!=null&&session.context().storyRunId().equals(run))dirty.add(player.getUniqueId());}}
    private final JavaPlugin plugin;private final PaperSessionAdapter players;private final PersonalQuest quests;private final QuestCatalog catalog;private final QuestJournal journal;private final Executor owner;
    private final Map<UUID,List<BossBar>> bars=new HashMap<>();private final Map<UUID,Object> attempted=new HashMap<>();private final Set<UUID> dirty=new HashSet<>();
    private final Set<UUID> hudOnly=new HashSet<>();
    private final Map<UUID,Long> requests=new HashMap<>();private final BukkitTask task;private boolean closed;private long requestId;
    public PaperQuestUI(JavaPlugin plugin,PaperSessionAdapter players,PersonalQuest quests,QuestCatalog catalog,QuestJournal journal,Executor owner){
        this.plugin=plugin;this.players=players;this.quests=quests;this.catalog=catalog;this.journal=journal;this.owner=owner;
        Bukkit.getPluginManager().registerEvents(this,plugin);players.developerInventory(this::allowed);
        quests.changed(ctx->dirtyRun(ctx.storyRunId()));journal.changed(ctx->dirty.add(ctx.playerId()));journal.sharedChanged(this::dirtyRun);
        task=Bukkit.getScheduler().runTaskTimer(plugin,()->{for(Player p:Bukkit.getOnlinePlayers()){
            if(dialogueBusy.test(p))continue;
            var s=players.current(p);if(s==null){
                clearProjection.accept(p);var prepared=players.prepared(p);
                if(prepared==null){clearBars(p);hudOnly.remove(p.getUniqueId());continue;}
                if(hudOnly.add(p.getUniqueId()))dirty.add(p.getUniqueId());
                if(!requests.containsKey(p.getUniqueId())&&(attempted.get(p.getUniqueId())!=prepared||dirty.remove(p.getUniqueId()))){attempted.put(p.getUniqueId(),prepared);readOnlyHud(p,prepared);}
                continue;
            }
            if(hudOnly.remove(p.getUniqueId()))dirty.add(p.getUniqueId());
            if(!projectionTick.test(p))dirty.add(p.getUniqueId());
            if(!requests.containsKey(p.getUniqueId()) && (attempted.get(p.getUniqueId())!=s || dirty.remove(p.getUniqueId()))){attempted.put(p.getUniqueId(),s);load(p,s,v->{if(p.getOpenInventory().getTopInventory().getHolder() instanceof Screen screen && screen.session==s)open(p,s,v,screen.page,screen.quest,screen.offset);});}
        }},20,20);
    }
    public boolean allowed(Player p,Inventory inventory){return inventory.getHolder() instanceof Screen s && s.player.equals(p.getUniqueId()) && players.current(p)==s.session && s.dialogueValid.getAsBoolean() && !closed;}
    private void clearBars(Player p){var old=bars.remove(p.getUniqueId());if(old!=null)old.forEach(p::hideBossBar);}
    public static List<BossBar> hudBars(List<String> lines){return lines.stream().map(line->BossBar.bossBar(Component.text(line),0,BossBar.Color.WHITE,BossBar.Overlay.PROGRESS)).toList();}
    private void showHud(Player p,QuestJournal.View view){clearBars(p);var list=hudBars(journal.hudLines(view));list.forEach(p::showBossBar);bars.put(p.getUniqueId(),list);}
    /** 사망/복귀 중에는 연결 검증을 통과한 표시 조회만 허용합니다. GUI/대화/진행 복원은 호출하지 않습니다. */
    private void readOnlyHud(Player p,PlayerSessions.Session session){
        clearBars(p);long id=++requestId;requests.put(p.getUniqueId(),id);
        quests.barrier(session.context().storyRunId()).thenComposeAsync(v->{
            if(closed||players.prepared(p)!=session)throw new IllegalStateException("STALE_HUD_SESSION");
            return journal.load(session.context(),session,()->players.prepared(p)==session);
        },owner).whenCompleteAsync((view,error)->{
            if(!Objects.equals(requests.get(p.getUniqueId()),id))return;
            requests.remove(p.getUniqueId());if(closed||players.prepared(p)!=session)return;
            if(error!=null){clearBars(p);Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();
                if(cause instanceof IllegalStateException&&Set.of("JOURNAL_BUSY","QUEST_LIST_BUSY").contains(Objects.toString(cause.getMessage(),"")))dirty.add(p.getUniqueId());return;
            }
            try{journal.requireView(session.context(),view);showHud(p,view);}catch(RuntimeException rejected){clearBars(p);dirty.add(p.getUniqueId());}
            if(players.current(p)==session)dirty.add(p.getUniqueId());
        },owner);
    }
    private void error(Player p){clearBars(p);clearProjection.accept(p);p.sendMessage(Component.text("개인 퀘스트 정보를 처리할 수 없습니다."));}
    private void load(Player p,PlayerSessions.Session session,Consumer<QuestJournal.View> show){request(p,session,journal.load(session.context(),session,()->players.current(p)==session),show);}
    private void request(Player p,PlayerSessions.Session session,CompletionStage<QuestJournal.View> work,Consumer<QuestJournal.View> show){
        long id=++requestId;requests.put(p.getUniqueId(),id);
        work.whenCompleteAsync((view,failure)->{
            if(!Objects.equals(requests.get(p.getUniqueId()),id))return;
            requests.remove(p.getUniqueId());if(closed)return;if(dialogueBusy.test(p)){dirty.add(p.getUniqueId());return;}if(players.current(p)!=session){if(players.prepared(p)==session)dirty.add(p.getUniqueId());return;}if(failure!=null){Throwable cause=failure;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();if(cause instanceof IllegalStateException&&Set.of("JOURNAL_BUSY","QUEST_LIST_BUSY").contains(Objects.toString(cause.getMessage(),""))){dirty.add(p.getUniqueId());return;}error(p);return;}
            try{journal.requireView(session.context(),view);showHud(p,view);projection.accept(p,view);show.accept(view);}catch(RuntimeException e){error(p);}
        },owner);
    }
    private void open(Player p,PlayerSessions.Session s,QuestJournal.View view,String page,String quest,int offset){
        if(players.current(p)!=s || !p.getItemOnCursor().isEmpty())throw new IllegalStateException("GUI unavailable");
        BooleanSupplier valid=()->true;
        if(Set.of("npc","npc-topics").contains(page)){
            if(!(p.getOpenInventory().getTopInventory().getHolder() instanceof Screen previous)||!previous.dialogueValid.getAsBoolean())return;
            valid=previous.dialogueValid;
        }
        var buttons=QuestScreens.screen(catalog,view,page,quest,Math.max(0,offset));if(page.equals("npc")){buttons=QuestScreens.npcGuide(buttons,storyNpc.test(quest));buttons=QuestScreens.npcJourney(buttons,playthrough!=null);}
        p.openInventory(new Screen(p.getUniqueId(),s,buttons,page,quest,Math.max(0,offset),valid).inventory);
    }
    public boolean command(Player p,String command){
        var s=players.current(p);if(s==null)return false;
        if(command.equals("/hearthjourney")){journey(p,s,null);return true;}
        if(command.startsWith("/hearthjourney ")){var args=command.split(" ",-1);if(args.length==3&&args[1].equals("talk")&&args[2].matches("[a-z][a-z0-9_]{0,63}"))journey(p,s,args[2]);else p.sendMessage(Component.text("사용법: /hearthjourney"));return true;}
        if(command.equals("/hearthinfo")||command.startsWith("/hearthinfo ")){
            var query=GameInformation.Query.parse(command);
            if(query.isEmpty()){p.sendMessage(Component.text("사용법: /hearthinfo summary|quests|map|diary|story [페이지]"));return true;}
            if(requests.containsKey(p.getUniqueId())){p.sendMessage(Component.text("이전 정보 조회를 처리 중입니다."));return true;}
            load(p,s,v->journal.projectInformation(s.context(),v,()->players.current(p)==s).lines(query.get()).forEach(line->p.sendMessage(Component.text(line))));return true;
        }
        if(command.equals("/hearthquest list")){load(p,s,v->open(p,s,v,"quests",null,0));return true;}
        if(command.startsWith("/hearthquest info ")||command.startsWith("/hearthquest manage ")){
            String[] parts=command.split(" ",-1);
            if(parts.length!=3||!parts[2].matches("[a-z][a-z0-9_]{0,63}")){p.sendMessage(Component.text(QuestListView.UNAVAILABLE));return true;}
            load(p,s,v->{if(parts[1].equals("info"))acceptedBook(p,s,v,parts[2]);else open(p,s,v,"detail",parts[2],0);});return true;
        }
        if(command.equals("/hearthphone rewards")||command.equals("/hearthphone chapters")){String page=command.endsWith("rewards")?"rewards":"chapters";load(p,s,v->open(p,s,v,page,null,0));return true;}
        if(command.equals("/hearthphone diary")){load(p,s,v->open(p,s,v,"diary",null,0));return true;}
        if(command.equals("/hearthphone")){load(p,s,v->open(p,s,v,"home",null,0));return true;}
        String[] args=command.split(" ",-1);
        if(args.length==2 && args[0].equals("/hearthquest") && args[1].equals("tracked")){
            load(p,s,v->{var lines=journal.hudLines(new QuestJournal.View(v.states(),new io.github.jesterportfolio.hearth.persistence.QuestJournalStore.Journal(v.journal().tracked(),new io.github.jesterportfolio.hearth.persistence.QuestJournalStore.Preferences(true,0),v.journal().notices()),v.exploration()));if(lines.isEmpty())p.sendMessage(Component.text("추적 중인 퀘스트가 없습니다."));else lines.forEach(x->p.sendMessage(Component.text(x)));});return true;
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
            if(players.current(p)!=s || p.getOpenInventory().getTopInventory()!=screen.inventory || !screen.dialogueValid.getAsBoolean())return;
            String action=button.action();
            if(action.equals("detail")||action.equals("book"))load(p,s,v->acceptedBook(p,s,v,button.quest()));
            else if(action.equals("npc-select")||action.equals("npc-action")){p.closeInventory();npcSelection.select(p,screen.quest,button.quest(),action.equals("npc-action")?button.revision():null);}
            else if(Set.of("npc-read","npc-topics-open","npc-topic","npc-menu","npc-close","npc-story","npc-journey").contains(action)){p.closeInventory();npcNavigation.open(p,screen.quest,action,button.quest());}
            else if(action.equals("journey-guide")){p.closeInventory();journey(p,s,null);}
            else if(action.equals("quest-retry")||action.equals("quest-reward")){p.closeInventory();questAction.accept(p,"/hearthquest "+(action.equals("quest-retry")?"retry ":"claim ")+button.quest());}
            else if(action.equals("quest-chapter-play")||action.equals("quest-chapter-skip")){p.closeInventory();questAction.accept(p,"/hearthquest "+(action.equals("quest-chapter-skip")?"chapter-skip ":"chapter-play ")+button.quest());}
            else if(action.equals("quest-chapters")){p.closeInventory();questAction.accept(p,"/hearthquest chapters");}
            else if(action.equals("npc-contact")){p.closeInventory();npcContact.accept(p,button.quest());}
            else if(action.equals("npc-dialogue")){p.closeInventory();npcDialogue.accept(p,button.quest());}
            else if(action.equals("story-guide")){p.closeInventory();storyGuide.accept(p);}
            else if(action.equals("track"))request(p,s,journal.track(s.context(),s,()->players.current(p)==s,button.quest(),button.revision()==1),v->open(p,s,v,"detail",button.quest(),0));
            else if(action.equals("hud"))request(p,s,journal.hud(s.context(),s,()->players.current(p)==s,button.revision()==1),v->open(p,s,v,"settings",null,0));
            else if(action.startsWith("setting:"))request(p,s,journal.setting(s.context(),s,()->players.current(p)==s,action.substring(8),button.revision()==1),v->open(p,s,v,"settings",null,0));
            else if(action.equals("place-notice"))request(p,s,journal.readPlaceNotice(s.context(),s,()->players.current(p)==s,button.quest()),v->open(p,s,v,"place",button.quest(),0));
            else if(action.equals("read-all"))request(p,s,journal.readAll(s.context(),s,()->players.current(p)==s),v->open(p,s,v,"notices",null,0));
            else if(action.equals("notice"))request(p,s,journal.readNotice(s.context(),s,()->players.current(p)==s,button.quest(),button.revision()),v->acceptedBook(p,s,v,button.quest()));
            else load(p,s,v->open(p,s,v,action,button.quest(),(int)button.revision()));
        },1);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Screen)e.setCancelled(true);}
    @EventHandler public void closed(InventoryCloseEvent e){if(e.getInventory().getHolder() instanceof Screen)requests.remove(e.getPlayer().getUniqueId());}
    @EventHandler public void quit(PlayerQuitEvent e){clearBars(e.getPlayer());clearProjection.accept(e.getPlayer());requests.remove(e.getPlayer().getUniqueId());attempted.remove(e.getPlayer().getUniqueId());dirty.remove(e.getPlayer().getUniqueId());hudOnly.remove(e.getPlayer().getUniqueId());}
    @Override public void close(){if(closed)return;closed=true;task.cancel();HandlerList.unregisterAll(this);players.developerInventory((p,i)->false);quests.changed(c->{});journal.changed(c->{});journal.sharedChanged(r->{});for(Player p:Bukkit.getOnlinePlayers()){clearBars(p);clearProjection.accept(p);if(p.getOpenInventory().getTopInventory().getHolder() instanceof Screen)p.closeInventory();}requests.clear();attempted.clear();dirty.clear();hudOnly.clear();}
}
