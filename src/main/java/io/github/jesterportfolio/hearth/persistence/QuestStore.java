package io.github.jesterportfolio.hearth.persistence;

import java.util.concurrent.CompletionStage;

/** 개인 퀘스트 정본. NOT_ACCEPTED는 유효한 개인 행에 퀘스트 행이 아직 없는 경우만 뜻합니다. */
public interface QuestStore {
    enum Phase { NOT_ACCEPTED, ACTIVE, REPORT_READY, COMPLETED }
    record Saved(Phase phase,long revision) {
        public Saved {
            if (phase==null || (phase==Phase.NOT_ACCEPTED ? revision!=-1 : revision<0)) throw new IllegalArgumentException("Invalid quest row");
        }
    }
    CompletionStage<Saved> readQuest(ServerProgressAccess.Context context,String quest);
    CompletionStage<Saved> advanceQuest(ServerProgressAccess.Context context,String quest,Saved expected,Phase next);
    static boolean follows(Phase before,Phase after) {
        return before!=Phase.COMPLETED && after.ordinal()==before.ordinal()+1;
    }
}
