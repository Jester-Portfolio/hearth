package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.exploration.*;
import java.util.*;

/** 실제 GUI와 시험이 함께 사용하는 불변 버튼/표시 데이터입니다. */
public final class QuestScreens {
    private QuestScreens(){}
    public record Button(int slot,String label,String action,String quest,long revision) {}
    public static final List<String> MENUS=List.of("퀘스트","월드 지도","일지","공동체","알림","설정");
    public static List<Button> screen(QuestCatalog catalog,QuestJournal.View view,String page,String id,int offset){
        var out=new ArrayList<Button>();
        if(page.equals("npc-topics"))return npcTopics(catalog,id,view.states(),c->!view.exploration().blockedQuests().contains(c.id()),offset);
        if(page.equals("npc"))return npc(catalog,id,view.states(),c->!view.exploration().blockedQuests().contains(c.id()),offset);
        if(page.equals("home")){
            String[] destinations={"quests","map","diary","community","notices","settings"};
            for(int i=0;i<MENUS.size();i++)out.add(new Button(i,MENUS.get(i),destinations[i],null,0));return List.copyOf(out);
        }
        out.add(new Button(45,"이전 화면","home",null,0));
        if(view.exploration().registered() && Set.of("map","place","region","diary","STORY","PERSON","EXPLORATION","CHRONICLE","fact","event","community","members","story","conditions","branches").contains(page))return explore(catalog,view,page,id,offset);
        if(Set.of("chapters","chapter-detail","chapter-confirm").contains(page))return completion(view,page,id,offset);
        if(page.equals("rewards")&&!view.completion().registered())return List.of(new Button(0,"보상 조회 비활성","none",null,0),new Button(45,"이전 화면","quests",null,0));
        if(Set.of("quests","reporting","tracked","completed","rewards").contains(page)){
            var filter=switch(page){case "reporting"->QuestListView.Filter.REPORTING;case "tracked"->QuestListView.Filter.TRACKED;case "completed"->QuestListView.Filter.COMPLETED;default->QuestListView.Filter.ALL;};
            var entries=QuestListView.filtered(catalog,view.states(),view.journal().tracked(),view.exploration().blockedQuests(),filter).stream().filter(q->!page.equals("rewards")||Optional.ofNullable(view.completion().rewards().get(q.questId())).map(QuestCompletionView.Reward::claimable).orElse(false)).toList();int end=Math.min(entries.size(),offset+36);
            for(int i=offset;i<end;i++){var q=entries.get(i);out.add(new Button(i-offset,"["+q.status()+"] "+q.title(),"detail",q.questId(),0));}
            if(entries.isEmpty())out.add(new Button(0,page.equals("quests")?"수락한 퀘스트가 없습니다":"표시할 수 있는 퀘스트가 없습니다.","none",null,0));
            navigation(out,offset,entries.size(),page);
            String[] tabs={"quests","reporting","tracked","completed"};String[] names={"전체","보고 가능","추적 중","완료"};
            for(int i=0;i<tabs.length;i++)out.add(new Button(36+i,names[i],tabs[i],null,0));
            if(view.completion().registered()){out.add(new Button(40,"보상 대기","rewards",null,0));out.add(new Button(52,"장별 따라잡기/스킵","chapters",null,0));}
            out.add(new Button(50,"다음 플레이 안내","journey-guide",null,0));
            out.add(new Button(49,"확정된 공동체 분기","branches",null,0));
        }else if(page.equals("detail")){
            var detail=view.exploration().blockedQuests().contains(id)?Optional.<QuestListView.Detail>empty():QuestListView.detail(catalog,view.states(),id);
            if(detail.isEmpty())out.add(new Button(0,QuestListView.UNAVAILABLE,"none",null,0));
            else{
                out.add(new Button(0,"["+detail.get().quest().status()+"] "+detail.get().quest().title(),"book",id,0));
                var row=view.states().get(id);boolean tracked=view.journal().tracked().contains(id);
                if(row.phase()==QuestStore.Phase.REPORT_READY)out.add(new Button(11,"가까운 NPC와 보고 대화 열기","npc-dialogue",id,0));
                if(row.phase()==QuestStore.Phase.FAILED)out.add(new Button(12,"퀘스트 재시작","quest-retry",id,0));
                var reward=view.completion().rewards().get(id);if(reward!=null){out.add(new Button(6,reward.label(),"none",null,0));if(reward.claimable())out.add(new Button(12,"미지급 보상 받기","quest-reward",id,0));}
                out.add(new Button(46,"알림 목록","notices",null,0));out.add(new Button(48,"추적 중인 퀘스트","tracked",null,0));
                if(view.exploration().registered())out.add(new Button(49,"공유 진행 안내를 채팅에서 확인","story-guide",null,0));
                if(!row.satisfies())out.add(new Button(10,tracked?"추적 해제":"추적","track",id,tracked?0:1));
                out.removeIf(b->b.slot()==45);out.add(new Button(45,"퀘스트 목록","quests",null,0));
            }
        }else if(page.equals("notices")){
            var rows=view.journal().notices().stream().filter(n->n.type().equals("PLACE")?view.exploration().place(n.quest()).filter(ExplorationView.Place::discovered).isPresent():!view.exploration().blockedQuests().contains(n.quest())&&QuestListView.detail(catalog,view.states(),n.quest()).isPresent()).toList();
            int end=Math.min(rows.size(),offset+36);for(int i=offset;i<end;i++){var n=rows.get(i);out.add(new Button(i-offset,(n.read()?"[읽음] ":"[안 읽음] ")+(n.type().equals("PLACE")?view.exploration().place(n.quest()).orElseThrow().name():catalog.quest(n.quest()).title())+" · "+notice(n.kind()),n.type().equals("PLACE")?"place-notice":"notice",n.quest(),n.revision()));}
            out.add(new Button(49,"미확인 "+rows.stream().filter(n->!n.read()).count()+"개 · 모두 읽음","read-all",null,0));navigation(out,offset,rows.size(),"notices");
        }else if(page.equals("settings")){
            out.add(new Button(0,"퀘스트 HUD: "+(view.journal().preferences().hudEnabled()?"켜짐":"꺼짐"),"hud",null,view.journal().preferences().hudEnabled()?0:1));
            String[] keys={"minimap","quest_marker","npc_marker","player_marker","place_marker"};String[] names={"미니맵 표시","미니맵 퀘스트 마커","미니맵 NPC 마커","미니맵 플레이어 마커","미니맵 특수 장소 마커"};
            for(int i=0;i<keys.length;i++){boolean on=view.journal().preferences().flag(keys[i]);out.add(new Button(i+1,names[i]+": "+(on?"켜짐":"꺼짐"),"setting:"+keys[i],null,on?0:1));}
            out.add(new Button(8,"미니맵 렌더러 미구현 · NPC 머리 위 표시와 별개","none",null,0));
        }
        else out.add(new Button(0,"미구현: "+switch(page){case "map"->"월드 지도";case "diary"->"일지";case "community"->"공동체 정보";default->"요청 화면";},"none",null,0));
        return List.copyOf(out);
    }
    public static List<Button> npc(QuestCatalog catalog,String npc,Map<String,QuestStore.Saved> states,java.util.function.Predicate<QuestContent> allowed,int offset){
        var options=NpcQuestView.build(catalog,npc,states,allowed).options();var buttons=new ArrayList<Button>();
        for(int i=offset;i<Math.min(options.size(),offset+18);i++){
            var q=options.get(i);var state=states.get(q.quest());var content=catalog.quest(q.quest());
            buttons.add(new Button(i-offset,"["+q.status()+"] "+q.title(),"npc-select",q.quest(),state.revision()));
            if(state.phase()==QuestStore.Phase.REPORT_READY||state.phase()==QuestStore.Phase.NOT_ACCEPTED&&content.startMode()==QuestContent.StartMode.NPC)
                buttons.add(new Button(18+i-offset,(state.phase()==QuestStore.Phase.NOT_ACCEPTED?"수락: ":"보고: ")+q.title(),"npc-action",q.quest(),state.revision()));
        }
        if(options.isEmpty())buttons.add(new Button(0,"현재 대화할 퀘스트가 없습니다.","none",null,0));
        if(!catalog.pages(npc).isEmpty())buttons.add(new Button(36,"대화 읽기","npc-read",null,0));
        if(!catalog.availableTopics(npc,states,id->allowed.test(catalog.quest(id))).isEmpty())buttons.add(new Button(37,"질문 선택","npc-topics-open",null,0));
        buttons.add(new Button(45,"대화 닫기","npc-close",null,0));
        if(offset>0)buttons.add(new Button(47,"이전 페이지","npc",npc,Math.max(0,offset-18)));
        if(offset+18<options.size())buttons.add(new Button(51,"다음 페이지","npc",npc,offset+18));
        for(int i=0;i<buttons.size();i++){var b=buttons.get(i);if(b.action().equals("npc"))buttons.set(i,new Button(b.slot(),b.label(),b.action(),npc,b.revision()));}
        return List.copyOf(buttons);
    }
    public static List<Button> npcTopics(QuestCatalog catalog,String npc,Map<String,QuestStore.Saved> states,java.util.function.Predicate<QuestContent> allowed,int offset){
        if(!states.keySet().equals(catalog.questIds()))throw new IllegalArgumentException("Incomplete NPC state");
        var topics=catalog.availableTopics(npc,states,id->allowed.test(catalog.quest(id)));var out=new ArrayList<Button>();
        for(int i=offset;i<Math.min(topics.size(),offset+36);i++)out.add(new Button(i-offset,topics.get(i).label(),"npc-topic",topics.get(i).id(),0));
        if(topics.isEmpty())out.add(new Button(0,"현재 질문할 항목이 없습니다.","none",null,0));
        out.add(new Button(45,"NPC 선택창","npc-menu",null,0));out.add(new Button(49,"대화 닫기","npc-close",null,0));navigation(out,offset,topics.size(),"npc-topics");
        for(int i=0;i<out.size();i++){var b=out.get(i);if(b.action().equals("npc-topics"))out.set(i,new Button(b.slot(),b.label(),b.action(),npc,b.revision()));}return List.copyOf(out);
    }
    private static List<Button> completion(QuestJournal.View view,String page,String id,int offset){
        var rows=new ArrayList<Button>();String back="quests";
        if(!view.completion().registered())rows.add(line(0,"장별 조회 비활성"));
        else if(page.equals("chapters"))for(var c:view.completion().chapters())rows.add(new Button(0,c.title()+" · "+(c.skipped()?"스킵 기록":"개인 따라잡기"),"chapter-detail",c.id(),0));
        else{back="chapters";var found=view.completion().chapter(id);if(found.isEmpty())rows.add(line(0,"조회할 수 있는 장이 없습니다."));else{var c=found.get();rows.add(line(0,c.title()));
            if(page.equals("chapter-confirm")){back="chapter-detail";rows.add(line(0,"이 장의 메인/필수 길목만 스킵합니다. 서브·공동체 결과는 유지합니다."));rows.add(line(0,"실제 완료/보상과 구분하며 스킵 보상 정책은 미정입니다."));if(!c.skipped())rows.add(new Button(0,"확인: 이 장 스킵","quest-chapter-skip",c.id(),0));}
            else{if(c.unaccepted()>0)rows.add(line(0,"미수락 필수 퀘스트 "+c.unaccepted()+"개 · 직접 플레이 안내에서 시작 경로를 확인하세요."));for(var part:c.parts())rows.add(new Button(0,"["+part.status()+"] "+part.title(),"detail",part.quest(),0));if(c.skipped())rows.add(line(0,"스킵 기록 보존 · 과거 장면 재현은 미구현"));else{rows.add(new Button(0,"직접 플레이 안내","quest-chapter-play",c.id(),0));rows.add(new Button(0,"장 스킵 확인 화면","chapter-confirm",c.id(),0));}}
        }}
        return pageRows(rows,back,page,id,offset);
    }
    private static List<Button> pageRows(List<Button> rows,String back,String page,String id,int offset){
        if(rows.isEmpty())rows.add(line(0,"등록된 정보가 없습니다."));var out=new ArrayList<Button>();int end=Math.min(rows.size(),offset+36);
        for(int i=offset;i<end;i++){var b=rows.get(i);out.add(new Button(i-offset,b.label(),b.action(),b.quest(),b.revision()));}
        out.add(new Button(45,"이전 화면",back,back.equals("chapter-detail")?id:null,0));navigation(out,offset,rows.size(),page);
        for(int i=0;i<out.size();i++){var button=out.get(i);if(button.action().equals(page)&&button.quest()==null)out.set(i,new Button(button.slot(),button.label(),button.action(),id,button.revision()));}return List.copyOf(out);
    }
    private static Button line(int slot,String text){return new Button(slot,text,"none",null,0);}
    private static String scope(boolean shared){return shared?"S:":"P:";}
    private static List<Button> explore(QuestCatalog catalog,QuestJournal.View snapshot,String page,String id,int offset){
        var view=snapshot.exploration();
        var rows=new ArrayList<Button>();String back="home";
        switch(page){
            case "map"->{for(var r:view.regions())rows.add(new Button(0,"지역: "+r.name()+" · "+r.access(),"region",r.id(),0));for(var p:view.places())rows.add(new Button(0,p.name()+" · "+p.status(),"place",p.key(),0));}
            case "region"->{back="map";var region=view.regions().stream().filter(r->r.id().equals(id)).findFirst();
                if(region.isEmpty())rows.add(line(0,"조회할 수 있는 지역이 없습니다."));else{rows.add(line(0,region.get().name()));for(var p:view.places())if(p.region().equals(region.get().name()))rows.add(new Button(0,p.name()+" · "+p.status(),"place",p.key(),0));}}
            case "place"->{back="map";var p=view.place(id);if(p.isEmpty())rows.add(line(0,"조회할 수 있는 장소가 없습니다."));else{
                var x=p.get();for(String text:List.of(x.name(),"지역: "+x.region(),"분류: "+x.category(),"발견: "+x.status(),x.location(),x.visited()?"직접 방문 기록 있음":"직접 방문 기록 없음",x.hubUnlocked()?"개인 거점 해금됨":"거점 이동 해금 없음","빠른 이동 실행은 미구현"))rows.add(line(0,text));}}
            case "diary"->{String[] kinds={"STORY","PERSON","EXPLORATION","CHRONICLE"};String[] titles={"스토리","인물","탐험","연대기"};for(int i=0;i<kinds.length;i++)rows.add(new Button(0,titles[i],kinds[i],null,0));}
            case "STORY","PERSON","EXPLORATION"->{back="diary";for(var f:view.facts())if(f.category().equals(page))rows.add(new Button(0,(f.shared()?"[공동체] ":"[개인] ")+f.title()+" · "+f.summary(),"fact",scope(f.shared())+f.id(),0));}
            case "CHRONICLE"->{back="diary";for(var e:view.events())rows.add(new Button(0,e.time()+" · "+(e.shared()?"[공동체] ":"[개인] ")+e.title(),"event",scope(e.shared())+e.id()+":"+e.version(),0));}
            case "fact"->{back="diary";var f=view.facts().stream().filter(x->(scope(x.shared())+x.id()).equals(id)).findFirst();if(f.isEmpty())rows.add(line(0,"기록을 확인할 수 없습니다."));else{
                var x=f.get();for(String text:List.of(x.title(),x.summary(),x.detail(),"정보 버전: "+x.version(),"최초 확인: "+x.first(),"최근 갱신: "+x.updated(),x.shared()?"공동체 공유 기록 · 개인 경험 여부 미판정":"개인 기록"))rows.add(line(0,text));
                for(var contact:NpcContacts.forFact(catalog,snapshot,id))rows.add(new Button(0,"관련 NPC와 대화: "+contact.name(),"npc-contact",contact.key(),0));}}
            case "event"->{back="CHRONICLE";var e=view.events().stream().filter(x->(scope(x.shared())+x.id()+":"+x.version()).equals(id)).findFirst();if(e.isEmpty())rows.add(line(0,"기록을 확인할 수 없습니다."));else{var x=e.get();for(String text:List.of(x.title(),x.summary(),x.detail(),"확인 시각: "+x.time(),x.shared()?"공동체 공유 기록":"개인 기록"))rows.add(line(0,text));}}
            case "community"->{rows.add(new Button(0,"구성원","members",null,0));rows.add(new Button(0,"스토리 진행","story",null,0));rows.add(new Button(0,"진행 조건","conditions",null,0));}
            case "members"->{back="community";rows.add(line(0,"현재 서버에서 확인한 진행 연결 · 전체 명단 아님"));for(var m:view.members())rows.add(line(0,(m.online()?"[온라인] ":"[오프라인] ")+m.name()+(m.self()?" (본인)":"")));}
            case "story"->{back="community";rows.add(new Button(0,"공유 진행 안내를 채팅에서 확인","story-guide",null,0));rows.add(line(0,"세계 효과: "+switch(view.effectStatus()){case "APPLIED"->"블록 확인 완료";case "NOT_REQUIRED"->"대상 효과 없음";case "NOT_IMPLEMENTED"->"미구현";case "EFFECT_CONFLICT"->"충돌·미적용";case "PENDING"->"확인 대기";default->"확인 실패";}));rows.add(line(0,view.story().title()==null?"현재 공유 단계 미등록":"현재 공유 단계: "+view.story().title()));rows.add(line(0,view.nextGoal()==null?"다음 공유 목표 미등록":"다음 공유 목표: "+view.nextGoal()));rows.add(line(0,view.story().explorationOpen()==null?"자유 탐험 개방 상태 미등록":view.story().explorationOpen()?"공유 자유 탐험 개방":"공유 자유 탐험 미개방"));}
            case "branches"->{back="quests";for(String branch:view.branches())rows.add(line(0,"확정 분기: "+branch));}
            case "conditions"->{back="community";view.conditions().lines().forEach(x->rows.add(line(0,x)));}
            default->throw new IllegalArgumentException("Unknown exploration screen");
        }
        return pageRows(rows,back,page,id,offset);
    }
    private static void navigation(List<Button> out,int offset,int size,String page){if(offset>0)out.add(new Button(47,"이전 페이지",page,null,Math.max(0,offset-36)));if(offset+36<size)out.add(new Button(51,"다음 페이지",page,null,offset+36));}
    public static String notice(String kind){return switch(kind){case "PLACE_FOUND"->"장소 발견";case "FAILED"->"퀘스트 실패";case "RESTARTED"->"퀘스트 재시작";case "STARTED"->"퀘스트 시작";case "OBJECTIVE_UPDATED"->"목표 단계 갱신";case "REPORT_READY"->"NPC 보고 가능";case "COMPLETED"->"퀘스트 완료";default->throw new IllegalArgumentException("Unknown notice");};}
}
