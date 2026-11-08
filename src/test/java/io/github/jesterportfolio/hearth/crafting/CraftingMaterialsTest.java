package io.github.jesterportfolio.hearth.crafting;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CraftingMaterialsTest {
    static CraftContent.Recipe recipe(){return new CraftContent.Recipe("stone_pickaxe","돌 곡괭이","minecraft:stone_pickaxe",List.of("COBBLESTONE","COBBLESTONE","COBBLESTONE","AIR","STICK","AIR","AIR","STICK","AIR"));}
    @Test void repeatedIngredientsAreAggregatedAndShortageNeverNegative(){var rows=CraftingMaterials.view(recipe(),Map.of("COBBLESTONE",2,"STICK",8));assertEquals(2,rows.size());assertEquals(new CraftingMaterials.Need("COBBLESTONE",3,2),rows.getFirst());assertEquals(1,rows.getFirst().missing());assertEquals(0,rows.get(1).missing());}
    @Test void emptyStorageShowsEveryRequiredMaterialAndUnrelatedCountsAreNotDisclosed(){var rows=CraftingMaterials.view(recipe(),Map.of("DIAMOND",64));assertEquals(List.of("COBBLESTONE","STICK"),rows.stream().map(CraftingMaterials.Need::material).toList());assertTrue(rows.stream().allMatch(n->n.available()==0));assertThrows(UnsupportedOperationException.class,()->rows.clear());}
    @Test void countLinesDistinguishOwnedAndMissingWithoutChangingInput(){var counts=new HashMap<>(Map.of("COBBLESTONE",3,"STICK",1));var before=Map.copyOf(counts);var rows=CraftingMaterials.view(recipe(),counts);assertEquals(before,counts);assertTrue(rows.getFirst().lines().contains("재료 준비 완료"));assertTrue(rows.get(1).lines().contains("부족 수량: 1"));}
    @Test void invalidCountsAreRejected(){assertThrows(IllegalArgumentException.class,()->new CraftingMaterials.Need("STONE",0,1));assertThrows(IllegalArgumentException.class,()->new CraftingMaterials.Need("STONE",1,-1));}
}
