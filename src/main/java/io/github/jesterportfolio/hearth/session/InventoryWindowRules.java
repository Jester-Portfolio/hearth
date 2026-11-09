package io.github.jesterportfolio.hearth.session;

import java.util.*;

/** 순정 제작/월드 보관 화면의 동작 범위. 실제 소유권·WorldGuard 검사는 어댑터가 매번 확인합니다. */
public final class InventoryWindowRules {
    private InventoryWindowRules(){}
    public enum Kind{CRAFTING,WORKBENCH,CONTAINER,OTHER}
    public enum Slot{STORAGE,EQUIPMENT,INPUT,CONTAINER,RESULT,OUTSIDE}
    public record Request(boolean active,boolean authorized,boolean creative,Kind kind,Slot slot,String click,String action,int hotbar,boolean cursorEmpty){}
    public static boolean inventory(Request r){
        if(!r.active||!r.authorized||r.creative||r.kind==Kind.OTHER||r.slot==Slot.OUTSIDE)return false;
        if(r.slot==Slot.RESULT)return r.kind!=Kind.CONTAINER&&Set.of("LEFT","RIGHT").contains(r.click)&&r.action.equals("PICKUP_ALL");
        if(r.slot==Slot.INPUT&&r.kind==Kind.CONTAINER||r.slot==Slot.CONTAINER&&r.kind!=Kind.CONTAINER)return false;
        if(Set.of("LEFT","RIGHT").contains(r.click))return r.slot!=Slot.EQUIPMENT&&Set.of("PICKUP_ALL","PICKUP_SOME","PICKUP_HALF","PICKUP_ONE","PLACE_ALL","PLACE_SOME","PLACE_ONE","SWAP_WITH_CURSOR","NOTHING").contains(r.action);
        if(!r.cursorEmpty)return false;
        return switch(r.click){case "SHIFT_LEFT","SHIFT_RIGHT"->r.action.equals("MOVE_TO_OTHER_INVENTORY");case "NUMBER_KEY"->r.hotbar>=0&&r.hotbar<9&&Set.of("HOTBAR_SWAP","HOTBAR_MOVE_AND_READD").contains(r.action);case "SWAP_OFFHAND"->r.action.equals("HOTBAR_SWAP");case "DROP"->r.action.equals("DROP_ONE_SLOT");case "CONTROL_DROP"->r.action.equals("DROP_ALL_SLOT");default->false;};
    }
    public static boolean drag(boolean active,boolean authorized,boolean creative,Kind kind,Collection<Slot> slots){return active&&authorized&&!creative&&kind!=Kind.OTHER&&!slots.isEmpty()&&slots.stream().allMatch(s->s==Slot.STORAGE||s==Slot.INPUT&&kind!=Kind.CONTAINER||s==Slot.CONTAINER&&kind==Kind.CONTAINER);}
    public static boolean container(String material){return Set.of("CHEST","TRAPPED_CHEST","BARREL","FURNACE","BLAST_FURNACE","SMOKER","HOPPER","DROPPER","DISPENSER","BREWING_STAND","SHULKER_BOX").contains(material)||material.endsWith("_SHULKER_BOX");}
}
