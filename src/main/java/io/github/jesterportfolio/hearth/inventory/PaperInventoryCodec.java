package io.github.jesterportfolio.hearth.inventory;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.inventory.*;

/** 일반 36·방어구 4·보조 손 1만 저장합니다. 추가 슬롯·커서·GUI·엔더 상자는 제외합니다. */
public final class PaperInventoryCodec {
    public InventoryPayload capture(PlayerInventory inventory) {
        return encode(contents(inventory));
    }
    private ItemStack[] contents(PlayerInventory inventory){
        main();
        ItemStack[] storage=inventory.getStorageContents(), armor=inventory.getArmorContents();
        if (storage.length!=36 || armor.length!=4) throw new IllegalArgumentException("Unsupported slot layout");
        ItemStack[] slots=Arrays.copyOf(storage,41); System.arraycopy(armor,0,slots,36,4);
        slots[40]=inventory.getItemInOffHand(); return slots;
    }
    /** 커서는 별도 저장하지 않고 검증된 36개 보관 슬롯에 합친 사본을 만듭니다. 실패 시 원본 불변. */
    public InventoryPayload capture(PlayerInventory inventory, ItemStack cursor) {
        return encode(withCursor(contents(inventory),cursor));
    }
    public ItemStack[] withCursor(ItemStack[] original,ItemStack cursor) {
        main();if(original.length!=41)throw new IllegalArgumentException("Expected 41 slots");
        ItemStack[] slots=Arrays.stream(original).map(i->i==null?null:i.clone()).toArray(ItemStack[]::new);
        if(cursor==null||cursor.isEmpty())return slots;
        if(cursor.getAmount()<1||cursor.getAmount()>cursor.getMaxStackSize())throw new IllegalArgumentException("INVALID_CURSOR");
        int left=cursor.getAmount();
        for(int i=0;i<36&&left>0;i++)if(slots[i]!=null&&!slots[i].isEmpty()&&slots[i].isSimilar(cursor)){
            int amount=Math.min(left,Math.max(0,slots[i].getMaxStackSize()-slots[i].getAmount()));
            slots[i].setAmount(slots[i].getAmount()+amount);left-=amount;
        }
        for(int i=0;i<36&&left>0;i++)if(slots[i]==null||slots[i].isEmpty()){
            slots[i]=cursor.clone();slots[i].setAmount(left);left=0;
        }
        if(left!=0)throw new IllegalStateException("CURSOR_STORAGE_FULL");
        return slots;
    }
    /** 제작 결과 미리보기는 포함하지 않습니다. 실제 투입 재료·커서만 기존 41칸에 합칩니다. */
    public ItemStack[] withTemporary(ItemStack[] original,ItemStack cursor,ItemStack[] matrix){
        main();if(matrix.length!=0&&matrix.length!=4&&matrix.length!=9)throw new IllegalArgumentException("TEMPORARY_MATRIX_LAYOUT");
        var returned=new ArrayList<ItemStack>();returned.add(empty(cursor));for(var item:matrix)returned.add(empty(item));
        var normalized=Arrays.stream(original).map(PaperInventoryCodec::empty).toList();
        return InventoryPacking.pack(normalized,returned,new InventoryPacking.Items<ItemStack>(){
            public ItemStack copy(ItemStack item){return item.clone();}public int amount(ItemStack item){return item.getAmount();}public int maximum(ItemStack item){return item.getMaxStackSize();}public boolean similar(ItemStack a,ItemStack b){return a.isSimilar(b);}public ItemStack amount(ItemStack item,int value){var result=item.clone();result.setAmount(value);return result;}
        }).toArray(ItemStack[]::new);
    }
    private static ItemStack empty(ItemStack item){return item==null||item.isEmpty()?null:item;}
    public InventoryPayload capture(PlayerInventory inventory,ItemStack cursor,CraftingInventory crafting){return encode(withTemporary(contents(inventory),cursor,crafting==null?new ItemStack[0]:crafting.getMatrix()));}
    public InventoryPayload encode(ItemStack[] slots) {
        main();
        if (slots==null || slots.length!=41) throw new IllegalArgumentException("Expected 41 slots");
        byte[][] bytes=new byte[41][];
        for (int i=0;i<41;i++) if (slots[i]!=null && !slots[i].isEmpty()) {
            if (slots[i].getAmount()<1 || slots[i].getAmount()>slots[i].getMaxStackSize()) throw new IllegalArgumentException("Invalid item amount");
            bytes[i]=slots[i].serializeAsBytes();
        }
        return InventoryPayload.pack(bytes);
    }
    public ItemStack[] decode(InventoryPayload payload) {
        main();
        byte[][] bytes=payload.slots(); ItemStack[] slots=new ItemStack[41];
        try {
            for (int i=0;i<41;i++) if (bytes[i]!=null) {
                slots[i]=ItemStack.deserializeBytes(bytes[i]);
                if (slots[i].isEmpty() || slots[i].getAmount()<1 || slots[i].getAmount()>slots[i].getMaxStackSize()) throw new IllegalArgumentException("Invalid item amount");
            }
            return slots;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("INVALID_ITEM_NBT"); }
    }
    /** NBT 표기 정규화 차이를 아이템 변경으로 세지 않습니다. CAS 저장의 바이트 검증과 별개입니다. */
    public boolean equivalent(InventoryPayload left,InventoryPayload right) { main();return Arrays.equals(decode(left),decode(right)); }
    public void apply(PlayerInventory inventory, InventoryPayload payload) {
        main(); ItemStack[] slots=decode(payload); // 모든 검증을 끝낸 뒤에만 적용합니다.
        inventory.setStorageContents(Arrays.copyOf(slots,36));
        inventory.setArmorContents(Arrays.copyOfRange(slots,36,40)); inventory.setItemInOffHand(slots[40]);
    }
    private static void main() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Inventory API requires main thread"); }
}
