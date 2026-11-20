package io.github.jesterportfolio.hearth.survival.platform;

import java.util.*;
import io.github.jesterportfolio.hearth.survival.enchants.Formula;

/** 운영 수치와 분리한 유한 플랫폼 설정. 파일은 준비/리로드 때만 읽는다. */
public record PlatformSettings(boolean upgrade, int maxLevel, Formula cost, Formula chance,
        String failure, Map<String,Integer> materials, Set<String> equipment,
        boolean autosell, Set<String> goods, int interval, int quantity,
        boolean resourceReset, int resetTimeout, String stacking, double statCap, JobPolicy jobs) {
    public static PlatformSettings parse(Properties p) {
        if (!"1".equals(p.getProperty("version"))) throw new IllegalArgumentException("PLATFORM_VERSION");
        var mats=new TreeMap<String,Integer>();
        for(String k:p.stringPropertyNames()) if(k.startsWith("upgrade.material.")) mats.put(k.substring(17),integer(p,k,1,4096));
        var result=new PlatformSettings(bool(p,"upgrade.enabled"),integer(p,"upgrade.max-level",1,1000),
            Formula.of(required(p,"upgrade.cost")),Formula.of(required(p,"upgrade.chance")),required(p,"upgrade.failure"),
            Map.copyOf(mats),set(p,"upgrade.equipment"),bool(p,"autosell.enabled"),set(p,"autosell.goods"),
            integer(p,"autosell.interval-ticks",20,12000),integer(p,"autosell.max-quantity",1,4096),
            bool(p,"resource-reset.enabled"),integer(p,"resource-reset.timeout-seconds",10,300),required(p,"stats.stacking"),
            Double.parseDouble(required(p,"stats.cap")),JobPolicy.parse(p,bool(p,"resource-reset.enabled")));
        if(!Set.of("KEEP","DOWN","RESET","DESTROY").contains(result.failure)||
           !Set.of("ADD","MAX_ABSOLUTE").contains(result.stacking)||!Double.isFinite(result.statCap)||result.statCap<=0||result.statCap>1e6||
           result.equipment.isEmpty()||result.equipment.stream().anyMatch(s->!Set.of("armor","helmet","boots","sword","bow","axe","pickaxe","tool","hoe","damaged-items","any").contains(s))||
           result.materials.keySet().stream().anyMatch(s->!s.matches("[A-Z_]{1,64}"))||result.goods.size()>128||result.goods.stream().anyMatch(s->!s.matches("[a-z][a-z0-9_-]{0,63}")))
            throw new IllegalArgumentException("PLATFORM_SETTINGS");
        for(int level=1;level<=result.maxLevel;level++) {
            double cost=result.cost.evaluate(Map.of("level",(double)level));double chance=result.chance.evaluate(Map.of("level",(double)level));
            if(cost<0||cost>1e12||chance<0||chance>1)throw new IllegalArgumentException("PLATFORM_FORMULA_RANGE");
        }
        return result;
    }
    public static String required(Properties p,String key){return Objects.requireNonNull(p.getProperty(key),key).strip();}
    private static boolean bool(Properties p,String key){String s=required(p,key);if(!Set.of("true","false").contains(s))throw new IllegalArgumentException(key);return Boolean.parseBoolean(s);}
    private static int integer(Properties p,String key,int lo,int hi){int n=Integer.parseInt(required(p,key));if(n<lo||n>hi)throw new IllegalArgumentException(key);return n;}
    private static Set<String> set(Properties p,String key){String value=required(p,key);if(value.isBlank())return Set.of();var values=Arrays.stream(value.split(",",-1)).map(String::strip).toList();if(values.size()!=new HashSet<>(values).size())throw new IllegalArgumentException(key);return Set.copyOf(values);}
}
