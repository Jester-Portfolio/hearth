package io.github.jesterportfolio.hearth.crafting;

import io.github.jesterportfolio.hearth.inventory.*;
import org.bukkit.*;
import org.bukkit.inventory.*;
import java.util.*;

/** Paper 순정 레시피 결과/잔여물을 사용합니다. 이름·설명·PDC가 붙은 재료는 자동 소모하지 않습니다. */
public final class PaperCraftingPlan {
    private final PaperInventoryCodec codec;
    public PaperCraftingPlan(PaperInventoryCodec codec){this.codec=codec;}
    public static ItemStack[] matrix(CraftContent.Recipe recipe){var matrix=new ItemStack[9];for(int i=0;i<9;i++){Material m=Material.matchMaterial(recipe.matrix().get(i));if(m==null||m!=Material.AIR&&!m.isItem())throw new IllegalArgumentException("CRAFT_MATERIAL");if(m!=Material.AIR)matrix[i]=new ItemStack(m);}return matrix;}
    public ItemCraftResult result(World world,CraftContent.Recipe recipe){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("CRAFT_MAIN_THREAD");var matrix=matrix(recipe);var nativeRecipe=Bukkit.getCraftingRecipe(matrix,world);
        if(!(nativeRecipe instanceof Keyed keyed)||!keyed.getKey().toString().equals(recipe.key()))throw new IllegalStateException("CRAFT_RECIPE_MISMATCH");
        var result=Bukkit.craftItemResult(matrix,world);if(result.getResult().isEmpty())throw new IllegalStateException("CRAFT_NO_RESULT");return result;
    }
    public InventoryPayload plan(World world,CraftContent.Recipe recipe,InventoryPayload before){
        return plan(world,recipe,before,1);
    }
    public InventoryPayload plan(World world,CraftContent.Recipe recipe,InventoryPayload before,int times){
        CraftingBatch.require(times);
        var result=result(world,recipe);var slots=codec.decode(before);var requiredItems=matrix(recipe);
        var returns=new ArrayList<ItemStack>();returns.add(result.getResult());returns.addAll(Arrays.asList(result.getResultingMatrix()));returns.addAll(result.getOverflowItems());
        for(int batch=0;batch<times;batch++){
            for(var required:requiredItems){if(required==null)continue;boolean found=false;for(int i=0;i<36;i++)if(slots[i]!=null&&slots[i].isSimilar(required)&&slots[i].getAmount()>0){slots[i].subtract(1);if(slots[i].isEmpty())slots[i]=null;found=true;break;}if(!found)throw new IllegalStateException("CRAFT_MISSING_INGREDIENT");}
            for(var item:returns)if(item!=null&&!item.isEmpty())slots=codec.withCursor(slots,item);
        }
        return codec.encode(slots);
    }
    public List<CraftingMaterials.Need> materials(CraftContent.Recipe recipe,InventoryPayload inventory){
        return materials(recipe,inventory,1);
    }
    public List<CraftingMaterials.Need> materials(CraftContent.Recipe recipe,InventoryPayload inventory,int times){
        CraftingBatch.require(times);
        var slots=codec.decode(inventory);var available=new LinkedHashMap<String,Integer>();
        for(String type:recipe.matrix())if(!type.equals("AIR")&&!available.containsKey(type)){var plain=new ItemStack(Objects.requireNonNull(Material.matchMaterial(type)));int count=0;for(int i=0;i<36;i++)if(slots[i]!=null&&slots[i].isSimilar(plain))count+=slots[i].getAmount();available.put(type,count);}
        return CraftingMaterials.view(recipe,available,times);
    }
    /** 같은 현장에 등록된 순정 재료 제작법만 연결합니다. 부족분을 자동 제작하지 않습니다. */
    public Map<String,String> materialRecipes(World world,CraftContent content,CraftContent.Facility facility,String selected,List<CraftingMaterials.Need> needs){
        var links=new LinkedHashMap<String,String>();
        if(needs.stream().noneMatch(n->n.missing()>0))return Map.of();
        for(String id:facility.recipes())if(!id.equals(selected)){
            var output=result(world,content.recipes().get(id)).getResult();
            for(var need:needs)if(need.missing()>0&&!links.containsKey(need.material())&&output.isSimilar(new ItemStack(Objects.requireNonNull(Material.matchMaterial(need.material())))))links.put(need.material(),id);
        }
        return Map.copyOf(links);
    }
    public CraftingPreparation.Plan preparation(World world,CraftContent content,CraftContent.Facility facility,List<CraftingBatch.Selection> selected,InventoryPayload inventory){
        if(!selected.stream().allMatch(s->facility.recipes().contains(s.recipe())))throw new IllegalArgumentException("CRAFT_PREPARATION_RECIPE");
        var formulas=new ArrayList<CraftingPreparation.Formula>();var relevant=new HashSet<String>();
        for(String id:facility.recipes()){
            var recipe=content.recipes().get(id);var nativeResult=result(world,recipe);var output=nativeResult.getResult();
            if(!output.isSimilar(new ItemStack(output.getType())))throw new IllegalStateException("CRAFT_PREPARATION_RESULT");
            var ingredients=new LinkedHashMap<String,Integer>();for(var need:CraftingMaterials.view(recipe,Map.of()))ingredients.put(need.material(),need.required());
            var remainders=new LinkedHashMap<String,Integer>();var returned=new ArrayList<ItemStack>(Arrays.asList(nativeResult.getResultingMatrix()));returned.addAll(nativeResult.getOverflowItems());
            for(var item:returned)if(item!=null&&!item.isEmpty()){
                if(!item.isSimilar(new ItemStack(item.getType())))throw new IllegalStateException("CRAFT_PREPARATION_RESULT");
                remainders.merge(item.getType().name(),item.getAmount(),Math::addExact);
            }
            relevant.addAll(ingredients.keySet());formulas.add(new CraftingPreparation.Formula(id,recipe.title(),output.getType().name(),output.getAmount(),ingredients,remainders));
        }
        var owned=new LinkedHashMap<String,Integer>();var slots=codec.decode(inventory);
        for(int slot=0;slot<36;slot++){var item=slots[slot];if(item!=null&&!item.isEmpty()&&relevant.contains(item.getType().name())&&item.isSimilar(new ItemStack(item.getType())))owned.merge(item.getType().name(),item.getAmount(),Math::addExact);}
        return CraftingPreparation.plan(formulas,selected,owned);
    }
    public static List<String> ingredients(CraftContent.Recipe recipe){var counts=new LinkedHashMap<String,Integer>();recipe.matrix().stream().filter(m->!m.equals("AIR")).forEach(m->counts.merge(m,1,Integer::sum));return counts.entrySet().stream().map(e->e.getKey()+" × "+e.getValue()).toList();}
}
