package io.github.jesterportfolio.hearth.phone;

import io.github.jesterportfolio.hearth.quest.*;
import io.github.jesterportfolio.hearth.session.*;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.*;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** 기존 격리 세션 게이트 안에서만 생성합니다. Dialog는 플레이어 인벤토리를 사용하지 않습니다. */
public final class PaperPhone implements Listener,AutoCloseable {
    private record Screen(PlayerSessions.Session session,Object nonce,PhoneChat chat){}
    @FunctionalInterface public interface CompletionAction{java.util.concurrent.CompletionStage<Void> execute(Player player,PlayerSessions.Session session,PhoneCompletion.Choice choice);}
    private final PhoneReferences references=new PhoneReferences();
    private final Map<PlayerSessions.Session,String> contextModes=new IdentityHashMap<>();
    private final Map<PlayerSessions.Session,PhoneHelp.Selection> helpSelections=new IdentityHashMap<>();
    private CompletionAction completion=(p,s,c)->java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("COMPLETION_DISABLED"));
    public void completion(CompletionAction handler){completion=handler;}
    public void completion(CompletionAction handler,CompletionFlow flow){completion=handler;completionFlow=flow;}
    private CompletionFlow completionFlow=new CompletionFlow();
    private final Set<PlayerSessions.Session> pendingActions=Collections.newSetFromMap(new IdentityHashMap<>());
    private record CompletionReturn(CompletionFlow.Request request,Consumer<Screen> back){}
    private final Map<PlayerSessions.Session,CompletionReturn> completionReturns=new IdentityHashMap<>();
    private Playthrough playthrough;private io.github.jesterportfolio.hearth.story.SharedStory sharedStory;
    @FunctionalInterface public interface StoryRequest{java.util.concurrent.CompletionStage<?> request(Player player,io.github.jesterportfolio.hearth.story.StoryGuide.Action action,java.util.function.BooleanSupplier current);}
    private StoryRequest storyRequest=(p,a,v)->java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("STORY_DISABLED"));
    public void storyProgress(io.github.jesterportfolio.hearth.story.SharedStory shared,StoryRequest request){sharedStory=shared;storyRequest=request;}
    public void playthrough(Playthrough content,io.github.jesterportfolio.hearth.story.SharedStory story){playthrough=content;sharedStory=story;}
    private final Map<UUID,Screen> screens=new HashMap<>();private final JavaPlugin plugin;private final PaperSessionAdapter players;
    private final QuestJournal journal;private final QuestCatalog catalog;
    private Set<String> escortQuests=Set.of();private java.util.function.BiConsumer<Player,String> escortGuide=(p,q)->{};
    public void escort(Set<String> quests,java.util.function.BiConsumer<Player,String> guide){escortQuests=Set.copyOf(quests);escortGuide=guide;}
    private String dungeonQuest;private java.util.function.BiConsumer<Player,String> dungeonGuide=(p,q)->{};
    public void dungeon(String quest,java.util.function.BiConsumer<Player,String> guide){dungeonQuest=quest;dungeonGuide=guide;}
    private java.util.function.BiConsumer<Player,String> report=(p,id)->{};
    private java.util.function.BiConsumer<Player,String> contact=(p,key)->{};
    public void contact(java.util.function.BiConsumer<Player,String> handler){contact=handler;}
    private java.util.function.BiConsumer<Player,String> peopleTalk=(p,npc)->{};
    public void peopleTalk(java.util.function.BiConsumer<Player,String> handler){peopleTalk=handler;}
    public void report(java.util.function.BiConsumer<Player,String> handler){report=handler;}private final Executor owner;private final MeasuredAssistant assistant;private final NamespacedKey device;private boolean closed;
    public PaperPhone(JavaPlugin plugin,PaperSessionAdapter players,QuestJournal journal,QuestCatalog catalog,Executor owner){
        this.plugin=plugin;this.players=players;this.journal=journal;this.catalog=catalog;this.owner=owner;device=new NamespacedKey(plugin,"dev_phone");
        String mode=plugin.getConfig().getString("phone-ai-mode","mock");
        assistant=new MeasuredAssistant(LocalAssistant.configured(mode,plugin.getConfig().getString("phone-ai-endpoint","http://127.0.0.1:11435/ask")));
        Objects.requireNonNull(plugin.getCommand("hearthai")).setExecutor((sender,command,label,args)->{AssistantDiagnostics.lines(sender instanceof org.bukkit.command.ConsoleCommandSender,args,mode,assistant::snapshot).forEach(sender::sendMessage);return true;});
        Bukkit.getPluginManager().registerEvents(this,plugin);
    }
    private Screen screen(Player p){var session=players.current(p);if(session==null)throw new IllegalStateException("PHONE_SESSION_REQUIRED");var old=screens.get(p.getUniqueId());if(old!=null&&old.session()==session)return old;if(old!=null){old.chat().close();contextModes.remove(old.session());helpSelections.remove(old.session());completionFlow.forget(old.session());completionReturns.remove(old.session());references.remove(old.session());}var next=new Screen(session,new Object(),new PhoneChat(assistant,owner));screens.put(p.getUniqueId(),next);return next;}
    private boolean valid(Player p,Screen s){return !closed&&p.isOnline()&&players.current(p)==s.session()&&screens.get(p.getUniqueId())==s;}
    private Screen advance(Player p){var old=screen(p);var s=new Screen(old.session(),new Object(),old.chat());screens.put(p.getUniqueId(),s);return s;}
    private ClickCallback.Options options(){return ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build();}
    private void dispatch(Player p,Screen s,Runnable action){Bukkit.getScheduler().runTaskLater(plugin,()->{if(valid(p,s))try{action.run();}catch(RuntimeException rejected){var current=screens.get(p.getUniqueId());if(current!=null&&valid(p,current)&&current.session()==s.session())info(p,current,"현재 처리 불가",List.of("정보가 변경됐거나 저장 중입니다. 앱에서 다시 조회해 주세요."));}},1);}
    private ActionButton button(Player p,Screen s,String label,Runnable action){return ActionButton.builder(Component.text(label)).width(110).action(DialogAction.customClick((response,audience)->{if(audience instanceof Player who&&who.getUniqueId().equals(p.getUniqueId()))dispatch(p,s,action);},options())).build();}
    private Component icon(Player p,Screen s,int index,String label,Runnable action){return Component.text(new String(Character.toChars(0xe001+index))).font(Key.key("hearth:phone")).append(Component.text(" "+label).font(Key.key("minecraft:default"))).clickEvent(ClickEvent.callback(audience->{if(audience instanceof Player who&&who.getUniqueId().equals(p.getUniqueId()))dispatch(p,s,action);},options()));}
    private void show(Player p,Screen s,String title,List<? extends DialogBody> body,List<? extends DialogInput> inputs,List<ActionButton> actions){
        if(!valid(p,s))return;p.closeInventory();
        p.showDialog(compose(title,body,inputs,actions));
    }
    public static Dialog compose(String title,List<? extends DialogBody> body,List<? extends DialogInput> inputs,List<ActionButton> actions){
        return Dialog.create(b->b.empty().base(DialogBase.builder(Component.text(title)).canCloseWithEscape(true).pause(false).body(body).inputs(inputs).build()).type(DialogType.multiAction(actions).columns(2).build()));
    }
    private Consumer<Player> featuresMenu;private Consumer<Player> deviceIssuer;
    public void featuresMenu(Consumer<Player> menu){featuresMenu=menu;}public void deviceIssuer(Consumer<Player> issue){deviceIssuer=issue;}
    public void home(Player p){
        var s=advance(p);var body=new ArrayList<DialogBody>();body.add(DialogBody.plainMessage(Component.text("\ue000").font(Key.key("hearth:phone"))));
        var actions=new ArrayList<ActionButton>();String[] labels={"AI 어시스턴트","퀘스트","연구","지도","기록","설정"};
        for(int i=0;i<labels.length;i++){final int app=i;Runnable open=()->app(p,app);body.add(DialogBody.plainMessage(icon(p,s,i,labels[i],open)));actions.add(button(p,s,labels[i],open));}
        if(featuresMenu!=null)actions.add(button(p,s,"지도·장면·개인 보관",()->{advance(p);p.closeDialog();featuresMenu.accept(p);}));
        if(playthrough!=null&&sharedStory!=null)actions.add(button(p,s,"코스 결과·기록",()->outcome(p,advance(p),1,null,null)));
        if(playthrough!=null&&sharedStory!=null)actions.add(button(p,s,"이어 하기",()->resume(p,advance(p),1,null,null)));
        actions.add(button(p,s,"종료",()->{screens.put(p.getUniqueId(),new Screen(s.session(),new Object(),s.chat()));p.closeDialog();}));
        show(p,s,"HEARTH OS",body,List.of(),actions);
    }
    private void app(Player p,int app){
        if(app==0){chat(p);return;}var s=advance(p);
        if(app==5){settings(p,s);return;}
        if(app==4){libraryMenu(p,s,"diary");return;}
        if(app==2){info(p,s,"연구",List.of("연구 기능은 아직 구현되지 않았습니다."));return;}
        String section=app==1?"quests":app==3?"map":"diary";
        browse(p,s,section,1);
    }
    private void browse(Player p,Screen s,String section,int page){
        journal.load(s.session().context(),s.session(),()->valid(p,s)).whenCompleteAsync((view,error)->{
            if(!valid(p,s))return;
            if(error!=null){info(p,s,"조회 오류",List.of("저장 처리 또는 오류 상태를 확인한 뒤 앱을 다시 열어 주세요."));return;}
            browseSnapshot(p,s,view,section,page);
        },owner);
    }
    private GameInformation information(Player p,Screen s,QuestJournal.View view){return journal.projectInformation(s.session().context(),view,()->valid(p,s));}
    private void browseSnapshot(Player p,Screen s,QuestJournal.View view,String section,int page){
        if(!valid(p,s))return;
        try{
            var data=information(p,s,view);var rows=PhonePages.list(data,section,page);var actions=new ArrayList<ActionButton>();
            for(var entry:rows.entries())actions.add(button(p,s,entry.label(),()->detail(p,advance(p),view,section,entry.index(),1,page)));
            if(page>1)actions.add(button(p,s,"이전 페이지",()->browseSnapshot(p,advance(p),view,section,page-1)));
            if(page<rows.total())actions.add(button(p,s,"다음 페이지",()->browseSnapshot(p,advance(p),view,section,page+1)));
            if(section.equals("quests")){
                actions.add(button(p,s,"추적 중",()->loadLibrary(p,advance(p),"tracked",1)));
                actions.add(button(p,s,"보고 가능",()->loadLibrary(p,advance(p),"reporting",1)));
                actions.add(button(p,s,"완료",()->loadLibrary(p,advance(p),"completed",1)));
                actions.add(button(p,s,"확정된 공유 분기",()->loadLibrary(p,advance(p),"branches",1)));
                actions.add(button(p,s,"실패·재시작",()->completionList(p,advance(p),"failed",1)));
                actions.add(button(p,s,"지급 대기 보상",()->completionList(p,advance(p),"rewards",1)));
                actions.add(button(p,s,"장별 따라잡기",()->completionList(p,advance(p),"chapters",1)));
                if(preparation!=null)actions.add(button(p,s,"시설·재료·던전 준비",()->preparation(p,advance(p),null,null)));
                actions.add(button(p,s,"다음 플레이 안내",()->journey(p,advance(p))));
                if(pendingActions.contains(s.session())||completionFlow.current(s.session())!=null)actions.add(button(p,s,"최근 처리 결과",()->completionResult(p,advance(p))));
                actions.add(button(p,s,"개인 알림",()->notices(p,advance(p),1)));
            }
            if(section.equals("map"))actions.add(button(p,s,"공개 지역·접근 상태",()->loadLibrary(p,advance(p),"regions",1)));
            actions.add(button(p,s,"새로고침",()->browse(p,advance(p),section,1)));actions.add(button(p,s,"홈",()->home(p)));
            String title=switch(section){case "quests"->"퀘스트";case "map"->"월드 지도 정보";default->"기록";};
            show(p,s,title,List.of(DialogBody.plainMessage(Component.text(rows.entries().isEmpty()?"조회 가능한 정보가 없습니다.":"항목을 눌러 상세 내용을 확인하세요. "+page+" / "+rows.total()))),List.of(),actions);
        }catch(RuntimeException stale){info(p,s,"조회 상태 변경",List.of("정보가 변경됐거나 저장 중입니다. 홈에서 앱을 다시 열어 주세요."));}
    }
    private void detail(Player p,Screen s,QuestJournal.View view,String section,int index,int page,int listPage){detail(p,s,view,section,index,page,listPage,section);}
    private void detail(Player p,Screen s,QuestJournal.View view,String section,int index,int page,int listPage,String returnSection){detail(p,s,view,section,index,page,listPage,returnSection,null);}
    private void detail(Player p,Screen s,QuestJournal.View view,String section,int index,int page,int listPage,String returnSection,PhoneReaderLinks.Source origin){
        if(!valid(p,s))return;
        try{
            var detail=PhonePages.detail(information(p,s,view),section,index);var actions=new ArrayList<ActionButton>();
            if(page>1)actions.add(button(p,s,"앞 내용",()->detail(p,advance(p),view,section,index,page-1,listPage,returnSection,origin)));
            if(page<detail.pages().size())actions.add(button(p,s,"다음 내용",()->detail(p,advance(p),view,section,index,page+1,listPage,returnSection,origin)));
            actions.add(button(p,s,"이 항목 AI 도움",()->help(p,s,PhoneHelp.select(catalog,view,information(p,s,view),section,index))));
            if(section.equals("map"))readerButton(p,s,view,PhoneReaderLinks.place(view,view.exploration().places().get(index).key()),actions);
            if(origin!=null)actions.add(button(p,s,"이전 정보",()->reader(p,advance(p),origin,1)));
            if(section.equals("quests")){
                var quest=PhoneQuestLinks.quests(catalog,view).get(index);
                if(escortQuests.contains(quest.id()))actions.add(button(p,s,"호위 현장 안내",()->{information(p,s,view);advance(p);p.closeDialog();escortGuide.accept(p,quest.id());}));
                if(quest.id().equals(dungeonQuest))actions.add(button(p,s,"던전 진행 안내",()->{information(p,s,view);advance(p);p.closeDialog();dungeonGuide.accept(p,quest.id());}));
                var reward=view.completion().rewards().get(quest.id());
                if(reward!=null)actions.add(button(p,s,reward.label(),()->completionList(p,advance(p),"rewards",1)));
                if(view.states().get(quest.id()).phase()==io.github.jesterportfolio.hearth.persistence.QuestStore.Phase.FAILED)actions.add(button(p,s,"재시작 확인",()->confirm(p,s,view,PhoneCompletion.Operation.RETRY,quest.id(),next->returnDetail(p,next,quest.id(),section,listPage,returnSection,origin))));
                if(reward!=null&&reward.claimable())actions.add(button(p,s,"지급 대기 보상 받기",()->confirm(p,s,view,PhoneCompletion.Operation.CLAIM,quest.id(),next->returnDetail(p,next,quest.id(),section,listPage,returnSection,origin))));
                if(quest.trackable()||quest.tracked())actions.add(button(p,s,quest.tracked()?"추적 해제":"추적",()->{
                    information(p,s,view);var next=advance(p);
                    journal.track(next.session().context(),next.session(),()->valid(p,next),quest.id(),!quest.tracked()).whenCompleteAsync((saved,error)->{
                        if(!valid(p,next))return;if(error!=null)info(p,next,"추적 저장 실패",List.of("변경 성공으로 표시하지 않습니다. 다시 조회해 주세요."));else try{if(returnSection.equals("course")||returnSection.equals("course_route")||returnSection.equals("ai_references")||returnSection.equals("resume")||returnSection.equals("outcome"))detail(p,next,saved,section,PhoneQuestLinks.questIndex(catalog,saved,quest.id()),1,listPage,returnSection,origin);else detail(p,next,saved,section,PhoneQuestLinks.questIndex(catalog,saved,quest.id()),1,1);}catch(RuntimeException unavailable){info(p,next,"퀘스트 상태 변경",List.of("현재 목록으로 다시 조회해 주세요."));}
                    },owner);
                }));
                if(quest.reporting())actions.add(button(p,s,"가까운 NPC와 보고 대화",()->{information(p,s,view);advance(p);p.closeDialog();report.accept(p,quest.id());}));
                actions.add(button(p,s,"개인 알림",()->notices(p,advance(p),1)));
            }
            actions.add(button(p,s,returnSection.equals("outcome")?"코스 결과로":returnSection.equals("resume")?"이어 하기로":returnSection.equals("ai_references")?"AI 참고자료로":returnSection.equals("course_route")?"현재 코스 동선으로":returnSection.equals("course")?"코스 진행판으로":"목록",()->{var next=advance(p);if(returnSection.equals("outcome"))outcome(p,next,listPage,null,null);else if(returnSection.equals("resume"))resume(p,next,listPage,null,null);else if(returnSection.equals("ai_references"))referenceList(p,next);else if(returnSection.equals("course_route"))route(p,next,null,false);else if(returnSection.equals("course"))course(p,next,listPage,null);else if(returnSection.equals(section))browseSnapshot(p,next,view,section,listPage);else librarySnapshot(p,next,view,returnSection,listPage);}));actions.add(button(p,s,"홈",()->home(p)));
            show(p,s,detail.title(),List.of(DialogBody.plainMessage(Component.text(page+" / "+detail.pages().size()+"\n\n"+detail.pages().get(page-1)))),List.of(),actions);
        }catch(RuntimeException stale){info(p,s,"조회 상태 변경",List.of("현재 정보로 다시 조회해 주세요."));}
    }
    private void completionList(Player p,Screen s,String section,int page){
        journal.load(s.session().context(),s.session(),()->valid(p,s)).whenCompleteAsync((view,error)->{
            if(!valid(p,s))return;if(error!=null){info(p,s,"상태 조회 실패",List.of("진행/지급 정보를 정상 또는 빈 상태로 표시하지 않습니다."));return;}completionSnapshot(p,s,view,section,page);
        },owner);
    }
    private void completionSnapshot(Player p,Screen s,QuestJournal.View view,String section,int page){
        if(!valid(p,s))return;
        try{
            information(p,s,view);var actions=new ArrayList<ActionButton>();var items=new ArrayList<Runnable>();var labels=new ArrayList<String>();
            if(section.equals("chapters"))for(var chapter:view.completion().chapters()){labels.add(chapter.title()+(chapter.skipped()?" · 스킵 기록":""));items.add(()->chapter(p,advance(p),view,chapter.id(),1));}
            else for(var q:PhoneQuestLinks.quests(catalog,view)){
                var state=view.states().get(q.id());var reward=view.completion().rewards().get(q.id());
                if(section.equals("failed")&&state.phase()==io.github.jesterportfolio.hearth.persistence.QuestStore.Phase.FAILED){labels.add(q.title());items.add(()->confirm(p,s,view,PhoneCompletion.Operation.RETRY,q.id(),next->completionList(p,next,section,page)));}
                if(section.equals("rewards")&&reward!=null&&reward.claimable()){labels.add(q.title());items.add(()->confirm(p,s,view,PhoneCompletion.Operation.CLAIM,q.id(),next->completionList(p,next,section,page)));}
            }
            int total=Math.max(1,(labels.size()+5)/6),current=Math.min(Math.max(page,1),total);
            for(int n=(current-1)*6;n<Math.min(labels.size(),current*6);n++)actions.add(button(p,s,labels.get(n),items.get(n)));
            if(current>1)actions.add(button(p,s,"이전",()->completionSnapshot(p,advance(p),view,section,current-1)));
            if(current<total)actions.add(button(p,s,"다음",()->completionSnapshot(p,advance(p),view,section,current+1)));
            actions.add(button(p,s,"새로고침",()->completionList(p,advance(p),section,1)));actions.add(button(p,s,"퀘스트 목록",()->browse(p,advance(p),"quests",1)));actions.add(button(p,s,"홈",()->home(p)));
            String title=section.equals("chapters")?"장별 따라잡기":section.equals("failed")?"실패 퀘스트 재시작":"지급 대기 보상";
            show(p,s,title,List.of(DialogBody.plainMessage(Component.text(items.isEmpty()?"현재 실행 가능한 항목이 없습니다.":current+" / "+total))),List.of(),actions);
        }catch(RuntimeException stale){info(p,s,"상태 변경",List.of("최신 상태로 다시 조회해 주세요."));}
    }
    private void chapter(Player p,Screen s,QuestJournal.View view,String id,int page){
        if(!valid(p,s))return;
        try{
            information(p,s,view);var chapter=view.completion().chapter(id).orElseThrow();var text=PhonePages.text(chapter.title(),PhoneCompletion.chapterLines(chapter));var actions=new ArrayList<ActionButton>();
            if(page>1)actions.add(button(p,s,"앞 내용",()->chapter(p,advance(p),view,id,page-1)));
            if(page<text.pages().size())actions.add(button(p,s,"다음 내용",()->chapter(p,advance(p),view,id,page+1)));
            actions.add(button(p,s,"전체 플레이 안내",()->journey(p,advance(p))));
            for(var part:chapter.parts())actions.add(button(p,s,"["+part.status()+"] "+part.title(),()->{information(p,s,view);detail(p,advance(p),view,"quests",PhoneQuestLinks.questIndex(catalog,view,part.quest()),1,1);}));
            if(!chapter.skipped())actions.add(button(p,s,"장 스킵 확인",()->confirm(p,s,view,PhoneCompletion.Operation.SKIP,id,next->journal.load(next.session().context(),next.session(),()->valid(p,next)).whenCompleteAsync((fresh,error)->{if(!valid(p,next))return;if(error!=null)info(p,next,"장 상태 조회 실패",List.of("현재 장 목록을 다시 조회하세요."));else chapter(p,next,fresh,id,1);},owner))));
            actions.add(button(p,s,"장 목록",()->completionList(p,advance(p),"chapters",1)));actions.add(button(p,s,"홈",()->home(p)));
            show(p,s,text.title(),List.of(DialogBody.plainMessage(Component.text(text.pages().get(page-1)))),List.of(),actions);
        }catch(RuntimeException stale){info(p,s,"장 상태 변경",List.of("현재 상태로 다시 조회해 주세요."));}
    }
    private void confirm(Player p,Screen s,QuestJournal.View view,PhoneCompletion.Operation operation,String id){confirm(p,s,view,operation,id,next->completionList(p,next,operation==PhoneCompletion.Operation.SKIP?"chapters":operation==PhoneCompletion.Operation.CLAIM?"rewards":"failed",1));}
    private void confirm(Player p,Screen s,QuestJournal.View view,PhoneCompletion.Operation operation,String id,Consumer<Screen> back){
        information(p,s,view);var choice=PhoneCompletion.choose(catalog,view,operation,id);var next=advance(p);
        show(p,next,choice.title(),List.of(DialogBody.plainMessage(Component.text(PhoneCompletion.warning(operation)))),List.of(),List.of(button(p,next,"확인",()->runCompletion(p,next,view,choice,back)),button(p,next,"취소 · 원래 안내로",()->back.accept(advance(p)))));
    }
    private void returnDetail(Player p,Screen s,String id,String section,int listPage,String returnSection,PhoneReaderLinks.Source origin){
        journal.load(s.session().context(),s.session(),()->valid(p,s)).whenCompleteAsync((view,error)->{
            if(!valid(p,s))return;if(error!=null){info(p,s,"의뢰 조회 실패",List.of("현재 상태를 다시 조회하세요."));return;}
            try{information(p,s,view);detail(p,s,view,section,PhoneQuestLinks.questIndex(catalog,view,id),1,listPage,returnSection,origin);}catch(RuntimeException changed){if(returnSection.equals("outcome"))outcome(p,s,listPage,null,null);else if(returnSection.equals("resume"))resume(p,s,listPage,null,null);else if(returnSection.equals("ai_references"))referenceList(p,s);else if(returnSection.equals("course_route"))route(p,s,null,false);else if(returnSection.equals("course"))course(p,s,listPage,null);else browse(p,s,"quests",1);}
        },owner);
    }
    private void runCompletion(Player p,Screen s,QuestJournal.View view,PhoneCompletion.Choice choice,Consumer<Screen> back){
        information(p,s,view);PhoneCompletion.require(catalog,view,choice);if(pendingActions.contains(s.session()))throw new IllegalStateException("STORY_REQUEST_PENDING");var request=completionFlow.begin(s.session(),choice);completionReturns.put(s.session(),new CompletionReturn(request,back));var next=advance(p);
        completionResult(p,next,request);final java.util.concurrent.CompletionStage<Void> work;
        try{work=completion.execute(p,next.session(),choice);}catch(RuntimeException error){completionFlow.complete(next.session(),request,false);completionResult(p,next,request);return;}
        work.whenCompleteAsync((v,error)->{
            if(!completionFlow.complete(next.session(),request,error==null))return;
            if(valid(p,next))completionResult(p,advance(p),request);
            else if(error!=null&&players.prepared(p)==next.session()&&p.isOnline())p.sendMessage(Component.text("처리 보류/실패 · 성공으로 기록하지 않습니다. 저장 상태를 확인하세요."));
        },owner);
    }
    private void completionResult(Player p,Screen s){var request=completionFlow.current(s.session());if(request==null){info(p,s,"처리 기록 없음",List.of("이 접속 세션의 처리 기록이 없습니다."));return;}completionResult(p,s,request);}
    private void completionResult(Player p,Screen s,CompletionFlow.Request request){
        if(!completionFlow.current(s.session(),request)){info(p,s,"처리 기록 변경",List.of("현재 요청의 결과를 다시 조회하세요."));return;}
        var actions=new ArrayList<ActionButton>();if(request.phase()==CompletionFlow.Phase.PENDING)actions.add(button(p,s,"결과 확인",()->completionResult(p,advance(p),request)));
        var route=completionReturns.get(s.session());if(route!=null&&route.request()==request)actions.add(button(p,s,"원래 의뢰·안내로",()->route.back().accept(advance(p))));
        actions.add(button(p,s,"퀘스트 목록",()->browse(p,advance(p),"quests",1)));actions.add(button(p,s,"홈",()->home(p)));
        show(p,s,request.title(),request.lines().stream().map(line->DialogBody.plainMessage(Component.text(line))).toList(),List.of(),actions);
    }
    private java.util.function.BiFunction<Player,QuestJournal.View,PreparationGuide.Page> preparation;
    public void preparation(java.util.function.BiFunction<Player,QuestJournal.View,PreparationGuide.Page> reader){preparation=reader;}
    private void preparation(Player p,Screen s,String selected,PreparationGuide.Page expected){preparation(p,s,selected,expected,null);}
    private void preparation(Player p,Screen s,String selected,PreparationGuide.Page expected,Integer coursePage){preparation(p,s,selected,expected,coursePage,null);}
    private void preparation(Player p,Screen s,String selected,PreparationGuide.Page expected,Integer coursePage,Consumer<Screen> back){
        if(preparation==null){info(p,s,"준비 안내 미등록",List.of("현재 콘텐츠에 준비 안내가 등록되지 않았습니다."));return;}
        journal.load(s.session().context(),s.session(),()->valid(p,s)).whenCompleteAsync((view,error)->{
            if(!valid(p,s))return;if(error!=null){info(p,s,"준비 안내 조회 실패",List.of("오류를 빈 목록이나 준비 완료로 처리하지 않습니다. 최신 상태로 다시 조회해 주세요."));return;}
            try{information(p,s,view);var page=preparation.apply(p,view);if(expected!=null)PreparationGuide.require(page,expected,selected);var actions=new ArrayList<ActionButton>();List<String> lines;
                if(selected==null){lines=page.cards().isEmpty()?List.of("현재 공개된 준비 안내가 없습니다."):page.cards().stream().map(PreparationGuide.Card::title).toList();for(var card:page.cards())actions.add(button(p,s,card.title(),()->preparation(p,advance(p),card.id(),page,coursePage,back)));}
                else{lines=page.card(selected).lines();actions.add(button(p,s,"준비 안내 목록",()->preparation(p,advance(p),null,null,coursePage,back)));}
                actions.add(button(p,s,"준비 상태 새로고침",()->preparation(p,advance(p),selected,null,coursePage,back)));if(back!=null)actions.add(button(p,s,"현재 코스 동선으로",()->back.accept(advance(p))));if(coursePage!=null)actions.add(button(p,s,"코스 진행판으로",()->course(p,advance(p),coursePage,null)));actions.add(button(p,s,"퀘스트 목록",()->browse(p,advance(p),"quests",1)));actions.add(button(p,s,"홈",()->home(p)));
                show(p,s,selected==null?"시설·재료·던전 준비":page.card(selected).title(),lines.stream().map(line->DialogBody.plainMessage(Component.text(line))).toList(),List.of(),actions);
            }catch(RuntimeException stale){info(p,s,"준비 안내 상태 변경",List.of("현재 공개 정보·재료·진행 상태를 다시 조회해 주세요."));}
        },owner);
    }
    private void journey(Player p,Screen s){
        if(playthrough==null||sharedStory==null){info(p,s,"플레이 안내",List.of("현재 개발 콘텐츠에 다음 플레이 안내가 등록되지 않았습니다."));return;}
        journal.load(s.session().context(),s.session(),()->valid(p,s)).whenCompleteAsync((view,error)->{
            if(!valid(p,s))return;if(error!=null){info(p,s,"안내 조회 실패",List.of("현재 상태로 다시 조회해 주세요."));return;}
            try{information(p,s,view);var page=journeyView(s,view);var guide=page.guide();var actions=new ArrayList<ActionButton>();
                for(var link:page.links())actions.add(button(p,s,link.label(),()->journeyAction(p,s,page,link)));
                if(preparation!=null)actions.add(button(p,s,"시설·재료·던전 준비",()->preparation(p,advance(p),null,null)));
                actions.add(button(p,s,"이어 하기",()->resume(p,advance(p),1,null,null)));
                actions.add(button(p,s,"관련 인물 안내",()->people(p,advance(p),1,null,null,false,back->journey(p,back))));
                if(courseRoute!=null)actions.add(button(p,s,"현재 코스 동선",()->route(p,advance(p),null,false)));
                actions.add(button(p,s,"안내 새로고침",()->journey(p,advance(p))));actions.add(button(p,s,"퀘스트 목록",()->browse(p,advance(p),"quests",1)));actions.add(button(p,s,"홈",()->home(p)));
                show(p,s,guide.title(),guide.lines().stream().map(line->DialogBody.plainMessage(Component.text(line))).toList(),List.of(),actions);
            }catch(RuntimeException stale){info(p,s,"안내 상태 변경",List.of("현재 상태로 다시 조회해 주세요."));}
        },owner);
    }
    private QuestJourneyView.Page journeyView(Screen s,QuestJournal.View view){var run=s.session().context().storyRunId();return QuestJourneyView.build(playthrough,catalog,view,sharedStory.cached(run),sharedStory.phase(run));}
    private void journeyAction(Player p,Screen s,QuestJourneyView.Page expected,QuestJourneyView.Link link){
        var waiting=advance(p);journal.load(waiting.session().context(),waiting.session(),()->valid(p,waiting)).whenCompleteAsync((view,error)->{
            if(!valid(p,waiting))return;if(error!=null){info(p,waiting,"안내 조회 실패",List.of("행동을 적용하지 않았습니다. 현재 상태로 다시 조회해 주세요."));return;}
            try{information(p,waiting,view);QuestJourneyView.require(journeyView(waiting,view),expected,link);
                switch(link.action()){
                    case DETAIL->detail(p,advance(p),view,"quests",PhoneQuestLinks.questIndex(catalog,view,link.quest()),1,1);
                    case TALK->{advance(p);p.closeDialog();report.accept(p,link.quest());}
                    case RETRY->confirm(p,waiting,view,PhoneCompletion.Operation.RETRY,link.quest(),next->journey(p,next));
                    case TRACK,UNTRACK->{var next=advance(p);journal.track(next.session().context(),next.session(),()->valid(p,next),link.quest(),link.action()==QuestJourneyView.Action.TRACK).whenCompleteAsync((saved,failure)->{if(!valid(p,next))return;if(failure!=null)info(p,next,"추적 저장 실패",List.of("저장 성공으로 표시하지 않습니다."));else journey(p,next);},owner);}
                    case COURSE->course(p,advance(p),1,null);
                    case SHARED->sharedProgress(p,advance(p));
                    case MAP->browse(p,advance(p),"map",1);
                    case DIARY->libraryMenu(p,advance(p),"diary");
                    case NOTICES->notices(p,advance(p),1);
                    case REWARDS->completionList(p,advance(p),"rewards",1);
                }
            }catch(RuntimeException stale){info(p,waiting,"안내 상태 변경",List.of("목표·추적·공유 진행이 달라졌습니다. 현재 상태로 다시 조회해 주세요."));}
        },owner);
    }
    private java.util.function.BiFunction<Player,QuestJournal.View,CourseRoute.Page> courseRoute;
    private CourseOutcome.Board outcomeView(Screen s,QuestJournal.View view){var run=s.session().context().storyRunId();return CourseOutcome.build(playthrough,catalog,view,sharedStory.cached(run),sharedStory.phase(run));}
    private void outcome(Player p,Screen s,int number,CourseOutcome.Board expected,CourseOutcome.Item selected){
        if(playthrough==null||sharedStory==null){info(p,s,"코스 결과 미등록",List.of("현재 콘텐츠의 개인 기록을 확인하세요."));return;}
        journal.load(s.session().context(),s.session(),()->valid(p,s)).whenCompleteAsync((view,error)->{if(!valid(p,s))return;if(error!=null){info(p,s,"결과 조회 실패",List.of("저장·공유 상태를 확인한 뒤 다시 조회하세요."));return;}try{
            information(p,s,view);var board=outcomeView(s,view);if(expected!=null)CourseOutcome.require(board,expected,number,selected);int at=Math.min(number,board.pages());
            if(selected!=null){if(selected.source().quest()!=null)detail(p,advance(p),view,"quests",PhoneQuestLinks.questIndex(catalog,view,selected.source().quest()),1,at,"outcome");else{var row=selected.source();courseRecord(p,(waiting,fresh)->{var latest=outcomeView(waiting,fresh);CourseOutcome.require(latest,board,at,selected);return CourseRecordView.open(playthrough,catalog,fresh,journal.placeFacts(waiting.session().context(),fresh),latest.course(),board.course(),row,board.course().rows().indexOf(row)/PlaythroughOverview.PAGE_SIZE+1);},back->outcome(p,back,at,null,null));}return;}
            var actions=new ArrayList<ActionButton>();if(board.available()){for(var item:board.page(at))if(item.readable())actions.add(button(p,s,item.source().title()+" · 결과/관련 기록",()->outcome(p,advance(p),at,board,item)));if(at>1)actions.add(button(p,s,"앞 결과",()->outcome(p,advance(p),at-1,board,null)));if(at<board.pages())actions.add(button(p,s,"다음 결과",()->outcome(p,advance(p),at+1,board,null)));actions.add(button(p,s,"이어 하기",()->resume(p,advance(p),1,null,null)));actions.add(button(p,s,"다음 행동",()->journey(p,advance(p))));}
            actions.add(button(p,s,"결과 새로고침",()->outcome(p,advance(p),at,null,null)));actions.add(button(p,s,"홈",()->home(p)));show(p,s,"코스 결과·기록 · "+at+" / "+board.pages(),board.book(at).pages().stream().map(line->DialogBody.plainMessage(Component.text(line))).toList(),List.of(),actions);
        }catch(RuntimeException stale){info(p,s,"결과 상태 변경",List.of("개인 결과나 공개 범위가 바뀌었습니다. 결과를 다시 조회하세요."));}},owner);
    }
    private CourseResume.Board resumeView(Screen s,QuestJournal.View view){var run=s.session().context().storyRunId();return CourseResume.build(playthrough,catalog,view,sharedStory.cached(run),sharedStory.phase(run));}
    private void resume(Player p,Screen s,int number,CourseResume.Board expected,CourseResume.Row selected){
        if(playthrough==null||sharedStory==null){info(p,s,"이어 하기 미등록",List.of("현재 콘텐츠의 퀘스트·기록을 확인하세요."));return;}
        journal.load(s.session().context(),s.session(),()->valid(p,s)).whenCompleteAsync((view,error)->{
            if(!valid(p,s))return;if(error!=null){info(p,s,"이어 하기 조회 실패",List.of("저장·공유 상태를 확인하지 못했습니다. 현재 정보로 다시 조회하세요."));return;}
            try{information(p,s,view);var board=resumeView(s,view);if(expected!=null)CourseResume.require(board,expected,number,selected);int at=Math.min(number,board.pages());
                if(selected!=null){detail(p,advance(p),view,"quests",PhoneQuestLinks.questIndex(catalog,view,selected.quest()),1,at,"resume");return;}
                var actions=new ArrayList<ActionButton>();
                if(board.available()){
                    for(var row:board.page(at))actions.add(button(p,s,row.title()+" · "+row.label(),()->resume(p,advance(p),at,board,row)));
                    if(at>1)actions.add(button(p,s,"앞 의뢰",()->resume(p,advance(p),at-1,board,null)));if(at<board.pages())actions.add(button(p,s,"다음 의뢰",()->resume(p,advance(p),at+1,board,null)));
                    actions.add(button(p,s,"코스 결과·기록",()->outcome(p,advance(p),1,null,null)));
                    actions.add(button(p,s,"다음 행동·담당 NPC",()->journey(p,advance(p))));if(courseRoute!=null)actions.add(button(p,s,"현재 코스 동선",()->route(p,advance(p),null,false)));actions.add(button(p,s,"현재 항목의 진행판",()->course(p,advance(p),board.coursePage(),null)));
                }
                actions.add(button(p,s,"이어 하기 새로고침",()->resume(p,advance(p),at,null,null)));actions.add(button(p,s,"홈",()->home(p)));var book=board.book(at);
                show(p,s,"이어 하기 · "+at+" / "+board.pages(),book.pages().stream().map(line->DialogBody.plainMessage(Component.text(line))).toList(),List.of(),actions);
            }catch(RuntimeException stale){info(p,s,"이어 하기 상태 변경",List.of("현재 개인 의뢰와 공동체 상태를 다시 조회하세요."));}
        },owner);
    }
    private NpcGuidance.Page peopleView(Screen s,QuestJournal.View view){return NpcGuidance.build(catalog,view,playthrough!=null&&sharedStory!=null?journeyView(s,view):null,sharedStory==null?Set.of():sharedStory.content().guideNpcs());}
    private void people(Player p,Screen s,int number,NpcGuidance.Page expected,NpcGuidance.Contact selected,boolean talk,Consumer<Screen> back){
        journal.load(s.session().context(),s.session(),()->valid(p,s)).whenCompleteAsync((view,error)->{
            if(!valid(p,s))return;if(error!=null){info(p,s,"인물 조회 실패",List.of("저장·공개 상태를 확인한 뒤 다시 조회하세요."));return;}
            try{information(p,s,view);var page=peopleView(s,view);if(expected!=null)NpcGuidance.require(page,expected,selected);int at=Math.min(number,page.pages());
                if(talk){if(selected==null)throw new IllegalStateException("NPC_GUIDANCE_SELECTION");advance(p);p.closeDialog();peopleTalk.accept(p,selected.npc());return;}
                var actions=new ArrayList<ActionButton>();List<String> lines;
                if(selected!=null){lines=selected.lines();actions.add(button(p,s,"가까운 NPC와 대화",()->people(p,advance(p),at,page,selected,true,back)));actions.add(button(p,s,"인물 목록으로",()->people(p,advance(p),at,null,null,false,back)));}
                else{lines=page.contacts().isEmpty()?List.of("현재 안내할 수 있는 인물이 없습니다."):page.page(at).stream().map(c->c.name()+" · "+String.join(" / ",c.reasons())).toList();for(var c:page.page(at))actions.add(button(p,s,c.name()+" · 연결 정보",()->people(p,advance(p),at,page,c,false,back)));if(at>1)actions.add(button(p,s,"앞 인물",()->people(p,advance(p),at-1,page,null,false,back)));if(at<page.pages())actions.add(button(p,s,"다음 인물",()->people(p,advance(p),at+1,page,null,false,back)));}
                actions.add(button(p,s,"인물 안내 새로고침",()->people(p,advance(p),at,null,null,false,back)));if(back!=null)actions.add(button(p,s,"이전 안내로",()->back.accept(advance(p))));actions.add(button(p,s,"홈",()->home(p)));
                show(p,s,selected==null?"인물 안내 · "+at+" / "+page.pages():selected.name(),lines.stream().map(line->DialogBody.plainMessage(Component.text(line))).toList(),List.of(),actions);
            }catch(RuntimeException stale){info(p,s,"인물 공개 상태 변경",List.of("현재 인물 안내를 다시 조회하세요."));}
        },owner);
    }
    public void courseRoute(java.util.function.BiFunction<Player,QuestJournal.View,CourseRoute.Page> reader){courseRoute=reader;}
    private void route(Player p,Screen s,CourseRoute.Page expected,boolean detail){
        if(courseRoute==null){info(p,s,"동선 안내 미등록",List.of("현재 코스의 다음 플레이 안내를 확인하세요."));return;}
        journal.load(s.session().context(),s.session(),()->valid(p,s)).whenCompleteAsync((view,error)->{
            if(!valid(p,s))return;if(error!=null){info(p,s,"동선 조회 실패",List.of("저장·공개 상태를 확인한 뒤 다시 조회하세요."));return;}
            try{information(p,s,view);var page=courseRoute.apply(p,view);if(expected!=null)CourseRoute.require(page,expected);var selected=page.board().rows().stream().filter(PlaythroughOverview.Row::current).findFirst().orElse(null);
                if(detail){if(selected==null||selected.quest()==null)throw new IllegalStateException("STALE_COURSE_ROUTE");detail(p,advance(p),view,"quests",PhoneQuestLinks.questIndex(catalog,view,selected.quest()),1,1,"course_route");return;}
                var actions=new ArrayList<ActionButton>();if(selected!=null&&selected.quest()!=null)actions.add(button(p,s,"현재 의뢰 상세·후속 처리",()->route(p,advance(p),page,true)));
                if(page.preparation()!=null)actions.add(button(p,s,"현재 시설 준비 안내",()->{
                    var next=advance(p);journal.load(next.session().context(),next.session(),()->valid(p,next)).whenCompleteAsync((fresh,failure)->{if(!valid(p,next))return;if(failure!=null){info(p,next,"동선 조회 실패",List.of("현재 시설을 다시 확인하세요."));return;}try{information(p,next,fresh);var latest=courseRoute.apply(p,fresh);CourseRoute.require(latest,page);preparation(p,next,latest.preparation(),null,null,back->route(p,back,null,false));}catch(RuntimeException changed){info(p,next,"동선 상태 변경",List.of("다음 플레이 안내에서 현재 동선을 다시 조회하세요."));}},owner);
                }));
                actions.add(button(p,s,"관련 인물 안내",()->people(p,advance(p),1,null,null,false,back->route(p,back,null,false))));
                actions.add(button(p,s,"이어 하기",()->resume(p,advance(p),1,null,null)));
                actions.add(button(p,s,"현재 항목의 진행판",()->course(p,advance(p),page.coursePage(),null)));actions.add(button(p,s,"다음 행동·NPC 안내",()->journey(p,advance(p))));actions.add(button(p,s,"동선 새로고침",()->route(p,advance(p),null,false)));actions.add(button(p,s,"홈",()->home(p)));
                show(p,s,"현재 코스 동선",page.lines().stream().map(line->DialogBody.plainMessage(Component.text(line))).toList(),List.of(),actions);
            }catch(RuntimeException changed){info(p,s,"동선 상태 변경",List.of("코스와 공개된 시설을 다시 조회하세요."));}
        },owner);
    }
    private PlaythroughOverview.Board courseView(Screen s,QuestJournal.View view){var run=s.session().context().storyRunId();return PlaythroughOverview.build(playthrough,catalog,view,sharedStory.cached(run),sharedStory.phase(run));}
    private void course(Player p,Screen s,int number,PlaythroughOverview.Board expected){
        if(playthrough==null||sharedStory==null){info(p,s,"코스 미등록",List.of("현재 콘텐츠에 공개 코스가 등록되지 않았습니다."));return;}
        journal.load(s.session().context(),s.session(),()->valid(p,s)).whenCompleteAsync((view,error)->{
            if(!valid(p,s))return;if(error!=null){info(p,s,"진행판 조회 실패",List.of("완료나 빈 기록으로 표시하지 않습니다. 현재 상태로 다시 조회해 주세요."));return;}
            try{information(p,s,view);var board=courseView(s,view);if(expected!=null)PlaythroughOverview.require(board,expected);var page=board.page(Math.min(number,board.pages()));var actions=new ArrayList<ActionButton>();
                for(var row:page.rows())if(row.quest()!=null)actions.add(button(p,s,row.title()+" · 상세",()->courseDetail(p,s,board,row,page.number())));
                for(var row:page.rows())if(CourseRecordView.readable(row))actions.add(button(p,s,row.title()+" · "+CourseRecordView.label(row),()->courseRecord(p,(waiting,fresh)->CourseRecordView.open(playthrough,catalog,fresh,journal.placeFacts(waiting.session().context(),fresh),courseView(waiting,fresh),board,row,page.number()))));
                if(page.number()>1)actions.add(button(p,s,"앞 항목",()->course(p,advance(p),page.number()-1,board)));
                if(page.number()<page.pages())actions.add(button(p,s,"다음 항목",()->course(p,advance(p),page.number()+1,board)));
                if(courseRoute!=null)actions.add(button(p,s,"현재 코스 동선",()->route(p,advance(p),null,false)));
                actions.add(button(p,s,"다음 행동 안내",()->journey(p,advance(p))));actions.add(button(p,s,"진행판 새로고침",()->course(p,advance(p),page.number(),null)));
                if(preparation!=null)actions.add(button(p,s,"시설·재료·던전 준비",()->preparation(p,advance(p),null,null,page.number())));
                actions.add(button(p,s,"홈",()->home(p)));show(p,s,"공개 코스 진행판",page.lines().stream().map(line->DialogBody.plainMessage(Component.text(line))).toList(),List.of(),actions);
            }catch(RuntimeException stale){info(p,s,"코스 기록 변경",List.of("현재 공개 정보와 개인·공유 진행을 다시 조회해 주세요."));}
        },owner);
    }
    private void courseDetail(Player p,Screen s,PlaythroughOverview.Board shown,PlaythroughOverview.Row selected,int number){
        var waiting=advance(p);journal.load(waiting.session().context(),waiting.session(),()->valid(p,waiting)).whenCompleteAsync((view,error)->{
            if(!valid(p,waiting))return;if(error!=null){info(p,waiting,"진행판 조회 실패",List.of("현재 상태로 다시 조회해 주세요."));return;}
            try{information(p,waiting,view);PlaythroughOverview.require(courseView(waiting,view),shown,selected);detail(p,advance(p),view,"quests",PhoneQuestLinks.questIndex(catalog,view,selected.quest()),1,number,"course");}
            catch(RuntimeException stale){info(p,waiting,"코스 기록 변경",List.of("선택한 기록을 현재 상태로 다시 조회해 주세요."));}
        },owner);
    }
    private void courseRecord(Player p,java.util.function.BiFunction<Screen,QuestJournal.View,CourseRecordView.Screen> build){courseRecord(p,build,null);}
    private void courseRecord(Player p,java.util.function.BiFunction<Screen,QuestJournal.View,CourseRecordView.Screen> build,Consumer<Screen> resultBack){
        var waiting=advance(p);journal.load(waiting.session().context(),waiting.session(),()->valid(p,waiting)).whenCompleteAsync((view,error)->{
            if(!valid(p,waiting))return;if(error!=null){courseRecordError(p,waiting);return;}
            try{information(p,waiting,view);var screen=build.apply(waiting,view);var page=screen.page();var actions=new ArrayList<ActionButton>();
                for(var link:page.links())actions.add(button(p,waiting,link.label(),()->courseRecordAction(p,screen,CourseRecordView.Action.RELATED,link,resultBack)));
                if(page.number()>1)actions.add(button(p,waiting,"앞 내용",()->courseRecordAction(p,screen,CourseRecordView.Action.PREVIOUS,null,resultBack)));
                if(page.number()<page.pages())actions.add(button(p,waiting,"다음 내용",()->courseRecordAction(p,screen,CourseRecordView.Action.NEXT,null,resultBack)));
                if(screen.path().size()>1)actions.add(button(p,waiting,"이전 정보",()->courseRecordAction(p,screen,CourseRecordView.Action.BACK,null,resultBack)));
                actions.add(button(p,waiting,"기록 새로고침",()->courseRecordAction(p,screen,CourseRecordView.Action.REFRESH,null,resultBack)));
                if(resultBack!=null)actions.add(button(p,waiting,"코스 결과로",()->resultBack.accept(advance(p))));
                actions.add(button(p,waiting,"코스 진행판으로",()->course(p,advance(p),screen.coursePage(),null)));actions.add(button(p,waiting,"홈",()->home(p)));
                show(p,waiting,screen.record().title(),page.lines().stream().map(line->DialogBody.plainMessage(Component.text(line))).toList(),List.of(),actions);
            }catch(RuntimeException stale){courseRecordError(p,waiting);}
        },owner);
    }
    private void courseRecordAction(Player p,CourseRecordView.Screen shown,CourseRecordView.Action action,CourseRecordView.Link selected,Consumer<Screen> resultBack){
        courseRecord(p,(waiting,view)->CourseRecordView.navigate(playthrough,catalog,view,journal.placeFacts(waiting.session().context(),view),courseView(waiting,view),shown,action,selected),resultBack);
    }
    private void courseRecordError(Player p,Screen s){
        show(p,s,"관련 기록 확인 필요",List.of(DialogBody.plainMessage(Component.text("관련 기록이나 공개 범위를 확인하지 못했습니다. 현재 상태로 다시 조회해 주세요."))),List.of(),List.of(button(p,s,"코스 진행판으로",()->course(p,advance(p),1,null)),button(p,s,"홈",()->home(p))));
    }
    private void sharedProgress(Player p,Screen s){
        if(sharedStory==null){info(p,s,"공동 조사 안내",List.of("공유 진행 엔진이 연결되지 않았습니다."));return;}
        journal.load(s.session().context(),s.session(),()->valid(p,s)).whenCompleteAsync((view,error)->{
            if(!valid(p,s))return;if(error!=null){info(p,s,"조회 오류",List.of("공유 진행을 확인하지 못했습니다. 진행을 확정하지 않았습니다."));return;}
            try{information(p,s,view);var run=s.session().context().storyRunId();var panel=io.github.jesterportfolio.hearth.story.StoryGuide.panel(run,sharedStory.content(),sharedStory.cached(run),sharedStory.phase(run),view.exploration().conditions());var actions=new ArrayList<ActionButton>();
                panel.action().ifPresent(action->actions.add(button(p,s,action.label(),()->{information(p,s,view);requestSharedProgress(p,s,action);})));
                actions.add(button(p,s,"새로고침",()->sharedProgress(p,advance(p))));actions.add(button(p,s,"스토리 진행",()->loadLibrary(p,advance(p),"shared",1)));actions.add(button(p,s,"홈",()->home(p)));
                show(p,s,"공동 조사 안내",panel.lines().stream().map(line->DialogBody.plainMessage(Component.text(line))).toList(),List.of(),actions);
            }catch(RuntimeException stale){info(p,s,"조회 상태 변경",List.of("현재 상태로 다시 조회해 주세요."));}
        },owner);
    }
    private void requestSharedProgress(Player p,Screen s,io.github.jesterportfolio.hearth.story.StoryGuide.Action action){
        if(completionFlow.current(s.session())!=null&&completionFlow.current(s.session()).phase()==CompletionFlow.Phase.PENDING||!pendingActions.add(s.session()))throw new IllegalStateException("STORY_REQUEST_PENDING");
        var waiting=advance(p);show(p,waiting,"진행 조건 확인 중",List.of(DialogBody.plainMessage(Component.text("접속 대상과 저장 결과를 확인 중입니다. 아직 공유 결과가 확정되지 않았습니다."))),List.of(),List.of(button(p,waiting,"홈",()->home(p))));
        java.util.concurrent.CompletionStage<?> work;
        try{work=storyRequest.request(p,action,()->valid(p,waiting));}catch(RuntimeException error){work=java.util.concurrent.CompletableFuture.failedFuture(error);}
        work.whenCompleteAsync((result,error)->{pendingActions.remove(s.session());if(!valid(p,waiting))return;
            if(error!=null){info(p,waiting,"진행 보류",List.of("접속 대상·조건·저장 상태를 확인하지 못했습니다. 진행을 확정하지 않았습니다."));return;}
            if(action.command().equals("/hearthstory vote")){advance(p);p.closeDialog();p.sendMessage(Component.text("분기 투표가 시작됐습니다. 채팅의 선택지로 투표해 주세요. 분기는 아직 확정되지 않았습니다."));}
            else sharedProgress(p,advance(p));
        },owner);
    }
    private void libraryMenu(Player p,Screen s,String menu){
        var actions=new ArrayList<ActionButton>();
        String[] sections=menu.equals("diary")?new String[]{"STORY","PERSON","EXPLORATION","CHRONICLE"}:new String[]{"members","shared","conditions"};
        for(String section:sections)actions.add(button(p,s,PhoneLibrary.title(section),()->loadLibrary(p,advance(p),section,1)));
        if(menu.equals("diary"))actions.add(button(p,s,"인물 안내",()->people(p,advance(p),1,null,null,false,back->libraryMenu(p,back,"diary"))));
        if(menu.equals("diary"))actions.add(button(p,s,"공동체 정보",()->libraryMenu(p,advance(p),"community")));
        if(references.containsKey(s.session()))actions.add(button(p,s,"최근 AI 참고자료",()->referenceList(p,advance(p))));
        actions.add(button(p,s,"홈",()->home(p)));
        show(p,s,menu.equals("diary")?"기록":"공동체",List.of(DialogBody.plainMessage(Component.text(menu.equals("diary")?"개인 기록과 공동체 공유 기록을 구분합니다.":"구성원·스토리 진행·진행 조건만 확인합니다. 가입/초대/탈퇴/권한 관리 기능은 없습니다."))),List.of(),actions);
    }
    private void loadLibrary(Player p,Screen s,String section,int page){
        journal.load(s.session().context(),s.session(),()->valid(p,s)).whenCompleteAsync((view,error)->{
            if(!valid(p,s))return;if(error!=null){info(p,s,"조회 오류",List.of("빈 기록으로 처리하지 않습니다. 다시 조회해 주세요."));return;}librarySnapshot(p,s,view,section,page);
        },owner);
    }
    private void librarySnapshot(Player p,Screen s,QuestJournal.View view,String section,int page){
        if(!valid(p,s))return;
        try{
            information(p,s,view);var rows=PhoneLibrary.rows(catalog,view,section);int total=Math.max(1,(rows.size()+5)/6),current=Math.min(Math.max(page,1),total);var actions=new ArrayList<ActionButton>();
            for(int i=(current-1)*6;i<Math.min(rows.size(),current*6);i++){int index=i;var row=rows.get(i);actions.add(button(p,s,row.title(),()->{
                var next=advance(p);if(row.targetSection()!=null)detail(p,next,view,row.targetSection(),row.targetIndex(),1,current,section);else libraryDetail(p,next,view,section,index,1,current);
            }));}
            if(current>1)actions.add(button(p,s,"이전 페이지",()->librarySnapshot(p,advance(p),view,section,current-1)));
            if(current<total)actions.add(button(p,s,"다음 페이지",()->librarySnapshot(p,advance(p),view,section,current+1)));
            if(Set.of("shared","conditions").contains(section)&&sharedStory!=null)actions.add(button(p,s,"공동 조사 안내",()->{information(p,s,view);sharedProgress(p,advance(p));}));
            actions.add(button(p,s,"새로고침",()->loadLibrary(p,advance(p),section,1)));
            actions.add(button(p,s,"분류 메뉴",()->{var next=advance(p);if(Set.of("tracked","reporting","completed","branches").contains(section))browse(p,next,"quests",1);else if(section.equals("regions"))browse(p,next,"map",1);else libraryMenu(p,next,Set.of("members","shared","conditions").contains(section)?"community":"diary");}));
            actions.add(button(p,s,"홈",()->home(p)));
            show(p,s,PhoneLibrary.title(section),List.of(DialogBody.plainMessage(Component.text(rows.isEmpty()?"현재 공개된 정보가 없습니다.":current+" / "+total))),List.of(),actions);
        }catch(RuntimeException stale){info(p,s,"조회 상태 변경",List.of("현재 상태로 다시 조회해 주세요."));}
    }
    private void libraryDetail(Player p,Screen s,QuestJournal.View view,String section,int index,int page,int listPage){libraryDetail(p,s,view,section,index,page,listPage,null);}
    private void libraryDetail(Player p,Screen s,QuestJournal.View view,String section,int index,int page,int listPage,PhoneReaderLinks.Source origin){
        if(!valid(p,s))return;
        try{
            information(p,s,view);var entry=PhoneLibrary.rows(catalog,view,section).get(index);var text=PhonePages.text(entry.title(),entry.lines());var actions=new ArrayList<ActionButton>();
            if(Set.of("STORY","PERSON","EXPLORATION").contains(section))actions.add(button(p,s,"이 기록 AI 도움",()->help(p,s,PhoneHelp.record(catalog,view,information(p,s,view),PhoneReaderLinks.library(catalog,view,section,index)))));
            if(Set.of("STORY","PERSON","EXPLORATION","CHRONICLE","regions").contains(section))readerButton(p,s,view,PhoneReaderLinks.library(catalog,view,section,index),actions);
            if(origin!=null)actions.add(button(p,s,"이전 정보",()->reader(p,advance(p),origin,1)));
            if(page>1)actions.add(button(p,s,"앞 내용",()->libraryDetail(p,advance(p),view,section,index,page-1,listPage,origin)));
            if(page<text.pages().size())actions.add(button(p,s,"다음 내용",()->libraryDetail(p,advance(p),view,section,index,page+1,listPage,origin)));
            actions.add(button(p,s,"목록",()->librarySnapshot(p,advance(p),view,section,listPage)));actions.add(button(p,s,"홈",()->home(p)));
            show(p,s,text.title(),List.of(DialogBody.plainMessage(Component.text(page+" / "+text.pages().size()+"\n\n"+text.pages().get(page-1)))),List.of(),actions);
        }catch(RuntimeException stale){info(p,s,"조회 상태 변경",List.of("현재 상태로 다시 조회해 주세요."));}
    }
    private void readerButton(Player p,Screen s,QuestJournal.View view,PhoneReaderLinks.Source source,List<ActionButton> actions){
        if(!PhoneReaderLinks.links(catalog,view,journal.placeFacts(s.session().context(),view),source).isEmpty())actions.add(button(p,s,"관련 정보",()->reader(p,advance(p),source,1)));
    }
    private void reader(Player p,Screen s,PhoneReaderLinks.Source source,int page){
        journal.load(s.session().context(),s.session(),()->valid(p,s)).whenCompleteAsync((view,error)->{
            if(!valid(p,s))return;if(error!=null){info(p,s,"조회 오류",List.of("관련 정보를 확인하지 못했습니다. 빈 기록으로 처리하지 않습니다."));return;}
            readerSnapshot(p,s,view,source,page);
        },owner);
    }
    private void readerSnapshot(Player p,Screen s,QuestJournal.View view,PhoneReaderLinks.Source source,int page){
        if(!valid(p,s))return;try{information(p,s,view);var links=PhoneReaderLinks.links(catalog,view,journal.placeFacts(s.session().context(),view),source);int total=Math.max(1,(links.size()+5)/6),current=Math.min(Math.max(1,page),total);var actions=new ArrayList<ActionButton>();
            for(var link:links.subList((current-1)*6,Math.min(current*6,links.size())))actions.add(button(p,s,link.label(),()->readerAction(p,advance(p),source,link)));
            if(current>1)actions.add(button(p,s,"이전 페이지",()->readerSnapshot(p,advance(p),view,source,current-1)));
            if(current<total)actions.add(button(p,s,"다음 페이지",()->readerSnapshot(p,advance(p),view,source,current+1)));
            actions.add(button(p,s,"원래 정보",()->readerTarget(p,advance(p),view,source.section(),source.key(),null)));actions.add(button(p,s,"홈",()->home(p)));
            show(p,s,"관련 정보",List.of(DialogBody.plainMessage(Component.text(source.entry().title()+" · "+current+" / "+total))),List.of(),actions);
        }catch(RuntimeException stale){info(p,s,"정보 상태 변경",List.of("기록이나 공개 범위가 바뀌었습니다. 앱에서 다시 조회해 주세요."));}
    }
    private void readerAction(Player p,Screen s,PhoneReaderLinks.Source source,PhoneReaderLinks.Link selected){
        journal.load(s.session().context(),s.session(),()->valid(p,s)).whenCompleteAsync((view,error)->{
            if(!valid(p,s))return;if(error!=null){info(p,s,"조회 오류",List.of("관련 정보 요청을 적용하지 않았습니다."));return;}
            try{information(p,s,view);PhoneReaderLinks.require(catalog,view,journal.placeFacts(s.session().context(),view),source,selected);
                if(selected.kind()==PhoneReaderLinks.Kind.NPC){advance(p);p.closeDialog();contact.accept(p,selected.key());}
                else readerTarget(p,advance(p),view,selected.section(),selected.key(),source);
            }catch(RuntimeException stale){info(p,s,"정보 상태 변경",List.of("현재 공개된 기록과 연결을 다시 확인해 주세요."));}
        },owner);
    }
    private void readerTarget(Player p,Screen s,QuestJournal.View view,String section,String key,PhoneReaderLinks.Source origin){
        information(p,s,view);int index=PhoneReaderLinks.index(catalog,view,section,key);
        if(section.equals("map"))detail(p,s,view,"map",index,1,1,"map",origin);else libraryDetail(p,s,view,section,index,1,1,origin);
    }
    private void notices(Player p,Screen s,int page){
        journal.load(s.session().context(),s.session(),()->valid(p,s)).whenCompleteAsync((view,error)->{
            if(!valid(p,s))return;if(error!=null){info(p,s,"알림 조회 실패",List.of("알림이 없는 상태로 처리하지 않습니다. 저장 상태를 확인해 주세요."));return;}
            noticeSnapshot(p,s,view,page);
        },owner);
    }
    private void noticeSnapshot(Player p,Screen s,QuestJournal.View view,int page){
        if(!valid(p,s))return;
        try{
            information(p,s,view);var rows=PhoneQuestLinks.notices(catalog,view);int pages=Math.max(1,(rows.size()+5)/6);page=Math.min(Math.max(1,page),pages);final int currentPage=page;var actions=new ArrayList<ActionButton>();
            for(int i=(page-1)*6;i<Math.min(rows.size(),page*6);i++){var n=rows.get(i);actions.add(button(p,s,n.label(),()->openNotice(p,s,view,n.source())));}
            if(page>1)actions.add(button(p,s,"이전 알림",()->noticeSnapshot(p,advance(p),view,currentPage-1)));
            if(page<pages)actions.add(button(p,s,"다음 알림",()->noticeSnapshot(p,advance(p),view,currentPage+1)));
            if(rows.stream().anyMatch(n->!n.source().read()))actions.add(button(p,s,"모두 읽음",()->{
                information(p,s,view);var next=advance(p);journal.readAll(next.session().context(),next.session(),()->valid(p,next)).whenCompleteAsync((saved,error)->{
                    if(!valid(p,next))return;if(error!=null)info(p,next,"읽음 저장 실패",List.of("다시 조회해 주세요."));else noticeSnapshot(p,next,saved,1);
                },owner);
            }));
            actions.add(button(p,s,"새로고침",()->notices(p,advance(p),1)));actions.add(button(p,s,"퀘스트 목록",()->browse(p,advance(p),"quests",1)));actions.add(button(p,s,"홈",()->home(p)));
            long unread=rows.stream().filter(n->!n.source().read()).count();show(p,s,"개인 알림 · 미확인 "+unread,List.of(DialogBody.plainMessage(Component.text(rows.isEmpty()?"확인할 개인 알림이 없습니다.":page+" / "+pages+" · 알림을 누르면 읽음 저장 후 관련 정보를 엽니다."))),List.of(),actions);
        }catch(RuntimeException stale){info(p,s,"알림 상태 변경",List.of("현재 상태로 다시 조회해 주세요."));}
    }
    private void openNotice(Player p,Screen s,QuestJournal.View view,io.github.jesterportfolio.hearth.persistence.QuestJournalStore.Notice source){
        information(p,s,view);PhoneQuestLinks.find(catalog,view,source);var next=advance(p);
        var work=source.type().equals("PLACE")?journal.readPlaceNotice(next.session().context(),next.session(),()->valid(p,next),source.quest()):journal.readNotice(next.session().context(),next.session(),()->valid(p,next),source.quest(),source.revision());
        work.whenCompleteAsync((saved,error)->{
            if(!valid(p,next))return;if(error!=null){info(p,next,"알림 읽음 저장 실패",List.of("성공으로 표시하지 않습니다. 다시 조회해 주세요."));return;}
            try{information(p,next,saved);var target=PhoneQuestLinks.find(catalog,saved,source);detail(p,next,saved,target.section(),target.target(),1,1);}
            catch(RuntimeException stale){info(p,next,"관련 정보 변경",List.of("현재 정보로 다시 조회해 주세요."));}
        },owner);
    }
    private void info(Player p,Screen s,String title,List<String> lines){show(p,s,title,lines.stream().map(line->DialogBody.plainMessage(Component.text(line))).toList(),List.of(),List.of(button(p,s,"홈",()->home(p))));}
    private void settings(Player p,Screen s){
        journal.load(s.session().context(),s.session(),()->valid(p,s)).whenCompleteAsync((v,e)->{
            if(!valid(p,s))return;if(e!=null){info(p,s,"설정",List.of("설정을 조회할 수 없습니다."));return;}
            String[] keys={"hud","minimap","quest_marker","npc_marker","player_marker","place_marker"};
            String[] labels={"퀘스트 HUD","미니맵 표시","퀘스트 마커","NPC 마커","플레이어 마커","특수 장소 마커"};
            var actions=new ArrayList<ActionButton>();
            for(int i=0;i<keys.length;i++){String key=keys[i];boolean on=v.journal().preferences().flag(key);
                actions.add(button(p,s,labels[i]+": "+(on?"켜짐":"꺼짐"),()->{
                    var next=advance(p);
                    journal.setting(next.session().context(),next.session(),()->valid(p,next),key,!on).whenCompleteAsync((saved,error)->{
                        if(!valid(p,next))return;
                        if(error!=null)info(p,next,"설정 저장 실패",List.of("기존 값을 성공한 것처럼 변경하지 않습니다. 저장 상태를 확인해 주세요."));else settings(p,next);
                    },owner);
                }));
            }
            actions.add(button(p,s,"홈",()->home(p)));
            show(p,s,"개인 표시 설정",List.of(DialogBody.plainMessage(Component.text("표시 설정만 변경합니다. 미니맵 렌더러는 아직 미구현입니다."))),List.of(),actions);
        },owner);
    }
    private ActionButton chatButton(Player p,Screen s,String label,Runnable action){boolean hasInput=!s.chat().busy();return ActionButton.builder(Component.text(label)).width(110).action(DialogAction.customClick((response,audience)->{if(audience instanceof Player who&&who.getUniqueId().equals(p.getUniqueId())){String draft=hasInput?response.getText("question"):null;dispatch(p,s,()->{if(!s.chat().busy()&&draft!=null)s.chat().draft(draft);action.run();});}},options())).build();}
    private void help(Player p,Screen s,PhoneHelp.Selection selection){
        if(!valid(p,s))return;if(s.chat().busy()){info(p,s,"AI 요청 처리 중",List.of("기존 요청을 취소하거나 완료한 뒤 다른 항목을 선택해 주세요."));return;}
        helpSelections.put(s.session(),selection);contextModes.put(s.session(),selection.section());references.remove(s.session());if(s.chat().draft().isBlank())s.chat().draft(PhoneHelp.question());chat(p);
    }
    private void returnHelp(Player p,Screen s){
        var selected=helpSelections.get(s.session());if(selected==null){chat(p);return;}
        var next=advance(p);journal.load(next.session().context(),next.session(),()->valid(p,next)).whenCompleteAsync((view,error)->{
            if(!valid(p,next))return;if(error!=null){info(p,next,"선택 정보 조회 실패",List.of("저장 상태를 확인하고 다시 조회해 주세요."));return;}
            try{var current=PhoneHelp.refresh(catalog,view,information(p,next,view),selected);helpSelections.put(next.session(),current);detail(p,next,view,current.section(),current.source().index(),1,1);}catch(RuntimeException stale){info(p,next,"선택 정보 변경",List.of("항목이 변경됐거나 더 이상 공개되지 않습니다. 앱에서 다시 선택해 주세요."));}
        },owner);
    }
    private void chat(Player p){
        var s=advance(p);var body=new ArrayList<DialogBody>();body.add(DialogBody.plainMessage(Component.text("최근 대화 · 현재 접속 세션에서만 유지\n"+PhoneContext.MODES.get(contextModes.getOrDefault(s.session(),"none")))));
        if(contextModes.getOrDefault(s.session(),"none").equals("none"))body.add(DialogBody.plainMessage(Component.text("일반 과학·기술 원리를 질문할 수 있습니다. 현재 게임 진행·좌표·보상 정보는 전달하지 않습니다.")));
        var selected=helpSelections.get(s.session());if(selected!=null)body.add(DialogBody.plainMessage(Component.text("선택한 자료 한 항목: "+selected.source().title())));
        if(references.containsKey(s.session()))body.add(DialogBody.plainMessage(Component.text("답변의 자료 번호는 ‘최근 요청 참고자료’에서 확인할 수 있습니다.")));
        body.add(DialogBody.plainMessage(Component.text(s.chat().includeHistory()?"같은 자료 범위의 최근 대화 일부를 함께 보냅니다.":"이전 대화를 보내지 않고 질문합니다. 화면의 대화 기록은 유지합니다.")));
        for(var turn:s.chat().history()){body.add(DialogBody.plainMessage(Component.text("나: "+turn.question())));body.add(DialogBody.plainMessage(Component.text("AI: "+turn.answer())));}
        if(s.chat().error()!=null)body.add(DialogBody.plainMessage(Component.text(s.chat().error())));
        if(s.chat().busy())body.add(DialogBody.plainMessage(Component.text("나: "+s.chat().question()+"\n응답 대기 중입니다. 잠시 후 응답 확인을 눌러 주세요.")));
        var actions=new ArrayList<ActionButton>();
        if(!s.chat().busy())actions.add(ActionButton.builder(Component.text("전송")).action(DialogAction.customClick((response,audience)->{
            if(audience instanceof Player who&&who.getUniqueId().equals(p.getUniqueId())){String question=response.getText("question");dispatch(p,s,()->{submitChat(p,s,question);});}
        },options())).build());
        if(s.chat().canRetry())actions.add(chatButton(p,s,"실패한 질문 재시도",()->{submitChat(p,s,s.chat().question());}));
        if(!s.chat().busy()&&(!s.chat().history().isEmpty()||s.chat().error()!=null))actions.add(chatButton(p,s,"대화 정리",()->clearConfirm(p)));
        if(s.chat().busy())actions.add(chatButton(p,s,"요청 취소",()->{s.chat().cancel();references.remove(s.session());chat(p);}));
        if(!s.chat().busy())actions.add(chatButton(p,s,"도움 범위 선택",()->contextMenu(p)));
        if(!s.chat().busy())actions.add(chatButton(p,s,"이전 대화 함께 보내기: "+(s.chat().includeHistory()?"켬":"끔"),()->{s.chat().includeHistory(!s.chat().includeHistory());chat(p);}));
        if(selected!=null){actions.add(chatButton(p,s,"원래 정보",()->returnHelp(p,s)));if(!s.chat().busy())actions.add(chatButton(p,s,"항목 선택 해제",()->{helpSelections.remove(s.session());references.remove(s.session());contextModes.put(s.session(),"none");chat(p);}));}
        if(references.containsKey(s.session()))actions.add(chatButton(p,s,"최근 요청 참고자료",()->referenceList(p,advance(p))));
        actions.add(chatButton(p,s,"응답 확인",()->chat(p)));actions.add(chatButton(p,s,"홈",()->home(p)));
        show(p,s,"AI 어시스턴트",body,s.chat().busy()?List.of():List.of(DialogInput.text("question",Component.text("질문")).initial(s.chat().draft()).maxLength(256).width(240).build()),actions);
    }
    private void contextMenu(Player p){
        var s=advance(p);var actions=new ArrayList<ActionButton>();
        PhoneContext.MODES.forEach((mode,label)->actions.add(button(p,s,label,()->{if(!s.chat().busy()){contextModes.put(s.session(),mode);helpSelections.remove(s.session());references.remove(s.session());chat(p);}})));
        actions.add(button(p,s,"취소",()->chat(p)));
        show(p,s,"AI 도움 범위",List.of(DialogBody.plainMessage(Component.text("선택한 범위의 본인 공개 정보만 요청 시 읽습니다. 다른 사람의 개인 정보·미발견 비밀·원본 콘텐츠는 전달하지 않습니다. 최근 대화 일부도 포함되며 실제 AI는 게임 상태를 변경할 수 없습니다."))),List.of(),actions);
    }
    private void submitChat(Player p,Screen s,String question){
        if(!valid(p,s)||s.chat().busy())return;
        if(question==null||question.isBlank()||question.codePointCount(0,question.length())>256){s.chat().submit(question).exceptionally(error->null);chat(p);return;}
        s.chat().draft(question);
        String mode=contextModes.getOrDefault(s.session(),"none");
        if(mode.equals("none")){references.remove(s.session());s.chat().submit(question,"",()->!closed&&players.current(p)==s.session()).exceptionally(error->null);chat(p);return;}
        var next=advance(p);
        show(p,next,"공개 정보 확인 중",List.of(DialogBody.plainMessage(Component.text("최신 정보를 확인한 뒤 AI에 질문합니다. 아직 전송 완료 상태가 아닙니다."))),List.of(),List.of(button(p,next,"취소/홈",()->home(p))));
        journal.load(next.session().context(),next.session(),()->valid(p,next)).whenCompleteAsync((view,error)->{
            if(!valid(p,next))return;
            if(error!=null){info(p,next,"AI 정보 조회 실패",List.of("빈 정보로 대체해 전송하지 않습니다. 저장 상태를 확인한 뒤 다시 시도해 주세요."));return;}
            try{
                var data=information(p,next,view);var selected=helpSelections.get(next.session());
                var plan=selected==null?PhoneGrounding.search(catalog,view,data,mode,question):PhoneGrounding.single(catalog,view,data,question,selected);
                previewContext(p,next,view,plan,1);
            }catch(RuntimeException invalid){info(p,next,"AI 정보 상태 변경",List.of("현재 공개 정보로 다시 요청해 주세요."));}
        },owner);
    }
    private void previewContext(Player p,Screen s,QuestJournal.View view,PhoneGrounding.Plan plan,int page){
        if(!valid(p,s))return;
        try{information(p,s,view);var text=plan.sendable()?plan.result().context():"전송할 자료를 한 개 이상 선택하세요.";var pages=PhonePages.text("전송할 공개 자료",List.of("질문: "+plan.question(),s.chat().includeHistory()?"같은 자료 범위의 최근 대화 일부도 함께 보냅니다.":"이전 대화는 보내지 않습니다.","질문에 맞춘 연속 발췌만 보냅니다. … 표시는 생략된 부분이며 전체 자료는 따로 읽을 수 있습니다.",text));var actions=new ArrayList<ActionButton>();
            if(page>1)actions.add(button(p,s,"앞 자료",()->previewContext(p,advance(p),view,plan,page-1)));
            if(page<pages.pages().size())actions.add(button(p,s,"다음 자료",()->previewContext(p,advance(p),view,plan,page+1)));
            for(var material:plan.materials()){
                var item=material.item();if(!plan.single())actions.add(button(p,s,(plan.included().contains(item.key())?"[포함] ":"[제외] ")+item.source().title(),()->previewContext(p,advance(p),view,plan.toggle(item.key()),1)));
                actions.add(button(p,s,item.source().title()+" · 전체 자료",()->previewMaterial(p,advance(p),view,plan,material,1)));
            }
            if(plan.sendable())actions.add(button(p,s,"선택 자료로 질문",()->submitGrounded(p,s,plan)));
            actions.add(button(p,s,"전송 취소",()->chat(p)));
            show(p,s,"공개 자료 확인 · "+plan.selected().size()+"개 선택",List.of(DialogBody.plainMessage(Component.text(page+" / "+pages.pages().size()+"\n\n"+pages.pages().get(page-1)))),List.of(),actions);
        }catch(RuntimeException stale){info(p,s,"참고자료 상태 변경",List.of("현재 공개 정보로 다시 요청해 주세요."));}
    }
    private void previewMaterial(Player p,Screen s,QuestJournal.View view,PhoneGrounding.Plan plan,PhoneGrounding.Material material,int page){
        if(!valid(p,s))return;try{information(p,s,view);if(!plan.materials().contains(material))throw new IllegalStateException("STALE_AI_MATERIAL");var detail=PhoneGrounding.detail(material);var actions=new ArrayList<ActionButton>();
            if(page>1)actions.add(button(p,s,"앞 내용",()->previewMaterial(p,advance(p),view,plan,material,page-1)));if(page<detail.pages().size())actions.add(button(p,s,"다음 내용",()->previewMaterial(p,advance(p),view,plan,material,page+1)));
            if(!plan.single())actions.add(button(p,s,plan.included().contains(material.item().key())?"이 자료 제외":"이 자료 포함",()->previewContext(p,advance(p),view,plan.toggle(material.item().key()),1)));
            actions.add(button(p,s,"자료 선택으로",()->previewContext(p,advance(p),view,plan,1)));actions.add(button(p,s,"전송 취소",()->chat(p)));
            show(p,s,detail.title(),List.of(DialogBody.plainMessage(Component.text(page+" / "+detail.pages().size()+"\n\n"+detail.pages().get(page-1)))),List.of(),actions);
        }catch(RuntimeException stale){info(p,s,"자료 상태 변경",List.of("현재 자료로 다시 선택하세요."));}
    }
    private void submitGrounded(Player p,Screen s,PhoneGrounding.Plan expected){
        var next=advance(p);
        java.util.function.BooleanSupplier sessionCurrent=()->!closed&&players.current(p)==next.session()&&contextModes.getOrDefault(next.session(),"none").equals(expected.mode())&&(expected.single()?PhoneHelp.same(helpSelections.get(next.session()),expected.materials().getFirst().item()):helpSelections.get(next.session())==null);
        journal.load(next.session().context(),next.session(),()->valid(p,next)&&sessionCurrent.getAsBoolean()).whenCompleteAsync((view,error)->{
            if(!valid(p,next)||!sessionCurrent.getAsBoolean())return;if(error!=null){info(p,next,"전송 자료 확인 실패",List.of("최신 자료를 확인하지 못해 전송하지 않았습니다. 질문 초안은 유지합니다."));return;}
            try{var plan=PhoneGrounding.refresh(catalog,view,information(p,next,view),expected.sent());var refs=references.begin(next.session(),plan);
                long[] stamp={-1};java.util.function.BooleanSupplier approved=()->{if(!sessionCurrent.getAsBoolean())return false;try{journal.projectInformation(next.session().context(),view,sessionCurrent);return stamp[0]<0||stamp[0]==journal.informationStamp(next.session().context());}catch(RuntimeException stale){return false;}};
                next.chat().submit(plan.question(),plan.result().context(),plan.mode(),approved,()->journal.load(next.session().context(),next.session(),sessionCurrent).thenApplyAsync(latest->{
                    var current=PhoneGrounding.refresh(catalog,latest,journal.projectInformation(next.session().context(),latest,sessionCurrent),plan);stamp[0]=journal.informationStamp(next.session().context());references.update(refs,current);return true;
                },owner),plan.scope()).exceptionally(failure->null);chat(p);
            }catch(RuntimeException stale){info(p,next,"선택 자료 변경",List.of("자료가 변경됐거나 공개 상태를 확인할 수 없습니다. 현재 자료로 다시 선택해 주세요. 질문 초안은 유지합니다."));}
        },owner);
    }
    private void referenceList(Player p,Screen s){reference(p,s,null,1);}
    private void reference(Player p,Screen s,String key,int page){
        var expected=references.get(s.session());if(expected==null){info(p,s,"참고자료 없음",List.of("이 세션에서 전송한 게임 도움 자료가 없습니다."));return;}
        journal.load(s.session().context(),s.session(),()->valid(p,s)).whenCompleteAsync((view,error)->{
            if(!valid(p,s))return;if(error!=null){info(p,s,"참고자료 조회 실패",List.of("현재 자료를 확인하지 못했습니다. 최신 정보로 다시 조회하세요."));return;}
            try{if(!references.current(expected))throw new IllegalStateException("STALE_AI_REFERENCE");var plan=PhoneGrounding.refresh(catalog,view,information(p,s,view),expected.plan());references.update(expected,plan);var actions=new ArrayList<ActionButton>();List<String> lines;String title;
                if(key==null){title="최근 게임 도움 참고자료";lines=List.of(plan.materials().isEmpty()?"현재 공개 정보에서 일치하는 자료가 없었던 요청입니다.":"AI에 전송한 공개 자료입니다. 현재 공개 상태를 다시 확인했습니다.");for(var material:plan.materials())actions.add(button(p,s,PhoneReferences.label(plan,material.item().key()),()->reference(p,advance(p),material.item().key(),1)));}
                else{var material=plan.materials().stream().filter(m->m.item().key().equals(key)).findFirst().orElseThrow(()->new IllegalStateException("STALE_AI_REFERENCE"));var detail=PhoneGrounding.detail(material);title=PhoneReferences.label(plan,key);lines=List.of(page+" / "+detail.pages().size()+"\n\n"+detail.pages().get(page-1));
                    if(page>1)actions.add(button(p,s,"앞 내용",()->reference(p,advance(p),key,page-1)));if(page<detail.pages().size())actions.add(button(p,s,"다음 내용",()->reference(p,advance(p),key,page+1)));
                    actions.add(button(p,s,"원래 정보",()->openReference(p,advance(p),plan,key)));
                    if(!material.item().section().equals("story")&&!s.chat().busy())actions.add(button(p,s,"이 자료 AI 도움",()->help(p,s,material.item())));
                    actions.add(button(p,s,"참고자료 목록",()->referenceList(p,advance(p))));
                }
                actions.add(button(p,s,"AI 대화",()->chat(p)));actions.add(button(p,s,"홈",()->home(p)));show(p,s,title,lines.stream().map(line->DialogBody.plainMessage(Component.text(line))).toList(),List.of(),actions);
            }catch(RuntimeException stale){references.remove(expected);info(p,s,"참고자료 갱신 필요",List.of("자료가 변경됐거나 더 이상 공개되지 않습니다. 최신 정보로 다시 질문해 주세요."));}
        },owner);
    }
    private void openReference(Player p,Screen s,PhoneGrounding.Plan expected,String key){
        journal.load(s.session().context(),s.session(),()->valid(p,s)).whenCompleteAsync((view,error)->{
            if(!valid(p,s))return;if(error!=null){info(p,s,"원래 정보 조회 실패",List.of("현재 공개 상태로 다시 조회하세요."));return;}
            try{var plan=PhoneGrounding.refresh(catalog,view,information(p,s,view),expected);var item=plan.materials().stream().filter(m->m.item().key().equals(key)).findFirst().orElseThrow().item();if(item.section().equals("story"))libraryMenu(p,s,"community");else detail(p,s,view,item.section(),item.source().index(),1,1,"ai_references");}catch(RuntimeException stale){info(p,s,"원래 정보 변경",List.of("자료가 변경됐거나 더 이상 공개되지 않습니다. 앱에서 다시 선택하세요."));}
        },owner);
    }
    private void clearConfirm(Player p){
        var s=advance(p);show(p,s,"대화 정리 확인",List.of(DialogBody.plainMessage(Component.text("현재 접속 세션의 AI 대화와 임시 응답을 지웁니다. 퀘스트·일지·인벤토리는 변경하지 않습니다."))),List.of(),List.of(button(p,s,"기록 지우기",()->{if(!s.chat().busy()){s.chat().clear();references.remove(s.session());}chat(p);}),button(p,s,"취소",()->chat(p))));
    }
    public boolean command(Player p,String command){
        if(command.equals("/hearthphone")){home(p);return true;}
        if(command.equals("/hearthphone notices")){notices(p,advance(p),1);return true;}
        if(command.equals("/hearthphone device")){
            if(deviceIssuer!=null){deviceIssuer.accept(p);return true;}
            if(!p.hasPermission("hearth.verify"))return true;
            if(!p.getItemOnCursor().isEmpty()||p.getInventory().firstEmpty()<0){p.sendMessage(Component.text("빈 인벤토리 슬롯이 필요합니다."));return true;}
            var item=new ItemStack(Material.PAPER);var meta=item.getItemMeta();meta.displayName(Component.text("HEARTH OS 개발 스마트폰"));meta.setItemModel(new NamespacedKey("hearth","phone"));meta.getPersistentDataContainer().set(device,PersistentDataType.BYTE,(byte)1);item.setItemMeta(meta);p.getInventory().addItem(item);return true;
        }
        return false;
    }
    private boolean device(ItemStack item){return item!=null&&item.hasItemMeta()&&item.getItemMeta().getPersistentDataContainer().has(device,PersistentDataType.BYTE);}
    private boolean deviceAllowed(Player p,ItemStack item){var s=players.current(p);if(s==null)return false;var data=item.getItemMeta().getPersistentDataContainer();String owner=data.get(new NamespacedKey(plugin,"phone_owner"),PersistentDataType.STRING);return owner==null?!plugin.getConfig().getBoolean("s4-features-enabled")&&p.hasPermission("hearth.verify"):p.getUniqueId().toString().equals(owner)&&s.context().storyRunId().toString().equals(data.get(new NamespacedKey(plugin,"phone_run"),PersistentDataType.STRING));}
    @EventHandler(priority=EventPriority.HIGHEST) public void use(PlayerInteractEvent e){if(device(e.getItem())&&e.getAction().isRightClick()){e.setCancelled(true);if(e.getHand()==EquipmentSlot.HAND&&deviceAllowed(e.getPlayer(),e.getItem())&&players.current(e.getPlayer())!=null)Bukkit.getScheduler().runTaskLater(plugin,()->{if(players.current(e.getPlayer())!=null&&!closed)home(e.getPlayer());},1);}}
    @EventHandler public void drop(PlayerDropItemEvent e){if(device(e.getItemDrop().getItemStack()))e.setCancelled(true);}
    @EventHandler public void quit(PlayerQuitEvent e){var s=screens.remove(e.getPlayer().getUniqueId());if(s!=null){s.chat().close();completionFlow.forget(s.session());completionReturns.remove(s.session());pendingActions.remove(s.session());contextModes.remove(s.session());helpSelections.remove(s.session());references.remove(s.session());}}
    @Override public void close(){closed=true;HandlerList.unregisterAll(this);screens.values().forEach(s->s.chat().close());screens.clear();pendingActions.clear();completionFlow.clear();completionReturns.clear();contextModes.clear();helpSelections.clear();references.clear();assistant.close();}
}
