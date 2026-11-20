package io.github.jesterportfolio.hearth.survival.travel;

import java.util.*;
import java.util.function.LongSupplier;

/** 접속 세대에 묶인 수락/소비 상태. 이동 중/결과 불명 요청은 다시 수락할 수 없다. */
public final class TpaRequests {
    public enum Phase{WAITING,MOVING,HOLD}
    public record Request(UUID id,UUID sender,UUID recipient,Object senderSession,Object recipientSession,long expires,Phase phase){}
    private final Map<UUID,Request> requests=new HashMap<>();private final LongSupplier clock;private final long ttl;
    public TpaRequests(LongSupplier clock,long ttl){if(ttl<15_000||ttl>300_000)throw new IllegalArgumentException("TPA_TTL");this.clock=clock;this.ttl=ttl;}
    public Request request(UUID sender,UUID recipient,Object senderSession,Object recipientSession){expire();if(sender.equals(recipient)||senderSession==null||recipientSession==null||requests.size()>=256||requests.values().stream().anyMatch(r->r.sender().equals(sender)||r.recipient().equals(sender)||r.sender().equals(recipient)||r.recipient().equals(recipient)))throw new IllegalStateException("TPA_BUSY");var r=new Request(UUID.randomUUID(),sender,recipient,senderSession,recipientSession,Math.addExact(clock.getAsLong(),ttl),Phase.WAITING);requests.put(r.id(),r);return r;}
    public Optional<Request> incoming(UUID recipient){expire();return requests.values().stream().filter(r->r.recipient().equals(recipient)&&r.phase()==Phase.WAITING).findFirst();}
    public Optional<Request> outgoing(UUID sender){expire();return requests.values().stream().filter(r->r.sender().equals(sender)&&r.phase()==Phase.WAITING).findFirst();}
    public Optional<Request> accept(UUID actor,Object senderSession,Object recipientSession,boolean eligible){return accept(actor,incoming(actor).map(Request::id).orElse(null),senderSession,recipientSession,eligible);}
    public Optional<Request> accept(UUID actor,UUID expected,Object senderSession,Object recipientSession,boolean eligible){var found=incoming(actor);if(found.isEmpty())return Optional.empty();var r=found.get();if(!r.id().equals(expected)||!eligible||r.senderSession()!=senderSession||r.recipientSession()!=recipientSession)return Optional.empty();var moving=new Request(r.id(),r.sender(),r.recipient(),r.senderSession(),r.recipientSession(),r.expires(),Phase.MOVING);requests.put(r.id(),moving);return Optional.of(moving);}
    public boolean current(Request r){return requests.get(r.id())==r&&r.phase()==Phase.MOVING&&clock.getAsLong()<r.expires();}
    public void complete(Request r,boolean unknown){if(requests.get(r.id())!=r)return;if(unknown)requests.put(r.id(),new Request(r.id(),r.sender(),r.recipient(),r.senderSession(),r.recipientSession(),r.expires(),Phase.HOLD));else requests.remove(r.id(),r);}
    public boolean cancel(UUID actor,UUID request){var r=requests.get(request);if(r==null||r.phase()!=Phase.WAITING||!r.sender().equals(actor)&&!r.recipient().equals(actor))return false;return requests.remove(request,r);}
    public void invalidate(UUID player){requests.values().removeIf(r->r.sender().equals(player)||r.recipient().equals(player));}
    public void expire(){long now=clock.getAsLong();requests.values().removeIf(r->r.phase()==Phase.WAITING&&r.expires()<=now);}
    public void close(){requests.clear();}
    public int size(){return requests.size();}
}
