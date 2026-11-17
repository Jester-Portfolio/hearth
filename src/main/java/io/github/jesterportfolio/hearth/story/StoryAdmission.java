package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.inventory.InventoryPayload;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 인증된 계정의 서버 연결 설정만 받습니다. 일반 인벤토리 자동 가져오기를 하지 않습니다. */
public final class StoryAdmission {
    @FunctionalInterface public interface Accounts{CompletionStage<InventoryStore.Stored> admit(ServerProgressAccess.Context context,StoryBinding binding,boolean create,InventoryPayload empty);}
    private final Map<UUID,StoryBinding> members;private final InventoryPayload empty;private final Accounts accounts;private final Executor owner;
    public StoryAdmission(Map<UUID,StoryBinding> members,InventoryPayload empty,Accounts accounts,Executor owner){this.members=Map.copyOf(members);this.empty=Objects.requireNonNull(empty);this.accounts=Objects.requireNonNull(accounts);this.owner=Objects.requireNonNull(owner);if(Arrays.stream(empty.slots()).anyMatch(Objects::nonNull))throw new IllegalArgumentException("STORY_EMPTY_REQUIRED");}
    public CompletionStage<InventoryStore.Stored> open(ServerProgressAccess.Context context,String sourceOwner,boolean sourceEmpty,BooleanSupplier current){
        try{
            var binding=members.get(context.playerId());
            if(binding==null||!binding.run().equals(context.storyRunId()))throw new IllegalStateException("STORY_MEMBER_UNCONFIGURED");
            if(sourceOwner!=null&&!sourceOwner.equals(binding.run().toString()))throw new IllegalStateException("STORY_INVENTORY_OWNER_CONFLICT");
            if(sourceOwner==null&&!sourceEmpty)throw new IllegalStateException("STORY_UNMANAGED_INVENTORY_PRESERVED");
            if(!current.getAsBoolean())throw new IllegalStateException("STORY_ADMISSION_STALE");
            return accounts.admit(context,binding,sourceEmpty,empty).thenApplyAsync(saved->{if(!current.getAsBoolean())throw new IllegalStateException("STORY_ADMISSION_STALE");return Objects.requireNonNull(saved);},owner);
        }catch(RuntimeException failure){return CompletableFuture.failedFuture(failure);}
    }
}
