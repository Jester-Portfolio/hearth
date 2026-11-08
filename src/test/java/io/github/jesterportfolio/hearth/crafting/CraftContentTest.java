package io.github.jesterportfolio.hearth.crafting;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class CraftContentTest {
    static Map<String,Object> recipe(){return Map.of("id","planks","title","판자","recipe-key","minecraft:oak_planks","matrix",List.of("OAK_LOG","AIR","AIR","AIR","AIR","AIR","AIR","AIR","AIR"));}
    static Map<String,Object> facility(){return Map.of("id","workbench","x",6,"y",64,"z",6,"recipes",List.of("planks"));}
    static Map<String,Object> root(){return new HashMap<>(Map.of("format",1,"isolated-prototype",true,"recipes",List.of(recipe()),"facilities",List.of(facility())));}
    @Test void plainRecipeAndFacilityLinksAreImmutable(){var c=CraftContent.parse(root());assertEquals("minecraft:oak_planks",c.recipes().get("planks").key());assertThrows(UnsupportedOperationException.class,()->c.facilities().clear());}
    @Test void duplicateOrMissingReferencesAreRejected(){var r=root();r.put("recipes",List.of(recipe(),recipe()));assertThrows(IllegalArgumentException.class,()->CraftContent.parse(r));var bad=root();var f=new HashMap<>(facility());f.put("recipes",List.of("missing"));bad.put("facilities",List.of(f));assertThrows(IllegalArgumentException.class,()->CraftContent.parse(bad));}
    @Test void malformedMatrixAndFractionalPositionsAreRejected(){var r=root();var recipe=new HashMap<>(recipe());recipe.put("matrix",List.of("OAK_LOG"));r.put("recipes",List.of(recipe));assertThrows(IllegalArgumentException.class,()->CraftContent.parse(r));var f=new HashMap<>(facility());f.put("x",6.5);var p=root();p.put("facilities",List.of(f));assertThrows(IllegalArgumentException.class,()->CraftContent.parse(p));}
    @Test void explicitPrototypeGateAndVanillaRecipeNamespaceRequired(){var r=root();r.put("isolated-prototype",false);assertThrows(IllegalArgumentException.class,()->CraftContent.parse(r));assertThrows(IllegalArgumentException.class,()->new CraftContent.Recipe("planks","판자","unknown:key",Collections.nCopies(9,"OAK_LOG")));}
}
