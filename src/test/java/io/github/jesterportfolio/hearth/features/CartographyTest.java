package io.github.jesterportfolio.hearth.features;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class CartographyTest {
    static final List<Cartography.Zone> ALL=List.of(new Cartography.Zone("known",-1000,1000,-1000,1000));
    @Test void noDiscoveryNeverReadsTerrainOrChunks(){var pixels=Cartography.render(0,0,1,false,List.of(),new Cartography.Terrain(){public boolean loaded(int x,int z){throw new AssertionError("hidden chunk read");}public byte color(int x,int z){throw new AssertionError("hidden terrain read");}});assertEquals(16384,pixels.length);for(byte b:pixels)assertEquals(0,b);}
    @Test void unloadedColumnsRemainUnknownAndNeverGenerateChunks(){int[] reads={0};Cartography.render(0,0,1,false,ALL,new Cartography.Terrain(){public boolean loaded(int x,int z){return false;}public byte color(int x,int z){reads[0]++;return 1;}});assertEquals(0,reads[0]);}
    @Test void onlyOwnDiscoveredBoundsAreSampled(){var zones=List.of(new Cartography.Zone("personal",0,12,0,12));int[] reads={0};Cartography.render(0,0,1,false,zones,new Cartography.Terrain(){public boolean loaded(int x,int z){assertTrue(zones.getFirst().contains(x,z));return true;}public byte color(int x,int z){reads[0]++;return 7;}});assertTrue(reads[0]>0&&reads[0]<=16);}
    @Test void sampleBudgetAndRoundMaskAreBounded(){int[] count={0};var terrain=new Cartography.Terrain(){public boolean loaded(int x,int z){return true;}public byte color(int x,int z){count[0]++;return 7;}};var square=Cartography.render(0,0,1,false,ALL,terrain);assertEquals(1024,count[0]);count[0]=0;var round=Cartography.render(0,0,1,true,ALL,terrain);assertTrue(count[0]<1024);assertEquals(0,round[0]);assertEquals(7,square[0]);assertEquals(7,round[64*128+64]);}
    @Test void renderArraysAndProgressViewsDoNotShareMutablePixels(){var terrain=new Cartography.Terrain(){public boolean loaded(int x,int z){return true;}public byte color(int x,int z){return 9;}};var a=Cartography.render(0,0,1,false,ALL,terrain);a[0]=1;assertEquals(9,Cartography.render(0,0,1,false,ALL,terrain)[0]);}
    @Test void invalidOrUnboundedLayoutsAreRejected(){assertThrows(IllegalArgumentException.class,()->new Cartography.Zone("x",10,0,0,1));var terrain=new Cartography.Terrain(){public boolean loaded(int x,int z){return true;}public byte color(int x,int z){return 1;}};assertThrows(IllegalArgumentException.class,()->Cartography.render(0,0,0,false,ALL,terrain));assertThrows(IllegalArgumentException.class,()->Cartography.render(0,0,1,false,Collections.nCopies(257,ALL.getFirst()),terrain));}
}
