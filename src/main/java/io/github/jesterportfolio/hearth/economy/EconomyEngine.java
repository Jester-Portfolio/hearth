package io.github.jesterportfolio.hearth.economy;

import java.util.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import static io.github.jesterportfolio.hearth.economy.EconomyContent.*;

/** 결정적 정수 수량·고정 소수 화폐. 가격 계산과 Tick은 Bukkit/SQL에 의존하지 않습니다. */
public final class EconomyEngine {
    private EconomyEngine(){}
    public static final long MAX_STOCK=1_000_000_000_000L;
    private static final MathContext MC=MathContext.DECIMAL128;
    public record Market(long stock,long price,long revision){public Market{if(stock<0||stock>MAX_STOCK||price<1||price>10000000000L||revision<0)throw new IllegalArgumentException("ECON_MARKET");}}
    public record Shipment(UUID request,String route,String from,String to,String good,long quantity,long due){public Shipment{if(request==null||quantity<1||due<1)throw new IllegalArgumentException("ECON_SHIPMENT");}}
    public record Active(UUID request,String event,long start,long end){public Active{if(request==null||start<0||end<=start)throw new IllegalArgumentException("ECON_ACTIVE_EVENT");}}
    public record State(long tick,Map<Key,Market> markets,List<Shipment> shipments,List<Active> events){public State{if(tick<0)throw new IllegalArgumentException("ECON_TICK");markets=Map.copyOf(markets);shipments=List.copyOf(shipments);events=events.stream().sorted(Comparator.comparing(Active::request)).toList();}}
    public record Step(State state,List<Shipment> departed,List<UUID> arrived,List<UUID> expired,Map<Key,Long> shortages){public Step{departed=List.copyOf(departed);arrived=List.copyOf(arrived);expired=List.copyOf(expired);shortages=Map.copyOf(shortages);}}
    public static State initial(EconomyContent c){var rows=new TreeMap<Key,Market>();for(var key:c.cells()){var good=c.goods().get(key.good());rows.put(key,new Market(good.target(),good.base(),0));}return new State(0,rows,List.of(),List.of());}
    private static void change(Map<Key,Long> stock,Key key,long delta){long n=Math.addExact(stock.get(key),delta);if(n<0||n>MAX_STOCK)throw new IllegalStateException("ECON_STOCK_LIMIT");stock.put(key,n);}
    private static int factor(EconomyContent c,State s,long tick,String kind,String target){int factor=10000;for(var active:s.events())if(active.start()<=tick&&tick<active.end()){var e=c.events().get(active.event());if(e==null)throw new IllegalStateException("ECON_EVENT_MISSING");if(e.kind().equals(kind)&&e.target().equals(target))factor=(int)Math.min(30000,(long)factor*e.factor()/10000);}return factor;}
    private static double supply(EconomyContent c,State state,long tick,Key key,Map<Key,Double> cache,Set<Key> visiting){
        if(cache.containsKey(key))return cache.get(key);if(!visiting.add(key))return 1;double capacity=0,available=0;
        for(var f:c.facilities())if(f.region().equals(key.region())&&f.good().equals(key.good())){double input=1;for(String good:f.inputs().keySet())input=Math.min(input,supply(c,state,tick,new Key(f.region(),good),cache,visiting));double weight=f.capacity()*(f.efficiency()/10000.0);capacity+=weight;available+=weight*input*factor(c,state,tick,"PRODUCTION",f.id())/10000.0;}
        for(var r:c.routes())if(r.to().equals(key.region())&&r.good().equals(key.good())){capacity+=r.amount();available+=r.amount()*supply(c,state,tick,new Key(r.from(),r.good()),cache,visiting)*factor(c,state,tick,"ROUTE",r.id())/10000.0;}
        visiting.remove(key);double value=capacity==0?1:Math.clamp(available/capacity,0,3);cache.put(key,value);return value;
    }
    public static Step advance(UUID realm,EconomyContent c,State s){
        if(!s.markets().keySet().equals(new HashSet<>(c.cells())))throw new IllegalArgumentException("ECON_CELL_MISMATCH");long tick=Math.incrementExact(s.tick());var stock=new TreeMap<Key,Long>();s.markets().forEach((k,v)->stock.put(k,v.stock()));var pending=new ArrayList<Shipment>();var arrived=new ArrayList<UUID>();
        for(var shipment:s.shipments())if(shipment.due()<=tick){change(stock,new Key(shipment.to(),shipment.good()),shipment.quantity());arrived.add(shipment.request());}else pending.add(shipment);
        for(var f:c.facilities()){
            var key=new Key(f.region(),f.good());long batches=f.capacity()*f.efficiency()/10000*factor(c,s,tick,"PRODUCTION",f.id())/10000;
            long needed=Math.max(0,c.goods().get(f.good()).target()+c.demand().getOrDefault(key,0L)-stock.get(key));batches=Math.min(batches,needed);
            for(var input:f.inputs().entrySet())batches=Math.min(batches,stock.get(new Key(f.region(),input.getKey()))/input.getValue());
            for(var input:f.inputs().entrySet())change(stock,new Key(f.region(),input.getKey()),-Math.multiplyExact(batches,input.getValue()));change(stock,key,batches);
        }
        var shortage=new TreeMap<Key,Long>();for(var key:c.cells()){long requested=c.demand().getOrDefault(key,0L)*factor(c,s,tick,"DEMAND",key.region())/10000;long consumed=Math.min(stock.get(key),requested);change(stock,key,-consumed);shortage.put(key,requested-consumed);}
        var departed=new ArrayList<Shipment>();for(var route:c.routes()){if(factor(c,s,tick,"ROUTE",route.id())==0)continue;var from=new Key(route.from(),route.good());long amount=Math.min(stock.get(from),route.amount());if(amount==0)continue;change(stock,from,-amount);var id=UUID.nameUUIDFromBytes((realm+"/"+tick+"/"+route.id()).getBytes(StandardCharsets.UTF_8));var shipment=new Shipment(id,route.id(),route.from(),route.to(),route.good(),amount,Math.addExact(tick,route.delay()));departed.add(shipment);pending.add(shipment);}
        var health=new HashMap<Key,Double>();var markets=new TreeMap<Key,Market>();for(var key:c.cells()){var old=s.markets().get(key);var good=c.goods().get(key.good());var p=c.policy();double pressure=factor(c,s,tick,"DEMAND",key.region())/10000.0+1-supply(c,s,tick,key,health,new HashSet<>());double desired=good.base()*Math.clamp(pressure,p.minimum()/10000.0,p.maximum()/10000.0);double smooth=old.price()+(desired-old.price())*p.smoothing()/10000.0;long delta=Math.max(1,old.price()*p.maxStep()/10000);long price=Math.clamp(Math.round(smooth),Math.max(1,old.price()-delta),Math.addExact(old.price(),delta));price=Math.clamp(price,Math.max(1,good.base()*p.minimum()/10000),Math.max(1,good.base()*p.maximum()/10000));markets.put(key,new Market(stock.get(key),price,old.revision()+1));}
        var expired=new ArrayList<UUID>();var active=new ArrayList<Active>();for(var event:s.events())if(event.end()<=tick)expired.add(event.request());else active.add(event);
        return new Step(new State(tick,markets,pending,active),departed,arrived,expired,shortage);
    }
    /** 선형인 한계가격의 구간 적분. 수량만큼 반복하지 않고 상·하한 구간을 최대 세 번 계산합니다. */
    private static BigDecimal area(EconomyContent c,Good good,Market market,long low,long high){
        var price=BigDecimal.valueOf(market.price());var b=price.multiply(BigDecimal.valueOf(c.policy().impact()),MC).divide(BigDecimal.valueOf(10000L*good.target()),MC);var a=price.add(b.multiply(BigDecimal.valueOf(good.target()),MC),MC);var min=BigDecimal.valueOf(good.base()).multiply(BigDecimal.valueOf(c.policy().minimum())).divide(BigDecimal.valueOf(10000),MC);var max=BigDecimal.valueOf(good.base()).multiply(BigDecimal.valueOf(c.policy().maximum())).divide(BigDecimal.valueOf(10000),MC);var l=BigDecimal.valueOf(low);var h=BigDecimal.valueOf(high);
        if(b.signum()==0)return price.max(min).min(max).multiply(h.subtract(l),MC);
        var upper=a.subtract(max,MC).divide(b,MC);var lower=a.subtract(min,MC).divide(b,MC);var result=BigDecimal.ZERO;var edge=h.min(upper).max(l);result=result.add(edge.subtract(l).multiply(max,MC),MC);l=edge;edge=h.min(lower).max(l);
        if(edge.compareTo(l)>0){var mid=edge.add(l).divide(BigDecimal.valueOf(2),MC);result=result.add(a.subtract(b.multiply(mid,MC),MC).multiply(edge.subtract(l),MC),MC);}l=edge;return result.add(h.subtract(l).multiply(min,MC),MC);
    }
    public static long quote(EconomyContent c,String goodId,Market market,int quantity,boolean buy){if(quantity<1||quantity>4096||buy&&market.stock()<quantity)throw new IllegalArgumentException("ECON_QUANTITY_OR_STOCK");var good=Objects.requireNonNull(c.goods().get(goodId));long low=buy?market.stock()-quantity:market.stock(),high=buy?market.stock():Math.addExact(market.stock(),quantity);if(high>MAX_STOCK)throw new IllegalArgumentException("ECON_STOCK_LIMIT");var total=area(c,good,market,low,high).multiply(BigDecimal.valueOf(10000+(buy?c.policy().spread():-c.policy().spread())),MC).divide(BigDecimal.valueOf(10000),MC);return total.setScale(0,buy?RoundingMode.CEILING:RoundingMode.FLOOR).longValueExact();}
    public static long quoteReference(EconomyContent c,String good,Market market,int quantity,boolean buy){BigDecimal total=BigDecimal.ZERO;long start=buy?market.stock()-quantity:market.stock();for(int i=0;i<quantity;i++)total=total.add(area(c,c.goods().get(good),market,start+i,start+i+1),MC);return total.multiply(BigDecimal.valueOf(10000+(buy?c.policy().spread():-c.policy().spread())),MC).divide(BigDecimal.valueOf(10000),MC).setScale(0,buy?RoundingMode.CEILING:RoundingMode.FLOOR).longValueExact();}
    public static List<String> signals(EconomyContent c,State state){var result=new ArrayList<String>();state.markets().forEach((k,v)->{long target=c.goods().get(k.good()).target();if(v.stock()<target/4)result.add(k+":SHORTAGE");if(v.stock()>target*2)result.add(k+":SURPLUS");});state.events().forEach(e->result.add("EVENT:"+e.event()));return List.copyOf(result);}
}
