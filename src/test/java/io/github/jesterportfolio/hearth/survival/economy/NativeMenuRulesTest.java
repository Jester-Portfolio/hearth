package io.github.jesterportfolio.hearth.survival.economy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class NativeMenuRulesTest{
 @Test void shop_left_click_and_explicit_peer_edit_inputs_only(){assertTrue(NativeMenuRules.allow(true,false,true,true,"LEFT",false));for(String click:new String[]{"RIGHT","SHIFT_LEFT","SHIFT_RIGHT"}){assertFalse(NativeMenuRules.allow(true,false,true,true,click,false));assertTrue(NativeMenuRules.allow(true,false,true,true,click,true));}for(String click:new String[]{"NUMBER_KEY","DOUBLE_CLICK","SWAP_OFFHAND","MIDDLE","DROP","CONTROL_DROP","UNKNOWN"})for(boolean peer:new boolean[]{true,false})assertFalse(NativeMenuRules.allow(true,false,true,true,click,peer));}
 @Test void cancelled_stale_bottom_and_cursor_items_can_never_activate_actions(){for(boolean peer:new boolean[]{true,false}){assertFalse(NativeMenuRules.allow(false,false,true,true,"LEFT",peer));assertFalse(NativeMenuRules.allow(true,true,true,true,"LEFT",peer));assertFalse(NativeMenuRules.allow(true,false,false,true,"LEFT",peer));assertFalse(NativeMenuRules.allow(true,false,true,false,"LEFT",peer));}}
}
