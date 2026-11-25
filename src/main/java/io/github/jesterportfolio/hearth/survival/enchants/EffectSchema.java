package io.github.jesterportfolio.hearth.survival.enchants;
import java.util.*;import java.util.function.Predicate;
/** Shared built-in argument semantics for files, GUI edits and reload. Extension modules own their schemas. */
public final class EffectSchema {
 private static final Map<String,Set<String>> KEYS=new HashMap<>();
 static{
  for(String id:List.of("heal","food","saturation","damage_multiplier","damage","fire","xp_transfer","xp_add","xp_multiplier","multi_arrow","pull"))keys(id,"amount,radius,friendly");
  for(String id:List.of("cleanse","cancel_damage","disarm","collect","head_drop","lightning_visual","flight","shuffle_hotbar"))keys(id,"radius,friendly");
  keys("potion","potion,amount,duration,radius,friendly");keys("explosion","amount,radius,break_blocks");keys("fall_guard","duration");keys("durability","amount,random_armor");keys("rage","maximum,increment,expiry");keys("leadership","amount,radius");keys("knockback","amount,vertical,radius");keys("smelt","bonus");keys("area_break","mode,amount");keys("farm","mode");keys("temporary_block","material,duration,radius");keys("summon","entity,amount,lifetime");keys("aura_stat","stat,amount");
 }
 private static void keys(String id,String fields){KEYS.put(id,Set.of(fields.split(",")));}
 public static void validate(EnchantDefinition.Effect effect,Predicate<String> block){
  var keys=KEYS.get(effect.type());if(keys==null)return;
  if(!keys.containsAll(effect.arguments().keySet()))throw new IllegalArgumentException("ENCHANT_EFFECT_ARGUMENT_"+effect.type());
  var args=effect.arguments();for(String name:List.of("friendly","random_armor","break_blocks"))if(args.containsKey(name)&&!Set.of("true","false").contains(args.get(name)))throw new IllegalArgumentException("ENCHANT_EFFECT_BOOLEAN");
  if(effect.type().equals("temporary_block")&&!block.test(args.getOrDefault("material","COBWEB")))throw new IllegalArgumentException("ENCHANT_TEMPORARY_MATERIAL");
  if(effect.type().equals("area_break")&&!Set.of("blast","tree","vein","harvest").contains(args.getOrDefault("mode","blast")))throw new IllegalArgumentException("ENCHANT_AREA_MODE");
  if(effect.type().equals("farm")&&!Set.of("grow","till","plant").contains(args.getOrDefault("mode","grow")))throw new IllegalArgumentException("ENCHANT_FARM_MODE");
  if(effect.type().equals("aura_stat")&&!args.getOrDefault("stat","auraskills/strength").matches("[a-z][a-z0-9_]+/[a-z][a-z0-9_]*"))throw new IllegalArgumentException("ENCHANT_STAT_ID");
 }
 public static void validateTrigger(EnchantDefinition.Trigger trigger,EnchantDefinition.Effect e){
  Set<EnchantDefinition.Trigger> allowed=switch(e.type()){
   case "xp_add"->Set.of(EnchantDefinition.Trigger.BLOCK_XP);
   case "xp_multiplier"->Set.of(EnchantDefinition.Trigger.MOB_XP);
   case "smelt","collect"->Set.of(EnchantDefinition.Trigger.DROP);
   case "multi_arrow"->Set.of(EnchantDefinition.Trigger.SHOOT);
   case "head_drop"->Set.of(EnchantDefinition.Trigger.KILL);
   case "area_break"->Set.of(EnchantDefinition.Trigger.BREAK);
   case "farm"->Set.of(EnchantDefinition.Trigger.INTERACT);
   case "damage_multiplier","cancel_damage","rage","leadership"->Set.of(EnchantDefinition.Trigger.ATTACK,EnchantDefinition.Trigger.DEFEND,EnchantDefinition.Trigger.ARROW_HIT);
   default->null;
  };if(allowed!=null&&!allowed.contains(trigger))throw new IllegalArgumentException("ENCHANT_EFFECT_TRIGGER_"+e.type());
 }
 private EffectSchema(){}
}
