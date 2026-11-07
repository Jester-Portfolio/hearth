package io.github.jesterportfolio.hearth.economy;

import java.util.*;
import static io.github.jesterportfolio.hearth.economy.EconomyContent.*;
import static io.github.jesterportfolio.hearth.economy.EconomyEngine.*;

/** 공개 설정을 적용한 읽기 전용 시장 정보. 계정·인벤토리·물리 월드 식별자를 DTO에 담지 않습니다. */
public final class EconomyReport {
    private EconomyReport(){}
    public record Region(String id,String name){}
    public record MarketRow(String good,String name,long stock,long target,long base,long anchor,StockStatus status,Long ask,Long bid,long demand,Flow flow){}
    public record RouteRow(String name,String from,String to,String good,long capacity,int delay,boolean blocked,long inTransit,int shipments,long nextArrival){}
    public record EventRow(String name,String description,boolean scheduled,long startsIn,long remaining){}
    public record InputRow(String name,long units,long stock){}
    public record FacilityRow(String name,String good,long capacity,long possible,String restriction,List<InputRow> inputs){public FacilityRow{inputs=List.copyOf(inputs);}}
    public record View(Region region,long tick,List<Region> regions,List<MarketRow> markets,List<RouteRow> routes,List<EventRow> events,List<FacilityRow> facilities){
        public View{regions=List.copyOf(regions);markets=List.copyOf(markets);routes=List.copyOf(routes);events=List.copyOf(events);facilities=List.copyOf(facilities);}
    }
    private record EventInfo(String name,String description,List<String> regions){private EventInfo{regions=List.copyOf(regions);}}
    public static final class Definition {
        private final List<String> regions,goods,routes,facilities;
        private final Map<String,String> regionNames,goodNames,routeNames,facilityNames;
        private final Map<String,EventInfo> events;
        private Definition(List<String> regions,List<String> goods,List<String> routes,List<String> facilities,Map<String,String> regionNames,Map<String,String> goodNames,Map<String,String> routeNames,Map<String,String> facilityNames,Map<String,EventInfo> events){
            this.regions=List.copyOf(regions);this.goods=List.copyOf(goods);this.routes=List.copyOf(routes);this.facilities=List.copyOf(facilities);this.regionNames=Map.copyOf(regionNames);this.goodNames=Map.copyOf(goodNames);this.routeNames=Map.copyOf(routeNames);this.facilityNames=Map.copyOf(facilityNames);this.events=Map.copyOf(events);
        }
        public boolean allows(String region){return regions.contains(region);}
        public List<Region> regions(){return regions.stream().map(id->new Region(id,regionNames.get(id))).toList();}
        public View project(EconomyContent c,State state,String region){return project(c,state,region,Map.of());}
        public View project(EconomyContent c,State state,String region,Map<Key,Flow> flows){
            if(!flows.isEmpty()&&(!flows.keySet().equals(state.markets().keySet())||flows.values().stream().anyMatch(f->f.tick()!=state.tick())))throw new IllegalArgumentException("ECON_FLOW_MISMATCH");
            if(!allows(region))throw new IllegalArgumentException("ECON_REPORT_REGION");
            var markets=new ArrayList<MarketRow>();
            for(String id:goods){var good=c.goods().get(id);var market=cell(state,region,id);Long ask=market.stock()>0?quote(c,id,market,1,true):null;Long bid=market.stock()<MAX_STOCK?quote(c,id,market,1,false):null;
                markets.add(new MarketRow(id,goodNames.get(id),market.stock(),good.target(),good.base(),market.price(),stockStatus(good,market),ask,bid,demandFor(c,state,state.tick(),new Key(region,id)),flows.get(new Key(region,id))));}
            var allRoutes=new HashMap<String,Route>();for(var r:c.routes())allRoutes.put(r.id(),r);
            var pending=new HashMap<String,List<Shipment>>();for(var shipment:state.shipments()){
                var route=allRoutes.get(shipment.route());if(route==null||!route.from().equals(shipment.from())||!route.to().equals(shipment.to())||!route.good().equals(shipment.good()))throw new IllegalArgumentException("ECON_REPORT_SHIPMENT");
                pending.computeIfAbsent(route.id(),key->new ArrayList<>()).add(shipment);
            }
            var routeRows=new ArrayList<RouteRow>();for(String id:routes){var r=allRoutes.get(id);if(!r.from().equals(region)&&!r.to().equals(region))continue;var shipments=pending.getOrDefault(id,List.of());long quantity=0,next=-1;
                for(var shipment:shipments){quantity=Math.addExact(quantity,shipment.quantity());long due=Math.max(0,shipment.due()-state.tick());next=next<0?due:Math.min(next,due);}
                routeRows.add(new RouteRow(routeNames.get(id),regionNames.get(r.from()),regionNames.get(r.to()),goodNames.get(r.good()),r.amount(),r.delay(),effectFactor(c,state,state.tick(),"ROUTE",id)==0,quantity,shipments.size(),next));}
            var eventRows=new ArrayList<EventRow>();for(var event:state.events()){
                var visible=events.get(event.event());if(visible==null||!visible.regions().contains(region)||event.end()<=state.tick())continue;
                eventRows.add(new EventRow(visible.name(),visible.description(),event.start()>state.tick(),Math.max(0,event.start()-state.tick()),event.end()-Math.max(state.tick(),event.start())));
            }
            var facilityRows=new ArrayList<FacilityRow>();for(var f:c.facilities())if(facilities.contains(f.id())&&f.region().equals(region)){
                var inputs=new ArrayList<InputRow>();for(var e:new TreeMap<>(f.inputs()).entrySet())inputs.add(new InputRow(goodNames.get(e.getKey()),e.getValue(),cell(state,region,e.getKey()).stock()));
                long capacity=productionCapacity(c,state,state.tick(),f),needed=Math.max(0,c.goods().get(f.good()).target()+c.demand().getOrDefault(new Key(region,f.good()),0L)-cell(state,region,f.good()).stock());
                long possible=Math.min(capacity,needed);for(var e:f.inputs().entrySet())possible=Math.min(possible,cell(state,region,e.getKey()).stock()/e.getValue());
                String restriction=capacity==0?"효율 또는 경제 사건으로 생산 중단":needed==0?"목표 재고 충족":possible<Math.min(capacity,needed)?"입력 재료 부족":"현재 재고 기준 생산 가능";
                facilityRows.add(new FacilityRow(facilityNames.get(f.id()),goodNames.get(f.good()),capacity,possible,restriction,inputs));}
            return new View(new Region(region,regionNames.get(region)),state.tick(),regions(),markets,routeRows,eventRows,facilityRows);
        }
    }
    private static Market cell(State s,String region,String good){var found=s.markets().get(new Key(region,good));if(found==null)throw new IllegalArgumentException("ECON_REPORT_MARKET_MISSING");return found;}
    private static List<String> ids(Properties p,String key,String fallback){String raw=p.getProperty(key,fallback).strip();if(raw.isEmpty())return List.of();var result=Arrays.stream(raw.split(",",-1)).map(String::strip).toList();result.forEach(EconomyContent::key);if(new HashSet<>(result).size()!=result.size())throw new IllegalArgumentException("ECON_REPORT_DUPLICATE");return result;}
    private static String label(Properties p,String key,String fallback,int max){String value=p.getProperty(key,fallback).strip();if(value.isEmpty()||value.length()>max)throw new IllegalArgumentException("ECON_REPORT_TEXT");return value;}
    private static Map<String,String> labels(Properties p,List<String> ids,String prefix){var result=new HashMap<String,String>();for(String id:ids)result.put(id,label(p,prefix+id+".name",id,80));return result;}
    public static Definition parse(Properties p,EconomyContent c,EconomyShop shop){
        var regions=ids(p,"report-regions",shop.region());var goods=ids(p,"report-goods",String.join(",",shop.goods()));var routes=ids(p,"report-routes","");var facilities=ids(p,"report-facilities","");var visibleEvents=ids(p,"report-events","");
        if(regions.isEmpty()||goods.isEmpty()||!regions.contains(shop.region())||!c.regions().containsAll(regions)||!c.goods().keySet().containsAll(goods))throw new IllegalArgumentException("ECON_REPORT_REF");
        var byRoute=new HashMap<String,Route>();for(var r:c.routes())byRoute.put(r.id(),r);for(String id:routes){var r=byRoute.get(id);if(r==null||!regions.containsAll(List.of(r.from(),r.to()))||!goods.contains(r.good()))throw new IllegalArgumentException("ECON_REPORT_ROUTE_REF");}
        var byFacility=new HashMap<String,Facility>();for(var f:c.facilities())byFacility.put(f.id(),f);for(String id:facilities){var f=byFacility.get(id);if(f==null||!regions.contains(f.region())||!goods.contains(f.good())||!goods.containsAll(f.inputs().keySet()))throw new IllegalArgumentException("ECON_REPORT_FACILITY_REF");}
        var events=new HashMap<String,EventInfo>();for(String id:visibleEvents){var scopes=ids(p,"report-event."+id+".regions","");if(!c.events().containsKey(id)||scopes.isEmpty()||!regions.containsAll(scopes))throw new IllegalArgumentException("ECON_REPORT_EVENT_REF");
            events.put(id,new EventInfo(label(p,"report-event."+id+".name",id,80),label(p,"report-event."+id+".description","공개된 경제 사건입니다.",200),scopes));}
        return new Definition(regions,goods,routes,facilities,labels(p,regions,"region."),labels(p,goods,"good."),labels(p,routes,"route."),labels(p,facilities,"facility."),events);
    }
    public static <T> List<T> page(List<T> rows,int page){if(page<0||page>=Math.max(1,(rows.size()+44)/45))throw new IllegalArgumentException("ECON_REPORT_PAGE");return List.copyOf(rows.subList(page*45,Math.min(rows.size(),(page+1)*45)));}
}
