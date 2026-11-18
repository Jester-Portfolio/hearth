package io.github.jesterportfolio.hearth.survival;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
final class SurvivalWorldTest {
 @Test void residence_wilderness_and_nether_have_distinct_fixed_buttons(){assertEquals(Set.of("residence","wilderness","nether"),Arrays.stream(SurvivalWorld.values()).map(SurvivalWorld::id).collect(java.util.stream.Collectors.toSet()));assertEquals(3,Arrays.stream(SurvivalWorld.values()).map(SurvivalWorld::slot).distinct().count());for(var w:SurvivalWorld.values()){assertSame(w,SurvivalWorld.id(w.id()));assertSame(w,SurvivalWorld.at(w.slot()).orElseThrow());}}
 @Test void a_current_left_click_on_the_top_button_selects_only_registered_worlds(){assertEquals(Optional.of(SurvivalWorld.WILDERNESS),SurvivalWorld.select(true,false,true,13,"LEFT"));assertTrue(SurvivalWorld.select(true,false,true,12,"LEFT").isEmpty());assertThrows(IllegalArgumentException.class,()->SurvivalWorld.id("127.0.0.1:25565"));}
 @Test void stale_cancelled_bottom_inventory_and_hotkey_events_do_not_select(){assertTrue(SurvivalWorld.select(false,false,true,11,"LEFT").isEmpty());assertTrue(SurvivalWorld.select(true,true,true,11,"LEFT").isEmpty());assertTrue(SurvivalWorld.select(true,false,false,11,"LEFT").isEmpty());for(String click:List.of("SHIFT_LEFT","RIGHT","NUMBER_KEY","DOUBLE_CLICK","SWAP_OFFHAND","MIDDLE"))assertTrue(SurvivalWorld.select(true,false,true,11,click).isEmpty());}
}
