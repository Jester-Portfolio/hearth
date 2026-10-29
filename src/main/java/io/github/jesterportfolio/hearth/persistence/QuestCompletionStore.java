package io.github.jesterportfolio.hearth.persistence;
import io.github.jesterportfolio.hearth.inventory.InventoryPayload;
import io.github.jesterportfolio.hearth.quest.QuestRules;
import java.util.*;
import java.util.concurrent.CompletionStage;
public interface QuestCompletionStore {
    record RewardRecord(String status,String fingerprint){public RewardRecord{if(!Set.of("PENDING","GRANTED","POLICY_PENDING").contains(status)||fingerprint==null||fingerprint.isBlank())throw new IllegalArgumentException("Invalid reward record");}}
    default CompletionStage<Map<String,RewardRecord>> readRewards(ServerProgressAccess.Context ctx){return java.util.concurrent.CompletableFuture.completedFuture(Map.of());}
    record ChapterState(String id,String fingerprint,long revision){}
    CompletionStage<String> rewardStatus(ServerProgressAccess.Context ctx,String quest);
    CompletionStage<InventoryStore.Stored> grantReward(ServerProgressAccess.Context ctx,String quest,String fingerprint,long inventoryRevision,InventoryPayload payload);
    CompletionStage<Map<String,ChapterState>> readChapters(ServerProgressAccess.Context ctx);
    CompletionStage<ChapterState> skipChapter(ServerProgressAccess.Context ctx,QuestRules.Chapter chapter);
}
