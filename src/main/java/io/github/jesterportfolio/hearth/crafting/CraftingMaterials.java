package io.github.jesterportfolio.hearth.crafting;

import java.util.*;

/** 본인의 제작 가능 재료 수만 표시합니다. 플레이어·진행·아이템 데이터는 포함하지 않습니다. */
public final class CraftingMaterials {
    private CraftingMaterials(){}
    public record Need(String material,int required,int available){
        public Need{if(material==null||!material.matches("[A-Z_]+")||required<1||required>9||available<0)throw new IllegalArgumentException("CRAFT_MATERIAL_COUNT");}
        public int missing(){return Math.max(0,required-available);}
        public List<String> lines(){return List.of("필요 수량: "+required,"사용 가능한 보유 수량: "+available,missing()==0?"재료 준비 완료":"부족 수량: "+missing());}
    }
    public static List<Need> view(CraftContent.Recipe recipe,Map<String,Integer> available){
        var needed=new LinkedHashMap<String,Integer>();recipe.matrix().stream().filter(m->!m.equals("AIR")).forEach(m->needed.merge(m,1,Integer::sum));
        return needed.entrySet().stream().map(e->new Need(e.getKey(),e.getValue(),available.getOrDefault(e.getKey(),0))).toList();
    }
}
