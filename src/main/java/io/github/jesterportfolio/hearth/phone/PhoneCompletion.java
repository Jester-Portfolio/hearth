package io.github.jesterportfolio.hearth.phone;

import io.github.jesterportfolio.hearth.quest.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;

/** 기존 지급/장 DTO의 현재 실행 가능 동작만 연결합니다. 보상·스킵 규칙을 만들지 않습니다. */
public final class PhoneCompletion {
    private PhoneCompletion(){}
    public enum Operation{RETRY,CLAIM,SKIP}
    public record Choice(Operation operation,String id,String title,long revision,long epoch){}
    public static Choice choose(QuestCatalog catalog,QuestJournal.View view,Operation operation,String id){
        if(id==null||!id.matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("INVALID_COMPLETION_ID");
        if(operation==Operation.SKIP){var chapter=view.completion().chapter(id).orElseThrow(()->new IllegalArgumentException("CHAPTER_UNAVAILABLE"));if(!view.completion().registered()||chapter.skipped())throw new IllegalArgumentException("CHAPTER_SKIP_UNAVAILABLE");return new Choice(operation,id,chapter.title(),-1,view.completion().epoch());}
        var detail=QuestListView.detail(catalog,view.states(),id);if(detail.isEmpty()||view.exploration().blockedQuests().contains(id))throw new IllegalArgumentException("QUEST_UNAVAILABLE");
        var row=view.states().get(id);
        if(operation==Operation.RETRY&&row.phase()!=QuestStore.Phase.FAILED)throw new IllegalArgumentException("QUEST_RETRY_UNAVAILABLE");
        if(operation==Operation.CLAIM&&(row.phase()!=QuestStore.Phase.COMPLETED||!view.completion().registered()||!Optional.ofNullable(view.completion().rewards().get(id)).map(QuestCompletionView.Reward::claimable).orElse(false)))throw new IllegalArgumentException("REWARD_UNAVAILABLE");
        return new Choice(operation,id,detail.get().quest().title(),row.revision(),view.completion().epoch());
    }
    public static void require(QuestCatalog catalog,QuestJournal.View current,Choice expected){if(!choose(catalog,current,expected.operation(),expected.id()).equals(expected))throw new IllegalStateException("STALE_COMPLETION_ACTION");}
    public static List<String> chapterLines(QuestCompletionView.Chapter chapter){var lines=new ArrayList<String>();lines.add(chapter.skipped()?"개인 장 스킵 기록 있음 · 실제 완료/보상과 별개":"개인 따라잡기 대상 장");if(chapter.unaccepted()>0)lines.add("미수락 필수 퀘스트 "+chapter.unaccepted()+"개 · 제목/세부 정보는 아직 공개하지 않습니다.");for(var part:chapter.parts())lines.add("["+part.status()+"] "+part.title());lines.add("공동체 분기·세계 상태는 유지합니다. 스킵 보상은 미정입니다.");return List.copyOf(lines);}
}
