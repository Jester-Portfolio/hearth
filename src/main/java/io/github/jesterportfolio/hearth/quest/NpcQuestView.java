package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.QuestStore;
import java.util.*;
import java.util.function.Predicate;

/** 허용된 개인 상태만으로 NPC 선택지와 표식을 만듭니다. DB·엔티티를 변경하지 않습니다. */
public record NpcQuestView(List<Option> options,Marker marker){
    public enum Marker{NONE,AVAILABLE,REPORT_READY}
    public record Option(String quest,String title,String status,int priority){}
    public NpcQuestView{options=List.copyOf(options);}
    public static NpcQuestView build(QuestCatalog catalog,String npc,Map<String,QuestStore.Saved> rows,Predicate<QuestContent> allowed){
        if(!rows.keySet().equals(catalog.questIds()))throw new IllegalArgumentException("Incomplete NPC quest state");
        var out=new ArrayList<Option>();boolean available=false,report=false;
        for(var c:catalog.forNpc(npc)){
            var saved=Objects.requireNonNull(rows.get(c.id()));
            if(!allowed.test(c)||!QuestListView.available(c,rows))continue;
            String label;int priority;
            switch(saved.phase()){
                case REPORT_READY -> {label="보고 가능";priority=0;report=true;}
                case NOT_ACCEPTED -> {boolean direct=c.startMode()==QuestContent.StartMode.NPC;label=direct?"수락 가능":"지역 진입으로 시작";priority=direct?1:3;available|=direct;}
                case ACTIVE -> {label="진행 중";priority=2;}
                case COMPLETED -> {label="완료";priority=4;}
                default -> throw new IllegalArgumentException("Unknown NPC quest phase");
            }
            out.add(new Option(c.id(),c.title(),label,priority));
        }
        out.sort(Comparator.comparingInt(Option::priority).thenComparing(Option::quest));
        return new NpcQuestView(out,report?Marker.REPORT_READY:available?Marker.AVAILABLE:Marker.NONE);
    }
}
