package io.github.jesterportfolio.hearth.inventory;

import io.github.jesterportfolio.hearth.session.InventoryWindowRules;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

/** 순정 동작을 실행하기 전 반환 공간만 계산합니다. 실제 아이템 이동/제작은 Minecraft가 처리합니다. */
public final class PaperInventoryWindow {
    private PaperInventoryWindow(){}
    public static InventoryWindowRules.Slot slot(InventoryView view,Inventory inventory,int slot){
        if(inventory==null)return InventoryWindowRules.Slot.OUTSIDE;
        if(inventory.equals(view.getBottomInventory()))return slot>=0&&slot<36?InventoryWindowRules.Slot.STORAGE:slot>=36&&slot<=40?InventoryWindowRules.Slot.EQUIPMENT:InventoryWindowRules.Slot.OUTSIDE;
        if(!inventory.equals(view.getTopInventory())||slot<0||slot>=inventory.getSize())return InventoryWindowRules.Slot.OUTSIDE;
        if(inventory instanceof CraftingInventory)return slot==0?InventoryWindowRules.Slot.RESULT:InventoryWindowRules.Slot.INPUT;
        return InventoryWindowRules.Slot.CONTAINER;
    }
    public static ItemStack[] slots(PlayerInventory inventory){var storage=inventory.getStorageContents();var armor=inventory.getArmorContents();if(storage.length!=36||armor.length!=4)throw new IllegalArgumentException("SLOT_LAYOUT");var result=Arrays.copyOf(storage,41);System.arraycopy(armor,0,result,36,4);result[40]=inventory.getItemInOffHand();return result;}
    public static CraftingInventory crafting(Inventory inventory){return inventory instanceof CraftingInventory c?c:null;}
    public static ItemStack[] matrix(Inventory inventory){var c=crafting(inventory);return c==null?new ItemStack[0]:c.getMatrix();}
    private static ItemStack copy(ItemStack item,int amount){if(item==null||item.isEmpty()||amount<=0)return null;var value=item.clone();value.setAmount(amount);return value;}
    private static int amount(ItemStack item){return item==null||item.isEmpty()?0:item.getAmount();}
    /** Paper의 3x3 계산 API에 개인 2x2 좌표를 명시적으로 매핑합니다. */
    public static ItemCraftResult recipe(ItemStack[] matrix,World world){
        if(matrix.length!=4&&matrix.length!=9)throw new IllegalArgumentException("CRAFT_MATRIX_LAYOUT");var padded=new ItemStack[9];
        for(int i=0;i<matrix.length;i++)padded[matrix.length==4?(i/2)*3+i%2:i]=matrix[i];var result=Bukkit.craftItemResult(padded,world);
        if(matrix.length==9)return result;var after=result.getResultingMatrix();var mapped=new ItemStack[]{after[0],after[1],after[3],after[4]};
        for(int i:new int[]{2,5,6,7,8})if(after[i]!=null&&!after[i].isEmpty())throw new IllegalStateException("CRAFT_OUTSIDE_PERSONAL_GRID");
        return new ItemCraftResult(){public ItemStack getResult(){return result.getResult();}public ItemStack[] getResultingMatrix(){return mapped.clone();}public List<ItemStack> getOverflowItems(){return result.getOverflowItems();}};
    }
    public record Click(InventoryWindowRules.Slot domain,int slot,InventoryAction action,int hotbar,ItemStack current,ItemCraftResult crafted){}
    public static void preview(PaperInventoryCodec codec,Player player,InventoryClickEvent event){
        var matrix=matrix(event.getView().getTopInventory());var domain=slot(event.getView(),event.getClickedInventory(),event.getSlot());var crafted=domain==InventoryWindowRules.Slot.RESULT?recipe(matrix,player.getWorld()):null;
        preview(codec,slots(player.getInventory()),matrix,player.getItemOnCursor(),new Click(domain,event.getSlot(),event.getAction(),event.getClick()==ClickType.SWAP_OFFHAND?40:event.getHotbarButton(),event.getCurrentItem(),crafted));
    }
    public static InventoryPayload preview(PaperInventoryCodec codec,ItemStack[] before,ItemStack[] inputs,ItemStack carriedItem,Click click){
        var storage=before.clone();var matrix=inputs.clone();var cursor=carriedItem;var current=click.current();var domain=click.domain();var action=click.action();int carried=amount(cursor),count=amount(current);ItemStack next=current;
        if(domain==InventoryWindowRules.Slot.RESULT){
            var result=click.crafted();if(result==null)throw new IllegalStateException("CRAFT_RESULT_REQUIRED");if(result.getResult().isEmpty()||current==null||!result.getResult().equals(current)||carried>0&&!cursor.isSimilar(current)||carried+count>current.getMaxStackSize())throw new IllegalStateException("CRAFT_RESULT_CHANGED");
            for(var remainder:result.getOverflowItems())storage=codec.withCursor(storage,remainder);
            matrix=result.getResultingMatrix();cursor=copy(current,carried+count);
        }else switch(action){
            case PICKUP_ALL,PICKUP_SOME,PICKUP_HALF,PICKUP_ONE->{int take=switch(action){case PICKUP_HALF->(count+1)/2;case PICKUP_ONE->1;default->Math.min(count,current==null?0:current.getMaxStackSize()-carried);};next=copy(current,count-take);cursor=copy(current,carried+take);}
            case PLACE_ALL,PLACE_SOME,PLACE_ONE->{int put=action==InventoryAction.PLACE_ONE?1:Math.min(carried,cursor==null?0:cursor.getMaxStackSize()-count);next=copy(cursor,count+put);cursor=copy(cursor,carried-put);}
            case SWAP_WITH_CURSOR->{next=cursor;cursor=current;}
            case HOTBAR_SWAP,HOTBAR_MOVE_AND_READD->{int hotbar=click.hotbar();if(hotbar<0||hotbar>40)throw new IllegalStateException("HOTBAR_LAYOUT");next=storage[hotbar];storage[hotbar]=current;}
            case DROP_ONE_SLOT->next=copy(current,count-1);
            case DROP_ALL_SLOT->next=null;
            case MOVE_TO_OTHER_INVENTORY->{// 제작 입력/보관 이동은 소유 아이템 총량을 바꾸지 않습니다.
                // 컨테이너→보관 Shift는 Minecraft가 보관 36칸의 공간만큼 처리합니다.
                if(domain==InventoryWindowRules.Slot.EQUIPMENT){storage[click.slot()]=null;cursor=current;}return codec.encode(codec.withTemporary(storage,cursor,matrix));
            }
            case NOTHING->{return codec.encode(codec.withTemporary(storage,cursor,matrix));}
            default->throw new IllegalStateException("UNSUPPORTED_WINDOW_ACTION");
        }
        if(domain==InventoryWindowRules.Slot.STORAGE||domain==InventoryWindowRules.Slot.EQUIPMENT)storage[click.slot()]=next;
        else if(domain==InventoryWindowRules.Slot.INPUT)matrix[click.slot()-1]=next;
        return codec.encode(codec.withTemporary(storage,cursor,matrix));
    }
    public static void previewDrag(PaperInventoryCodec codec,Player player,InventoryDragEvent event){
        var storage=slots(player.getInventory());var matrix=matrix(event.getView().getTopInventory());
        for(var entry:event.getNewItems().entrySet()){int raw=entry.getKey(),slot=event.getView().convertSlot(raw);var domain=slot(event.getView(),event.getView().getInventory(raw),slot);if(domain==InventoryWindowRules.Slot.STORAGE)storage[slot]=entry.getValue();else if(domain==InventoryWindowRules.Slot.INPUT)matrix[slot-1]=entry.getValue();}
        codec.withTemporary(storage,event.getCursor(),matrix);
    }
}
