package io.github.jesterportfolio.hearth.combat;

import io.github.jesterportfolio.hearth.persistence.ServerProgressAccess.Context;
import java.util.*;
import java.util.concurrent.*;

/** 종료된 도전의 복귀 권한만 보관합니다. 미접속 대기 정보에는 Player·월드·전투 객체가 없습니다. */
public final class CombatDepartures {
    public record Ticket(UUID attempt,UUID run,UUID player,UUID world,CombatContent.Exit policy,String mode) {}
    public static final class Claim {
        private final Ticket ticket;private final Object session;
        private Claim(Ticket ticket,Object session){this.ticket=ticket;this.session=session;}
        public Ticket ticket(){return ticket;}
        boolean matches(Context context,Object session){return this.session==session&&ticket.run().equals(context.storyRunId())&&ticket.player().equals(context.playerId());}
    }
    private final UUID run,attempt;private final Map<UUID,Ticket> pending=new HashMap<>();private final Map<UUID,Claim> claims=new HashMap<>();private final Set<UUID> held=new HashSet<>();
    public CombatDepartures(CombatAttempt.Snapshot state,UUID world,CombatContent.Exit policy,Map<UUID,String> modes){
        if(!CombatReturn.ended(state)||world==null||policy==null||state.members().isEmpty()||modes.size()!=state.members().size())throw new IllegalArgumentException("COMBAT_DEPARTURE_SOURCE");run=state.run();attempt=state.attempt();
        for(var member:state.members()){
            String mode=modes.get(member.player());if(mode==null||!Set.of("SURVIVAL","ADVENTURE").contains(mode))throw new IllegalArgumentException("COMBAT_DEPARTURE_MODE");
            pending.put(member.player(),new Ticket(attempt,run,member.player(),world,policy,mode));
        }
    }
    /** 종료 정리에서 기존 실패나 미완료 작업을 버리지 않습니다. 동기 대기는 하지 않습니다. */
    static boolean settled(List<CompletionStage<Void>> barriers){
        boolean done=true;for(var barrier:barriers){var future=barrier.toCompletableFuture();if(future.isCompletedExceptionally())throw new IllegalStateException("COMBAT_DEPARTURE_SAVE_HOLD");done&=future.isDone();}return done;
    }
    public UUID run(){return run;}
    public UUID attempt(){return attempt;}
    boolean overlaps(CombatDepartures other){return run.equals(other.run)&&pending.keySet().stream().anyMatch(other.pending::containsKey);}
    public boolean pending(Context context){return run.equals(context.storyRunId())&&pending.containsKey(context.playerId());}
    public boolean empty(){return pending.isEmpty();}
    public Claim claim(Context context,Object session){
        if(session==null||!pending(context)||held.contains(context.playerId())||claims.containsKey(context.playerId()))throw new IllegalStateException("COMBAT_DEPARTURE_NOT_READY");
        var claim=new Claim(pending.get(context.playerId()),session);claims.put(context.playerId(),claim);return claim;
    }
    public boolean current(Claim claim,Context context,Object session){return claim!=null&&claim.matches(context,session)&&claims.get(context.playerId())==claim&&pending.get(context.playerId())==claim.ticket()&&!held.contains(context.playerId());}
    public void complete(Claim claim){
        UUID player=claim.ticket().player();if(claims.get(player)!=claim||held.contains(player)||pending.get(player)!=claim.ticket())throw new IllegalStateException("COMBAT_DEPARTURE_STALE");
        claims.remove(player);pending.remove(player);
    }
    public void hold(Claim claim){if(claims.get(claim.ticket().player())==claim)held.add(claim.ticket().player());}
}
