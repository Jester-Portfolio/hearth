package io.github.jesterportfolio.hearth.quest;
import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.session.PlayerSessions;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import java.util.concurrent.*;
import java.util.concurrent.Executor;

/** 일반 36슬롯만 보상에 사용하며 장비·보조 손·커서·월드 파일을 변경하지 않습니다. */
public final class QuestRewards {
    private java.util.function.Consumer<ServerProgressAccess.Context> changed=ctx->{};
    public void changed(java.util.function.Consumer<ServerProgressAccess.Context> listener){changed=listener;}
    private void signal(ServerProgressAccess.Context ctx){try{changed.accept(ctx);}catch(RuntimeException ignored){}}
    private final QuestRules rules;private final QuestCompletionStore store;private final PaperInventoryCodec codec;private final Executor owner;
    public QuestRewards(QuestRules rules,QuestCompletionStore store,PaperInventoryCodec codec,Executor owner){this.rules=rules;this.store=store;this.codec=codec;this.owner=owner;
        for(var rule:rules.rules().values())if(rule.reward()!=null)for(var item:rule.reward().items()){var material=Material.matchMaterial(item.material());if(material==null||!material.isItem()||material.isAir())throw new IllegalArgumentException("Invalid reward item material");}}
    public InventoryPayload add(InventoryPayload before,QuestRules.Reward reward){var slots=codec.decode(before);
        for(var spec:reward.items()){var sample=new ItemStack(Material.valueOf(spec.material()));int remaining=spec.amount();
            for(int i=0;i<36&&remaining>0;i++)if(slots[i]!=null&&slots[i].isSimilar(sample)){int amount=Math.min(remaining,Math.max(0,slots[i].getMaxStackSize()-slots[i].getAmount()));slots[i].setAmount(slots[i].getAmount()+amount);remaining-=amount;}
            for(int i=0;i<36&&remaining>0;i++)if(slots[i]==null||slots[i].isEmpty()){int amount=Math.min(remaining,sample.getMaxStackSize());slots[i]=sample.clone();slots[i].setAmount(amount);remaining-=amount;}
            if(remaining>0)throw new StorageFailure("reward","INVENTORY_FULL");
        }return codec.encode(slots);
    }
    public CompletionStage<Void> claim(PlayerSessions sessions,PlayerSessions.Session session,String quest){var reward=rules.rule(quest).reward();if(reward==null)return CompletableFuture.failedFuture(new StorageFailure("reward","REWARD_UNREGISTERED"));
        signal(session.context());return sessions.inventoryTransaction(session,before->store.rewardStatus(session.context(),quest).thenComposeAsync(status->{
            if(!status.equals("PENDING")&&!status.equals("GRANTED"))return CompletableFuture.failedFuture(new StorageFailure("reward","REWARD_NOT_ELIGIBLE"));
            var payload=status.equals("GRANTED")?before.payload():add(before.payload(),reward);return store.grantReward(session.context(),quest,reward.fingerprint(),before.revision(),payload);
        },owner)).whenCompleteAsync((v,error)->signal(session.context()),owner);
    }
}
