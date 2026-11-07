package io.github.jesterportfolio.hearth.economy;

import java.util.*;

/** 상인의 논리 지역·취급 상품·시험 배치. 계정 소유권과 물리 월드를 새로 만들지 않습니다. */
public final class EconomyMerchants {
    public record Point(double x,double y,double z){public Point{if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.abs(x)>29999980||Math.abs(z)>29999980||Math.abs(y)>4096)throw new IllegalArgumentException("ECON_MERCHANT_POINT");}}
    public record Merchant(String id,String name,Point point,EconomyShop shop){
        public Merchant{EconomyContent.key(id);Objects.requireNonNull(point);Objects.requireNonNull(shop);if(name==null||name.isBlank()||name.length()>80)throw new IllegalArgumentException("ECON_MERCHANT_NAME");}
        public void require(EconomyStore.Offer offer){if(!shop.region().equals(offer.key().region())||!shop.goods().contains(offer.key().good()))throw new IllegalArgumentException("ECON_MERCHANT_OFFER");}
    }
    /** 객체 동일성까지 확인해 같은 NPC의 새 대화도 이전 비동기 결과와 구분합니다. */
    public record Visit(String merchant,UUID entity,long generation){
        public Visit{EconomyContent.key(merchant);Objects.requireNonNull(entity);if(generation<0)throw new IllegalArgumentException("ECON_MERCHANT_GENERATION");}
        public boolean current(Visit selected,UUID liveEntity,long currentGeneration){return this==selected&&entity.equals(liveEntity)&&generation==currentGeneration;}
    }
    private record Chunk(int x,int z){}
    private final List<Merchant> all;private final Map<String,Merchant> byId;private final Map<Chunk,List<Merchant>> chunks;
    private EconomyMerchants(List<Merchant> merchants){all=List.copyOf(merchants);var index=new LinkedHashMap<String,Merchant>();for(var m:all)if(index.put(m.id(),m)!=null)throw new IllegalArgumentException("ECON_MERCHANT_DUPLICATE");byId=Map.copyOf(index);var grouped=new HashMap<Chunk,List<Merchant>>();for(var merchant:all){var point=merchant.point();grouped.computeIfAbsent(new Chunk(((int)Math.floor(point.x()))>>4,((int)Math.floor(point.z()))>>4),key->new ArrayList<>()).add(merchant);}var frozen=new HashMap<Chunk,List<Merchant>>();grouped.forEach((key,value)->frozen.put(key,List.copyOf(value)));chunks=Map.copyOf(frozen);}
    public List<Merchant> inChunk(int x,int z){return chunks.getOrDefault(new Chunk(x,z),List.of());}
    public List<Merchant> all(){return all;}public Merchant first(){return all.getFirst();}
    public Merchant get(String id){var found=byId.get(id);if(found==null)throw new IllegalArgumentException("ECON_MERCHANT_MISSING");return found;}
    private static double coordinate(Properties p,String key){return Double.parseDouble(Objects.requireNonNull(p.getProperty(key),key).strip());}
    public static EconomyMerchants parse(Properties p,EconomyContent content){
        String raw=p.getProperty("merchants");var result=new ArrayList<Merchant>();
        if(raw==null){result.add(new Merchant("legacy_merchant",Objects.requireNonNull(p.getProperty("merchant-name"),"merchant-name"),new Point(coordinate(p,"merchant-x"),coordinate(p,"merchant-y"),coordinate(p,"merchant-z")),EconomyShop.parse(p,content)));}
        else{var ids=Arrays.stream(raw.split(",",-1)).map(String::strip).toList();if(ids.isEmpty()||ids.size()>128||new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("ECON_MERCHANT_DUPLICATE");
            for(String id:ids){EconomyContent.key(id);String prefix="merchant."+id+".";var selected=new Properties();selected.putAll(p);selected.setProperty("merchant-region",Objects.requireNonNull(p.getProperty(prefix+"region"),prefix+"region"));selected.setProperty("merchant-goods",Objects.requireNonNull(p.getProperty(prefix+"goods"),prefix+"goods"));
                result.add(new Merchant(id,Objects.requireNonNull(p.getProperty(prefix+"name"),prefix+"name"),new Point(coordinate(p,prefix+"x"),coordinate(p,prefix+"y"),coordinate(p,prefix+"z")),EconomyShop.parse(selected,content)));}}
        return new EconomyMerchants(result);
    }
}
