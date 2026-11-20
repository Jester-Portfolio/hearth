package io.github.jesterportfolio.hearth.survival.platform;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import java.util.*;

/** 바닐라/특수 인챈트·외부 메타데이터와 분리된 강화 데이터 계약. */
public final class EquipmentData {
    private static final NamespacedKey LEVEL=Objects.requireNonNull(NamespacedKey.fromString("hearth:upgrade"));
    private static final NamespacedKey VERSION=Objects.requireNonNull(NamespacedKey.fromString("hearth:equipment_version"));
    private static final NamespacedKey PROTECTION=Objects.requireNonNull(NamespacedKey.fromString("hearth:upgrade_protection"));
    private EquipmentData(){}
    public static int level(ItemStack item){if(item==null||!item.hasItemMeta())return 0;var p=item.getItemMeta().getPersistentDataContainer();int version=p.getOrDefault(VERSION,PersistentDataType.INTEGER,1),value=p.getOrDefault(LEVEL,PersistentDataType.INTEGER,0);if(version!=1||value<0||value>1000)throw new IllegalArgumentException("EQUIPMENT_VERSION_OR_LEVEL");return value;}
    public static ItemStack level(ItemStack original,int value){level(original);if(value<0||value>1000)throw new IllegalArgumentException("EQUIPMENT_LEVEL");var result=original.clone();result.editMeta(meta->{meta.getPersistentDataContainer().set(VERSION,PersistentDataType.INTEGER,1);meta.getPersistentDataContainer().set(LEVEL,PersistentDataType.INTEGER,value);var lore=new ArrayList<>(Optional.ofNullable(meta.lore()).orElse(List.of()));lore.removeIf(row->"hearth:upgrade_lore_v1".equals(row.insertion()));if(value>0)lore.add(Component.text("강화 +"+value).insertion("hearth:upgrade_lore_v1"));meta.lore(lore);});return result;}
    public static ItemStack protection(){var result=new ItemStack(org.bukkit.Material.PAPER);result.editMeta(meta->{meta.displayName(Component.text("강화 보호 주문서"));meta.getPersistentDataContainer().set(PROTECTION,PersistentDataType.INTEGER,1);});return result;}
    public static boolean protection(ItemStack item){return item!=null&&item.hasItemMeta()&&item.getItemMeta().getPersistentDataContainer().getOrDefault(PROTECTION,PersistentDataType.INTEGER,0)==1;}
}
