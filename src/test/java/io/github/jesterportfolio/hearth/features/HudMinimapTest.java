package io.github.jesterportfolio.hearth.features;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class HudMinimapTest {
    @Test void frameIsBoundedRoundAndHasNoSourceMutation(){var pixels=new byte[16384];Arrays.fill(pixels,(byte)7);var image=HudMinimap.frame(pixels,List.of(),0,true,c->0x123456);assertEquals(16,image.size());assertTrue(image.stream().allMatch(row->row.size()==16));assertEquals(-1,image.getFirst().getFirst());assertEquals(0xffffff,image.get(8).get(8));assertEquals(7,pixels[0]);assertThrows(UnsupportedOperationException.class,()->image.get(8).clear());}
    @Test void unknownTerrainHasSameAppearanceAndDirectionCanBeHidden(){var a=HudMinimap.frame(new byte[16384],List.of(),90,false,c->{throw new AssertionError();});assertEquals(0x18202a,a.get(8).get(8));assertEquals(0x18202a,a.get(8).get(6));}
    @Test void approvedMarkersAreBoundedAndOutsideCircleStaysTransparent(){var frame=HudMinimap.frame(new byte[16384],List.of(new HudMinimap.Mark(16,16,0x42),new HudMinimap.Mark(-128,-128,0x55)),0,false,c->0);assertEquals(0x42,frame.get(9).get(9));assertEquals(-1,frame.getFirst().getFirst());}
    @Test void offsetEncodingIsBoundedAndBalanced(){assertEquals("",HudMinimap.shifts(0));assertEquals(2,HudMinimap.shifts(96).codePointCount(0,HudMinimap.shifts(96).length()));assertThrows(IllegalArgumentException.class,()->HudMinimap.shifts(513));assertThrows(IllegalArgumentException.class,()->HudMinimap.frame(new byte[1],List.of(),0,false,c->0));}
}
