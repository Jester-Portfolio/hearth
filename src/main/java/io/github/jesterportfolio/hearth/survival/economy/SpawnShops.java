package io.github.jesterportfolio.hearth.survival.economy;

import io.github.jesterportfolio.hearth.economy.*;
import java.util.*;

/** 단일 스폰 시장의 분류별 상인. 기존 realm/콘텐츠를 자동 변환하지 않습니다. */
public final class SpawnShops {
    private final boolean enabled;
    private final List<EconomyMerchants.Merchant> categories;
    private final Map<String,String> icons;
    private SpawnShops(boolean enabled,List<EconomyMerchants.Merchant> categories,Map<String,String> icons){this.enabled=enabled;this.categories=List.copyOf(categories);this.icons=Map.copyOf(icons);}
    public boolean enabled(){return enabled;}
    public List<EconomyMerchants.Merchant> page(int page){return EconomyReport.page(categories,page);}
    public int size(){return categories.size();}
    public String icon(String id){return Objects.requireNonNull(icons.get(id),"SHOP_CATEGORY");}
    public static SpawnShops parse(Properties p,EconomyContent content,EconomyMerchants merchants){
        String layout=p.getProperty("shop-layout","legacy");if(!Set.of("legacy","spawn").contains(layout))throw new IllegalArgumentException("SHOP_LAYOUT");
        boolean spawn=layout.equals("spawn");var goods=new HashSet<String>();var positions=new HashSet<EconomyMerchants.Point>();var icons=new HashMap<String,String>();
        if(spawn&&(content.regions().size()!=1||!content.routes().isEmpty()))throw new IllegalArgumentException("SPAWN_SINGLE_MARKET");
        for(var merchant:merchants.all()){
            var at=merchant.point();String icon=p.getProperty("merchant."+merchant.id()+".icon",content.goods().get(merchant.shop().goods().getFirst()).material());if(!icon.matches("[A-Z_]+"))throw new IllegalArgumentException("SHOP_ICON");icons.put(merchant.id(),icon);
            if(spawn){if(!p.getProperty("merchant."+merchant.id()+".world","residence").equals("residence")||!merchant.shop().region().equals(content.regions().getFirst())||Math.abs(at.x())>32||Math.abs(at.y())>8||Math.abs(at.z())>32||!positions.add(at))throw new IllegalArgumentException("SPAWN_MERCHANT_POSITION");for(String good:merchant.shop().goods())if(!goods.add(good))throw new IllegalArgumentException("SPAWN_DUPLICATE_CATEGORY_GOOD");}
        }
        if(spawn&&!goods.equals(content.goods().keySet()))throw new IllegalArgumentException("SPAWN_CATEGORY_MISSING");
        return new SpawnShops(spawn,merchants.all(),icons);
    }
}
