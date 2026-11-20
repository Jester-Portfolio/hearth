package io.github.jesterportfolio.hearth.survival.rpg;

import java.util.*;
import java.util.random.RandomGenerator;
import io.github.jesterportfolio.hearth.survival.skills.WorldAccessPolicy;
import io.github.jesterportfolio.hearth.survival.skills.WorldAccessPolicy.Role;

/** 콘텐츠 숫자와 시스템 경계를 분리한다. 미정 정책은 파괴적 처리 대신 준비 보류다. */
public record RpgPolicy(Map<Role,Death> deaths,DropUnit dropUnit,XpScope xpScope,Rounding rounding,
                        Set<CharmScope> charmScopes,boolean lostXpOrbs,boolean penaltyEnabled,
                        WorldAccessPolicy.Rule penaltyRule,Set<Role> xpBlocked,Set<Role> abilitiesBlocked,
                        Map<String,Double> itemRates,Set<Role> penaltyWorlds) {
    public enum DropUnit { ITEM,STACK,UNSET }
    public enum XpScope { VANILLA_TOTAL,VANILLA_PROGRESS,AURA_PROGRESS,BOTH_TOTAL,BOTH_PROGRESS,UNSET }
    public enum Rounding { FLOOR,CEIL,NEAREST,UNSET }
    public enum CharmScope { ITEMS,VANILLA_XP,AURA_XP }
    public enum XpLoss { NONE,THIRD,VANILLA,UNSET }
    public record Death(XpLoss loss,Double dropRate){public Death{Objects.requireNonNull(loss);if(dropRate!=null&&(!Double.isFinite(dropRate)||dropRate<0||dropRate>1))throw new IllegalArgumentException("RPG_DEATH_RATE");}}
    public RpgPolicy {penaltyWorlds=Set.copyOf(penaltyWorlds);deaths=Map.copyOf(deaths);charmScopes=Set.copyOf(charmScopes);xpBlocked=Set.copyOf(xpBlocked);abilitiesBlocked=Set.copyOf(abilitiesBlocked);itemRates=Map.copyOf(itemRates);if(deaths.size()!=Role.values().length||penaltyEnabled&&penaltyRule==null)throw new IllegalArgumentException("RPG_POLICY");for(double p:itemRates.values())if(!Double.isFinite(p)||p<0||p>1)throw new IllegalArgumentException("RPG_ITEM_RATE");}
    public boolean ready(Role role){if(role==null)return false;var p=deaths.get(role);return p!=null&&p.dropRate()!=null&&p.loss()!=XpLoss.UNSET&&(p.dropRate()==0||dropUnit!=DropUnit.UNSET)&&(p.loss()!=XpLoss.THIRD||xpScope!=XpScope.UNSET&&rounding!=Rounding.UNSET);}
    public long remainingThird(long before){if(before<0||rounding==Rounding.UNSET)throw new IllegalArgumentException("RPG_XP_UNSET");long loss=switch(rounding){case FLOOR->before/3;case CEIL->before/3+(before%3==0?0:1);case NEAREST->before/3+(before%3>=2?1:0);default->throw new IllegalStateException();};return before-loss;}
    public boolean vanillaLoss(){return Set.of(XpScope.VANILLA_TOTAL,XpScope.VANILLA_PROGRESS,XpScope.BOTH_TOTAL,XpScope.BOTH_PROGRESS).contains(xpScope);}
    public boolean auraLoss(){return Set.of(XpScope.AURA_PROGRESS,XpScope.BOTH_TOTAL,XpScope.BOTH_PROGRESS).contains(xpScope);}
    public record Slot(int index,String identity,int amount,double rate,boolean charm){public Slot{if(index<0||identity==null||amount<1||amount>256||!Double.isFinite(rate)||rate<0||rate>1)throw new IllegalArgumentException("RPG_SLOT");}}
    public record Split(Map<Integer,Integer> kept,Map<Integer,Integer> dropped,Integer charmSlot){public Split{kept=Map.copyOf(kept);dropped=Map.copyOf(dropped);}}
    public boolean needsCharm(List<Slot> slots,Death death){return slots.stream().anyMatch(s->s.rate()>0)&&charmScopes.contains(CharmScope.ITEMS)||(death.loss()==XpLoss.VANILLA||death.loss()==XpLoss.THIRD&&vanillaLoss())&&charmScopes.contains(CharmScope.VANILLA_XP)||death.loss()==XpLoss.THIRD&&auraLoss()&&charmScopes.contains(CharmScope.AURA_XP);}
    public Split split(List<Slot> slots,boolean useCharm,RandomGenerator random){
        if(slots.stream().map(Slot::index).distinct().count()!=slots.size())throw new IllegalArgumentException("RPG_DUPLICATE_SLOT");
        Integer charm=useCharm&&!charmScopes.isEmpty()?slots.stream().filter(Slot::charm).map(Slot::index).findFirst().orElse(null):null;
        var kept=new HashMap<Integer,Integer>();var dropped=new HashMap<Integer,Integer>();
        for(var s:slots){int amount=s.amount()-(Objects.equals(charm,s.index())?1:0),lost=0;
            if(!(charm!=null&&charmScopes.contains(CharmScope.ITEMS))){if(dropUnit==DropUnit.UNSET&&s.rate()>0)throw new IllegalStateException("RPG_DROP_UNIT_UNSET");if(dropUnit==DropUnit.STACK)lost=random.nextDouble()<s.rate()?amount:0;else for(int i=0;i<amount;i++)if(random.nextDouble()<s.rate())lost++;}
            if(amount>lost)kept.put(s.index(),amount-lost);if(lost>0)dropped.put(s.index(),lost);
        }return new Split(kept,dropped,charm);
    }
    public static RpgPolicy parse(Properties p){
        if(!"1".equals(p.getProperty("format")))throw new IllegalArgumentException("RPG_FORMAT");var d=new EnumMap<Role,Death>(Role.class);
        for(var role:Role.values()){String prefix="death."+role.name().toLowerCase(Locale.ROOT)+".";String raw=required(p,prefix+"drop");d.put(role,new Death(XpLoss.valueOf(required(p,prefix+"xp").toUpperCase(Locale.ROOT)),raw.equals("unset")?null:Double.parseDouble(raw)));}
        var scopes=EnumSet.noneOf(CharmScope.class);String raw=p.getProperty("charm.scopes","none");if(!raw.equals("none"))for(String scope:raw.split(","))scopes.add(CharmScope.valueOf(scope.strip().toUpperCase(Locale.ROOT)));
        boolean penalty=bool(p,"penalty.enabled");WorldAccessPolicy.Rule rule=null;if(penalty)rule=new WorldAccessPolicy.Rule(WorldAccessPolicy.Kind.valueOf(required(p,"penalty.kind").toUpperCase(Locale.ROOT)),Arrays.stream(required(p,"penalty.skills").split(",")).map(String::strip).toList(),Integer.parseInt(required(p,"penalty.minimum")));
        var rates=new HashMap<String,Double>();for(String key:p.stringPropertyNames())if(key.startsWith("item-rate."))rates.put(key.substring(10),Double.parseDouble(p.getProperty(key)));
        return new RpgPolicy(d,DropUnit.valueOf(required(p,"drop-unit").toUpperCase(Locale.ROOT)),XpScope.valueOf(required(p,"xp-scope").toUpperCase(Locale.ROOT)),Rounding.valueOf(required(p,"xp-rounding").toUpperCase(Locale.ROOT)),scopes,bool(p,"lost-xp-orbs"),penalty,rule,roles(p,"xp-blocked"),roles(p,"abilities-blocked"),rates,p.containsKey("penalty-worlds")?roles(p,"penalty-worlds"):EnumSet.allOf(Role.class));
    }
    public static String required(Properties p,String k){return Objects.requireNonNull(p.getProperty(k),k).strip();}
    public static boolean bool(Properties p,String k){String raw=required(p,k);if(!Set.of("true","false").contains(raw))throw new IllegalArgumentException(k);return Boolean.parseBoolean(raw);}
    private static Set<Role> roles(Properties p,String k){var result=EnumSet.noneOf(Role.class);String raw=required(p,k);if(!raw.equals("none"))for(String name:raw.split(","))result.add(Role.valueOf(name.strip().toUpperCase(Locale.ROOT)));return result;}
}
