package io.github.jesterportfolio.hearth.inventory;

import org.bukkit.inventory.*;

/** 닫기/저장/퇴장에 같은 정리 순서를 사용합니다. 계산 실패 시 실제 슬롯을 건드리지 않습니다. */
public final class TemporaryInventory {
    public interface Port{
        ItemStack[] slots();ItemStack cursor();ItemStack[] matrix();void apply(InventoryPayload value);void cursor(ItemStack value);void clearMatrix();
    }
    private final PaperInventoryCodec codec;
    public TemporaryInventory(PaperInventoryCodec codec){this.codec=codec;}
    public InventoryPayload capture(Port port){return codec.encode(codec.withTemporary(port.slots(),port.cursor(),port.matrix()));}
    public void settle(Port port){
        boolean matrix=java.util.Arrays.stream(port.matrix()).anyMatch(i->i!=null&&!i.isEmpty());var cursor=port.cursor();
        if(!matrix&&(cursor==null||cursor.isEmpty()))return;
        var value=capture(port);port.apply(value);port.clearMatrix();port.cursor(null);
    }
}
