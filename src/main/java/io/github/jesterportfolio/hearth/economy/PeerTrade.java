package io.github.jesterportfolio.hearth.economy;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 양쪽이 같은 최종 제안 revision에 동의해야만 확정 가능한 일시적 거래. escrow는 없습니다. */
public final class PeerTrade {
    public enum State{INVITED,OPEN,COMMITTING,DONE,CANCELLED,HOLD}
    public record Stack(String hash,int quantity){public Stack{if(hash==null||!hash.matches("[a-f0-9]{64}")||quantity<1||quantity>4096)throw new IllegalArgumentException("ECON_PEER_STACK");}}
    public record Offer(Map<String,Integer> goods,long money,Map<Integer,Stack> items){
        public Offer(Map<String,Integer> goods,long money){this(goods,money,Map.of());}
        public Offer{items=Map.copyOf(items);if(items.size()>36||items.keySet().stream().anyMatch(slot->slot<0||slot>=36))throw new IllegalArgumentException("ECON_PEER_SLOT");goods=Map.copyOf(goods);if(money<0||goods.size()>64||goods.entrySet().stream().anyMatch(e->!e.getKey().matches("[a-z][a-z0-9_]{0,63}")||e.getValue()<1||e.getValue()>4096))throw new IllegalArgumentException("ECON_PEER_OFFER");}
        public static Offer empty(){return new Offer(Map.of(),0);}
        public String canonical(){var text=new StringBuilder(Long.toString(money));new TreeMap<>(goods).forEach((id,n)->text.append('/').append(id).append(':').append(n));new TreeMap<>(items).forEach((slot,item)->text.append("/slot:").append(slot).append(":").append(item.hash()).append(":").append(item.quantity()));return text.toString();}
        public boolean emptyValue(){return money==0&&goods.isEmpty()&&items.isEmpty();}
    }
    private final UUID id,realm,first,second;private State state=State.INVITED;private long revision;private Offer left=Offer.empty(),right=Offer.empty();private final Set<UUID> approvals=new HashSet<>();
    public PeerTrade(UUID id,UUID realm,UUID first,UUID second){this.id=Objects.requireNonNull(id);this.realm=Objects.requireNonNull(realm);this.first=Objects.requireNonNull(first);this.second=Objects.requireNonNull(second);if(first.equals(second))throw new IllegalArgumentException("ECON_PEER_SELF");}
    public UUID id(){return id;}public UUID realm(){return realm;}public UUID first(){return first;}public UUID second(){return second;}public long revision(){return revision;}public State state(){return state;}public Set<UUID> approvals(){return Set.copyOf(approvals);}
    private void actor(UUID player){if(!first.equals(player)&&!second.equals(player))throw new SecurityException("ECON_PEER_ACTOR");}
    public Offer offer(UUID player){actor(player);return first.equals(player)?left:right;}
    public void accept(UUID player){actor(player);if(!second.equals(player)||state!=State.INVITED)throw new IllegalStateException("ECON_PEER_INVITATION");state=State.OPEN;}
    public void edit(UUID player,Offer offer){actor(player);if(state!=State.OPEN)throw new IllegalStateException("ECON_PEER_CLOSED");Objects.requireNonNull(offer);if(offer(player).equals(offer))return;if(first.equals(player))left=offer;else right=offer;revision=Math.incrementExact(revision);approvals.clear();}
    public boolean confirm(UUID player,long seen){actor(player);if(state!=State.OPEN||seen!=revision)throw new IllegalStateException("ECON_PEER_CHANGED");if(left.emptyValue()&&right.emptyValue())throw new IllegalStateException("ECON_PEER_EMPTY");approvals.add(player);if(approvals.size()==2){state=State.COMMITTING;return true;}return false;}
    public void cancel(UUID player){actor(player);if(state==State.INVITED||state==State.OPEN){state=State.CANCELLED;approvals.clear();}else if(state==State.COMMITTING)state=State.HOLD;}
    public void complete(boolean success){if(state!=State.COMMITTING&&state!=State.HOLD)throw new IllegalStateException("ECON_PEER_RESULT");state=success&&state==State.COMMITTING?State.DONE:State.HOLD;}
    public void refused(){if(state!=State.COMMITTING&&state!=State.HOLD)throw new IllegalStateException("ECON_PEER_RESULT");state=State.CANCELLED;approvals.clear();}
    public record Balances(long first,long second){}
    public static Balances balances(long first,long second,Offer left,Offer right){
        if(first<0||second<0||first<left.money()||second<right.money())throw new IllegalArgumentException("ECON_PEER_FUNDS");
        try{return new Balances(Math.addExact(first-left.money(),right.money()),Math.addExact(second-right.money(),left.money()));}catch(ArithmeticException overflow){throw new IllegalArgumentException("ECON_PEER_FUNDS");}
    }
    public String fingerprint(){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((realm+"/"+first+"/"+second+"/"+left.canonical()+"/"+right.canonical()).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
