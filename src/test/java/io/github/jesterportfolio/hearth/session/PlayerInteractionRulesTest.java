package io.github.jesterportfolio.hearth.session;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PlayerInteractionRulesTest {
    static PlayerInteractionRules.InventoryRequest request(String click,String action){return new PlayerInteractionRules.InventoryRequest(true,true,true,true,9,click,action,0);}
    @Test void cursorlessHotbarEquipmentAndDropsAreSupported(){
        for(String click:new String[]{"SHIFT_LEFT","SHIFT_RIGHT"})assertTrue(PlayerInteractionRules.inventory(request(click,"MOVE_TO_OTHER_INVENTORY")));
        for(String action:new String[]{"HOTBAR_SWAP","HOTBAR_MOVE_AND_READD"})assertTrue(PlayerInteractionRules.inventory(request("NUMBER_KEY",action)));
        assertTrue(PlayerInteractionRules.inventory(request("SWAP_OFFHAND","HOTBAR_SWAP")));
        assertTrue(PlayerInteractionRules.inventory(request("DROP","DROP_ONE_SLOT")));
        assertTrue(PlayerInteractionRules.inventory(request("CONTROL_DROP","DROP_ALL_SLOT")));
    }
    @Test void preparationHoldsOtherInventoriesCursorAndUnsupportedSlotsAreDenied(){
        for(int invalid=0;invalid<4;invalid++)assertFalse(PlayerInteractionRules.inventory(new PlayerInteractionRules.InventoryRequest(invalid!=0,invalid!=1,invalid!=2,invalid!=3,9,"NUMBER_KEY","HOTBAR_SWAP",0)));
        for(int slot:new int[]{-999,-1,41,45})assertFalse(PlayerInteractionRules.inventory(new PlayerInteractionRules.InventoryRequest(true,true,true,true,slot,"NUMBER_KEY","HOTBAR_SWAP",0)));
        for(int slot:new int[]{0,35,36,39,40})assertTrue(PlayerInteractionRules.inventory(new PlayerInteractionRules.InventoryRequest(true,true,true,true,slot,"NUMBER_KEY","HOTBAR_SWAP",8)));
        for(int key:new int[]{-1,9,40})assertFalse(PlayerInteractionRules.inventory(new PlayerInteractionRules.InventoryRequest(true,true,true,true,9,"NUMBER_KEY","HOTBAR_SWAP",key)));
    }
    @Test void craftingCursorCollectCreativeAndMismatchedActionsRemainDenied(){
        for(String click:new String[]{"LEFT","RIGHT","MIDDLE","DOUBLE_CLICK","CREATIVE","UNKNOWN","WINDOW_BORDER_LEFT"})assertFalse(PlayerInteractionRules.inventory(request(click,"PICKUP_ALL")));
        for(String click:new String[]{"SHIFT_LEFT","NUMBER_KEY","SWAP_OFFHAND","DROP","CONTROL_DROP"})assertFalse(PlayerInteractionRules.inventory(request(click,"COLLECT_TO_CURSOR")));
    }
    @Test void onlyNonContainerFacilitiesAreAllowedAndHelpStatesLimits(){
        for(String block:new String[]{"OAK_DOOR","IRON_DOOR","OAK_TRAPDOOR","OAK_FENCE_GATE","STONE_BUTTON","LEVER"})assertTrue(PlayerInteractionRules.facility(block));
        for(String block:new String[]{"CHEST","BARREL","ENDER_CHEST","FURNACE","CRAFTING_TABLE","LECTERN","CHISELED_BOOKSHELF","OAK_SIGN","DISPENSER","STONE"})assertFalse(PlayerInteractionRules.facility(block));
        assertTrue(PlayerInteractionRules.help().stream().anyMatch(x->x.contains("아직 지원하지 않습니다")));
    }
}
