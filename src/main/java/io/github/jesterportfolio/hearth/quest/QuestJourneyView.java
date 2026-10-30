package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.story.*;
import java.util.*;

/** 기존 공개 코스와 개인 상태로 다음 행동만 계산합니다. 조회·실행의 소유권은 현재 세션이 결정합니다. */
public final class QuestJourneyView {
    private QuestJourneyView(){}
    public enum Action{DETAIL,TALK,RETRY,TRACK,UNTRACK,SHARED,MAP,DIARY,NOTICES,REWARDS}
    public record Link(String label,Action action,String quest,long revision){}
    public record Page(Playthrough.Guide guide,List<Link> links,long sharedRevision,long completionEpoch){public Page{links=List.copyOf(links);}}
    public static Page build(Playthrough plan,QuestCatalog catalog,QuestJournal.View view,StoryStore.Snapshot shared,SharedStory.Phase phase){
        var guide=plan.guide(catalog,view,shared,phase);var links=new ArrayList<Link>();
        if(shared==null||phase!=SharedStory.Phase.READY&&phase!=SharedStory.Phase.OPEN||shared.story().revision()!=view.exploration().story().revision())return new Page(guide,List.of(),-1,view.completion().epoch());
        if(guide.quest()!=null){
            String id=guide.quest();var row=Objects.requireNonNull(view.states().get(id));
            if(guide.talkQuest()!=null)links.add(new Link("담당 NPC 대화",Action.TALK,id,row.revision()));
            if(row.phase()!=QuestStore.Phase.NOT_ACCEPTED){links.add(new Link("현재 목표 상세",Action.DETAIL,id,row.revision()));
                if(row.phase()==QuestStore.Phase.FAILED)links.add(new Link("실패 퀘스트 재시작",Action.RETRY,id,row.revision()));
                else if(!row.satisfies()){boolean tracked=view.journal().tracked().contains(id);links.add(new Link(tracked?"퀘스트 추적 해제":"퀘스트 추적",tracked?Action.UNTRACK:Action.TRACK,id,row.revision()));}
            }
        }
        if("/hearthstory guide".equals(guide.command()))links.add(new Link("공동 조사 안내",Action.SHARED,null,-1));
        if(view.completion().registered()&&view.completion().rewards().entrySet().stream().anyMatch(e->e.getValue().claimable()&&!view.exploration().blockedQuests().contains(e.getKey())&&Optional.ofNullable(view.states().get(e.getKey())).map(s->s.phase()==QuestStore.Phase.COMPLETED).orElse(false)))links.add(new Link("지급 대기 보상 조회",Action.REWARDS,null,-1));
        links.add(new Link("지도 정보",Action.MAP,null,-1));links.add(new Link("일지",Action.DIARY,null,-1));links.add(new Link("개인 알림",Action.NOTICES,null,-1));
        return new Page(guide,links,shared.story().revision(),view.completion().epoch());
    }
    /** 화면을 만든 후 목표·추적·공유/지급 상태가 달라지면 먼저 새 안내를 조회합니다. */
    public static void require(Page current,Page expected,Link selected){if(!current.equals(expected)||!current.links().contains(selected))throw new IllegalStateException("STALE_JOURNEY_ACTION");}
}
