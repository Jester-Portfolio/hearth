package io.github.jesterportfolio.hearth.network;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** 소스 저장이 확정된 뒤에만 요청합니다. 불명 결과는 동결 보존하며 자동 재시도하지 않습니다. */
public final class BackendTransfer {
    public interface Port {boolean current();void freeze(boolean value);CompletionStage<Void> save();void send(NetworkProtocol.Frame frame);void notice(String code);}
    public enum Phase{SAVING,WAITING,HOLD}
    private static final class Pending {final UUID player;final Object token;final Port port;final NetworkProtocol.Frame frame;Phase phase=Phase.SAVING;Pending(UUID p,Object t,Port port,NetworkProtocol.Frame f){player=p;token=t;this.port=port;frame=f;}}
    private final NetworkSettings settings;private final LongSupplier clock;private final Executor owner;private final Map<UUID,Pending> pending=new HashMap<>();private boolean closed;
    public BackendTransfer(NetworkSettings settings,LongSupplier clock,Executor owner){this.settings=settings;this.clock=clock;this.owner=owner;}
    public UUID begin(UUID player,Object token,Port port){if(closed||pending.containsKey(player)||pending.size()>=256||!port.current())throw new IllegalStateException("NETWORK_TRANSFER_UNAVAILABLE");var frame=new NetworkProtocol.Frame(settings.network(),UUID.randomUUID(),settings.server(),settings.destination(),Math.addExact(clock.getAsLong(),15000),NetworkProtocol.Kind.REQUEST);var request=new Pending(player,token,port,frame);port.freeze(true);pending.put(player,request);try{port.save().whenCompleteAsync((v,error)->{if(pending.get(player)!=request||closed)return;if(error!=null||!port.current()||clock.getAsLong()>=frame.expiresMillis()){hold(request,"NETWORK_SAVE_HOLD");return;}try{request.phase=Phase.WAITING;port.send(frame);}catch(RuntimeException failure){hold(request,"NETWORK_SEND_HOLD");}},owner);}catch(RuntimeException error){hold(request,"NETWORK_SAVE_HOLD");}return frame.request();}
    private void hold(Pending p,String code){p.phase=Phase.HOLD;p.port.notice(code);}
    public void response(UUID player,Object token,NetworkProtocol.Frame response){var p=pending.get(player);if(p==null||p.token!=token||p.phase!=Phase.WAITING||!p.port.current()||!p.frame.network().equals(response.network())||!p.frame.request().equals(response.request())||!p.frame.from().equals(response.from())||!p.frame.to().equals(response.to())||p.frame.expiresMillis()!=response.expiresMillis())return;if(response.kind()==NetworkProtocol.Kind.REJECTED&&clock.getAsLong()<p.frame.expiresMillis()){pending.remove(player,p);p.port.freeze(false);p.port.notice("NETWORK_TARGET_REFUSED");}else if(response.kind()==NetworkProtocol.Kind.HOLD)hold(p,"NETWORK_RESULT_HOLD");}
    public void tick(){for(var p:List.copyOf(pending.values()))if(p.phase!=Phase.HOLD&&clock.getAsLong()>=p.frame.expiresMillis())hold(p,"NETWORK_TIMEOUT_HOLD");}
    public void quit(UUID player,Object token){var p=pending.get(player);if(p!=null&&p.token==token)pending.remove(player,p);}
    public Optional<Phase> phase(UUID player){return Optional.ofNullable(pending.get(player)).map(p->p.phase);}
    public void close(){closed=true;for(var p:pending.values())if(p.port.current())hold(p,"NETWORK_CLOSED_HOLD");pending.clear();}
}
