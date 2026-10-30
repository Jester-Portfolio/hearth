package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.phone.PhoneCompletion;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PhoneCompletionTest {
    static QuestJournal.View view(QuestStore.Phase phase,long revision,long epoch,String reward,boolean skipped){
        var rows=new HashMap<>(QuestNavigationTest.states());rows.put("active",new QuestStore.Saved(phase,revision));
        var completion=new QuestCompletionView(true,epoch,Map.of("active",new QuestCompletionView.Reward(reward,"현재 보상 상태",reward.equals("PENDING"))),List.of(new QuestCompletionView.Chapter("chapter","공개 시험 장",skipped,List.of(new QuestCompletionView.Part("active","공개 퀘스트","개인 상태")),2)));
        return new QuestJournal.View(rows,QuestNavigationTest.view().journal(),QuestNavigationTest.view().exploration(),completion);
    }
    @Test void onlyFailedQuestCanRetryAndChangedRevisionOrEpochRejectsOldConfirmation(){
        var c=QuestNavigationTest.catalog();var v=view(QuestStore.Phase.FAILED,3,1,"NONE",false);var choice=PhoneCompletion.choose(c,v,PhoneCompletion.Operation.RETRY,"active");PhoneCompletion.require(c,v,choice);
        assertThrows(IllegalStateException.class,()->PhoneCompletion.require(c,view(QuestStore.Phase.FAILED,4,1,"NONE",false),choice));assertThrows(IllegalStateException.class,()->PhoneCompletion.require(c,view(QuestStore.Phase.FAILED,3,2,"NONE",false),choice));
        for(var phase:List.of(QuestStore.Phase.ACTIVE,QuestStore.Phase.REPORT_READY,QuestStore.Phase.COMPLETED,QuestStore.Phase.SKIPPED))assertThrows(IllegalArgumentException.class,()->PhoneCompletion.choose(c,view(phase,4,1,"NONE",false),PhoneCompletion.Operation.RETRY,"active"));
    }
    @Test void rewardNeedsActualCompletionPendingRecordAndCurrentDefinition(){
        var c=QuestNavigationTest.catalog();var ready=view(QuestStore.Phase.COMPLETED,3,1,"PENDING",false);assertNotNull(PhoneCompletion.choose(c,ready,PhoneCompletion.Operation.CLAIM,"active"));
        for(String state:List.of("NONE","UNREGISTERED","GRANTED","POLICY_PENDING","CONFIG_MISMATCH"))assertThrows(IllegalArgumentException.class,()->PhoneCompletion.choose(c,view(QuestStore.Phase.COMPLETED,3,1,state,false),PhoneCompletion.Operation.CLAIM,"active"));
        assertThrows(IllegalArgumentException.class,()->PhoneCompletion.choose(c,view(QuestStore.Phase.SKIPPED,3,1,"PENDING",false),PhoneCompletion.Operation.CLAIM,"active"));
    }
    @Test void chapterSkipRequiresExposedUnskippedChapterAndNeverRevealsUnacceptedTitles(){
        var c=QuestNavigationTest.catalog();var v=view(QuestStore.Phase.ACTIVE,0,1,"NONE",false);assertNotNull(PhoneCompletion.choose(c,v,PhoneCompletion.Operation.SKIP,"chapter"));
        assertThrows(IllegalArgumentException.class,()->PhoneCompletion.choose(c,v,PhoneCompletion.Operation.SKIP,"unknown"));assertThrows(IllegalArgumentException.class,()->PhoneCompletion.choose(c,view(QuestStore.Phase.ACTIVE,0,1,"NONE",true),PhoneCompletion.Operation.SKIP,"chapter"));
        var text=PhoneCompletion.chapterLines(v.completion().chapters().getFirst());assertTrue(text.toString().contains("2개"));assertFalse(text.toString().contains("unaccepted"));assertTrue(text.toString().contains("보상은 미정"));
    }
    @Test void blockedUnknownAndUnacceptedActionsStayUnavailableAndDoNotChangeState(){
        var c=QuestNavigationTest.catalog();var v=view(QuestStore.Phase.FAILED,3,1,"NONE",false);var before=v.toString();
        for(String id:List.of("unknown","unaccepted","../bad"))assertThrows(RuntimeException.class,()->PhoneCompletion.choose(c,v,PhoneCompletion.Operation.RETRY,id));
        var x=v.exploration();var blocked=new io.github.jesterportfolio.hearth.exploration.ExplorationView(x.registered(),x.regions(),x.places(),x.facts(),x.events(),x.members(),x.story(),x.conditions(),x.nextGoal(),Set.of("active"),x.branches());
        assertThrows(IllegalArgumentException.class,()->PhoneCompletion.choose(c,new QuestJournal.View(v.states(),v.journal(),blocked,v.completion()),PhoneCompletion.Operation.RETRY,"active"));assertEquals(before,v.toString());
    }
}
