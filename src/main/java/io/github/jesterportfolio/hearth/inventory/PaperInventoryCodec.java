package io.github.jesterportfolio.hearth.inventory;

import java.util.Arrays;
import org.bukkit.Bukkit;
import org.bukkit.inventory.*;

/** 일반 36·방어구 4·보조 손 1만 저장합니다. 추가 슬롯·커서·GUI·엔더 상자는 제외합니다. */
public final class PaperInventoryCodec {
    public InventoryPayload capture(PlayerInventory inventory) {
        main();
        ItemStack[] storage=inventory.getStorageContents(), armor=inventory.getArmorContents();
        if (storage.length!=36 || armor.length!=4) throw new IllegalArgumentException("Unsupported slot layout");
        ItemStack[] slots=Arrays.copyOf(storage,41); System.arraycopy(armor,0,slots,36,4);
        slots[40]=inventory.getItemInOffHand(); return encode(slots);
    }
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
