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
    private record References(QuestJournal.View view,PhoneRetrieval.Result result){}
    private final Map<PlayerSessions.Session,References> references=new IdentityHashMap<>();
    private final Map<PlayerSessions.Session,String> contextModes=new IdentityHashMap<>();
    private CompletionAction completion=(p,s,c)->java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("COMPLETION_DISABLED"));
    public void completion(CompletionAction handler){completion=handler;}
    private final Map<PlayerSessions.Session,Boolean> actionResults=new IdentityHashMap<>();
    private final Set<PlayerSessions.Session> pendingActions=Collections.newSetFromMap(new IdentityHashMap<>());
    private Playthrough playthrough;private io.github.jesterportfolio.hearth.story.SharedStory sharedStory;
    @FunctionalInterface public interface StoryRequest{java.util.concurrent.CompletionStage<?> request(Player player,io.github.jesterportfolio.hearth.story.StoryGuide.Action action,java.util.function.BooleanSupplier current);}
    private StoryRequest storyRequest=(p,a,v)->java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("STORY_DISABLED"));
    public void storyProgress(io.github.jesterportfolio.hearth.story.SharedStory shared,StoryRequest request){sharedStory=shared;storyRequest=request;}
    public void playthrough(Playthrough content,io.github.jesterportfolio.hearth.story.SharedStory story){playthrough=content;sharedStory=story;}
    private final Map<UUID,Screen> screens=new HashMap<>();private final JavaPlugin plugin;private final PaperSessionAdapter players;
    private final QuestJournal journal;private final QuestCatalog catalog;
    private java.util.function.BiConsumer<Player,String> report=(p,id)->{};
    public void report(java.util.function.BiConsumer<Player,String> handler){report=handler;}private final Executor owner;private final AssistantService assistant;private final NamespacedKey device;private boolean closed;
    public PaperPhone(JavaPlugin plugin,PaperSessionAdapter players,QuestJournal journal,QuestCatalog catalog,Executor owner){
        this.plugin=plugin;this.players=players;this.journal=journal;this.catalog=catalog;this.owner=owner;device=new NamespacedKey(plugin,"dev_phone");
        assistant=plugin.getConfig().getString("phone-ai-mode","mock").equals("local")?new LocalAssistant(plugin.getConfig().getString("phone-ai-endpoint","http://127.0.0.1:11435/ask")):LocalAssistant.mock();
        Bukkit.getPluginManager().registerEvents(this,plugin);
    }
    private Screen screen(Player p){var session=players.current(p);if(session==null)throw new IllegalStateException("PHONE_SESSION_REQUIRED");var old=screens.get(p.getUniqueId());if(old!=null&&old.session()==session)return old;if(old!=null){old.chat().close();contextModes.remove(old.session());actionResults.remove(old.session());references.remove(old.session());}var next=new Screen(session,new Object(),new PhoneChat(assistant,owner));screens.put(p.getUniqueId(),next);return next;}
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
    public void home(Player p){
        var s=advance(p);var body=new ArrayList<DialogBody>();body.add(DialogBody.plainMessage(Component.text("\ue000").font(Key.key("hearth:phone"))));
        var actions=new ArrayList<ActionButton>();String[] labels={"AI 어시스턴트","퀘스트","연구","지도","기록","설정"};
        for(int i=0;i<labels.length;i++){final int app=i;Runnable open=()->app(p,app);body.add(DialogBody.plainMessage(icon(p,s,i,labels[i],open)));actions.add(button(p,s,labels[i],open));}
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
                actions.add(button(p,s,"다음 플레이 안내",()->journey(p,advance(p))));
                if(pendingActions.contains(s.session())||actionResults.containsKey(s.session()))actions.add(button(p,s,"최근 처리 결과",()->completionResult(p,advance(p))));
                actions.add(button(p,s,"개인 알림",()->notices(p,advance(p),1)));
            }
            if(section.equals("map"))actions.add(button(p,s,"공개 지역·접근 상태",()->loadLibrary(p,advance(p),"regions",1)));
            actions.add(button(p,s,"새로고침",()->browse(p,advance(p),section,1)));actions.add(button(p,s,"홈",()->home(p)));
            String title=switch(section){case "quests"->"퀘스트";case "map"->"월드 지도 정보";default->"기록";};
            show(p,s,title,List.of(DialogBody.plainMessage(Component.text(rows.entries().isEmpty()?"조회 가능한 정보가 없습니다.":"항목을 눌러 상세 내용을 확인하세요. "+page+" / "+rows.total()))),List.of(),actions);
        }catch(RuntimeException stale){info(p,s,"조회 상태 변경",List.of("정보가 변경됐거나 저장 중입니다. 홈에서 앱을 다시 열어 주세요."));}
    }
    private void detail(Player p,Screen s,QuestJournal.View view,String section,int index,int page,int listPage){detail(p,s,view,section,index,page,listPage,section);}
    private void detail(Player p,Screen s,QuestJournal.View view,String section,int index,int page,int listPage,String returnSection){
        if(!valid(p,s))return;
        try{
            var detail=PhonePages.detail(information(p,s,view),section,index);var actions=new ArrayList<ActionButton>();
            if(page>1)actions.add(button(p,s,"앞 내용",()->detail(p,advance(p),view,section,index,page-1,listPage,returnSection)));
            if(page<detail.pages().size())actions.add(button(p,s,"다음 내용",()->detail(p,advance(p),view,section,index,page+1,listPage,returnSection)));
            if(section.equals("quests")){
                var quest=PhoneQuestLinks.quests(catalog,view).get(index);
                var reward=view.completion().rewards().get(quest.id());
                if(reward!=null)actions.add(button(p,s,reward.label(),()->completionList(p,advance(p),"rewards",1)));
                if(view.states().get(quest.id()).phase()==io.github.jesterportfolio.hearth.persistence.QuestStore.Phase.FAILED)actions.add(button(p,s,"재시작 확인",()->confirm(p,s,view,PhoneCompletion.Operation.RETRY,quest.id())));
                if(reward!=null&&reward.claimable())actions.add(button(p,s,"지급 대기 보상 받기",()->confirm(p,s,view,PhoneCompletion.Operation.CLAIM,quest.id())));
                if(quest.trackable()||quest.tracked())actions.add(button(p,s,quest.tracked()?"추적 해제":"추적",()->{
                    information(p,s,view);var next=advance(p);
                    journal.track(next.session().context(),next.session(),()->valid(p,next),quest.id(),!quest.tracked()).whenCompleteAsync((saved,error)->{
                        if(!valid(p,next))return;if(error!=null)info(p,next,"추적 저장 실패",List.of("변경 성공으로 표시하지 않습니다. 다시 조회해 주세요."));else try{detail(p,next,saved,section,PhoneQuestLinks.questIndex(catalog,saved,quest.id()),1,1);}catch(RuntimeException unavailable){info(p,next,"퀘스트 상태 변경",List.of("현재 목록으로 다시 조회해 주세요."));}
                    },owner);
                }));
                if(quest.reporting())actions.add(button(p,s,"가까운 NPC와 보고 대화",()->{information(p,s,view);advance(p);p.closeDialog();report.accept(p,quest.id());}));
                actions.add(button(p,s,"개인 알림",()->notices(p,advance(p),1)));
            }
            actions.add(button(p,s,"목록",()->{var next=advance(p);if(returnSection.equals(section))browseSnapshot(p,next,view,section,listPage);else librarySnapshot(p,next,view,returnSection,listPage);}));actions.add(button(p,s,"홈",()->home(p)));
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
                if(section.equals("failed")&&state.phase()==io.github.jesterportfolio.hearth.persistence.QuestStore.Phase.FAILED){labels.add(q.title());items.add(()->confirm(p,s,view,PhoneCompletion.Operation.RETRY,q.id()));}
                if(section.equals("rewards")&&reward!=null&&reward.claimable()){labels.add(q.title());items.add(()->confirm(p,s,view,PhoneCompletion.Operation.CLAIM,q.id()));}
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
            if(!chapter.skipped())actions.add(button(p,s,"장 스킵 확인",()->confirm(p,s,view,PhoneCompletion.Operation.SKIP,id)));
            actions.add(button(p,s,"장 목록",()->completionList(p,advance(p),"chapters",1)));actions.add(button(p,s,"홈",()->home(p)));
            show(p,s,text.title(),List.of(DialogBody.plainMessage(Component.text(text.pages().get(page-1)))),List.of(),actions);
        }catch(RuntimeException stale){info(p,s,"장 상태 변경",List.of("현재 상태로 다시 조회해 주세요."));}
    }
    private void confirm(Player p,Screen s,QuestJournal.View view,PhoneCompletion.Operation operation,String id){
        information(p,s,view);var choice=PhoneCompletion.choose(catalog,view,operation,id);var next=advance(p);
        String warning=operation==PhoneCompletion.Operation.SKIP?"해당 장의 메인/필수 길목만 스킵합니다. 실제 완료·보상과 구분하며 공동체 결과는 유지합니다. 스킵 보상 정책은 미정입니다.":operation==PhoneCompletion.Operation.CLAIM?"지급 대기 기록에 대해서만 기존 인벤토리·DB 저장 경로로 요청합니다. 중복 지급하지 않습니다.":"콘텐츠에 지정된 기존 실패/재시작 정책으로 재시작합니다.";
        show(p,next,choice.title(),List.of(DialogBody.plainMessage(Component.text(warning))),List.of(),List.of(button(p,next,"확인",()->runCompletion(p,next,view,choice)),button(p,next,"취소",()->completionList(p,advance(p),operation==PhoneCompletion.Operation.SKIP?"chapters":operation==PhoneCompletion.Operation.CLAIM?"rewards":"failed",1))));
    }
    private void runCompletion(Player p,Screen s,QuestJournal.View view,PhoneCompletion.Choice choice){
        information(p,s,view);PhoneCompletion.require(catalog,view,choice);
        if(!pendingActions.add(s.session()))throw new IllegalStateException("PHONE_ACTION_BUSY");actionResults.remove(s.session());var next=advance(p);
        show(p,next,"처리 중",List.of(DialogBody.plainMessage(Component.text("기존 저장 작업의 완료를 기다립니다. 아직 성공으로 확정하지 않았습니다."))),List.of(),List.of(button(p,next,"결과 확인",()->completionResult(p,advance(p))),button(p,next,"홈",()->home(p))));
        final java.util.concurrent.CompletionStage<Void> work;
        try{work=completion.execute(p,next.session(),choice);}catch(RuntimeException error){pendingActions.remove(next.session());throw error;}
        work.whenCompleteAsync((v,error)->{
            pendingActions.remove(next.session());
            if(!closed&&players.prepared(p)==next.session())actionResults.put(next.session(),error==null);
        },owner);
    }
    private void completionResult(Player p,Screen s){
        if(pendingActions.contains(s.session())){show(p,s,"처리 중",List.of(DialogBody.plainMessage(Component.text("기존 저장 작업의 완료를 기다립니다. 아직 성공으로 확정하지 않았습니다."))),List.of(),List.of(button(p,s,"결과 확인",()->completionResult(p,advance(p))),button(p,s,"홈",()->home(p))));return;}
        Boolean success=actionResults.get(s.session());
        info(p,s,success==null?"처리 기록 없음":success?"처리 완료":"처리 보류/실패",List.of(success==null?"이 접속 세션에서 실행한 스마트폰 처리 결과가 없습니다.":success?"기존 저장 경로의 완료를 확인했습니다. 목록에서 최신 상태를 조회해 주세요.":"성공으로 기록하지 않습니다. 저장/HOLD·인벤토리 공간·최신 진행 상태를 확인해 주세요."));
    }
    private void journey(Player p,Screen s){
        if(playthrough==null||sharedStory==null){info(p,s,"플레이 안내",List.of("현재 개발 콘텐츠에 다음 플레이 안내가 등록되지 않았습니다."));return;}
        journal.load(s.session().context(),s.session(),()->valid(p,s)).whenCompleteAsync((view,error)->{
            if(!valid(p,s))return;if(error!=null){info(p,s,"안내 조회 실패",List.of("현재 상태로 다시 조회해 주세요."));return;}
            try{information(p,s,view);var guide=playthrough.guide(catalog,view,sharedStory.cached(s.session().context().storyRunId()),sharedStory.phase(s.session().context().storyRunId()));var actions=new ArrayList<ActionButton>();
                if(guide.talkQuest()!=null)actions.add(button(p,s,"가까운 담당 NPC와 대화",()->{information(p,s,view);advance(p);p.closeDialog();report.accept(p,guide.talkQuest());}));
                if("/hearthstory guide".equals(guide.command()))actions.add(button(p,s,"공동 조사 안내",()->{information(p,s,view);sharedProgress(p,advance(p));}));
                actions.add(button(p,s,"퀘스트 목록",()->browse(p,advance(p),"quests",1)));actions.add(button(p,s,"홈",()->home(p)));
                show(p,s,guide.title(),guide.lines().stream().map(line->DialogBody.plainMessage(Component.text(line))).toList(),List.of(),actions);
            }catch(RuntimeException stale){info(p,s,"안내 상태 변경",List.of("현재 상태로 다시 조회해 주세요."));}
        },owner);
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
        if(!pendingActions.add(s.session()))throw new IllegalStateException("STORY_REQUEST_PENDING");
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
        if(menu.equals("diary"))actions.add(button(p,s,"공동체 정보",()->libraryMenu(p,advance(p),"community")));
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
    private void libraryDetail(Player p,Screen s,QuestJournal.View view,String section,int index,int page,int listPage){
        if(!valid(p,s))return;
        try{
            information(p,s,view);var entry=PhoneLibrary.rows(catalog,view,section).get(index);var text=PhonePages.text(entry.title(),entry.lines());var actions=new ArrayList<ActionButton>();
            if(page>1)actions.add(button(p,s,"앞 내용",()->libraryDetail(p,advance(p),view,section,index,page-1,listPage)));
            if(page<text.pages().size())actions.add(button(p,s,"다음 내용",()->libraryDetail(p,advance(p),view,section,index,page+1,listPage)));
            actions.add(button(p,s,"목록",()->librarySnapshot(p,advance(p),view,section,listPage)));actions.add(button(p,s,"홈",()->home(p)));
            show(p,s,text.title(),List.of(DialogBody.plainMessage(Component.text(page+" / "+text.pages().size()+"\n\n"+text.pages().get(page-1)))),List.of(),actions);
        }catch(RuntimeException stale){info(p,s,"조회 상태 변경",List.of("현재 상태로 다시 조회해 주세요."));}
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
    private void chat(Player p){
        var s=advance(p);var body=new ArrayList<DialogBody>();body.add(DialogBody.plainMessage(Component.text("최근 대화 · 현재 접속 세션에서만 유지\n"+PhoneContext.MODES.get(contextModes.getOrDefault(s.session(),"none")))));
        for(var turn:s.chat().history()){body.add(DialogBody.plainMessage(Component.text("나: "+turn.question())));body.add(DialogBody.plainMessage(Component.text("AI: "+turn.answer())));}
        if(s.chat().error()!=null)body.add(DialogBody.plainMessage(Component.text(s.chat().error())));
        if(s.chat().busy())body.add(DialogBody.plainMessage(Component.text("나: "+s.chat().question()+"\n응답 대기 중입니다. 잠시 후 응답 확인을 눌러 주세요.")));
        var actions=new ArrayList<ActionButton>();
        if(!s.chat().busy())actions.add(ActionButton.builder(Component.text("전송")).action(DialogAction.customClick((response,audience)->{
            if(audience instanceof Player who&&who.getUniqueId().equals(p.getUniqueId())){String question=response.getText("question");dispatch(p,s,()->{submitChat(p,s,question);});}
        },options())).build());
        if(s.chat().canRetry())actions.add(button(p,s,"실패한 질문 재시도",()->{submitChat(p,s,s.chat().question());}));
        if(!s.chat().busy()&&(!s.chat().history().isEmpty()||s.chat().error()!=null))actions.add(button(p,s,"대화 정리",()->clearConfirm(p)));
        if(s.chat().busy())actions.add(button(p,s,"요청 취소",()->{s.chat().cancel();references.remove(s.session());chat(p);}));
        if(!s.chat().busy())actions.add(button(p,s,"도움 범위 선택",()->contextMenu(p)));
        if(references.containsKey(s.session()))actions.add(button(p,s,"최근 요청 참고자료",()->referenceList(p,advance(p))));
        actions.add(button(p,s,"응답 확인",()->chat(p)));actions.add(button(p,s,"홈",()->home(p)));
        show(p,s,"AI 어시스턴트",body,s.chat().busy()?List.of():List.of(DialogInput.text("question",Component.text("질문")).initial(s.chat().draft()).maxLength(256).width(240).build()),actions);
    }
    private void contextMenu(Player p){
        var s=advance(p);var actions=new ArrayList<ActionButton>();
        PhoneContext.MODES.forEach((mode,label)->actions.add(button(p,s,label,()->{if(!s.chat().busy()){contextModes.put(s.session(),mode);references.remove(s.session());chat(p);}})));
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
                var result=PhoneRetrieval.find(information(p,next,view),mode,question);
                previewContext(p,next,view,mode,question,result,1);
            }catch(RuntimeException invalid){info(p,next,"AI 정보 상태 변경",List.of("현재 공개 정보로 다시 요청해 주세요."));}
        },owner);
    }
    private void previewContext(Player p,Screen s,QuestJournal.View view,String mode,String question,PhoneRetrieval.Result result,int page){
        if(!valid(p,s))return;
        try{
            information(p,s,view);var pages=PhonePages.text("전송할 공개 자료",List.of("질문: "+question,result.context()));var actions=new ArrayList<ActionButton>();
            if(page>1)actions.add(button(p,s,"앞 자료",()->previewContext(p,advance(p),view,mode,question,result,page-1)));
            if(page<pages.pages().size())actions.add(button(p,s,"다음 자료",()->previewContext(p,advance(p),view,mode,question,result,page+1)));
            actions.add(button(p,s,"이 자료로 질문",()->{
                information(p,s,view);var next=advance(p);references.put(next.session(),new References(view,result));
                java.util.function.BooleanSupplier approved=()->{if(closed||players.current(p)!=next.session())return false;try{journal.projectInformation(next.session().context(),view,()->players.current(p)==next.session());return true;}catch(RuntimeException stale){return false;}};
                next.chat().submit(question,result.context(),mode,approved).exceptionally(error->null);chat(p);
            }));
            actions.add(button(p,s,"전송 취소",()->chat(p)));
            show(p,s,"공개 자료 확인 · "+result.sources().size()+"개",List.of(DialogBody.plainMessage(Component.text(page+" / "+pages.pages().size()+"\n\n"+pages.pages().get(page-1)))),List.of(),actions);
        }catch(RuntimeException stale){info(p,s,"참고자료 상태 변경",List.of("현재 공개 정보로 다시 요청해 주세요."));}
    }
    private void referenceList(Player p,Screen s){
        var refs=references.get(s.session());
        if(refs==null){info(p,s,"참고자료 없음",List.of("이 세션에서 전송한 게임 도움 자료가 없습니다."));return;}
        try{
            information(p,s,refs.view());var actions=new ArrayList<ActionButton>();
            for(var source:refs.result().sources())actions.add(button(p,s,source.title(),()->{
                information(p,s,refs.view());var next=advance(p);
                if(source.section().equals("story"))info(p,next,source.title(),List.of(source.text()));else detail(p,next,refs.view(),source.section(),source.index(),1,1);
            }));
            actions.add(button(p,s,"AI 대화",()->chat(p)));actions.add(button(p,s,"홈",()->home(p)));
            show(p,s,"최근 게임 도움 참고자료",List.of(DialogBody.plainMessage(Component.text(refs.result().sources().isEmpty()?"현재 공개 정보에서 키워드 일치 자료가 없었습니다.":"이 목록은 AI 요청에 제공한 자료입니다. AI 답변의 정확성을 보증하지 않으며 자료에 없는 내용은 별도로 확인해야 합니다."))),List.of(),actions);
        }catch(RuntimeException stale){references.remove(s.session());info(p,s,"참고자료 갱신 필요",List.of("이전 자료는 더 이상 현재 상태로 확인할 수 없습니다. 최신 정보로 다시 질문해 주세요."));}
    }
    private void clearConfirm(Player p){
        var s=advance(p);show(p,s,"대화 정리 확인",List.of(DialogBody.plainMessage(Component.text("현재 접속 세션의 AI 대화 기록만 지웁니다. 퀘스트·일지·인벤토리는 변경하지 않습니다."))),List.of(),List.of(button(p,s,"기록 지우기",()->{if(!s.chat().busy()){s.chat().clear();references.remove(s.session());}chat(p);}),button(p,s,"취소",()->chat(p))));
    }
    public boolean command(Player p,String command){
        if(command.equals("/hearthphone")){home(p);return true;}
        if(command.equals("/hearthphone notices")){notices(p,advance(p),1);return true;}
        if(command.equals("/hearthphone device")){
            if(!p.hasPermission("hearth.verify"))return true;
            if(!p.getItemOnCursor().isEmpty()||p.getInventory().firstEmpty()<0){p.sendMessage(Component.text("빈 인벤토리 슬롯이 필요합니다."));return true;}
            var item=new ItemStack(Material.PAPER);var meta=item.getItemMeta();meta.displayName(Component.text("HEARTH OS 개발 스마트폰"));meta.setItemModel(new NamespacedKey("hearth","phone"));meta.getPersistentDataContainer().set(device,PersistentDataType.BYTE,(byte)1);item.setItemMeta(meta);p.getInventory().addItem(item);return true;
        }
        return false;
    }
    private boolean device(ItemStack item){return item!=null&&item.hasItemMeta()&&item.getItemMeta().getPersistentDataContainer().has(device,PersistentDataType.BYTE);}
    @EventHandler(priority=EventPriority.HIGHEST) public void use(PlayerInteractEvent e){if(device(e.getItem())&&e.getAction().isRightClick()){e.setCancelled(true);if(e.getHand()==EquipmentSlot.HAND&&players.current(e.getPlayer())!=null)Bukkit.getScheduler().runTaskLater(plugin,()->{if(players.current(e.getPlayer())!=null&&!closed)home(e.getPlayer());},1);}}
    @EventHandler public void drop(PlayerDropItemEvent e){if(device(e.getItemDrop().getItemStack()))e.setCancelled(true);}
    @EventHandler public void quit(PlayerQuitEvent e){var s=screens.remove(e.getPlayer().getUniqueId());if(s!=null){s.chat().close();actionResults.remove(s.session());contextModes.remove(s.session());references.remove(s.session());}}
    @Override public void close(){closed=true;HandlerList.unregisterAll(this);screens.values().forEach(s->s.chat().close());screens.clear();pendingActions.clear();actionResults.clear();contextModes.clear();references.clear();assistant.close();}
}
