package io.github.jesterportfolio.hearth.inventory;

import java.util.*;

/** 임시 아이템을 기존 보관 36칸에 되돌린 사본. 장비/보조 손·원본은 변경하지 않습니다. */
public final class InventoryPacking {
    private InventoryPacking(){}
    public interface Items<T>{T copy(T item);int amount(T item);int maximum(T item);boolean similar(T a,T b);T amount(T item,int value);}
    public static <T> List<T> pack(List<T> original,List<T> returned,Items<T> items){
        if(original.size()!=41||returned.size()>11)throw new IllegalArgumentException("TEMPORARY_SLOT_LAYOUT");
        var slots=copy(original,items);var extra=copy(returned,items);
        if(put(slots,extra,items))return Collections.unmodifiableList(slots);
        // 실제 슬롯을 나누어 둔 탓에 부족할 때만 같은 메타데이터의 보관 스택을 합칩니다.
        slots=copy(original,items);var storage=new ArrayList<T>(slots.subList(0,36));for(int i=0;i<36;i++)slots.set(i,null);
        if(!put(slots,storage,items)||!put(slots,extra,items))throw new IllegalStateException("TEMPORARY_STORAGE_FULL");
        return Collections.unmodifiableList(slots);
    }
    private static <T> ArrayList<T> copy(List<T> source,Items<T> items){var values=new ArrayList<T>();for(T item:source){if(item!=null&&(items.amount(item)<1||items.amount(item)>items.maximum(item)))throw new IllegalArgumentException("INVALID_TEMPORARY_ITEM");values.add(item==null?null:items.copy(item));}return values;}
    private static <T> boolean put(List<T> slots,List<T> source,Items<T> items){
        for(T item:source){if(item==null)continue;int left=items.amount(item);
            for(int i=0;i<36&&left>0;i++){T old=slots.get(i);if(old!=null&&items.similar(old,item)){int count=Math.min(left,items.maximum(old)-items.amount(old));if(count>0){slots.set(i,items.amount(old,items.amount(old)+count));left-=count;}}}
            for(int i=0;i<36&&left>0;i++)if(slots.get(i)==null){int count=Math.min(left,items.maximum(item));slots.set(i,items.amount(item,count));left-=count;}
            if(left>0)return false;
        }return true;
    }
}
