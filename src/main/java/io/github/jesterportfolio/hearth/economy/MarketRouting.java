package io.github.jesterportfolio.hearth.economy;

import java.util.*;
import static io.github.jesterportfolio.hearth.economy.EconomyContent.*;
import static io.github.jesterportfolio.hearth.economy.EconomyEngine.*;

/** 부족량/도착 예정량/현지 소비·제조 재료를 고려하는 결정적 운송 에이전트입니다. 재고를 생성하지 않습니다. */
public final class MarketRouting {
    public record Policy(int reserveBps,int bufferTicks){public Policy{if(reserveBps<0||reserveBps>10000||bufferTicks<1||bufferTicks>16)throw new IllegalArgumentException("MARKET_ROUTING_POLICY");}}
    private MarketRouting(){}
    private static long add(long a,long b){return Math.min(MAX_STOCK,Math.addExact(a,b));}
    private static long multiply(long a,long b){return a==0||b==0?0:a>MAX_STOCK/b?MAX_STOCK:a*b;}
    public static Map<Key,Long> requirements(EconomyContent c,State state,long tick,int buffer){var result=new HashMap<Key,Long>();for(var key:c.cells())result.put(key,multiply(demandFor(c,state,tick,key),buffer));for(var facility:c.facilities()){long batches=productionCapacity(c,state,tick,facility);for(var input:facility.inputs().entrySet()){var key=new Key(facility.region(),input.getKey());result.put(key,add(result.get(key),multiply(multiply(batches,input.getValue()),buffer)));}}return result;}
    public static Map<String,Long> plan(EconomyContent c,State state,long tick,Map<Key,Long> stock,List<Shipment> transit,Policy policy){
        if(!stock.keySet().equals(new HashSet<>(c.cells())))throw new IllegalArgumentException("MARKET_ROUTING_CELLS");var use=requirements(c,state,tick,policy.bufferTicks());var need=new HashMap<Key,Long>();var remaining=new HashMap<Key,Long>();for(var key:c.cells()){long target=c.goods().get(key.good()).target();need.put(key,Math.max(0,add(target,use.get(key))-stock.get(key)));long reserve=Math.max((target*policy.reserveBps()+9999)/10000,use.get(key));remaining.put(key,Math.max(0,stock.get(key)-reserve));}for(var shipment:transit){var key=new Key(shipment.to(),shipment.good());need.computeIfPresent(key,(k,n)->Math.max(0,n-Math.min(n,shipment.quantity())));}
        var grouped=new TreeMap<Key,List<Route>>();var quotas=new HashMap<String,Long>();for(var route:c.routes()){quotas.put(route.id(),0L);if(effectFactor(c,state,tick,"ROUTE",route.id())!=0)grouped.computeIfAbsent(new Key(route.from(),route.good()),k->new ArrayList<>()).add(route);}
        for(var group:grouped.entrySet()){var routes=group.getValue().stream().sorted(Comparator.comparing(Route::id)).toList();var requests=new LinkedHashMap<String,Long>();long total=0;for(var route:routes){long requested=Math.min(route.amount(),need.get(new Key(route.to(),route.good())));requests.put(route.id(),requested);total+=requested;}long available=Math.min(remaining.get(group.getKey()),total);if(total==0||available==0)continue;long allocated=0;for(var route:routes){long n=available*requests.get(route.id())/total;quotas.put(route.id(),n);allocated+=n;}long extra=available-allocated;int start=(int)(tick%routes.size());for(int i=0;i<routes.size()&&extra>0;i++){var route=routes.get((start+i)%routes.size());if(quotas.get(route.id())<requests.get(route.id())){quotas.put(route.id(),quotas.get(route.id())+1);extra--;}}}
        // 서로 다른 공급자의 중복 출하도 같은 목적지의 남은 부족량을 공유합니다.
        var ordered=c.routes().stream().sorted(Comparator.comparing(Route::id)).toList();if(!ordered.isEmpty()){int start=(int)(tick%ordered.size());for(int i=0;i<ordered.size();i++){var route=ordered.get((start+i)%ordered.size());var destination=new Key(route.to(),route.good());long n=Math.min(quotas.get(route.id()),need.get(destination));quotas.put(route.id(),n);need.put(destination,need.get(destination)-n);}}
        return Map.copyOf(quotas);
    }
}
