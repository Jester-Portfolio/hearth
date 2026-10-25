package io.github.jesterportfolio.hearth.persistence;

import io.github.jesterportfolio.hearth.inventory.InventoryPayload;
import java.util.concurrent.CompletionStage;

public interface InventoryStore {
    record Stored(InventoryPayload payload,long revision) {
        public Stored { if (payload==null || revision<0) throw new IllegalArgumentException("Invalid inventory state"); }
    }
    CompletionStage<Stored> createInventory(ServerProgressAccess.Context context,InventoryPayload initial);
    CompletionStage<Stored> readInventory(ServerProgressAccess.Context context);
    CompletionStage<Stored> saveInventory(ServerProgressAccess.Context context,long revision,InventoryPayload payload);
}
