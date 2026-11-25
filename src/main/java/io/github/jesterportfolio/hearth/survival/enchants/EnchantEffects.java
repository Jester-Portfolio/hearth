// Adapted from CrazyEnchantments, Copyright (c) 2016-2023 CrazyCrew, MIT.
// Original source/license: META-INF/licenses/CrazyEnchantments-MIT.txt and enchants/catalog.json.
package io.github.jesterportfolio.hearth.survival.enchants;

import java.util.*;import java.util.function.*;
import org.bukkit.*;import org.bukkit.block.*;import org.bukkit.block.data.Ageable;import org.bukkit.entity.*;import org.bukkit.event.*;import org.bukkit.event.entity.*;import org.bukkit.event.block.*;import org.bukkit.inventory.*;import org.bukkit.inventory.meta.*;import org.bukkit.potion.*;import org.bukkit.attribute.*;import org.bukkit.util.Vector;

/** 공통 정책을 통과한 현재 Player/실제 이벤트만 받는 Java 효과 모듈 registry. */
public final class EnchantEffects {
    @FunctionalInterface public interface Module {void apply(Context context,EnchantEngine.Invocation invocation,LivingEntity target);}
    public record Context(EnchantRuntime runtime,Player player,LivingEntity other,Block block,Event event,ItemStack item,EquipmentSlot slot){}
    private final Map<String,Consumer<EnchantDefinition.Effect>> validators=new HashMap<>();
    private final Map<String,Module> modules=new LinkedHashMap<>();
    public EnchantEffects(){
        register("potion",(c,i,t)->{if(t!=null){var type=Registry.POTION_EFFECT_TYPE.get(NamespacedKey.minecraft(arg(i,"potion","SPEED").toLowerCase(Locale.ROOT)));if(type==null)throw new IllegalArgumentException("ENCHANT_POTION");c.runtime.potion(t,new PotionEffect(type,(int)Math.clamp(value(i,"duration",i.duration()),1,120000),(int)Math.clamp(value(i,"amount",0),0,255)),c.player,c.event==null);}});
        register("heal",(c,i,t)->{if(t!=null&&!t.isDead())t.setHealth(Math.clamp(t.getHealth()+value(i,"amount",0),0,Objects.requireNonNull(t.getAttribute(Attribute.MAX_HEALTH)).getValue()));});
        register("food",(c,i,t)->{if(t instanceof Player p)p.setFoodLevel((int)Math.clamp(p.getFoodLevel()+value(i,"amount",0),0,20));});
        register("saturation",(c,i,t)->{if(t instanceof Player p)p.setSaturation((float)Math.clamp(p.getSaturation()+value(i,"amount",0),0,20));});
        register("damage_multiplier",(c,i,t)->{if(c.event instanceof EntityDamageEvent e)e.setDamage(Math.clamp(e.getDamage()*value(i,"amount",1),0,100000));});
        register("cancel_damage",(c,i,t)->{if(c.event instanceof EntityDamageEvent e)e.setCancelled(true);});
        register("damage",(c,i,t)->{if(t!=null)c.runtime.damage(c.player,t,value(i,"amount",0));});
        register("fire",(c,i,t)->{if(t!=null)t.setFireTicks((int)Math.clamp(value(i,"amount",0),0,120000));});
        register("cleanse",(c,i,t)->{if(t!=null)for(var p:t.getActivePotionEffects())if(Set.of("blindness","nausea","hunger","poison","slowness","mining_fatigue","weakness","wither").contains(p.getType().getKey().getKey()))t.removePotionEffect(p.getType());});
        register("knockback",(c,i,t)->{if(t==null)return;Vector direction=t==c.player&&c.other!=null?c.player.getLocation().toVector().subtract(c.other.getLocation().toVector()):t.getLocation().toVector().subtract(c.player.getLocation().toVector());if(direction.lengthSquared()<.001)direction=c.player.getLocation().getDirection();t.setVelocity(direction.normalize().multiply(Math.clamp(value(i,"amount",1),0,5)).setY(Math.clamp(value(i,"vertical",.5),-1,2)));});
        register("pull",(c,i,t)->{if(t!=null){var v=c.player.getLocation().toVector().subtract(t.getLocation().toVector());if(v.lengthSquared()>.01)t.setVelocity(v.normalize().multiply(Math.clamp(value(i,"amount",1),0,5)));}});
        register("lightning_visual",(c,i,t)->c.player.getWorld().strikeLightningEffect(t!=null?t.getLocation():c.block!=null?c.block.getLocation():c.player.getLocation()));
        register("explosion",(c,i,t)->c.runtime.explode(c,i));
        register("fall_guard",(c,i,t)->{if(t!=null)c.runtime.fall.put(t.getUniqueId(),System.currentTimeMillis()+(long)value(i,"duration",i.duration())*50);});
        register("durability",(c,i,t)->{ItemStack item=c.item;if("true".equals(arg(i,"random_armor","false"))&&t instanceof Player p){var armor=p.getInventory().getArmorContents();item=armor[java.util.concurrent.ThreadLocalRandom.current().nextInt(4)];}if(item!=null&&!item.isEmpty()&&item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable m){m.setDamage((int)Math.clamp(m.getDamage()+value(i,"amount",0),0,Math.max(0,item.getType().getMaxDurability()-1)));item.setItemMeta(m);}});
        register("disarm",(c,i,t)->{if(t instanceof Player p&&c.runtime.rpg.runtime.economy().free(p)){var inv=p.getInventory();int free=inv.firstEmpty();if(free<0||free>=36)return;int slot=new int[]{36,37,38,39}[java.util.concurrent.ThreadLocalRandom.current().nextInt(4)];var armor=inv.getItem(slot);if(armor!=null&&!armor.isEmpty()){inv.setItem(free,armor.clone());inv.setItem(slot,null);}}});
        register("shuffle_hotbar",(c,i,t)->{if(t instanceof Player p&&c.runtime.rpg.runtime.economy().free(p)){var list=new ArrayList<ItemStack>();for(int x=0;x<9;x++)list.add(p.getInventory().getItem(x));Collections.shuffle(list);for(int x=0;x<9;x++)p.getInventory().setItem(x,list.get(x));}});
        register("xp_transfer",(c,i,t)->{if(t instanceof Player p){long current=io.github.jesterportfolio.hearth.survival.rpg.VanillaXp.at(p.getLevel())+Math.round(p.getExp()*io.github.jesterportfolio.hearth.survival.rpg.VanillaXp.next(p.getLevel()));int amount=(int)Math.min(current,Math.max(0,value(i,"amount",0)));p.giveExp(-amount);c.player.giveExp(amount);}});
        register("rage",(c,i,t)->{if(c.event instanceof EntityDamageEvent e){var old=c.runtime.rage.get(c.player.getUniqueId());double multiplier=old!=null&&old[1]>System.currentTimeMillis()?Math.min(value(i,"maximum",5),old[0]+value(i,"increment",.1)):1;c.runtime.rage.put(c.player.getUniqueId(),new double[]{multiplier,System.currentTimeMillis()+value(i,"expiry",4000)});e.setDamage(e.getDamage()*multiplier);}});
        register("leadership",(c,i,t)->{if(c.event instanceof EntityDamageEvent e){double radius=value(i,"radius",4);long count=c.player.getNearbyEntities(radius,radius,radius).stream().filter(x->x instanceof Player p&&c.runtime.friendly(c.player,p)).count();e.setDamage(e.getDamage()+count*value(i,"amount",.5));}});
        register("xp_add",(c,i,t)->{if(c.event instanceof BlockBreakEvent e)e.setExpToDrop((int)Math.clamp(e.getExpToDrop()+value(i,"amount",0),0,Integer.MAX_VALUE));});
        register("xp_multiplier",(c,i,t)->{if(c.event instanceof EntityDeathEvent e)e.setDroppedExp((int)Math.clamp(e.getDroppedExp()*value(i,"amount",1),0,Integer.MAX_VALUE));});
        register("head_drop",(c,i,t)->{if(c.event instanceof EntityDeathEvent e&&c.other!=null){Material material=switch(c.other.getType()){case PLAYER->Material.PLAYER_HEAD;case ZOMBIE->Material.ZOMBIE_HEAD;case SKELETON->Material.SKELETON_SKULL;case WITHER_SKELETON->Material.WITHER_SKELETON_SKULL;case CREEPER->Material.CREEPER_HEAD;case PIGLIN->Material.PIGLIN_HEAD;case ENDER_DRAGON->Material.DRAGON_HEAD;default->null;};if(material!=null&&c.runtime.allowBonus(e,c.player)&&e.getDrops().stream().noneMatch(x->x.getType()==material)){var head=new ItemStack(material);if(c.other instanceof Player p)head.editMeta(m->((SkullMeta)m).setOwningPlayer(p));e.getDrops().add(head);}}});
        register("multi_arrow",(c,i,t)->{if(c.event instanceof EntityShootBowEvent e&&e.getProjectile() instanceof AbstractArrow original){for(int n=0;n<(int)Math.clamp(value(i,"amount",1),0,32);n++){var arrow=c.player.launchProjectile(Arrow.class,original.getVelocity().clone().add(new Vector((n%2==0?.04:-.04)*(n+1),0,.02*(n+1))));copyArrow(original,arrow);c.runtime.arrows.put(arrow.getUniqueId(),c.runtime.arrows.get(original.getUniqueId()));}}});
        register("smelt",(c,i,t)->{if(c.event instanceof BlockDropItemEvent e&&e.getBlockState().getType().name().endsWith("_ORE"))for(var entity:List.copyOf(e.getItems())){var drop=entity.getItemStack();if(drop.hasItemMeta())continue;Material smelt=switch(drop.getType()){case RAW_IRON->Material.IRON_INGOT;case RAW_COPPER->Material.COPPER_INGOT;case RAW_GOLD->Material.GOLD_INGOT;default->drop.getType();};int bonus=0;for(int n=0;n<(int)Math.clamp(value(i,"bonus",0),0,64);n++)if(java.util.concurrent.ThreadLocalRandom.current().nextDouble()<Math.clamp((25+25*i.level())/100.0,0,1))bonus++;if(smelt!=drop.getType())drop=drop.withType(smelt);entity.setItemStack(drop);if(bonus>0)c.runtime.bonus(e,i.definition().id()+"/"+entity.getUniqueId(),drop.asQuantity(bonus));}});
        register("collect",(c,i,t)->{if(c.event instanceof BlockDropItemEvent e)c.runtime.collect(e);});
        register("area_break",(c,i,t)->c.runtime.area(c,arg(i,"mode","blast"),(int)value(i,"amount",1)));
        register("farm",(c,i,t)->c.runtime.farm(c,arg(i,"mode","grow")));
        register("temporary_block",(c,i,t)->c.runtime.web(c,t!=null?t:c.other(),(int)value(i,"duration",100),Material.matchMaterial(arg(i,"material","COBWEB")),(int)value(i,"radius",1)));
        register("summon",(c,i,t)->{if(t!=null)c.runtime.summon(c,t,EntityType.valueOf(arg(i,"entity","WOLF")),(int)value(i,"amount",1),(int)value(i,"lifetime",1200));});
        register("flight",(c,i,t)->c.runtime.fly(c.player));
        register("aura_stat",(c,i,t)->c.runtime.stat(c.player,i.definition().id()+"/"+c.slot.name()+"/"+arg(i,"stat","auraskills/strength"),arg(i,"stat","auraskills/strength"),value(i,"amount",0)));
    }
    public static void copyArrow(AbstractArrow source,AbstractArrow target){target.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);target.setDamage(source.getDamage());target.setCritical(source.isCritical());target.setFireTicks(source.getFireTicks());}
    public void register(String id,Module module){if(!id.matches("[a-z][a-z0-9_]{0,40}")||modules.putIfAbsent(id,Objects.requireNonNull(module))!=null)throw new IllegalArgumentException("ENCHANT_MODULE_ID");}
    public void register(String id,Module module,Consumer<EnchantDefinition.Effect> validator){Objects.requireNonNull(validator);register(id,module);validators.put(id,validator);}
    public void validate(EnchantDefinition.Effect effect){var validator=validators.get(effect.type());if(validator!=null)validator.accept(effect);}
    public Set<String> types(){return Set.copyOf(modules.keySet());}
    public void apply(Context c,EnchantEngine.Invocation i,LivingEntity target){Objects.requireNonNull(modules.get(i.effect().type())).apply(c,i,target);}
    public static String arg(EnchantEngine.Invocation i,String key,String fallback){return i.effect().arguments().getOrDefault(key,fallback);}
    public static double value(EnchantEngine.Invocation i,String key,double fallback){Formula value=i.effect().expressions().get(key);return value==null?fallback:value.evaluate(i.variables());}
}
