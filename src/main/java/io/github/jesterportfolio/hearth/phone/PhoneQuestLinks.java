package io.github.jesterportfolio.hearth.phone;

import io.github.jesterportfolio.hearth.quest.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;

/** 현재 개인 조회 스냅샷의 화면 연결만 만듭니다. 직접 저장·보고·보상 처리는 하지 않습니다. */
public final class PhoneQuestLinks {
    private PhoneQuestLinks(){}
    public record Quest(String id,String title,boolean tracked,boolean trackable,boolean reporting){}
    public record Notice(QuestJournalStore.Notice source,String label,String section,int target){}
    public static List<Quest> quests(QuestCatalog catalog,QuestJournal.View view){
        return QuestListView.entries(catalog,view.states()).stream().filter(q->!view.exploration().blockedQuests().contains(q.questId())).map(q->new Quest(q.questId(),q.title(),view.journal().tracked().contains(q.questId()),!view.states().get(q.questId()).satisfies(),q.phase()==QuestStore.Phase.REPORT_READY)).toList();
    }
    public static int questIndex(QuestCatalog catalog,QuestJournal.View view,String id){var rows=quests(catalog,view);for(int i=0;i<rows.size();i++)if(rows.get(i).id().equals(id))return i;throw new IllegalArgumentException("QUEST_UNAVAILABLE");}
    public static List<Notice> notices(QuestCatalog catalog,QuestJournal.View view){
        var quests=quests(catalog,view);var out=new ArrayList<Notice>();
        var ordered=view.journal().notices().stream().sorted(Comparator.comparing(QuestJournalStore.Notice::created).reversed().thenComparing(QuestJournalStore.Notice::type).thenComparing(QuestJournalStore.Notice::quest).thenComparingLong(QuestJournalStore.Notice::revision)).toList();
        for(var n:ordered){String label=null,section=null;int target=-1;
            if(n.type().equals("QUEST"))for(int i=0;i<quests.size();i++)if(quests.get(i).id().equals(n.quest())){label=quests.get(i).title()+" · "+kind(n.kind());section="quests";target=i;break;}
            if(n.type().equals("PLACE"))for(int i=0;i<view.exploration().places().size();i++){var p=view.exploration().places().get(i);if(p.key().equals(n.quest())&&p.discovered()){label=p.name()+" · 장소 발견";section="map";target=i;break;}}
            if(label!=null)out.add(new Notice(n,(n.read()?"[읽음] ":"[안 읽음] ")+label,section,target));
        }return List.copyOf(out);
    }
    public static Notice find(QuestCatalog catalog,QuestJournal.View view,QuestJournalStore.Notice source){return notices(catalog,view).stream().filter(n->n.source().type().equals(source.type())&&n.source().quest().equals(source.quest())&&n.source().revision()==source.revision()).findFirst().orElseThrow(()->new IllegalArgumentException("NOTICE_UNAVAILABLE"));}
    private static String kind(String type){return switch(type){case "STARTED"->"시작";case "OBJECTIVE_UPDATED"->"목표 갱신";case "REPORT_READY"->"보고 가능";case "COMPLETED"->"완료";default->"퀘스트 갱신";};}
}
