package io.github.jesterportfolio.hearth.survival.platform;
import java.util.*;import org.bukkit.Material;import org.bukkit.inventory.ItemStack;
import io.github.jesterportfolio.hearth.economy.EconomyContent;
/** 36개 소지 슬롯만 한 번 확인한다. 강화/커스텀/PDC 아이템은 일반 상품으로 취급하지 않는다. */
public final class AutoSalePlanner {
    public record Selection(String good,int quantity){}
    private AutoSalePlanner(){}
    public static Optional<Selection> select(ItemStack[] inventory,Map<String,EconomyContent.Good> definitions,Set<String> allowed,int maximum){if(inventory.length!=41||maximum<1||maximum>4096)throw new IllegalArgumentException("AUTOSELL_PLAN");var byMaterial=new HashMap<Material,String>();for(String good:allowed){var definition=Objects.requireNonNull(definitions.get(good));var material=Objects.requireNonNull(Material.matchMaterial(definition.material()));if(byMaterial.put(material,good)!=null)throw new IllegalArgumentException("AUTOSELL_AMBIGUOUS_MATERIAL");}var counts=new TreeMap<String,Integer>();for(int i=0;i<36;i++){var item=inventory[i];if(item==null||item.isEmpty())continue;String good=byMaterial.get(item.getType());if(good!=null&&item.isSimilar(new ItemStack(item.getType())))counts.merge(good,item.getAmount(),Math::addExact);}return counts.entrySet().stream().findFirst().map(e->new Selection(e.getKey(),Math.min(e.getValue(),maximum)));}
}
