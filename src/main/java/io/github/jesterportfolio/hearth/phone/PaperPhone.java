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
    private final Map<UUID,Screen> screens=new HashMap<>();private final JavaPlugin plugin;private final PaperSessionAdapter players;
    private final QuestJournal journal;private final Executor owner;private final AssistantService assistant;private final NamespacedKey device;private boolean closed;
    public PaperPhone(JavaPlugin plugin,PaperSessionAdapter players,QuestJournal journal,Executor owner){
        this.plugin=plugin;this.players=players;this.journal=journal;this.owner=owner;device=new NamespacedKey(plugin,"dev_phone");
        assistant=plugin.getConfig().getString("phone-ai-mode","mock").equals("local")?new LocalAssistant(plugin.getConfig().getString("phone-ai-endpoint","http://127.0.0.1:11435/ask")):LocalAssistant.mock();
        Bukkit.getPluginManager().registerEvents(this,plugin);
    }
    private Screen screen(Player p){var session=players.current(p);if(session==null)throw new IllegalStateException("PHONE_SESSION_REQUIRED");var old=screens.get(p.getUniqueId());if(old!=null&&old.session()==session)return old;if(old!=null)old.chat().close();var next=new Screen(session,new Object(),new PhoneChat(assistant,owner));screens.put(p.getUniqueId(),next);return next;}
    private boolean valid(Player p,Screen s){return !closed&&p.isOnline()&&players.current(p)==s.session()&&screens.get(p.getUniqueId())==s;}
    private Screen advance(Player p){var old=screen(p);var s=new Screen(old.session(),new Object(),old.chat());screens.put(p.getUniqueId(),s);return s;}
    private ClickCallback.Options options(){return ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build();}
    private void dispatch(Player p,Screen s,Runnable action){Bukkit.getScheduler().runTaskLater(plugin,()->{if(valid(p,s))action.run();},1);}
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
            actions.add(button(p,s,"새로고침",()->browse(p,advance(p),section,1)));actions.add(button(p,s,"홈",()->home(p)));
            String title=switch(section){case "quests"->"퀘스트";case "map"->"월드 지도 정보";default->"기록";};
            show(p,s,title,List.of(DialogBody.plainMessage(Component.text(rows.entries().isEmpty()?"조회 가능한 정보가 없습니다.":"항목을 눌러 상세 내용을 확인하세요. "+page+" / "+rows.total()))),List.of(),actions);
        }catch(RuntimeException stale){info(p,s,"조회 상태 변경",List.of("정보가 변경됐거나 저장 중입니다. 홈에서 앱을 다시 열어 주세요."));}
    }
    private void detail(Player p,Screen s,QuestJournal.View view,String section,int index,int page,int listPage){
        if(!valid(p,s))return;
        try{
            var detail=PhonePages.detail(information(p,s,view),section,index);var actions=new ArrayList<ActionButton>();
            if(page>1)actions.add(button(p,s,"앞 내용",()->detail(p,advance(p),view,section,index,page-1,listPage)));
            if(page<detail.pages().size())actions.add(button(p,s,"다음 내용",()->detail(p,advance(p),view,section,index,page+1,listPage)));
            actions.add(button(p,s,"목록",()->browseSnapshot(p,advance(p),view,section,listPage)));actions.add(button(p,s,"홈",()->home(p)));
            show(p,s,detail.title(),List.of(DialogBody.plainMessage(Component.text(page+" / "+detail.pages().size()+"\n\n"+detail.pages().get(page-1)))),List.of(),actions);
        }catch(RuntimeException stale){info(p,s,"조회 상태 변경",List.of("현재 정보로 다시 조회해 주세요."));}
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
        var s=advance(p);var body=new ArrayList<DialogBody>();body.add(DialogBody.plainMessage(Component.text("최근 대화 · 현재 접속 세션에서만 유지")));
        for(var turn:s.chat().history()){body.add(DialogBody.plainMessage(Component.text("나: "+turn.question())));body.add(DialogBody.plainMessage(Component.text("AI: "+turn.answer())));}
        if(s.chat().error()!=null)body.add(DialogBody.plainMessage(Component.text(s.chat().error())));
        if(s.chat().busy())body.add(DialogBody.plainMessage(Component.text("나: "+s.chat().question()+"\n응답 대기 중입니다. 잠시 후 응답 확인을 눌러 주세요.")));
        var actions=new ArrayList<ActionButton>();
        if(!s.chat().busy())actions.add(ActionButton.builder(Component.text("전송")).action(DialogAction.customClick((response,audience)->{
            if(audience instanceof Player who&&who.getUniqueId().equals(p.getUniqueId())){String question=response.getText("question");dispatch(p,s,()->{s.chat().submit(question).exceptionally(error->null);chat(p);});}
        },options())).build());
        if(s.chat().canRetry())actions.add(button(p,s,"실패한 질문 재시도",()->{s.chat().retry().exceptionally(error->null);chat(p);}));
        if(!s.chat().busy()&&(!s.chat().history().isEmpty()||s.chat().error()!=null))actions.add(button(p,s,"대화 정리",()->clearConfirm(p)));
        actions.add(button(p,s,"응답 확인",()->chat(p)));actions.add(button(p,s,"홈",()->home(p)));
        show(p,s,"AI 어시스턴트",body,s.chat().busy()?List.of():List.of(DialogInput.text("question",Component.text("질문")).maxLength(256).width(240).build()),actions);
    }
    private void clearConfirm(Player p){
        var s=advance(p);show(p,s,"대화 정리 확인",List.of(DialogBody.plainMessage(Component.text("현재 접속 세션의 AI 대화 기록만 지웁니다. 퀘스트·일지·인벤토리는 변경하지 않습니다."))),List.of(),List.of(button(p,s,"기록 지우기",()->{if(!s.chat().busy())s.chat().clear();chat(p);}),button(p,s,"취소",()->chat(p))));
    }
    public boolean command(Player p,String command){
        if(command.equals("/hearthphone")){home(p);return true;}
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
    @EventHandler public void quit(PlayerQuitEvent e){var s=screens.remove(e.getPlayer().getUniqueId());if(s!=null)s.chat().close();}
    @Override public void close(){closed=true;HandlerList.unregisterAll(this);screens.values().forEach(s->s.chat().close());screens.clear();assistant.close();}
}
