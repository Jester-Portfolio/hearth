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
        var result=result(world,recipe);var slots=codec.decode(before);
        for(var required:matrix(recipe)){if(required==null)continue;boolean found=false;for(int i=0;i<36;i++)if(slots[i]!=null&&slots[i].isSimilar(required)&&slots[i].getAmount()>0){slots[i].subtract(1);if(slots[i].isEmpty())slots[i]=null;found=true;break;}if(!found)throw new IllegalStateException("CRAFT_MISSING_INGREDIENT");}
        var returns=new ArrayList<ItemStack>();returns.add(result.getResult());returns.addAll(Arrays.asList(result.getResultingMatrix()));returns.addAll(result.getOverflowItems());
        for(var item:returns)if(item!=null&&!item.isEmpty())slots=codec.withCursor(slots,item);
        return codec.encode(slots);
    }
    public static List<String> ingredients(CraftContent.Recipe recipe){var counts=new LinkedHashMap<String,Integer>();recipe.matrix().stream().filter(m->!m.equals("AIR")).forEach(m->counts.merge(m,1,Integer::sum));return counts.entrySet().stream().map(e->e.getKey()+" × "+e.getValue()).toList();}
}
