package io.github.jesterportfolio.hearth.economy;

import java.util.*;
import java.nio.charset.StandardCharsets;
import static io.github.jesterportfolio.hearth.economy.EconomyContent.*;
import static io.github.jesterportfolio.hearth.economy.EconomyEngine.*;

/** 효용 기반 경제 에이전트. LLM은 서버가 새로 검증한 후보 중 하나만 추천합니다. */
public final class MarketAutomation {
    public enum Mode{BALANCED,RESTOCK,CONSERVE}
    public enum Source{INITIAL,RULE,LLM}
    public record Policy(int interval,int confirmations,int minimumHold,int reserveLow,int reserveNormal,int reserveHigh,int buffer,int lowStockBps){
        public Policy{if(interval<1||interval>1200||confirmations<1||confirmations>8||minimumHold<interval||minimumHold>24000||reserveLow<0||reserveLow>reserveNormal||reserveNormal>reserveHigh||reserveHigh>10000||buffer<1||buffer>8||lowStockBps<1000||lowStockBps>9000)throw new IllegalArgumentException("MARKET_POLICY");}
        public String fingerprint(){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(("market-v1/"+toString()).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
        public MarketRouting.Policy routing(Mode mode){return switch(mode){case BALANCED->new MarketRouting.Policy(reserveNormal,buffer);case RESTOCK->new MarketRouting.Policy(reserveLow,Math.min(16,buffer*2));case CONSERVE->new MarketRouting.Policy(reserveHigh,1);};}
    }
    public record Observation(long tick,int sourcesLow,int destinationsLow,int routesReady,long unmetDemand,long transit,List<Mode> candidates){public Observation{candidates=List.copyOf(candidates);if(tick<0||sourcesLow<0||destinationsLow<0||routesReady<0||unmetDemand<0||transit<0||candidates.isEmpty()||candidates.size()>3||new HashSet<>(candidates).size()!=candidates.size())throw new IllegalArgumentException("MARKET_OBSERVATION");}}
    public record Cursor(long tick,Mode mode,long since,Mode candidate,int votes,long revision){public Cursor{Objects.requireNonNull(mode);Objects.requireNonNull(candidate);if(tick<0||since<0||since>tick||votes<0||votes>8||revision<0)throw new IllegalArgumentException("MARKET_CURSOR");}}
    public record Advice(Mode mode,long tick,String policyHash){public Advice{Objects.requireNonNull(mode);if(tick<0||policyHash==null||!policyHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("MARKET_ADVICE");}}
    public record Decision(Cursor cursor,Observation observation,Mode rule,Source source,boolean changed){}
    private MarketAutomation(){}
    public static Observation observe(EconomyContent c,State state,Map<Key,Flow> flows,Policy policy){var sources=new HashSet<Key>();var destinations=new HashSet<Key>();int ready=0;var requirements=MarketRouting.requirements(c,state,state.tick()+1,1);for(var route:c.routes()){var from=new Key(route.from(),route.good());var to=new Key(route.to(),route.good());if(state.markets().get(from).stock()*10000<c.goods().get(from.good()).target()*policy.lowStockBps()||state.markets().get(from).stock()<requirements.get(from))sources.add(from);if(state.markets().get(to).stock()*10000<c.goods().get(to.good()).target()*policy.lowStockBps())destinations.add(to);if(effectFactor(c,state,state.tick()+1,"ROUTE",route.id())!=0&&state.markets().get(from).stock()>requirements.get(from))ready++;}long unmet=0,transit=0;for(var f:flows.values())unmet=Math.min(MAX_STOCK,unmet+Math.min(MAX_STOCK,f.shortage()));for(var shipment:state.shipments())transit=Math.min(MAX_STOCK,transit+Math.min(MAX_STOCK,shipment.quantity()));List<Mode> choices=!sources.isEmpty()?List.of(Mode.CONSERVE,Mode.BALANCED):!destinations.isEmpty()&&ready>0?List.of(Mode.RESTOCK,Mode.BALANCED):List.of(Mode.BALANCED);return new Observation(state.tick(),sources.size(),destinations.size(),ready,unmet,transit,choices);}
    public static Decision decide(EconomyContent c,State state,Map<Key,Flow> flows,Policy policy,Cursor cursor,Optional<Advice> advice){if(cursor.tick()!=state.tick())throw new IllegalArgumentException("MARKET_CURSOR_STALE");long next=state.tick()+1;var observation=observe(c,state,flows,policy);Mode rule=observation.candidates().getFirst();if(next%policy.interval()!=0)return new Decision(new Cursor(next,cursor.mode(),cursor.since(),cursor.candidate(),cursor.votes(),cursor.revision()+1),observation,rule,Source.RULE,false);
        var selected=rule;Source source=Source.RULE;if(advice.isPresent()){var a=advice.get();if(a.policyHash().equals(policy.fingerprint())&&a.tick()<=state.tick()&&state.tick()-a.tick()<=policy.interval()&&observation.candidates().contains(a.mode())){selected=a.mode();source=Source.LLM;}}
        int votes=selected==cursor.candidate()?Math.min(policy.confirmations(),cursor.votes()+1):1;boolean change=selected!=cursor.mode()&&votes>=policy.confirmations()&&next-cursor.since()>=policy.minimumHold();Mode mode=change?selected:cursor.mode();long since=change?next:cursor.since();return new Decision(new Cursor(next,mode,since,selected,votes,cursor.revision()+1),observation,rule,source,change);
    }
}
