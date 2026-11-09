package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.exploration.ExplorationContent;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.story.SharedStory;
import java.util.*;

/** 코스의 현재 공개 항목을 기존 시설 준비 안내에 연결합니다. 진행 조건이나 이동을 추가하지 않습니다. */
public record CourseRoute(List<Binding> bindings) {
    public record Binding(String step,int objective,String preparation){}
    public record Page(PlaythroughOverview.Board board,int coursePage,String preparation,List<String> lines){
        public Page{lines=List.copyOf(lines);}
        public QuestBookView book(){return new QuestBookView("현재 코스 동선",QuestBookView.paginate(lines));}
    }
    public CourseRoute{bindings=List.copyOf(bindings);}
    public static CourseRoute parse(Map<?,?> root,Playthrough plan,QuestCatalog catalog,PreparationGuide preparation,ExplorationContent exploration){
        if(!(root.get("routes") instanceof List<?> rows)||rows.isEmpty()||rows.size()>64)throw new IllegalArgumentException("COURSE_ROUTE_FORMAT");
        var result=new ArrayList<Binding>();var used=new HashSet<String>();
        for(Object value:rows){
            if(!(value instanceof Map<?,?> row)||!(row.get("step") instanceof String step)||!(row.get("preparation") instanceof String target)||!(row.get("objective") instanceof Number n)||n.doubleValue()!=n.intValue())throw new IllegalArgumentException("COURSE_ROUTE_LINK");
            int objective=n.intValue();var definition=plan.steps().stream().filter(s->s.id().equals(step)).findFirst().orElseThrow(()->new IllegalArgumentException("COURSE_ROUTE_STEP"));
            var entry=preparation.entries().stream().filter(e->e.id().equals(target)).findFirst().orElseThrow(()->new IllegalArgumentException("COURSE_ROUTE_PREPARATION"));
            if(!used.add(step+":"+objective))throw new IllegalArgumentException("COURSE_ROUTE_DUPLICATE");
            boolean valid=false;
            if(definition.kind()==Playthrough.Kind.PLACE)valid=objective==-1&&Objects.equals(entry.place(),definition.reference());
            else if(definition.kind()==Playthrough.Kind.QUEST){
                var q=catalog.quest(definition.reference());if(objective<0||objective>=q.goals().size())throw new IllegalArgumentException("COURSE_ROUTE_OBJECTIVE");var goal=q.goals().get(objective);
                valid=switch(entry.kind()){
                    case CRAFTING->entry.recipes().stream().anyMatch(id->Objects.equals(goal.interaction(),"craft:"+entry.reference()+":"+id));
                    case DUNGEON->Objects.equals(entry.quest(),q.id())&&Objects.equals(goal.interaction(),"dungeon:"+entry.reference());
                    case GATHERING->{var place=exploration.places().get(entry.place());yield goal.interaction()==null&&place!=null&&overlaps(goal.area(),place.bounds());}
                };
            }
            if(!valid)throw new IllegalArgumentException("COURSE_ROUTE_TARGET_MISMATCH");result.add(new Binding(step,objective,target));
        }
        return new CourseRoute(result);
    }
    private static boolean overlaps(QuestContent.Area a,QuestContent.Area b){return a.minX()<=b.maxX()&&a.maxX()>=b.minX()&&a.minY()<=b.maxY()&&a.maxY()>=b.minY()&&a.minZ()<=b.maxZ()&&a.maxZ()>=b.minZ();}
    public Page build(Playthrough plan,QuestCatalog catalog,QuestJournal.View view,StoryStore.Snapshot shared,SharedStory.Phase phase,PreparationGuide.Page preparation){
        var board=PlaythroughOverview.build(plan,catalog,view,shared,phase);var current=board.rows().stream().filter(PlaythroughOverview.Row::current).findFirst().orElse(null);var lines=new ArrayList<String>();
        lines.add(board.guide().title());lines.addAll(board.guide().lines());String target=null;int number=1;
        if(current!=null){
            number=board.rows().indexOf(current)/PlaythroughOverview.PAGE_SIZE+1;
            int objective=current.kind()==PlaythroughOverview.Kind.EXPLORATION?-1:current.quest()!=null&&view.states().get(current.quest()).phase()==QuestStore.Phase.ACTIVE?view.states().get(current.quest()).objectiveIndex():-2;
            var link=bindings.stream().filter(b->b.step().equals(current.key())&&b.objective()==objective).findFirst();
            if(link.isPresent()){
                var card=preparation.cards().stream().filter(c->c.id().equals(link.get().preparation())).findFirst();
                if(card.isPresent()){target=card.get().id();lines.add("현재 시설 준비: "+card.get().title());lines.addAll(card.get().lines());}
                else lines.add("현재 공개된 시설 준비 안내를 확인할 수 없습니다. 다음 플레이 안내와 지도를 확인하세요.");
            }
        }
        lines.add("의뢰 수락·보고, 시설 입퇴장·제작, 공유 진행·투표는 기존 현장과 안내에서 진행하세요.");
        return new Page(board,number,target,lines);
    }
    public static void require(Page fresh,Page shown){
        PlaythroughOverview.require(fresh.board(),shown.board());if(fresh.coursePage()!=shown.coursePage()||!Objects.equals(fresh.preparation(),shown.preparation()))throw new IllegalStateException("STALE_COURSE_ROUTE");
    }
}
