package io.github.jesterportfolio.hearth.economy;

import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.persistence.InventoryStore;

/** 확인과 정리를 분리합니다. 현재 DB 값을 재지급·차감하거나 과거 payload로 덮지 않습니다. */
public final class EconomyRecovery {
    private EconomyRecovery(){}
    public enum Decision { COMMITTED_RELOAD, NOT_COMMITTED_RELOAD, REVIEW_REQUIRED }
    public static Decision decide(EconomyStore.Request request,InventoryStore.Stored before,InventoryPayload after,EconomyStore.Inspection found){
        var current=found.account().inventory();
        if(found.entry().isPresent()){
            var row=found.entry().get();
            if(!row.id().equals(request.id())||!row.key().equals(request.key())||row.quantity()!=request.quantity()||row.buy()!=request.buy()||row.inventoryRevision()!=before.revision()+1)return Decision.REVIEW_REQUIRED;
            return current.revision()==row.inventoryRevision()&&current.payload().equals(after)?Decision.COMMITTED_RELOAD:Decision.REVIEW_REQUIRED;
        }
        return current.equals(before)?Decision.NOT_COMMITTED_RELOAD:Decision.REVIEW_REQUIRED;
    }
}
