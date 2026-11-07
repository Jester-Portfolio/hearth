package io.github.jesterportfolio.hearth.economy;

import java.util.*;
import static io.github.jesterportfolio.hearth.economy.EconomyContent.*;

/** 공개 DTO의 공급망/안내만 생성합니다. 거래·발견·퀘스트 진행을 발생시키지 않습니다. */
public final class EconomySupply {
    private EconomySupply(){}
    public enum Section { MARKET, PRODUCER, USE, ROUTE }
    public record Good(String id,String name){}
    public record Node(Section section,String region,String reference,String title,List<String> lines){public Node{lines=List.copyOf(lines);}}
    public record Step(String title,String text,Node target){}
    public record View(String good,String name,long tick,List<Good> goods,List<Node> nodes,List<Step> steps){public View{goods=List.copyOf(goods);nodes=List.copyOf(nodes);steps=List.copyOf(steps);}}
    private record Instruction(Section section,String region,String reference,String title,String text){}
    public static final class Plan {
        private final List<Instruction> instructions;
        private Plan(List<Instruction> instructions){this.instructions=List.copyOf(instructions);}
        public int size(){return instructions.size();}
    }
    private static String text(Properties p,String key,int maximum){String value=Objects.requireNonNull(p.getProperty(key),key).strip();if(value.isEmpty()||value.length()>maximum)throw new IllegalArgumentException("ECON_GUIDE_TEXT");return value;}
    public static Plan parse(Properties p,EconomyContent content,EconomyReport.Definition definition){
        String raw=p.getProperty("guide-steps","").strip();if(raw.isEmpty())return new Plan(List.of());var ids=Arrays.stream(raw.split(",",-1)).map(String::strip).toList();if(ids.size()>32||new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("ECON_GUIDE_DUPLICATE");
        var result=new ArrayList<Instruction>();for(String id:ids){EconomyContent.key(id);String prefix="guide-step."+id+".";Section kind=Section.valueOf(text(p,prefix+"kind",20));String region=text(p,prefix+"region",64),ref=text(p,prefix+"reference",64);EconomyContent.key(region);EconomyContent.key(ref);
            boolean valid=definition.allows(region)&&switch(kind){case MARKET->definition.allowsGood(ref);case PRODUCER,USE->definition.allowsFacility(ref)&&content.facilities().stream().anyMatch(f->f.id().equals(ref)&&f.region().equals(region));case ROUTE->definition.allowsRoute(ref)&&content.routes().stream().anyMatch(r->r.id().equals(ref)&&(r.from().equals(region)||r.to().equals(region)));};
            if(!valid)throw new IllegalArgumentException("ECON_GUIDE_REF");result.add(new Instruction(kind,region,ref,text(p,prefix+"title",80),text(p,prefix+"text",240)));}
        return new Plan(result);
    }
    private static String price(Long value){return value==null?"거래 불가":value.toString();}
    private static Node market(EconomyReport.View view,EconomyReport.MarketRow row,EconomyMerchants merchants){
        var lines=new ArrayList<String>();lines.add("지역: "+view.region().name());lines.add("재고: "+row.stock()+" / 목표: "+row.target());lines.add("1개 구매: "+price(row.ask())+" / 판매: "+price(row.bid()));lines.add("현재 수요 설정: "+row.demand());
        if(row.flow()!=null){var f=row.flow();lines.add("완료 Tick "+f.tick()+" · 생산 "+f.produced()+" / 소비 "+f.consumed());lines.add("도착 "+f.arrived()+" / 출발 "+f.departed()+" / 제조 투입 "+f.inputs());}
        else lines.add("완료 Tick 실적 미등록");
        boolean found=false;for(var merchant:merchants.all())if(merchant.shop().region().equals(view.region().id())&&merchant.shop().goods().contains(row.good())){var point=merchant.point();lines.add("취급 상인: "+merchant.name()+" ("+point.x()+", "+point.y()+", "+point.z()+")");found=true;}
        if(!found)lines.add("등록된 취급 상인 없음");lines.add("다른 지역 상인은 직접 방문해야 합니다");return new Node(Section.MARKET,view.region().id(),row.good(),view.region().name()+" · "+row.name(),lines);
    }
    private static Node facility(EconomyReport.View view,EconomyReport.FacilityRow row,Section section){
        var lines=new ArrayList<String>();lines.add("지역: "+view.region().name()+" / 생산: "+row.good());lines.add("생산 상한: "+row.capacity()+" / 현재 재고 단독 계산: "+row.possible());lines.add(row.restriction());for(var input:row.inputs())lines.add(input.name()+" · 1회 투입 "+input.units()+" / 재고 "+input.stock());lines.add("시설은 경제 Tick에서 자동 생산하며 직접 제작 버튼이 아닙니다");return new Node(section,view.region().id(),row.id(),row.name(),lines);
    }
    private static Node route(EconomyReport.View view,EconomyReport.RouteRow row){return new Node(Section.ROUTE,view.region().id(),row.id(),row.name(),List.of(row.from()+" → "+row.to()+" / "+row.good(),"신규 출발: "+(row.blocked()?"차단":"개방"),"기본 운송량: "+row.capacity()+" / 지연: "+row.delay()+" Tick","운송 중: "+row.inTransit()+" / "+row.shipments()+"건",row.nextArrival()<0?"도착 예정 물량 없음":"가장 빠른 도착까지: "+row.nextArrival()+" Tick","시장 운송은 개인 가방의 아이템과 별개입니다"));}
    public static View project(EconomyContent content,EconomyReport.Definition definition,EconomyMerchants merchants,Plan plan,EconomyEngine.State state,Map<Key,EconomyEngine.Flow> flows,String good){
        if(!definition.allowsGood(good))throw new IllegalArgumentException("ECON_SUPPLY_GOOD");var views=new LinkedHashMap<String,EconomyReport.View>();for(var region:definition.regions())views.put(region.id(),definition.project(content,state,region.id(),flows));
        var goods=new ArrayList<Good>();var first=views.values().iterator().next();for(var row:first.markets())goods.add(new Good(row.good(),row.name()));var nodes=new ArrayList<Node>();
        for(var view:views.values())for(var row:view.markets())if(row.good().equals(good))nodes.add(market(view,row,merchants));
        var facilities=new HashMap<String,Facility>();for(var f:content.facilities())facilities.put(f.id(),f);
        for(var view:views.values())for(var row:view.facilities()){var f=facilities.get(row.id());if(f.good().equals(good))nodes.add(facility(view,row,Section.PRODUCER));if(row.inputs().stream().anyMatch(i->i.good().equals(good)))nodes.add(facility(view,row,Section.USE));}
        var routes=new HashMap<String,Route>();for(var r:content.routes())routes.put(r.id(),r);var seen=new HashSet<String>();
        for(var view:views.values())for(var row:view.routes())if(routes.get(row.id()).good().equals(good)&&seen.add(row.id()))nodes.add(route(view,row));
        var steps=new ArrayList<Step>();for(var instruction:plan.instructions){var view=views.get(instruction.region());if(view==null)throw new IllegalArgumentException("ECON_GUIDE_REF");Node target=switch(instruction.section()){
            case MARKET->market(view,view.markets().stream().filter(r->r.good().equals(instruction.reference())).findFirst().orElseThrow(),merchants);
            case PRODUCER,USE->facility(view,view.facilities().stream().filter(r->r.id().equals(instruction.reference())).findFirst().orElseThrow(),instruction.section());
            case ROUTE->route(view,view.routes().stream().filter(r->r.id().equals(instruction.reference())).findFirst().orElseThrow());};steps.add(new Step(instruction.title(),instruction.text(),target));}
        return new View(good,goods.stream().filter(g->g.id().equals(good)).findFirst().orElseThrow().name(),state.tick(),goods,nodes,steps);
    }
    public static boolean localTrade(Node node,EconomyMerchants.Merchant merchant){return node.section()==Section.MARKET&&node.region().equals(merchant.shop().region())&&merchant.shop().goods().contains(node.reference());}
    public static List<String> flowLines(EconomyEngine.Flow flow){if(flow==null)return List.of("완료 Tick 실적 미등록");return List.of("완료 Tick "+flow.tick()+" · 생산 "+flow.produced()+" / 소비 "+flow.consumed(),"제조 투입 "+flow.inputs()+" / 미충족 수요 "+flow.shortage(),"도착 "+flow.arrived()+" / 출발 "+flow.departed(),"Tick 재고 "+flow.before()+" → "+flow.after());}
}
