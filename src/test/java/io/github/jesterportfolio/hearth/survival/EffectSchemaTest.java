package io.github.jesterportfolio.hearth.survival;
import io.github.jesterportfolio.hearth.survival.enchants.*;
import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class EffectSchemaTest{
 @Test void shippedCatalogKeepsAll105DefinitionsValid()throws Exception{for(var d:EnchantEngineTest.catalog().values())for(var r:d.rules())for(var e:r.effects()){EffectSchema.validateTrigger(r.trigger(),e);assertDoesNotThrow(()->EffectSchema.validate(e,s->Set.of("COBWEB").contains(s)),d.id());}}
 @Test void malformedMaterialsModesBooleansAndTyposAreRejectedBeforePublish(){for(var example:List.of(Map.of("type","temporary_block","material","INVALID"),Map.of("type","farm","mode","typo"),Map.of("type","area_break","mode","unknown"),Map.of("type","explosion","break_blocks","yes"),Map.of("type","heal","amout","10"),Map.of("type","aura_stat","stat","bad id"))){var args=new HashMap<>(example);String type=args.remove("type");var e=new EnchantDefinition.Effect(type,EnchantDefinition.Target.SELF,args);assertThrows(IllegalArgumentException.class,()->EffectSchema.validate(e,s->false),type);}}
 @Test void impossibleEventEffectCombinationIsRejected(){var e=new EnchantDefinition.Effect("collect",EnchantDefinition.Target.SELF,Map.of());assertThrows(IllegalArgumentException.class,()->EffectSchema.validateTrigger(EnchantDefinition.Trigger.TICK,e));}
}
