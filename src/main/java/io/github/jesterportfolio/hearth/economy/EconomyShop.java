package io.github.jesterportfolio.hearth.economy;

import java.util.*;

/** 상점 표시 데이터와 콘텐츠 검증. Bukkit·플레이어·SQL 의존성을 두지 않습니다. */
public record EconomyShop(String region,List<String> goods,Map<String,String> names){
    public EconomyShop{EconomyContent.key(region);goods=List.copyOf(goods);names=Map.copyOf(names);if(goods.isEmpty()||goods.size()>64||new HashSet<>(goods).size()!=goods.size())throw new IllegalArgumentException("ECON_SHOP_GOODS");}
    public static EconomyShop parse(Properties p,EconomyContent content){
        String region=Objects.requireNonNull(p.getProperty("merchant-region"));String raw=Objects.requireNonNull(p.getProperty("merchant-goods",p.getProperty("merchant-good")));
        var goods=Arrays.stream(raw.split(",",-1)).map(String::trim).toList();if(!content.regions().contains(region)||!content.goods().keySet().containsAll(goods))throw new IllegalArgumentException("ECON_SHOP_REF");
        var names=new HashMap<String,String>();for(String id:goods){String name=p.getProperty("good."+id+".name",id).strip();if(name.isEmpty()||name.length()>80)throw new IllegalArgumentException("ECON_SHOP_NAME");names.put(id,name);}return new EconomyShop(region,goods,names);
    }
    public String name(String good){if(!goods.contains(good))throw new IllegalArgumentException("ECON_SHOP_GOOD");return names.getOrDefault(good,good);}
    public record Price(int quantity,boolean buy,long total){}
    public List<Price> prices(EconomyContent content,EconomyStore.Offer offer){
        if(!offer.key().region().equals(region)||!goods.contains(offer.key().good()))throw new IllegalArgumentException("ECON_SHOP_OFFER");
        var result=new ArrayList<Price>();for(boolean buy:List.of(true,false))for(int q:new int[]{1,8,32}){
            if(buy&&offer.market().stock()<q)continue;result.add(new Price(q,buy,EconomyEngine.quote(content,offer.key().good(),offer.market(),q,buy)));
        }return List.copyOf(result);
    }
    public List<String> page(int page){if(page<0||page>=(goods.size()+44)/45)throw new IllegalArgumentException("ECON_SHOP_PAGE");return goods.subList(page*45,Math.min(goods.size(),(page+1)*45));}
}
