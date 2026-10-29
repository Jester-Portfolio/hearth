package io.github.jesterportfolio.hearth.quest;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
/** 본인에게 현재 공개 가능한 지급·장 진행만 담습니다. 다른 분기의 정의나 좌표를 담지 않습니다. */
public record QuestCompletionView(boolean registered,long epoch,Map<String,Reward> rewards,List<Chapter> chapters){
    public record Reward(String status,String label,boolean claimable){}
    public record Part(String quest,String title,String status){}
    public record Chapter(String id,String title,boolean skipped,List<Part> parts,int unaccepted){public Chapter{parts=List.copyOf(parts);}}
    public QuestCompletionView{rewards=Map.copyOf(rewards);chapters=List.copyOf(chapters);}
    public static QuestCompletionView empty(){return new QuestCompletionView(false,0,Map.of(),List.of());}
    public Optional<Chapter> chapter(String id){return chapters.stream().filter(c->c.id().equals(id)).findFirst();}
    public static QuestCompletionView build(QuestCatalog catalog,QuestRules rules,Map<String,QuestStore.Saved> states,Set<String> blocked,Map<String,QuestCompletionStore.RewardRecord> grants,Map<String,QuestCompletionStore.ChapterState> skips,StoryStore.Snapshot shared,long epoch){
        var rewards=new HashMap<String,Reward>();
        for(var entry:states.entrySet()){
            if(entry.getValue().phase()==QuestStore.Phase.NOT_ACCEPTED||blocked.contains(entry.getKey()))continue;
            var configured=rules.rule(entry.getKey()).reward();var saved=grants.get(entry.getKey());String status=saved==null?(configured==null?"NONE":"UNREGISTERED"):saved.status();
            boolean claim=status.equals("PENDING")&&configured!=null&&configured.fingerprint().equals(saved.fingerprint());
            if(status.equals("PENDING")&&!claim)status="CONFIG_MISMATCH";
            String label=switch(status){case "NONE"->"보상 설정 없음";case "UNREGISTERED"->"지급 기록 없음 · 소급 지급하지 않음";case "PENDING"->"보상 지급 대기 · 인벤토리 공간을 확인하세요";case "GRANTED"->"보상 지급 완료";case "POLICY_PENDING"->"스킵 보상 정책 미정";case "CONFIG_MISMATCH"->"보상 정의 확인 필요 · 지급 보류";default->throw new IllegalStateException("Invalid reward status");};
            rewards.put(entry.getKey(),new Reward(status,label,claim));
        }
        var chapters=new ArrayList<Chapter>();
        for(var c:rules.chapters().values().stream().sorted(Comparator.comparing(QuestRules.Chapter::id)).toList()){
            var selected=c.selected(shared);if(selected.isEmpty())continue;var saved=skips.get(c.id());if(saved!=null&&!saved.fingerprint().equals(c.fingerprint()))throw new IllegalStateException("CHAPTER_CONTENT_MISMATCH");
            int unaccepted=0;var parts=new ArrayList<Part>();for(String id:selected.get())if(!blocked.contains(id)&&QuestListView.available(catalog.quest(id),states)){
                var state=states.get(id);if(state.phase()==QuestStore.Phase.NOT_ACCEPTED){unaccepted++;continue;}String status=switch(state.phase()){case NOT_ACCEPTED->"미수락";case ACTIVE->"진행 중";case REPORT_READY->"보고 대기";case COMPLETED->"직접 완료";case FAILED->"재시작 대기";case SKIPPED->"장 스킵";};parts.add(new Part(id,catalog.quest(id).title(),status));}
            chapters.add(new Chapter(c.id(),c.title(),saved!=null,parts,unaccepted));
        }
        return new QuestCompletionView(true,epoch,rewards,chapters);
    }
}
