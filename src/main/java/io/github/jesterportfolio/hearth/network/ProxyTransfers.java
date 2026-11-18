package io.github.jesterportfolio.hearth.network;

import java.util.*;
import java.util.function.LongSupplier;

/** Velocity 이벤트의 순수 조정기. Player/서버 객체를 합성하지 않습니다. 같은 연결·경로·요청만 한 번 허용합니다. */
public final class ProxyTransfers {
    private static final class Link {final Object token;String current;NetworkProtocol.Frame pending;boolean permitUsed;final Map<UUID,Long> seen=new HashMap<>();Link(Object token){this.token=token;}}
    private final NetworkSettings settings;private final LongSupplier clock;private final Map<UUID,Link> links=new HashMap<>();
    public ProxyTransfers(NetworkSettings settings,LongSupplier clock){this.settings=settings;this.clock=clock;}
    public synchronized void connected(UUID player,Object token){Objects.requireNonNull(token);if(links.size()>=4096||links.containsKey(player))throw new IllegalStateException("NETWORK_CONNECTION_CONFLICT");links.put(player,new Link(token));}
    public synchronized boolean request(UUID player,Object token,String source,NetworkProtocol.Frame frame){var link=links.get(player);long remaining=frame.expiresMillis()-clock.getAsLong();if(link==null||link.token!=token||!Objects.equals(link.current,source)||link.pending!=null||frame.kind()!=NetworkProtocol.Kind.REQUEST||!frame.network().equals(settings.network())||!frame.from().equals(source)||!settings.edge(frame.from(),frame.to())||remaining<=0||remaining>20000)return false;link.seen.entrySet().removeIf(e->e.getValue()<=clock.getAsLong());if(link.seen.size()>=128||link.seen.putIfAbsent(frame.request(),frame.expiresMillis())!=null)return false;link.pending=frame;return true;}
    public synchronized boolean permit(UUID player,Object token,String previous,String target){var link=links.get(player);if(link==null||link.token!=token)return false;if(link.current==null){if(previous!=null||!target.equals(settings.lobby())||link.pending!=null||link.permitUsed)return false;link.permitUsed=true;return true;}if(!Objects.equals(previous,link.current)||link.pending==null||link.permitUsed||!link.pending.to().equals(target)||link.pending.expiresMillis()<=clock.getAsLong())return false;link.permitUsed=true;return true;}
    public synchronized boolean arrived(UUID player,Object token,String target){var link=links.get(player);if(link==null||link.token!=token)return false;if(link.current==null&&link.permitUsed&&target.equals(settings.lobby())){link.current=target;link.permitUsed=false;return true;}if(link.pending==null||!link.permitUsed||!link.pending.to().equals(target))return false;link.current=target;link.pending=null;link.permitUsed=false;return true;}
    /** 연결 실패가 확정됐고 아직 원래 서버일 때만 요청을 정리합니다. 시간 초과는 정리하지 않습니다. */
    public synchronized boolean refused(UUID player,Object token,UUID request,String current){var link=links.get(player);if(link==null||link.token!=token||link.pending==null||!link.pending.request().equals(request)||!Objects.equals(link.current,current)||clock.getAsLong()>=link.pending.expiresMillis())return false;link.pending=null;link.permitUsed=false;return true;}
    public synchronized void disconnected(UUID player,Object token){var link=links.get(player);if(link!=null&&link.token==token)links.remove(player);}
    public synchronized int connections(){return links.size();}
}
