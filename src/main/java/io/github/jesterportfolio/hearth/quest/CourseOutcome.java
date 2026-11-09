package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.story.SharedStory;
import java.util.*;

/** 등록된 공개 코스의 개인/공유 결과만 요약합니다. 새 완료·보상·세계 효과를 확정하지 않습니다. */
public final class CourseOutcome {
    private CourseOutcome(){}
    public record Counts(int completed,int skipped,int active,int reporting,int failed,int sharedReached,int sharedCompleted,int discovered,int visited,int pendingRewards,int grantedRewards,int heldRewards){}
    public record Item(PlaythroughOverview.Row source,String reward){public boolean readable(){return source.quest()!=null||CourseRecordView.readable(source);}}
    public record Board(PlaythroughOverview.Board course,Counts counts,List<Item> items){
        public Board{items=List.copyOf(items);}public boolean available(){return course.available();}public int pages(){return Math.max(1,(items.size()+5)/6);}
        public List<Item> page(int number){if(number<1||number>pages())throw new IllegalArgumentException("OUTCOME_PAGE");int from=Math.min(items.size(),(number-1)*6);return items.subList(from,Math.min(items.size(),from+6));}
        public QuestBookView book(int number){var rows=page(number);var lines=new ArrayList<String>();
            if(!available())lines.add("저장·공유 상태를 확인하지 못했습니다. 결과를 완료나 빈 기록으로 판정하지 않습니다.");
            else{lines.add(course.guide().complete()?"공개 코스 안내 조건 충족":"공개 코스 진행 중");lines.add("개인 직접 완료 "+counts.completed()+" · 장 스킵 "+counts.skipped()+" · 진행 "+counts.active()+" · 보고 "+counts.reporting()+" · 재시작 "+counts.failed());lines.add("등록된 공유 단계 도달 "+counts.sharedReached()+" · 완료 "+counts.sharedCompleted()+" · 개인 장소 발견 "+counts.discovered()+" · 직접 방문 "+counts.visited());lines.add("코스 의뢰 보상: 수령 가능 "+counts.pendingRewards()+" · 지급 완료 "+counts.grantedRewards()+" · 정책/정의 확인 "+counts.heldRewards());
                for(var item:rows){var row=item.source();lines.add((row.current()?"[현재 안내] ":"")+row.title()+" · "+row.status());if(item.reward()!=null)lines.add(item.reward());}
                lines.add("이 결과는 본인의 현재 공개 기록입니다. 다른 구성원의 개인 완료·아이템·방문을 대신하지 않으며 스킵과 직접 완료·보상 지급을 구분합니다.");lines.add("과거 단계의 장면을 재생하거나 분기·세계 상태를 다시 적용하지 않습니다.");}
            lines.add(number+" / "+pages()+"쪽");return new QuestBookView("코스 결과·기록",QuestBookView.paginate(lines));}
    }
    public static Board build(Playthrough plan,QuestCatalog catalog,QuestJournal.View view,StoryStore.Snapshot shared,SharedStory.Phase phase){
        var course=PlaythroughOverview.build(plan,catalog,view,shared,phase);if(!course.available())return new Board(course,new Counts(0,0,0,0,0,0,0,0,0,0,0,0),List.of());
        int completed=0,skipped=0,active=0,reporting=0,failed=0,discovered=0,visited=0,pending=0,granted=0,held=0;var stages=new HashSet<String>();var items=new ArrayList<Item>();
        for(var row:course.rows()){
            String reward=null;var step=plan.steps().stream().filter(s->s.id().equals(row.key())).findFirst();
            if(row.quest()!=null){var saved=view.states().get(row.quest());switch(saved.phase()){case COMPLETED->completed++;case SKIPPED->skipped++;case ACTIVE->active++;case REPORT_READY->reporting++;case FAILED->failed++;default->{}}
                var r=view.completion().rewards().get(row.quest());if(r!=null){reward=r.label();if(saved.phase()==QuestStore.Phase.COMPLETED&&r.claimable())pending++;if(r.status().equals("GRANTED"))granted++;if(Set.of("CONFIG_MISMATCH","POLICY_PENDING","UNREGISTERED").contains(r.status()))held++;}}
            if(step.isPresent()&&row.kind()==PlaythroughOverview.Kind.EXPLORATION){var place=view.exploration().place(step.get().reference()).orElseThrow();if(place.discovered())discovered++;if(place.visited())visited++;}
            if(step.isPresent()&&row.kind()==PlaythroughOverview.Kind.SHARED)stages.add(step.get().reference());items.add(new Item(row,reward));
        }
        int reached=(int)stages.stream().filter(s->shared.completed().contains(s)||Objects.equals(shared.story().stage(),s)).count(),done=(int)stages.stream().filter(shared.completed()::contains).count();
        return new Board(course,new Counts(completed,skipped,active,reporting,failed,reached,done,discovered,visited,pending,granted,held),items);
    }
    public static void require(Board fresh,Board shown,int number,Item selected){if(!fresh.available()||!fresh.equals(shown))throw new IllegalStateException("STALE_COURSE_OUTCOME");var rows=shown.page(number);if(selected!=null&&(!rows.contains(selected)||!selected.readable()))throw new IllegalStateException("STALE_COURSE_OUTCOME_ITEM");}
}
