package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.story.SharedStory;
import java.util.*;

/** 저장된 개인 의뢰와 현재 공개 코스를 읽습니다. 입장·추적·재시작·지급을 자동 실행하지 않습니다. */
public final class CourseResume {
    private CourseResume(){}
    public static final int PAGE_SIZE=6;
    public enum Kind{REPORT,FAILED,REWARD,ACTIVE}
    public record Row(String quest,String title,Kind kind,boolean current,boolean tracked,long revision,int objective,List<String> lines){
        public Row{lines=List.copyOf(lines);}
        public String label(){return switch(kind){case REPORT->"개인 보고 대기";case FAILED->"재시작 확인 필요";case REWARD->"보상 수령 대기";case ACTIVE->"저장된 목표 진행 중";};}
    }
    public record Board(boolean available,PlaythroughOverview.Board course,List<Row> rows,List<String> sharedConditions){
        public Board{rows=List.copyOf(rows);sharedConditions=List.copyOf(sharedConditions);}
        public int pages(){return Math.max(1,(rows.size()+PAGE_SIZE-1)/PAGE_SIZE);}
        public List<Row> page(int number){if(number<1||number>pages())throw new IllegalArgumentException("RESUME_PAGE");int start=Math.min(rows.size(),(number-1)*PAGE_SIZE);return rows.subList(start,Math.min(rows.size(),start+PAGE_SIZE));}
        public int coursePage(){for(int i=0;i<course.rows().size();i++)if(course.rows().get(i).current())return i/PlaythroughOverview.PAGE_SIZE+1;return 1;}
        public QuestBookView book(int number){
            var selected=page(number);var lines=new ArrayList<String>();
            if(!available)lines.add("저장·공유 진행을 확인한 뒤 다시 조회하세요. 재개 가능이나 완료로 판정하지 않았습니다.");
            else{
                lines.add("현재 코스: "+course.guide().title());lines.addAll(course.guide().lines());
                lines.add("현재 확인할 개인 의뢰 "+rows.size()+"건 · 보고 "+rows.stream().filter(r->r.kind()==Kind.REPORT).count()+"건 · 재시작 "+rows.stream().filter(r->r.kind()==Kind.FAILED).count()+"건 · 보상 "+rows.stream().filter(r->r.kind()==Kind.REWARD).count()+"건");
                for(var row:selected){lines.add((row.current()?"[현재 코스] ":"")+row.title()+" · "+row.label()+(row.tracked()?" · 추적 중":""));lines.addAll(row.lines());}
                if(rows.isEmpty())lines.add("별도로 이어갈 수락 의뢰나 지급 대기 보상이 없습니다. 현재 코스의 다음 안내를 확인하세요.");
                if(!sharedConditions.isEmpty()){lines.add("현재 공동체 조건");lines.addAll(sharedConditions);}
                lines.add("개인 의뢰·보고·보상과 공동체 진행은 각각 확인합니다. 전투·이동 상태는 현장 안내에서 확인하며 자동 입장·재시작하지 않습니다.");
            }
            lines.add(number+" / "+pages()+"쪽");return new QuestBookView("이어 하기",QuestBookView.paginate(lines));
        }
    }
    public static Board build(Playthrough plan,QuestCatalog catalog,QuestJournal.View view,StoryStore.Snapshot shared,SharedStory.Phase phase){
        var course=PlaythroughOverview.build(plan,catalog,view,shared,phase);if(!course.available())return new Board(false,course,List.of(),List.of());
        var rows=new ArrayList<Row>();String current=course.guide().quest();
        for(var entry:QuestListView.entries(catalog,view.states())){
            var q=catalog.quest(entry.questId());var saved=view.states().get(q.id());if(!QuestFollowups.visible(view,q.id())||view.blockedNpcs().contains(q.npcId()))continue;
            var kind=switch(saved.phase()){case ACTIVE->Kind.ACTIVE;case REPORT_READY->Kind.REPORT;case FAILED->Kind.FAILED;case COMPLETED->Kind.REWARD;default->throw new IllegalStateException("RESUME_PHASE");};
            var lines=new ArrayList<String>();lines.add(q.detailGuidance(saved));lines.add("담당 NPC: "+q.name());if(kind==Kind.REWARD)lines.add(view.completion().rewards().get(q.id()).label());
            rows.add(new Row(q.id(),q.title(),kind,Objects.equals(current,q.id()),view.journal().tracked().contains(q.id()),saved.revision(),saved.objectiveIndex(),lines));
        }
        rows.sort(Comparator.comparing(Row::current).reversed().thenComparing(Row::kind).thenComparing(Comparator.comparing(Row::tracked).reversed()).thenComparing(Row::quest));
        var conditions="/hearthstory guide".equals(course.guide().command())?view.exploration().conditions().lines():List.<String>of();
        return new Board(true,course,rows,conditions);
    }
    public static void require(Board current,Board shown,int number,Row selected){if(!current.available()||!current.equals(shown))throw new IllegalStateException("STALE_RESUME_ACTION");var rows=shown.page(number);if(selected!=null&&!rows.contains(selected))throw new IllegalStateException("STALE_RESUME_ACTION");}
}
