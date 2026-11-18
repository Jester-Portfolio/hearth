package io.github.jesterportfolio.hearth.network;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
final class NetworkMenuRulesTest {
 @Test void only_current_authorized_left_button_activates(){assertTrue(NetworkMenuRules.activate(true,false,4,"LEFT"));}
 @Test void prior_denial_or_stale_screen_is_preserved(){assertFalse(NetworkMenuRules.activate(true,true,4,"LEFT"));assertFalse(NetworkMenuRules.activate(false,false,4,"LEFT"));}
 @Test void inventory_movement_hotkeys_and_repeated_click_types_are_not_actions(){for(String click:List.of("RIGHT","SHIFT_LEFT","NUMBER_KEY","DOUBLE_CLICK","SWAP_OFFHAND","DROP","MIDDLE"))assertFalse(NetworkMenuRules.activate(true,false,4,click));}
 @Test void bottom_inventory_and_other_slots_do_not_select_server(){for(int slot:List.of(-1,0,3,5,8,9,40))assertFalse(NetworkMenuRules.activate(true,false,slot,"LEFT"));}
}
