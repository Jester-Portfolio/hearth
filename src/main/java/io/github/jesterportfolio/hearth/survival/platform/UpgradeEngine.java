package io.github.jesterportfolio.hearth.survival.platform;

import java.util.*;
import org.bukkit.inventory.ItemStack;
import io.github.jesterportfolio.hearth.survival.enchants.EnchantItems;

/** 한 번 계획한 결과/난수는 요청과 함께 저장한다. GUI는 물품을 보관하지 않는다. */
public final class UpgradeEngine {
    public record Plan(ItemStack[] after,int level,double chance,double roll,long cost,String outcome){public Plan{after=after.clone();}}
    public Plan plan(ItemStack[] original,int equipment,int protection,PlatformSettings policy,double roll){
        if(!policy.upgrade()||original.length!=41||equipment<0||equipment>=41||protection< -1||protection>=41||equipment==protection||roll<0||roll>=1||!Double.isFinite(roll))throw new IllegalArgumentException("UPGRADE_REQUEST");
        var after=Arrays.stream(original).map(i->i==null?null:i.clone()).toArray(ItemStack[]::new);var item=Objects.requireNonNull(after[equipment]);
        if(item.getAmount()!=1||!policy.equipment().stream().anyMatch(t->EnchantItems.matches(t,item.getType())))throw new IllegalArgumentException("UPGRADE_EQUIPMENT");
        int old=EquipmentData.level(item);if(old>=policy.maxLevel())throw new IllegalArgumentException("UPGRADE_MAX_LEVEL");int next=old+1;
        if(protection>=0&&!EquipmentData.protection(after[protection]))throw new IllegalArgumentException("UPGRADE_PROTECTION");
        for(var material:policy.materials().entrySet()){int left=material.getValue();for(int slot=0;slot<36&&left>0;slot++){var value=after[slot];if(slot==equipment||slot==protection||value==null||value.hasItemMeta()||!value.getType().name().equals(material.getKey()))continue;int count=Math.min(left,value.getAmount());left-=count;value.subtract(count);if(value.isEmpty())after[slot]=null;}if(left>0)throw new IllegalArgumentException("UPGRADE_MATERIALS");}
        var vars=Map.of("level",(double)next);double chance=policy.chance().evaluate(vars);long cost=(long)Math.floor(policy.cost().evaluate(vars));boolean success=roll<chance;String outcome=success?"SUCCESS":protection>=0?"PROTECTED":policy.failure();
        if(protection>=0){after[protection].subtract(1);if(after[protection].isEmpty())after[protection]=null;}
        after[equipment]=success?EquipmentData.level(item,next):protection>=0||policy.failure().equals("KEEP")?item:switch(policy.failure()){case "DOWN"->EquipmentData.level(item,Math.max(0,old-1));case "RESET"->EquipmentData.level(item,0);case "DESTROY"->null;default->throw new IllegalArgumentException("UPGRADE_FAILURE");};
        return new Plan(after,next,chance,roll,cost,outcome);
    }
}
