package io.github.jesterportfolio.hearth.survival;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** 같은 플레이어를 이동합니다. 아이템/경험치/체력의 별도 캡처·적용·복원 경로는 없습니다. */
public final class WorldTravel implements AutoCloseable {
    public interface Port {boolean current();boolean atOrigin();void freeze(boolean value);CompletionStage<Object> prepare(SurvivalWorld target);CompletionStage<Boolean> move(Object prepared);boolean atTarget(Object prepared);void notice(String code);}
    public enum Phase{PREPARING,MOVING,HOLD}
    private static final class Pending {final Object token;final Port port;final long deadline;Phase phase=Phase.PREPARING;Object target;Pending(Object token,Port port,long deadline){this.token=token;this.port=port;this.deadline=deadline;}}
    private final Map<UUID,Pending> pending=new HashMap<>();private final Executor owner;private final LongSupplier clock;private boolean closed;
    public WorldTravel(Executor owner,LongSupplier clock){this.owner=owner;this.clock=clock;}
    public void begin(UUID player,Object token,SurvivalWorld target,Port port){if(closed||pending.size()>=256||pending.containsKey(player)||!port.current())throw new IllegalStateException("WORLD_TRAVEL_BUSY");var work=new Pending(token,port,Math.addExact(clock.getAsLong(),10000));port.freeze(true);pending.put(player,work);try{port.prepare(target).whenCompleteAsync((ready,error)->{if(pending.get(player)!=work||closed)return;if(error!=null||ready==null){refuse(player,work,"WORLD_TARGET_UNAVAILABLE");return;}if(!work.port.current()||!work.port.atOrigin()||clock.getAsLong()>=work.deadline){hold(work,"WORLD_TRAVEL_STALE");return;}work.target=Objects.requireNonNull(ready);work.phase=Phase.MOVING;try{port.move(ready).whenCompleteAsync((ok,failure)->{if(pending.get(player)!=work||closed)return;if(failure!=null){hold(work,"WORLD_MOVE_UNKNOWN");return;}if(work.phase==Phase.MOVING&&clock.getAsLong()<work.deadline&&Boolean.TRUE.equals(ok)&&port.current()&&port.atTarget(ready)){pending.remove(player,work);port.freeze(false);port.notice("WORLD_MOVE_COMPLETE");}else refuse(player,work,"WORLD_MOVE_REFUSED");},owner);}catch(RuntimeException failure){hold(work,"WORLD_MOVE_UNKNOWN");}},owner);}catch(RuntimeException failure){refuse(player,work,"WORLD_TARGET_UNAVAILABLE");}}
    private void hold(Pending p,String code){p.phase=Phase.HOLD;p.port.notice(code);}
    private void refuse(UUID player,Pending p,String code){if(p.port.current()&&p.port.atOrigin()&&p.phase!=Phase.HOLD){pending.remove(player,p);p.port.freeze(false);p.port.notice(code);}else hold(p,"WORLD_MOVE_UNKNOWN");}
    public boolean moving(UUID player,Object token,Object target){var p=pending.get(player);return !closed&&p!=null&&p.token==token&&p.phase==Phase.MOVING&&p.target==target&&p.port.current()&&clock.getAsLong()<p.deadline;}
    public boolean busy(UUID player){return pending.containsKey(player);}
    public Optional<Phase> phase(UUID player){return Optional.ofNullable(pending.get(player)).map(p->p.phase);}
    public void quit(UUID player,Object token){var p=pending.get(player);if(p!=null&&p.token==token)pending.remove(player,p);}
    public void tick(){for(var p:List.copyOf(pending.values()))if(p.phase!=Phase.HOLD&&clock.getAsLong()>=p.deadline)hold(p,"WORLD_MOVE_TIMEOUT");}
    @Override public void close(){closed=true;pending.clear();}
}
