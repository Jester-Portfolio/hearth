package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.combat.DungeonPlay;
import io.github.jesterportfolio.hearth.gathering.GatheringContent;
import io.github.jesterportfolio.hearth.persistence.QuestStore;
import java.util.*;
import java.util.function.Predicate;

/** 등록된 공개 시설과 본인의 현재 목표를 잇는 읽기 전용 안내입니다. 입장 조건을 추가하지 않습니다. */
public final class FacilityObjectives {
    public static final int PAGE_SIZE=6;
    public enum Kind { VISIT,DUNGEON,NEXT,REPORT }
    public record Row(String quest,String title,String npc,QuestStore.Phase phase,long revision,int objective,Kind kind,String guidance) {
        public String label(){return switch(kind){case VISIT->"채집 구역 방문 목표";case DUNGEON->"현재 던전 목표";case NEXT->"현재 다음 목표";case REPORT->"담당 NPC 보고 대기";};}
    }
    public record Page(int number,int pages,List<Row> rows,List<String> lines){
        public Page{rows=List.copyOf(rows);lines=List.copyOf(lines);}
        public QuestBookView book(String title){return new QuestBookView(title,QuestBookView.paginate(lines));}
    }
    public record Board(String source,String title,long sharedRevision,List<Row> rows,List<String> status){
        public Board{rows=List.copyOf(rows);status=List.copyOf(status);}
        public int pages(){return Math.max(1,(rows.size()+PAGE_SIZE-1)/PAGE_SIZE);}
        public Page page(int number){
            if(number<1||number>pages())throw new IllegalArgumentException("FACILITY_OBJECTIVES_PAGE");
            int from=(number-1)*PAGE_SIZE;var selected=rows.subList(from,Math.min(rows.size(),from+PAGE_SIZE));var lines=new ArrayList<String>(status);
            if(rows.isEmpty())lines.add("현재 공개된 진행 중 개인 목표나 보고 대기 의뢰가 없습니다. 담당 NPC와 다음 플레이 안내를 확인하세요.");
            for(var row:selected){lines.add(row.title()+" · "+row.label());lines.add(row.guidance());lines.add("담당 NPC: "+row.npc());}
            if(source.startsWith("gathering:"))lines.add("방문 기록은 실제 채집 횟수나 아이템 획득을 뜻하지 않습니다.");lines.add("읽기 전용 안내 · 이동/입장/제작/수락/보고를 실행하지 않습니다.");lines.add(number+" / "+pages()+"쪽");
            return new Page(number,pages(),selected,lines);
        }
    }
    private FacilityObjectives(){}
    public static Board gathering(QuestCatalog catalog,QuestJournal.View view,GatheringContent content,Set<String> related,List<String> status){
        var spawn=content.spawn();Predicate<QuestContent.Goal> visit=g->g.interaction()==null&&g.area().contains(spawn.x(),spawn.y(),spawn.z());
        for(String id:related)if(catalog.quest(id).goals().stream().noneMatch(visit))throw new IllegalArgumentException("FACILITY_GATHERING_LINK");
        return build(catalog,view,"gathering:"+content.id(),"채집장 개인 목표·다음 행동",related,visit,Kind.VISIT,status);
    }
    public static Board dungeon(QuestCatalog catalog,QuestJournal.View view,DungeonPlay.Definition definition,List<String> status){
        var q=catalog.quest(definition.quest());Predicate<QuestContent.Goal> goal=g->definition.reference().equals(g.interaction());
        if(!q.npcId().equals(definition.npc())||q.goals().stream().noneMatch(goal))throw new IllegalArgumentException("FACILITY_DUNGEON_LINK");
        return build(catalog,view,definition.reference(),"던전 개인 목표·다음 행동",Set.of(q.id()),goal,Kind.DUNGEON,status);
    }
    private static Board build(QuestCatalog catalog,QuestJournal.View view,String source,String title,Set<String> related,Predicate<QuestContent.Goal> goal,Kind kind,List<String> status){
        var rows=new ArrayList<Row>();
        for(var entry:QuestListView.filtered(catalog,view.states(),view.journal().tracked(),view.exploration().blockedQuests(),QuestListView.Filter.ALL)){
            if(!related.contains(entry.questId())||entry.phase()!=QuestStore.Phase.ACTIVE&&entry.phase()!=QuestStore.Phase.REPORT_READY)continue;
            var q=catalog.quest(entry.questId());var state=view.states().get(q.id());
            var current=state.phase()==QuestStore.Phase.REPORT_READY?Kind.REPORT:goal.test(q.goal(state))?kind:Kind.NEXT;
            rows.add(new Row(q.id(),q.title(),q.name(),state.phase(),state.revision(),state.objectiveIndex(),current,q.detailGuidance(state)));
        }
        return new Board(source,title,view.exploration().story().revision(),rows,status);
    }
    /** 초 단위 상태 문구 갱신은 허용하되 목표/공개 범위의 변경은 다시 선택하게 합니다. */
    public static void require(Board fresh,Board shown){
        if(!fresh.source().equals(shown.source())||fresh.sharedRevision()!=shown.sharedRevision()||!fresh.rows().equals(shown.rows()))throw new IllegalStateException("STALE_FACILITY_OBJECTIVES");
    }
    public static void require(Board fresh,Board shown,int number,Row selected){
        require(fresh,shown);if(!shown.page(number).rows().contains(selected))throw new IllegalStateException("STALE_FACILITY_OBJECTIVE");
    }
}
