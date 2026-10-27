package io.github.jesterportfolio.hearth.persistence;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** 개인 퀘스트 UI 데이터만 저장합니다. 월드/인벤토리 정본을 변경하지 않습니다. */
public interface QuestJournalStore {
    record Preferences(boolean hudEnabled,long revision,boolean minimapEnabled,boolean questMarker,boolean npcMarker,boolean playerMarker,boolean placeMarker) {
        public Preferences(boolean enabled,long revision){this(enabled,revision,true,true,true,true,true);}
        public boolean flag(String key){return switch(key){case "hud"->hudEnabled;case "minimap"->minimapEnabled;case "quest_marker"->questMarker;case "npc_marker"->npcMarker;case "player_marker"->playerMarker;case "place_marker"->placeMarker;default->throw new IllegalArgumentException("Unknown setting");};}
    }
    record Notice(String quest,long revision,String kind,Instant created,boolean read,String type) {
        public Notice(String quest,long revision,String kind,Instant created,boolean read){this(quest,revision,kind,created,read,"QUEST");}
    }
    record Journal(Set<String> tracked,Preferences preferences,List<Notice> notices) {
        public Journal {tracked=Set.copyOf(tracked);notices=List.copyOf(notices);}
    }
    CompletionStage<Journal> readJournal(ServerProgressAccess.Context context);
    CompletionStage<Void> track(ServerProgressAccess.Context context,String quest,QuestStore.Saved expected,boolean enabled);
    CompletionStage<Void> hud(ServerProgressAccess.Context context,Preferences expected,boolean enabled);
    default CompletionStage<Void> setting(ServerProgressAccess.Context ctx,Preferences expected,String key,boolean enabled){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Settings storage missing"));}
    default CompletionStage<Void> readPlaceNotice(ServerProgressAccess.Context ctx,String place){return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Place notice storage missing"));}
    CompletionStage<Void> readNotice(ServerProgressAccess.Context context,String quest,long revision);
    CompletionStage<Void> readAllNotices(ServerProgressAccess.Context context);
}
