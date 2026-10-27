package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;

/** 실제 GUI와 시험이 함께 사용하는 불변 버튼/표시 데이터입니다. */
public final class QuestScreens {
    private QuestScreens(){}
    public record Button(int slot,String label,String action,String quest,long revision) {}
    public static final List<String> MENUS=List.of("퀘스트","월드 지도","일지","공동체","알림","설정");
    public static List<Button> screen(QuestCatalog catalog,QuestJournal.View view,String page,String id,int offset){
        var out=new ArrayList<Button>();
        if(page.equals("home")){
            String[] destinations={"quests","map","diary","community","notices","settings"};
            for(int i=0;i<MENUS.size();i++)out.add(new Button(i,MENUS.get(i),destinations[i],null,0));return List.copyOf(out);
        }
        out.add(new Button(45,"이전 화면","home",null,0));
        if(page.equals("quests")){
            var entries=QuestListView.entries(catalog,view.states());int end=Math.min(entries.size(),offset+36);
            for(int i=offset;i<end;i++){var q=entries.get(i);out.add(new Button(i-offset,"["+q.status()+"] "+q.title(),"detail",q.questId(),0));}
            navigation(out,offset,entries.size(),"quests");
        }else if(page.equals("detail")){
            var detail=QuestListView.detail(catalog,view.states(),id);
            if(detail.isEmpty())out.add(new Button(0,QuestListView.UNAVAILABLE,"none",null,0));
            else{
                int slot=0;for(String line:QuestListView.detailLines(detail))out.add(new Button(slot++,line,"none",null,0));
                var row=view.states().get(id);boolean tracked=view.journal().tracked().contains(id);
                if(row.phase()!=QuestStore.Phase.COMPLETED)out.add(new Button(10,tracked?"추적 해제":"추적","track",id,tracked?0:1));
                out.removeIf(b->b.slot()==45);out.add(new Button(45,"퀘스트 목록","quests",null,0));
            }
        }else if(page.equals("notices")){
            var rows=view.journal().notices().stream().filter(n->QuestListView.detail(catalog,view.states(),n.quest()).isPresent()).toList();
            int end=Math.min(rows.size(),offset+36);for(int i=offset;i<end;i++){var n=rows.get(i);out.add(new Button(i-offset,(n.read()?"[읽음] ":"[안 읽음] ")+catalog.quest(n.quest()).title()+" · "+notice(n.kind()),"notice",n.quest(),n.revision()));}
            out.add(new Button(49,"미확인 "+rows.stream().filter(n->!n.read()).count()+"개 · 모두 읽음","read-all",null,0));navigation(out,offset,rows.size(),"notices");
        }else if(page.equals("settings"))out.add(new Button(0,"퀘스트 HUD: "+(view.journal().preferences().hudEnabled()?"켜짐":"꺼짐"),"hud",null,view.journal().preferences().hudEnabled()?0:1));
        else out.add(new Button(0,"미구현: "+switch(page){case "map"->"월드 지도";case "diary"->"일지";case "community"->"공동체 정보";default->"요청 화면";},"none",null,0));
        return List.copyOf(out);
    }
    private static void navigation(List<Button> out,int offset,int size,String page){if(offset>0)out.add(new Button(47,"이전 페이지",page,null,Math.max(0,offset-36)));if(offset+36<size)out.add(new Button(51,"다음 페이지",page,null,offset+36));}
    public static String notice(String kind){return switch(kind){case "STARTED"->"퀘스트 시작";case "OBJECTIVE_UPDATED"->"목표 단계 갱신";case "REPORT_READY"->"NPC 보고 가능";case "COMPLETED"->"퀘스트 완료";default->throw new IllegalArgumentException("Unknown notice");};}
}
