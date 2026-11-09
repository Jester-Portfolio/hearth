package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.story.*;
import java.util.*;

/** 본인에게 이미 공개된 코스 기록을 모읍니다. 새 의뢰/단계/장소의 공개 조건을 만들지 않습니다. */
public final class PlaythroughOverview {
    public static final int PAGE_SIZE=6;
    public enum Kind { PERSONAL, SHARED, EXPLORATION, WAITING }
    public record Row(String key,Kind kind,String title,String status,boolean current,boolean fulfilled,boolean tracked,String quest,long revision,List<String> lines){
        public Row {lines=List.copyOf(lines);}
    }
    public record Slice(int number,int pages,List<Row> rows,List<String> lines){
        public Slice{rows=List.copyOf(rows);lines=List.copyOf(lines);}
        public QuestBookView book(){return new QuestBookView("공개 코스 진행판",QuestBookView.paginate(lines));}
    }
    public record Board(boolean available,Playthrough.Guide guide,List<Row> rows,long sharedRevision,long completionEpoch){
        public Board {rows=List.copyOf(rows);}
        public int pages(){return Math.max(1,(rows.size()+PAGE_SIZE-1)/PAGE_SIZE);}
        public Slice page(int number){
            if(number<1||number>pages())throw new IllegalArgumentException("COURSE_PAGE_UNAVAILABLE");
            var selected=rows.subList((number-1)*PAGE_SIZE,Math.min(rows.size(),number*PAGE_SIZE));var lines=new ArrayList<String>();
            if(!available)lines.add("공유 진행과 저장 상태를 확인한 뒤 다시 조회해 주세요.");
            else{
                long visible=rows.stream().filter(r->r.kind()!=Kind.WAITING).count(),fulfilled=rows.stream().filter(Row::fulfilled).count();
                lines.add("현재 공개된 항목 "+visible+"개 · 안내 조건 충족 "+fulfilled+"개");
                lines.add("다음 안내: "+guide.title());
                for(var row:selected){lines.add((row.current()?"[현재 안내] ":"")+row.title()+" · "+row.status()+(row.tracked()?" · 추적 중":""));lines.addAll(row.lines());}
                if(selected.isEmpty())lines.add("현재 표시할 코스 기록이 없습니다.");
                lines.add("개인 기록과 공유 단계는 각각의 진행 상태입니다. 코스 안내는 보상이나 게임 완료를 확정하지 않습니다.");
            }
            lines.add(number+" / "+pages()+"쪽");return new Slice(number,pages(),selected,lines);
        }
    }
    private PlaythroughOverview(){}
    public static Board build(Playthrough plan,QuestCatalog catalog,QuestJournal.View view,StoryStore.Snapshot shared,SharedStory.Phase phase){
        var guide=plan.guide(catalog,view,shared,phase);
        if(shared==null||phase!=SharedStory.Phase.READY&&phase!=SharedStory.Phase.OPEN||shared.story().revision()!=view.exploration().story().revision())return new Board(false,guide,List.of(),-1,view.completion().epoch());
        var rows=new ArrayList<Row>();boolean nextFound=false;
        for(var step:plan.steps()){
            boolean fulfilled=switch(step.kind()){
                case QUEST->view.states().get(step.reference()).satisfies();
                case STAGE->Objects.equals(shared.story().stage(),step.reference())||shared.completed().contains(step.reference());
                case STAGE_COMPLETE->shared.completed().contains(step.reference());
                case PLACE->view.exploration().place(step.reference()).filter(p->p.discovered()).isPresent();
            };
            boolean current=!fulfilled&&!nextFound;if(current)nextFound=true;
            if(step.kind()==Playthrough.Kind.QUEST){
                var content=catalog.quest(step.reference());var saved=view.states().get(content.id());
                boolean shown=!view.exploration().blockedQuests().contains(content.id())&&QuestListView.available(content,view.states());
                // 미수락 미래 의뢰는 기존 다음 안내의 차례가 되기 전까지 표시하지 않습니다.
                if(!shown||saved.phase()==QuestStore.Phase.NOT_ACCEPTED&&!current){if(current)rows.add(waiting(guide));continue;}
                boolean accepted=saved.phase()!=QuestStore.Phase.NOT_ACCEPTED;
                rows.add(new Row(step.id(),Kind.PERSONAL,content.title(),QuestListView.status(saved.phase()),current,fulfilled,accepted&&!fulfilled&&view.journal().tracked().contains(content.id()),accepted?content.id():null,saved.revision(),List.of("본인의 개인 의뢰 기록입니다.","담당 NPC: "+content.name())));
            }else if(step.kind()==Playthrough.Kind.PLACE){
                var place=view.exploration().place(step.reference());if(place.isEmpty()){if(current)rows.add(waiting(guide));continue;}
                var p=place.get();rows.add(new Row(step.id(),Kind.EXPLORATION,p.name(),p.discovered()?"개인 발견 기록":"공개 장소 탐색 대기",current,fulfilled,false,null,-1,List.of(p.location(),"발견은 다른 플레이어에게 자동 공유하지 않습니다.")));
            }else{
                boolean known=shared.completed().contains(step.reference())||Objects.equals(shared.story().stage(),step.reference());
                if(!known&&!current)continue;
                String status=shared.completed().contains(step.reference())?"공유 단계 완료":fulfilled?"공유 단계 도달":"공유 조건 확인 대기";
                rows.add(new Row(step.id(),Kind.SHARED,current?guide.title():step.title(),status,current,fulfilled,false,null,shared.story().revision(),List.of(step.kind()==Playthrough.Kind.STAGE?"단계 도달 안내입니다. 해당 단계의 완료와 구분합니다.":"공유 단계의 완료 기록을 확인합니다.")));
            }
        }
        return new Board(true,guide,rows,shared.story().revision(),view.completion().epoch());
    }
    private static Row waiting(Playthrough.Guide guide){return new Row("_conditions",Kind.WAITING,guide.title(),"공개 조건 확인 대기",true,false,false,null,-1,List.of("다음 플레이 안내에서 현재 공개된 조건을 확인하세요."));}
    public static void require(Board fresh,Board shown){if(!fresh.available()||!fresh.equals(shown))throw new IllegalStateException("STALE_COURSE_OVERVIEW");}
    public static void requireRow(Board fresh,Board shown,Row selected){require(fresh,shown);if(!shown.rows().contains(selected)||selected.kind()==Kind.WAITING)throw new IllegalStateException("STALE_COURSE_OVERVIEW");}
    public static void require(Board fresh,Board shown,Row selected){requireRow(fresh,shown,selected);if(selected.quest()==null)throw new IllegalStateException("STALE_COURSE_OVERVIEW");}
}
