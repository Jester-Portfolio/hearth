package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.phone.PhoneCompletion;
import io.github.jesterportfolio.hearth.persistence.QuestStore;
import java.util.*;

/** 공개된 본인 실패/지급 대기 기록만 기존 확인 동작으로 연결합니다. */
public final class QuestFollowups {
    private QuestFollowups(){}
    public static boolean reward(QuestJournal.View view,String id){
        var row=view.states().get(id);return row!=null&&row.phase()==QuestStore.Phase.COMPLETED&&view.completion().registered()&&!view.exploration().blockedQuests().contains(id)&&Optional.ofNullable(view.completion().rewards().get(id)).map(QuestCompletionView.Reward::claimable).orElse(false);
    }
    public static boolean visible(QuestJournal.View view,String id){
        var row=Objects.requireNonNull(view.states().get(id));return !view.exploration().blockedQuests().contains(id)&&(row.phase()==QuestStore.Phase.ACTIVE||row.phase()==QuestStore.Phase.REPORT_READY||row.phase()==QuestStore.Phase.FAILED||reward(view,id));
    }
    public static List<PhoneCompletion.Choice> choices(QuestCatalog catalog,QuestJournal.View view,String id){
        var detail=QuestListView.detail(catalog,view.states(),id);if(detail.isEmpty()||view.exploration().blockedQuests().contains(id))return List.of();
        if(detail.get().quest().phase()==QuestStore.Phase.FAILED)return List.of(PhoneCompletion.choose(catalog,view,PhoneCompletion.Operation.RETRY,id));
        return reward(view,id)?List.of(PhoneCompletion.choose(catalog,view,PhoneCompletion.Operation.CLAIM,id)):List.of();
    }
    public static String label(PhoneCompletion.Choice choice){return switch(choice.operation()){case RETRY->"재시작 확인";case CLAIM->"지급 대기 보상 확인";case SKIP->"장 스킵 확인";};}
}
