package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.story.*;
import io.github.jesterportfolio.hearth.exploration.ExplorationContent;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;

/** 공개 시험 콘텐츠 안내만 계산합니다. 완료 기록이나 게임 진입 조건을 새로 만들지 않습니다. */
public record Playthrough(List<Step> steps){
    public enum Kind{QUEST,STAGE,STAGE_COMPLETE,PLACE}
    public record Step(String id,Kind kind,String reference,String title,String hint){}
    public record Guide(String title,List<String> lines,String command,String talkQuest,boolean complete){public Guide{lines=List.copyOf(lines);}}
    public Playthrough{steps=List.copyOf(steps);}
    public static Playthrough parse(Map<?,?> root,QuestCatalog quests,StoryContent story,ExplorationContent exploration){
        if(!(root.get("format") instanceof Number n)||n.doubleValue()!=1||!(root.get("steps") instanceof List<?> rows)||rows.isEmpty()||rows.size()>64)throw new IllegalArgumentException("Invalid playthrough format");
        var steps=new ArrayList<Step>();var ids=new HashSet<String>();var refs=new HashSet<String>();
        for(Object item:rows){if(!(item instanceof Map<?,?> row))throw new IllegalArgumentException("Invalid playthrough step");
            String id=text(row,"id",64),ref=text(row,"reference",64);var kind=Kind.valueOf(text(row,"kind",32));
            if(!id.matches("[a-z][a-z0-9_]{0,63}")||!ids.add(id)||!refs.add(kind+":"+ref))throw new IllegalArgumentException("Duplicate playthrough step");
            boolean known=switch(kind){case QUEST->quests.questIds().contains(ref);case STAGE,STAGE_COMPLETE->story.stages().containsKey(ref);case PLACE->exploration.places().containsKey(ref);};
            if(!known)throw new IllegalArgumentException("Missing playthrough reference");
            steps.add(new Step(id,kind,ref,text(row,"title",120),text(row,"hint",500)));
        }
        return new Playthrough(steps);
    }
    private static String text(Map<?,?> row,String key,int max){if(!(row.get(key) instanceof String value)||value.isBlank()||value.length()>max)throw new IllegalArgumentException("Invalid playthrough text");return value;}
    public Guide guide(QuestCatalog catalog,QuestJournal.View view,StoryStore.Snapshot shared,SharedStory.Phase phase){
        if(shared==null||phase!=SharedStory.Phase.READY&&phase!=SharedStory.Phase.OPEN||shared.story().revision()!=view.exploration().story().revision())return new Guide("플레이 안내 확인 대기",List.of("공유 진행·저장 상태를 확인한 뒤 다시 조회해 주세요."),null,null,false);
        QuestListView.entries(catalog,view.states());
        for(var step:steps){boolean done=switch(step.kind()){
            case QUEST->view.states().get(step.reference()).phase()==QuestStore.Phase.COMPLETED;
            case STAGE->Objects.equals(shared.story().stage(),step.reference())||shared.completed().contains(step.reference());
            case STAGE_COMPLETE->shared.completed().contains(step.reference());
            case PLACE->view.exploration().place(step.reference()).filter(p->p.discovered()).isPresent();
        };if(done)continue;
            if(step.kind()==Kind.QUEST){var c=catalog.quest(step.reference());var saved=view.states().get(c.id());
                if(view.exploration().blockedQuests().contains(c.id())||!QuestListView.available(c,view.states()))return new Guide("공유 진행 조건 확인",List.of("현재 접근할 수 있는 퀘스트만 안내합니다. 공동체 진행을 먼저 확인해 주세요."),"/hearthstory guide",null,false);
                String line=saved.phase()==QuestStore.Phase.NOT_ACCEPTED?c.text().get("offer"):c.detailGuidance(saved);
                boolean talk=saved.phase()==QuestStore.Phase.REPORT_READY||saved.phase()==QuestStore.Phase.NOT_ACCEPTED&&c.startMode()==QuestContent.StartMode.NPC;
                return new Guide(c.title(),List.of(step.hint(),line,"담당 NPC: "+c.name()),talk?null:saved.phase()==QuestStore.Phase.NOT_ACCEPTED?"/hearthphone":"/hearthquest info "+c.id(),talk?c.id():null,false);
            }
            if(step.kind()==Kind.PLACE)return new Guide("개인 탐험 진행",List.of("개방된 지역을 탐험하고 지도·일지에서 본인이 발견한 정보를 확인하세요. 다른 사람의 발견은 자동 공유하지 않습니다."),"/hearthphone",null,false);
            return new Guide(step.title(),List.of(step.hint(),"접속 중인 공동체 구성원의 조건을 확인한 뒤 기존 공유 진행·채팅 투표로 진행합니다."),"/hearthstory guide",null,false);
        }
        return new Guide("공개 시험 코스 안내 완료",List.of("개인 퀘스트·공유 진행·탐험 안내를 마쳤습니다. 보상·실제 스토리 완료를 뜻하지 않습니다.","지도·일지·알림에서 기록을 확인할 수 있습니다."),"/hearthphone",null,true);
    }
}
