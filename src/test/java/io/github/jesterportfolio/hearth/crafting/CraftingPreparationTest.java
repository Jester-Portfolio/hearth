package io.github.jesterportfolio.hearth.crafting;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CraftingPreparationTest {
    static CraftingPreparation.Formula formula(String id,String output,int amount,Map<String,Integer> input){return new CraftingPreparation.Formula(id,id,output,amount,input,Map.of());}
    static List<CraftingPreparation.Formula> tools(){
        var wood=new LinkedHashMap<String,Integer>();wood.put("OAK_PLANKS",3);wood.put("STICK",2);
        var stone=new LinkedHashMap<String,Integer>();stone.put("COBBLESTONE",3);stone.put("STICK",2);
        return List.of(formula("planks","OAK_PLANKS",4,Map.of("OAK_LOG",1)),formula("sticks","STICK",4,Map.of("OAK_PLANKS",2)),formula("wood","WOODEN_PICKAXE",1,wood),formula("stone","STONE_PICKAXE",1,stone));
    }
    static CraftingPreparation.Plan wood(Map<String,Integer> owned){return CraftingPreparation.plan(tools(),"wood",1,owned);}
    @Test void emptyInventoryIncludesIntermediateRecipesRawRoundingAndSurplus(){
        var p=wood(Map.of());assertEquals(CraftingPreparation.Status.NEEDS_MATERIALS,p.status());assertEquals(Map.of("OAK_LOG",2),p.additional());
        assertEquals(List.of("planks","planks","sticks","wood"),p.steps().stream().map(CraftingPreparation.Step::recipe).toList());assertEquals(Map.of("OAK_PLANKS",3,"STICK",2),p.surplus());assertTrue(p.ownedUsed().isEmpty());
    }
    @Test void ownedIntermediateMaterialsAvoidUnneededRawMaterials(){var p=wood(Map.of("OAK_PLANKS",3,"STICK",2));assertEquals(CraftingPreparation.Status.READY,p.status());assertEquals(List.of("wood"),p.steps().stream().map(CraftingPreparation.Step::recipe).toList());assertEquals(Map.of("OAK_PLANKS",3,"STICK",2),p.ownedUsed());assertTrue(p.additional().isEmpty());}
    @Test void combinedToolGoalsReuseSurplusAndNeverAllocateOwnedResourcesTwice(){
        var owned=new HashMap<>(Map.of("OAK_LOG",2,"COBBLESTONE",3,"DIAMOND",64));var original=Map.copyOf(owned);
        var p=CraftingPreparation.plan(tools(),List.of(new CraftingBatch.Selection("wood",1),new CraftingBatch.Selection("stone",1)),owned);
        assertEquals(CraftingPreparation.Status.READY,p.status());assertEquals(Map.of("OAK_LOG",2,"COBBLESTONE",3),p.ownedUsed());assertEquals(Map.of("OAK_PLANKS",3),p.surplus());assertTrue(p.additional().isEmpty());assertEquals(original,owned);assertFalse(p.toString().contains("DIAMOND"));
    }
    @Test void sameOwnedStackCannotSupplyTwoGoals(){var recipes=List.of(formula("a","STICK",1,Map.of("OAK_LOG",1)),formula("b","COBBLESTONE",1,Map.of("OAK_LOG",1)));var p=CraftingPreparation.plan(recipes,List.of(new CraftingBatch.Selection("a",1),new CraftingBatch.Selection("b",1)),Map.of("OAK_LOG",1));assertEquals(Map.of("OAK_LOG",1),p.additional());assertEquals(Map.of("OAK_LOG",1),p.ownedUsed());}
    @Test void requestedResultsAreKeptWhenAnotherGoalUsesSameMaterial(){var p=CraftingPreparation.plan(tools(),List.of(new CraftingBatch.Selection("planks",1),new CraftingBatch.Selection("sticks",1)),Map.of("OAK_LOG",1));assertEquals(Map.of("OAK_LOG",1),p.additional());assertEquals(Map.of("OAK_PLANKS",2),p.surplus());assertEquals(2,p.goals().size());}
    @Test void batchCountsMeanCraftsRatherThanResultItems(){var p=CraftingPreparation.plan(tools(),"wood",4,Map.of());assertEquals(4,p.goals().getFirst().produced());assertEquals(Map.of("OAK_LOG",4),p.additional());assertEquals(4,p.steps().getLast().times());assertTrue(p.surplus().isEmpty());}
    @Test void partlyOwnedRawAndIntermediateStacksAreCountedTogether(){var p=wood(Map.of("OAK_LOG",1,"OAK_PLANKS",1,"STICK",1));assertEquals(CraftingPreparation.Status.READY,p.status());assertEquals(Map.of("OAK_LOG",1,"OAK_PLANKS",1,"STICK",1),p.ownedUsed());assertEquals(Map.of("STICK",3),p.surplus());}
    @Test void remaindersCanSupplyLaterGoals(){var cake=new CraftingPreparation.Formula("cake","케이크","CAKE",1,Map.of("MILK_BUCKET",3),Map.of("BUCKET",3));var other=formula("other","STICK",1,Map.of("BUCKET",3));var p=CraftingPreparation.plan(List.of(cake,other),List.of(new CraftingBatch.Selection("cake",1),new CraftingBatch.Selection("other",1)),Map.of("MILK_BUCKET",3));assertEquals(CraftingPreparation.Status.READY,p.status());assertEquals(Map.of("MILK_BUCKET",3),p.ownedUsed());assertTrue(p.surplus().isEmpty());}
    @Test void cyclesAreUnavailableWithoutPublishingPartialPlan(){var recipes=List.of(formula("a","STICK",1,Map.of("OAK_PLANKS",1)),formula("b","OAK_PLANKS",1,Map.of("STICK",1)));var p=CraftingPreparation.plan(recipes,"a",1,Map.of());assertEquals(CraftingPreparation.Status.UNAVAILABLE,p.status());assertEquals(CraftingPreparation.Reason.CYCLE,p.reason());assertTrue(p.steps().isEmpty());assertTrue(p.additional().isEmpty());assertTrue(p.ownedUsed().isEmpty());var funded=CraftingPreparation.plan(recipes,"a",1,Map.of("OAK_PLANKS",1));assertEquals(CraftingPreparation.Status.READY,funded.status());}
    @Test void longDependencyChainsStopWithinBound(){var recipes=new ArrayList<CraftingPreparation.Formula>();for(int i=0;i<20;i++)recipes.add(formula("item_"+i,"ITEM_"+"A".repeat(i+1),1,Map.of("ITEM_"+"A".repeat(i+2),1)));var p=CraftingPreparation.plan(recipes,"item_0",8,Map.of());assertEquals(CraftingPreparation.Status.UNAVAILABLE,p.status());assertEquals(CraftingPreparation.Reason.LIMIT,p.reason());assertTrue(p.steps().isEmpty());}
    @Test void wideDependencyTreesCannotOverrunMenuStepSlots(){
        var recipes=new ArrayList<CraftingPreparation.Formula>();var input=new LinkedHashMap<String,Integer>();
        for(char branch='A';branch<='I';branch++){
            input.put("MATERIAL_"+branch+"_A",1);
            for(int level=1;level<=4;level++)recipes.add(formula("branch_"+Character.toLowerCase(branch)+"_"+level,"MATERIAL_"+branch+"_"+"A".repeat(level),1,Map.of(level==4?"RAW_"+branch:"MATERIAL_"+branch+"_"+"A".repeat(level+1),1)));
        }
        recipes.add(formula("goal","STICK",1,input));var plan=CraftingPreparation.plan(recipes,"goal",1,Map.of());assertEquals(CraftingPreparation.Status.UNAVAILABLE,plan.status());assertEquals(CraftingPreparation.Reason.LIMIT,plan.reason());assertTrue(plan.steps().isEmpty());
    }
    @Test void alternativeRecipesFollowFacilityOrder(){var a=formula("a","OAK_PLANKS",4,Map.of("OAK_LOG",1));var b=formula("b","OAK_PLANKS",4,Map.of("BIRCH_LOG",1));var tool=formula("tool","STICK",4,Map.of("OAK_PLANKS",2));assertEquals(Map.of("OAK_LOG",1),CraftingPreparation.plan(List.of(a,b,tool),"tool",1,Map.of()).additional());assertEquals(Map.of("BIRCH_LOG",1),CraftingPreparation.plan(List.of(b,a,tool),"tool",1,Map.of()).additional());}
    @Test void resultsAreImmutableAndInvalidInputsFail(){var p=wood(Map.of());assertThrows(UnsupportedOperationException.class,()->p.steps().clear());assertThrows(UnsupportedOperationException.class,()->p.additional().clear());assertThrows(IllegalArgumentException.class,()->CraftingPreparation.plan(tools(),"wood",2,Map.of()));assertThrows(IllegalArgumentException.class,()->wood(Map.of("OAK_LOG",-1)));assertThrows(IllegalArgumentException.class,()->CraftingPreparation.plan(tools(),"unknown",1,Map.of()));assertThrows(IllegalArgumentException.class,()->formula("bad","AIR",1,Map.of("OAK_LOG",1)));}
    @Test void materialBalanceHoldsAcrossMixedOwnedInventory(){
        var recipes=tools();var byId=new HashMap<String,CraftingPreparation.Formula>();recipes.forEach(f->byId.put(f.id(),f));
        for(int logs=0;logs<6;logs++)for(int planks=0;planks<6;planks++)for(int sticks=0;sticks<6;sticks++){
            var owned=Map.of("OAK_LOG",logs,"OAK_PLANKS",planks,"STICK",sticks);var p=wood(owned);assertNotEquals(CraftingPreparation.Status.UNAVAILABLE,p.status());var stock=new HashMap<>(owned);p.additional().forEach((m,n)->stock.merge(m,n,Integer::sum));
            for(var step:p.steps()){var f=byId.get(step.recipe());f.ingredients().forEach((m,n)->{stock.merge(m,-n*step.times(),Integer::sum);assertTrue(stock.get(m)>=0);});stock.merge(f.output(),step.produced(),Integer::sum);}
            p.goals().forEach(g->stock.merge(g.output(),-g.produced(),Integer::sum));
            for(var e:stock.entrySet())assertEquals(owned.getOrDefault(e.getKey(),0)-p.ownedUsed().getOrDefault(e.getKey(),0)+p.surplus().getOrDefault(e.getKey(),0),e.getValue());
            p.ownedUsed().forEach((m,n)->assertTrue(n<=owned.getOrDefault(m,0)));
        }
    }
}
