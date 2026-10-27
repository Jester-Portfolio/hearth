package io.github.jesterportfolio.hearth.persistence;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** 개인 퀘스트 UI 데이터만 저장합니다. 월드/인벤토리 정본을 변경하지 않습니다. */
public interface QuestJournalStore {
    record Preferences(boolean hudEnabled,long revision) {}
    record Notice(String quest,long revision,String kind,Instant created,boolean read) {}
    record Journal(Set<String> tracked,Preferences preferences,List<Notice> notices) {
        public Journal {tracked=Set.copyOf(tracked);notices=List.copyOf(notices);}
    }
    CompletionStage<Journal> readJournal(ServerProgressAccess.Context context);
    CompletionStage<Void> track(ServerProgressAccess.Context context,String quest,QuestStore.Saved expected,boolean enabled);
    CompletionStage<Void> hud(ServerProgressAccess.Context context,Preferences expected,boolean enabled);
    CompletionStage<Void> readNotice(ServerProgressAccess.Context context,String quest,long revision);
    CompletionStage<Void> readAllNotices(ServerProgressAccess.Context context);
}
