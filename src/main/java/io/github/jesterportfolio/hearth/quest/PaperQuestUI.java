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
    private String dungeonNpc,dungeonQuest;public void dungeon(String npc,String quest){dungeonNpc=npc;dungeonQuest=quest;}
    private Consumer<Player> storyGuide=p->{};
    private Predicate<String> storyNpc=npc->false;
    public void storyNpcs(Predicate<String> registered){storyNpc=registered;}
    private java.util.function.BiFunction<Player,QuestJournal.View,PreparationGuide.Page> preparation;
    private final Map<UUID,Object> preparationFrames=new HashMap<>();
    public void preparation(java.util.function.BiFunction<Player,QuestJournal.View,PreparationGuide.Page> reader){preparation=reader;}
    private Component preparationButton(Player p,PlayerSessions.Session session,Object frame,BooleanSupplier current,String label,Runnable action){return Component.text(label).clickEvent(net.kyori.adventure.text.event.ClickEvent.callback(audience->{if(audience!=p)return;Bukkit.getScheduler().runTaskLater(plugin,()->{if(players.current(p)==session&&preparationFrames.get(p.getUniqueId())==frame&&current.getAsBoolean())action.run();},1);},net.kyori.adventure.text.event.ClickCallback.Options.builder().uses(1).lifetime(java.time.Duration.ofMinutes(2)).build()));}
    private void preparation(Player p,PlayerSessions.Session session,BooleanSupplier current,List<Component> back,String selected,PreparationGuide.Page expected){
        if(preparation==null){p.sendMessage(Component.text("현재 콘텐츠에 준비 안내가 등록되지 않았습니다."));return;}if(requests.containsKey(p.getUniqueId())){p.sendMessage(Component.text("기존 조회를 처리 중입니다."));return;}
        Object frame=new Object();preparationFrames.put(p.getUniqueId(),frame);
        load(p,session,view->{if(!current.getAsBoolean()||preparationFrames.get(p.getUniqueId())!=frame)return;try{var page=preparation.apply(p,view);if(expected!=null)PreparationGuide.require(page,expected,selected);var actions=new ArrayList<Component>();List<String> lines;
            if(selected==null){lines=page.cards().isEmpty()?List.of("현재 공개된 준비 안내가 없습니다."):page.cards().stream().map(PreparationGuide.Card::title).toList();for(var card:page.cards())actions.add(preparationButton(p,session,frame,current,card.title(),()->preparation(p,session,current,back,card.id(),page)));}
            else{lines=page.card(selected).lines();actions.add(preparationButton(p,session,frame,current,"준비 안내 목록",()->preparation(p,session,current,back,null,null)));}
            actions.add(preparationButton(p,session,frame,current,"준비 상태 새로고침",()->preparation(p,session,current,back,selected,null)));actions.addAll(back);showBook(p,session,new QuestBookView(selected==null?"시설·재료·던전 준비":page.card(selected).title(),QuestBookView.paginate(lines)),actions);
        }catch(RuntimeException stale){p.sendMessage(Component.text("준비 안내 상태가 바뀌었거나 조회할 수 없습니다. 최신 상태로 다시 조회해 주세요."));}});
    }
    private Playthrough playthrough;
    private io.github.jesterportfolio.hearth.story.SharedStory sharedStory;
    private final Map<UUID,Object> peopleFrames=new HashMap<>();
    private BiConsumer<Player,String> peopleTalk=(p,npc)->{};
    public void peopleTalk(BiConsumer<Player,String> handler){peopleTalk=handler;}
    private NpcGuidance.Page peopleView(PlayerSessions.Session session,QuestJournal.View view){return NpcGuidance.build(catalog,view,playthrough!=null&&sharedStory!=null?journeyView(session,view):null,sharedStory==null?Set.of():sharedStory.content().guideNpcs());}
    private Component peopleButton(Player p,PlayerSessions.Session session,Object frame,BooleanSupplier current,String label,Runnable action){return Component.text(label).clickEvent(net.kyori.adventure.text.event.ClickEvent.callback(audience->{if(audience==p)Bukkit.getScheduler().runTaskLater(plugin,()->{if(players.current(p)==session&&peopleFrames.get(p.getUniqueId())==frame&&current.getAsBoolean())action.run();},1);},net.kyori.adventure.text.event.ClickCallback.Options.builder().uses(1).lifetime(java.time.Duration.ofMinutes(2)).build()));}
    private void people(Player p,PlayerSessions.Session session,BooleanSupplier current,List<Component> back,int number,NpcGuidance.Page expected,NpcGuidance.Contact selected,boolean talk){
        if(!current.getAsBoolean())return;if(requests.containsKey(p.getUniqueId())){p.sendMessage(Component.text("기존 조회를 처리 중입니다."));return;}Object frame=new Object();peopleFrames.put(p.getUniqueId(),frame);
        load(p,session,view->{if(!current.getAsBoolean()||peopleFrames.get(p.getUniqueId())!=frame)return;try{
            journal.requireView(session.context(),view);var page=peopleView(session,view);if(expected!=null)NpcGuidance.require(page,expected,selected);int at=Math.min(number,page.pages());
            if(talk){if(selected==null)throw new IllegalStateException("NPC_GUIDANCE_SELECTION");peopleTalk.accept(p,selected.npc());return;}
            var actions=new ArrayList<Component>();QuestBookView book;
            if(selected!=null){book=selected.book();actions.add(peopleButton(p,session,frame,current,"가까운 NPC와 대화",()->people(p,session,current,back,at,page,selected,true)));actions.add(peopleButton(p,session,frame,current,"인물 목록으로",()->people(p,session,current,back,at,null,null,false)));}
            else{book=page.book(at);for(var contact:page.page(at))actions.add(peopleButton(p,session,frame,current,contact.name()+" · 연결 정보",()->people(p,session,current,back,at,page,contact,false)));if(at>1)actions.add(peopleButton(p,session,frame,current,"앞 인물",()->people(p,session,current,back,at-1,page,null,false)));if(at<page.pages())actions.add(peopleButton(p,session,frame,current,"다음 인물",()->people(p,session,current,back,at+1,page,null,false)));}
            actions.add(peopleButton(p,session,frame,current,"인물 안내 새로고침",()->people(p,session,current,back,at,null,null,false)));actions.addAll(back);showBook(p,session,book,actions);
        }catch(RuntimeException stale){p.sendMessage(Component.text("인물이나 공개 상태가 바뀌었습니다. 현재 안내를 다시 조회하세요."));}});
    }
    private java.util.function.BiFunction<Player,QuestJournal.View,CourseRoute.Page> courseRoute;
    private final Map<UUID,Object> routeFrames=new HashMap<>();
    public void courseRoute(java.util.function.BiFunction<Player,QuestJournal.View,CourseRoute.Page> reader){courseRoute=reader;}
    private Component routeButton(Player p,PlayerSessions.Session session,Object frame,BooleanSupplier current,String label,Runnable action){
        return Component.text(label).clickEvent(net.kyori.adventure.text.event.ClickEvent.callback(audience->{if(audience==p)Bukkit.getScheduler().runTaskLater(plugin,()->{if(players.current(p)==session&&routeFrames.get(p.getUniqueId())==frame&&current.getAsBoolean())action.run();},1);},net.kyori.adventure.text.event.ClickCallback.Options.builder().uses(1).lifetime(java.time.Duration.ofMinutes(2)).build()));
    }
    private void route(Player p,PlayerSessions.Session session,BooleanSupplier current,List<Component> back,CourseRoute.Page expected,boolean detail){
        if(courseRoute==null||!current.getAsBoolean())return;if(requests.containsKey(p.getUniqueId())){p.sendMessage(Component.text("기존 조회를 처리 중입니다."));return;}
        Object frame=new Object();routeFrames.put(p.getUniqueId(),frame);
        load(p,session,view->{if(!current.getAsBoolean()||routeFrames.get(p.getUniqueId())!=frame)return;
            try{var page=courseRoute.apply(p,view);if(expected!=null)CourseRoute.require(page,expected);
                var returnToRoute=routeButton(p,session,frame,current,"현재 코스 동선으로",()->route(p,session,current,back,null,false));
                var selected=page.board().rows().stream().filter(PlaythroughOverview.Row::current).findFirst().orElse(null);
                if(detail){if(selected==null||selected.quest()==null)throw new IllegalStateException("STALE_COURSE_ROUTE");acceptedBook(p,session,view,selected.quest(),List.of(returnToRoute),current);return;}
                var actions=new ArrayList<Component>();
                actions.add(routeButton(p,session,frame,current,"관련 인물 안내",()->people(p,session,current,List.of(returnToRoute),1,null,null,false)));
                if(selected!=null&&selected.quest()!=null)actions.add(routeButton(p,session,frame,current,"현재 의뢰 상세·후속 처리",()->route(p,session,current,back,page,true)));
                if(page.preparation()!=null)actions.add(routeButton(p,session,frame,current,"현재 시설 준비 안내",()->{
                    load(p,session,fresh->{if(!current.getAsBoolean()||routeFrames.get(p.getUniqueId())!=frame)return;var latest=courseRoute.apply(p,fresh);CourseRoute.require(latest,page);preparation(p,session,current,List.of(returnToRoute),latest.preparation(),null);});
                }));
                actions.add(routeButton(p,session,frame,current,"현재 항목의 진행판",()->course(p,session,current,List.of(returnToRoute),page.coursePage(),null)));
                actions.add(routeButton(p,session,frame,current,"다음 행동·NPC 안내",()->journey(p,session,null,current,List.of(returnToRoute))));
                actions.add(routeButton(p,session,frame,current,"동선 새로고침",()->route(p,session,current,back,null,false)));actions.addAll(back);showBook(p,session,page.book(),actions);
            }catch(RuntimeException stale){p.sendMessage(Component.text("현재 코스나 공개된 시설이 바뀌었습니다. 동선 안내를 다시 조회하세요."));}
        });
    }
    public void playthrough(Playthrough content,io.github.jesterportfolio.hearth.story.SharedStory story){playthrough=content;sharedStory=story;}
    private final Map<UUID,Object> facilityFrames=new HashMap<>();
    public void facility(Player player,Function<QuestJournal.View,FacilityObjectives.Board> reader,BooleanSupplier current,Runnable back){
        var session=players.current(player);if(session!=null)facility(player,session,reader,current,back,1,null,null);
    }
    private Component facilityButton(Player p,PlayerSessions.Session session,Object frame,BooleanSupplier current,String label,Runnable action){
        return Component.text(label).clickEvent(net.kyori.adventure.text.event.ClickEvent.callback(audience->{if(audience!=p)return;Bukkit.getScheduler().runTaskLater(plugin,()->{if(players.current(p)==session&&facilityFrames.get(p.getUniqueId())==frame&&current.getAsBoolean())action.run();},1);},net.kyori.adventure.text.event.ClickCallback.Options.builder().uses(1).lifetime(java.time.Duration.ofMinutes(2)).build()));
    }
    private void facility(Player p,PlayerSessions.Session session,Function<QuestJournal.View,FacilityObjectives.Board> reader,BooleanSupplier current,Runnable back,int number,FacilityObjectives.Board expected,FacilityObjectives.Row selected){
        if(!current.getAsBoolean())return;if(requests.containsKey(p.getUniqueId())){p.sendMessage(Component.text("기존 조회를 처리 중입니다."));return;}
        Object frame=new Object();facilityFrames.put(p.getUniqueId(),frame);
        load(p,session,view->{
            if(!current.getAsBoolean()||facilityFrames.get(p.getUniqueId())!=frame)return;
            try{var board=reader.apply(view);if(expected!=null)FacilityObjectives.require(board,expected);var page=board.page(Math.min(number,board.pages()));
                var returnToGuide=facilityButton(p,session,frame,current,"시설 목표 안내로",()->facility(p,session,reader,current,back,page.number(),null,null));
                if(selected!=null){FacilityObjectives.require(board,expected,number,selected);acceptedBook(p,session,view,selected.quest(),List.of(returnToGuide),current);return;}
                var actions=new ArrayList<Component>();
                for(var row:page.rows())actions.add(facilityButton(p,session,frame,current,row.title()+" · 상세/보고 안내",()->facility(p,session,reader,current,back,page.number(),board,row)));
                if(page.number()>1)actions.add(facilityButton(p,session,frame,current,"앞 의뢰",()->facility(p,session,reader,current,back,page.number()-1,board,null)));
                if(page.number()<page.pages())actions.add(facilityButton(p,session,frame,current,"다음 의뢰",()->facility(p,session,reader,current,back,page.number()+1,board,null)));
                actions.add(facilityButton(p,session,frame,current,"목표·상태 새로고침",()->facility(p,session,reader,current,back,page.number(),null,null)));
                if(preparation!=null)actions.add(facilityButton(p,session,frame,current,"시설·재료 준비",()->preparation(p,session,current,List.of(returnToGuide),null,null)));
                if(playthrough!=null)actions.add(facilityButton(p,session,frame,current,"다음 플레이 안내",()->journey(p,session,null,current,List.of(returnToGuide))));
                actions.add(facilityButton(p,session,frame,current,"현장/이전 안내로",back));showBook(p,session,page.book(board.title()),actions);
            }catch(RuntimeException stale){p.sendMessage(Component.text("시설 목표나 공개 범위가 바뀌었습니다. 현장 안내를 다시 열어 주세요."));}
        });
    }
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
            actions.add(Component.text("관련 인물 안내").clickEvent(net.kyori.adventure.text.event.ClickEvent.callback(audience->{if(audience==p)Bukkit.getScheduler().runTaskLater(plugin,()->{if(players.current(p)==session&&current.getAsBoolean())people(p,session,current,back,1,null,null,false);},1);},net.kyori.adventure.text.event.ClickCallback.Options.builder().uses(1).lifetime(java.time.Duration.ofMinutes(2)).build())));
            if(courseRoute!=null)actions.add(Component.text("현재 코스 동선").clickEvent(net.kyori.adventure.text.event.ClickEvent.callback(audience->{if(audience==p)Bukkit.getScheduler().runTaskLater(plugin,()->{if(players.current(p)==session&&current.getAsBoolean())route(p,session,current,back,null,false);},1);},net.kyori.adventure.text.event.ClickCallback.Options.builder().uses(1).lifetime(java.time.Duration.ofMinutes(2)).build())));
            if(preparation!=null)actions.add(Component.text("시설·재료·던전 준비").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/hearthprepare")));
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
            case COURSE->course(p,session,current,back,1,null);
            case SHARED->storyGuide.accept(p);
            case MAP,DIARY,NOTICES,REWARDS->open(p,session,view,link.action().name().toLowerCase(java.util.Locale.ROOT),null,0);
        }
    }
    private final Map<UUID,Object> courseFrames=new HashMap<>();
    private PlaythroughOverview.Board courseView(PlayerSessions.Session session,QuestJournal.View view){var run=session.context().storyRunId();return PlaythroughOverview.build(playthrough,catalog,view,sharedStory.cached(run),sharedStory.phase(run));}
    private Component courseButton(Player p,PlayerSessions.Session session,Object frame,BooleanSupplier current,String label,Runnable action){
        return Component.text(label).clickEvent(net.kyori.adventure.text.event.ClickEvent.callback(audience->{if(audience!=p)return;Bukkit.getScheduler().runTaskLater(plugin,()->{if(players.current(p)==session&&courseFrames.get(p.getUniqueId())==frame&&current.getAsBoolean())action.run();},1);},net.kyori.adventure.text.event.ClickCallback.Options.builder().uses(1).lifetime(java.time.Duration.ofMinutes(2)).build()));
    }
    private void course(Player p,PlayerSessions.Session session,BooleanSupplier current,List<Component> back,int number,PlaythroughOverview.Board expected){
        if(playthrough==null||sharedStory==null||!current.getAsBoolean())return;
        if(requests.containsKey(p.getUniqueId())){p.sendMessage(Component.text("기존 조회를 처리 중입니다."));return;}
        Object frame=new Object();courseFrames.put(p.getUniqueId(),frame);
        load(p,session,view->{
            if(!current.getAsBoolean()||courseFrames.get(p.getUniqueId())!=frame)return;
            var board=courseView(session,view);if(expected!=null)PlaythroughOverview.require(board,expected);var page=board.page(Math.min(number,board.pages()));var actions=new ArrayList<Component>();
            for(var row:page.rows())if(row.quest()!=null)actions.add(courseButton(p,session,frame,current,row.title()+" · 상세",()->{
                load(p,session,fresh->{if(!current.getAsBoolean()||courseFrames.get(p.getUniqueId())!=frame)return;PlaythroughOverview.require(courseView(session,fresh),board,row);
                    acceptedBook(p,session,fresh,row.quest(),List.of(courseButton(p,session,frame,current,"코스 진행판으로",()->course(p,session,current,back,page.number(),null))),current);
                });
            }));
            for(var row:page.rows())if(CourseRecordView.readable(row))actions.add(courseButton(p,session,frame,current,row.title()+" · "+CourseRecordView.label(row),()->
                courseRecord(p,session,current,back,fresh->CourseRecordView.open(playthrough,catalog,fresh,journal.placeFacts(session.context(),fresh),courseView(session,fresh),board,row,page.number()))));
            if(page.number()>1)actions.add(courseButton(p,session,frame,current,"앞 항목",()->course(p,session,current,back,page.number()-1,board)));
            if(page.number()<page.pages())actions.add(courseButton(p,session,frame,current,"다음 항목",()->course(p,session,current,back,page.number()+1,board)));
            if(courseRoute!=null)actions.add(courseButton(p,session,frame,current,"현재 코스 동선",()->route(p,session,current,back,null,false)));
            actions.add(courseButton(p,session,frame,current,"다음 행동 안내",()->journey(p,session,null,current,back)));
            actions.add(courseButton(p,session,frame,current,"진행판 새로고침",()->course(p,session,current,back,page.number(),null)));
            if(preparation!=null)actions.add(courseButton(p,session,frame,current,"시설·재료·던전 준비",()->preparation(p,session,current,List.of(courseButton(p,session,frame,current,"코스 진행판으로",()->course(p,session,current,back,page.number(),null))),null,null)));
            actions.addAll(back);showBook(p,session,page.book(),actions);
        });
    }
    private void courseRecord(Player p,PlayerSessions.Session session,BooleanSupplier current,List<Component> back,Function<QuestJournal.View,CourseRecordView.Screen> build){
        if(!current.getAsBoolean())return;if(requests.containsKey(p.getUniqueId())){p.sendMessage(Component.text("기존 조회를 처리 중입니다."));return;}
        Object frame=new Object();courseFrames.put(p.getUniqueId(),frame);
        load(p,session,view->{
            if(!current.getAsBoolean()||courseFrames.get(p.getUniqueId())!=frame)return;
            try{var screen=build.apply(view);var page=screen.page();var actions=new ArrayList<Component>();
                for(var link:page.links())actions.add(courseButton(p,session,frame,current,link.label(),()->courseRecordAction(p,session,current,back,screen,CourseRecordView.Action.RELATED,link)));
                if(page.number()>1)actions.add(courseButton(p,session,frame,current,"앞 내용",()->courseRecordAction(p,session,current,back,screen,CourseRecordView.Action.PREVIOUS,null)));
                if(page.number()<page.pages())actions.add(courseButton(p,session,frame,current,"다음 내용",()->courseRecordAction(p,session,current,back,screen,CourseRecordView.Action.NEXT,null)));
                if(screen.path().size()>1)actions.add(courseButton(p,session,frame,current,"이전 정보",()->courseRecordAction(p,session,current,back,screen,CourseRecordView.Action.BACK,null)));
                actions.add(courseButton(p,session,frame,current,"기록 새로고침",()->courseRecordAction(p,session,current,back,screen,CourseRecordView.Action.REFRESH,null)));
                actions.add(courseButton(p,session,frame,current,"코스 진행판으로",()->course(p,session,current,back,screen.coursePage(),null)));actions.addAll(back);
                showBook(p,session,page.book(screen.record().title()),actions);
            }catch(RuntimeException stale){p.sendMessage(Component.text("관련 기록이나 공개 범위가 바뀌었습니다. 다음 플레이 안내에서 진행판을 다시 열어 주세요.").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/hearthjourney")));}
        });
    }
    private void courseRecordAction(Player p,PlayerSessions.Session session,BooleanSupplier current,List<Component> back,CourseRecordView.Screen shown,CourseRecordView.Action action,CourseRecordView.Link selected){
        courseRecord(p,session,current,back,view->CourseRecordView.navigate(playthrough,catalog,view,journal.placeFacts(session.context(),view),courseView(session,view),shown,action,selected));
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
        if(page.equals("npc")){buttons=QuestScreens.npcGuide(buttons,storyNpc.test(npc));buttons=QuestScreens.npcJourney(buttons,playthrough!=null);if(Objects.equals(dungeonNpc,npc)&&journal.questAllowed(session.context(),dungeonQuest))buttons=QuestScreens.npcDungeon(buttons,catalog,rows,npc,dungeonNpc,dungeonQuest);}
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
    private void acceptedBook(Player p,PlayerSessions.Session session,QuestJournal.View view,String id){acceptedBook(p,session,view,id,List.of());}
    private void acceptedBook(Player p,PlayerSessions.Session session,QuestJournal.View view,String id,List<Component> back){acceptedBook(p,session,view,id,back,()->true);}
    private void acceptedBook(Player p,PlayerSessions.Session session,QuestJournal.View view,String id,List<Component> back,BooleanSupplier current){
        if(!current.getAsBoolean())return;
        journal.requireView(session.context(),view);
        var detail=QuestBookView.accepted(catalog,view.states(),view.exploration().blockedQuests(),id);
        if(detail.isEmpty()){p.sendMessage(Component.text(QuestListView.UNAVAILABLE));return;}
        var oldOwner=completionOwners.put(p.getUniqueId(),session);if(oldOwner!=null&&oldOwner!=session)completionFlow.forget(oldOwner);
        Object frame=new Object();completionFrames.put(p.getUniqueId(),frame);var links=new ArrayList<Component>(back);
        Runnable reload=()->load(p,session,fresh->{if(current.getAsBoolean())acceptedBook(p,session,fresh,id,back,current);});
        for(var choice:QuestFollowups.choices(catalog,view,id))links.add(completionButton(p,session,frame,current,QuestFollowups.label(choice),()->completionConfirm(p,session,choice,current,reload)));
        if(Objects.equals(id,dungeonQuest))links.add(Component.text("[던전 전투 상태]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/hearthcombat status")));links.addAll(List.of(Component.text("[진행 관리]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/hearthquest manage "+id)),Component.text("[퀘스트 목록]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/hearthquest list"))));showBook(p,session,detail.get(),links);
    }
    private io.github.jesterportfolio.hearth.phone.CompletionFlow completionFlow=new io.github.jesterportfolio.hearth.phone.CompletionFlow();
    private io.github.jesterportfolio.hearth.phone.PaperPhone.CompletionAction completion=(p,s,c)->CompletableFuture.failedFuture(new IllegalStateException("COMPLETION_DISABLED"));
    private final Map<UUID,Object> completionFrames=new HashMap<>();
    private final Map<UUID,PlayerSessions.Session> completionOwners=new HashMap<>();
    public void completion(io.github.jesterportfolio.hearth.phone.PaperPhone.CompletionAction action,io.github.jesterportfolio.hearth.phone.CompletionFlow flow){completion=action;completionFlow=flow;}
    public void contextQuest(Player p,String id,BooleanSupplier current,Runnable back){
        var session=players.current(p);if(session==null||!current.getAsBoolean())return;
        Object frame=new Object();completionFrames.put(p.getUniqueId(),frame);
        load(p,session,view->{if(!current.getAsBoolean()||completionFrames.get(p.getUniqueId())!=frame)return;
            var origin=Component.text("원래 시설 안내로").clickEvent(net.kyori.adventure.text.event.ClickEvent.callback(audience->{if(audience==p)Bukkit.getScheduler().runTaskLater(plugin,()->{if(players.current(p)==session&&current.getAsBoolean())back.run();},1);},net.kyori.adventure.text.event.ClickCallback.Options.builder().uses(1).lifetime(java.time.Duration.ofMinutes(2)).build()));
            acceptedBook(p,session,view,id,List.of(origin),current);
        });
    }
    private Component completionButton(Player p,PlayerSessions.Session session,Object frame,BooleanSupplier current,String label,Runnable action){
        return Component.text(label).clickEvent(net.kyori.adventure.text.event.ClickEvent.callback(audience->{if(audience==p)Bukkit.getScheduler().runTaskLater(plugin,()->{if(players.current(p)==session&&completionFrames.get(p.getUniqueId())==frame&&current.getAsBoolean())action.run();},1);},net.kyori.adventure.text.event.ClickCallback.Options.builder().uses(1).lifetime(java.time.Duration.ofMinutes(2)).build()));
    }
    private void completionConfirm(Player p,PlayerSessions.Session session,io.github.jesterportfolio.hearth.phone.PhoneCompletion.Choice choice,BooleanSupplier current,Runnable back){
        Object frame=new Object();completionFrames.put(p.getUniqueId(),frame);
        load(p,session,view->{if(!current.getAsBoolean()||completionFrames.get(p.getUniqueId())!=frame)return;
            io.github.jesterportfolio.hearth.phone.PhoneCompletion.require(catalog,view,choice);
            showBook(p,session,new QuestBookView(choice.title(),QuestBookView.paginate(List.of(io.github.jesterportfolio.hearth.phone.PhoneCompletion.warning(choice.operation())))),List.of(completionButton(p,session,frame,current,"확인",()->completionRun(p,session,choice,current,back)),completionButton(p,session,frame,current,"취소 · 의뢰로",back)));
        });
    }
    private void completionRun(Player p,PlayerSessions.Session session,io.github.jesterportfolio.hearth.phone.PhoneCompletion.Choice choice,BooleanSupplier current,Runnable back){
        Object expected=completionFrames.get(p.getUniqueId());
        load(p,session,view->{if(!current.getAsBoolean()||completionFrames.get(p.getUniqueId())!=expected)return;io.github.jesterportfolio.hearth.phone.PhoneCompletion.require(catalog,view,choice);
            var request=completionFlow.begin(session,choice);completionResult(p,session,request,current,back);final CompletionStage<Void> work;
            try{work=completion.execute(p,session,choice);}catch(RuntimeException error){completionFlow.complete(session,request,false);completionResult(p,session,request,current,back);return;}
            var shown=completionFrames.get(p.getUniqueId());work.whenCompleteAsync((v,error)->{
                if(!completionFlow.complete(session,request,error==null))return;
                if(players.current(p)==session&&completionFrames.get(p.getUniqueId())==shown&&current.getAsBoolean())completionResult(p,session,request,current,back);
                else if(error!=null&&players.prepared(p)==session&&p.isOnline())p.sendMessage(Component.text("처리 보류/실패 · 성공으로 기록하지 않습니다. 저장 상태를 확인하세요."));
            },owner);
        });
    }
    private void completionResult(Player p,PlayerSessions.Session session,io.github.jesterportfolio.hearth.phone.CompletionFlow.Request request,BooleanSupplier current,Runnable back){
        if(!current.getAsBoolean()||!completionFlow.current(session,request))return;Object frame=new Object();completionFrames.put(p.getUniqueId(),frame);
        var actions=new ArrayList<Component>();if(request.phase()==io.github.jesterportfolio.hearth.phone.CompletionFlow.Phase.PENDING)actions.add(completionButton(p,session,frame,current,"결과 확인",()->completionResult(p,session,request,current,back)));
        actions.add(completionButton(p,session,frame,current,"현재 의뢰로",back));showBook(p,session,new QuestBookView(request.title(),QuestBookView.paginate(request.lines())),actions);
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
        var buttons=QuestScreens.screen(catalog,view,page,quest,Math.max(0,offset));if(page.equals("npc")){buttons=QuestScreens.npcGuide(buttons,storyNpc.test(quest));buttons=QuestScreens.npcJourney(buttons,playthrough!=null);if(Objects.equals(dungeonNpc,quest)&&journal.questAllowed(s.context(),dungeonQuest))buttons=QuestScreens.npcDungeon(buttons,catalog,view.states(),quest,dungeonNpc,dungeonQuest);}
        p.openInventory(new Screen(p.getUniqueId(),s,buttons,page,quest,Math.max(0,offset),valid).inventory);
    }
    public boolean command(Player p,String command){
        var s=players.current(p);if(s==null)return false;
        if(command.equals("/hearthprepare")){preparation(p,s,()->true,List.of(Component.text("다음 플레이 안내").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/hearthjourney"))),null,null);return true;}
        if(command.startsWith("/hearthprepare ")){p.sendMessage(Component.text("사용법: /hearthprepare"));return true;}
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
            else if(Set.of("npc-read","npc-topics-open","npc-topic","npc-menu","npc-close","npc-story","npc-journey","npc-dungeon","npc-dungeon-guide").contains(action)){p.closeInventory();npcNavigation.open(p,screen.quest,action,button.quest());}
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
    @EventHandler public void quit(PlayerQuitEvent e){peopleFrames.remove(e.getPlayer().getUniqueId());routeFrames.remove(e.getPlayer().getUniqueId());completionFrames.remove(e.getPlayer().getUniqueId());var quitting=completionOwners.remove(e.getPlayer().getUniqueId());if(quitting!=null)completionFlow.forget(quitting);facilityFrames.remove(e.getPlayer().getUniqueId());courseFrames.remove(e.getPlayer().getUniqueId());preparationFrames.remove(e.getPlayer().getUniqueId());clearBars(e.getPlayer());clearProjection.accept(e.getPlayer());requests.remove(e.getPlayer().getUniqueId());attempted.remove(e.getPlayer().getUniqueId());dirty.remove(e.getPlayer().getUniqueId());hudOnly.remove(e.getPlayer().getUniqueId());}
    @Override public void close(){peopleFrames.clear();routeFrames.clear();completionOwners.clear();completionFrames.clear();completionFlow.clear();facilityFrames.clear();courseFrames.clear();preparationFrames.clear();if(closed)return;closed=true;task.cancel();HandlerList.unregisterAll(this);players.developerInventory((p,i)->false);quests.changed(c->{});journal.changed(c->{});journal.sharedChanged(r->{});for(Player p:Bukkit.getOnlinePlayers()){clearBars(p);clearProjection.accept(p);if(p.getOpenInventory().getTopInventory().getHolder() instanceof Screen)p.closeInventory();}requests.clear();attempted.clear();dirty.clear();hudOnly.clear();}
}
