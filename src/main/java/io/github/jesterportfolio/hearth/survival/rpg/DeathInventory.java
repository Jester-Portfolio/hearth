package io.github.jesterportfolio.hearth.survival.rpg;
import java.util.*;
import org.bukkit.inventory.ItemStack;
/** 소유 41칸과 커서/개인 제작 입력을 한 번 분할한다. 결과 슬롯/공용 컨테이너는 소유품으로 취급하지 않는다. */
public final class DeathInventory {
 private DeathInventory(){}
 public static int[] inputs(org.bukkit.event.inventory.InventoryType type){return switch(type){case CRAFTING->new int[]{1,2,3,4};case WORKBENCH->new int[]{1,2,3,4,5,6,7,8,9};case ANVIL,GRINDSTONE,CARTOGRAPHY,MERCHANT,ENCHANTING->new int[]{0,1};case SMITHING,LOOM->new int[]{0,1,2};case STONECUTTER->new int[]{0};default->new int[0];};}
 public static ItemStack[] pack(ItemStack[] kept,int nativeSize){var result=Arrays.copyOf(kept,nativeSize);for(int i=0;i<result.length;i++)if(result[i]!=null)result[i]=result[i].clone();for(int i=nativeSize;i<kept.length;i++){var item=kept[i];if(item==null||item.isEmpty())continue;item=item.clone();for(int j=0;j<Math.min(36,nativeSize)&&!item.isEmpty();j++){var slot=result[j];if(slot!=null&&slot.isSimilar(item)){int moved=Math.min(item.getAmount(),Math.max(0,slot.getMaxStackSize()-slot.getAmount()));slot.setAmount(slot.getAmount()+moved);item.subtract(moved);}}for(int j=0;j<Math.min(36,nativeSize)&&!item.isEmpty();j++)if(result[j]==null||result[j].isEmpty()){int moved=Math.min(item.getAmount(),item.getMaxStackSize());result[j]=item.asQuantity(moved);item.subtract(moved);}if(!item.isEmpty())throw new IllegalStateException("DEATH_WORKSPACE_CAPACITY");}return result;}
}
