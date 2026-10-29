package io.github.jesterportfolio.hearth.persistence;

import java.util.concurrent.CompletionStage;

/** 개인 퀘스트 정본. NOT_ACCEPTED는 유효한 개인 행에 퀘스트 행이 아직 없는 경우만 뜻합니다. */
public interface QuestStore {
    enum Phase { NOT_ACCEPTED, ACTIVE, REPORT_READY, COMPLETED, FAILED, SKIPPED }
    record Saved(Phase phase,long revision,int objectiveIndex,long deadlineMillis) {
        public Saved(Phase phase,long revision,int objectiveIndex){this(phase,revision,objectiveIndex,0);}
        public boolean satisfies(){return phase==Phase.COMPLETED||phase==Phase.SKIPPED;}
        public Saved(Phase phase,long revision){this(phase,revision,0);}
        public Saved {
            if (deadlineMillis<0 || phase!=Phase.ACTIVE&&deadlineMillis!=0 || objectiveIndex<0 || phase==null || phase==Phase.NOT_ACCEPTED && objectiveIndex!=0 || (phase==Phase.NOT_ACCEPTED ? revision!=-1 : revision<0)) throw new IllegalArgumentException("Invalid quest row");
        }
    }
    CompletionStage<Saved> readQuest(ServerProgressAccess.Context context,String quest);
    CompletionStage<Saved> advanceQuest(ServerProgressAccess.Context context,String quest,Saved expected,Phase next);
    default CompletionStage<Saved> advanceQuest(ServerProgressAccess.Context context,String quest,Saved expected,Phase next,int objectiveIndex){
        if(objectiveIndex==expected.objectiveIndex())return advanceQuest(context,quest,expected,next);
        return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Objective storage missing"));
    }
    default CompletionStage<Saved> advanceQuest(ServerProgressAccess.Context ctx,String quest,Saved expected,Phase next,int index,long deadline){
        if(deadline==0&&expected.deadlineMillis()==0)return advanceQuest(ctx,quest,expected,next,index);
        return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Quest deadline storage missing"));
    }
    static boolean follows(Phase before,Phase after){return before==Phase.NOT_ACCEPTED&&after==Phase.ACTIVE||before==Phase.ACTIVE&&(after==Phase.REPORT_READY||after==Phase.COMPLETED)||before==Phase.REPORT_READY&&after==Phase.COMPLETED;}
}
