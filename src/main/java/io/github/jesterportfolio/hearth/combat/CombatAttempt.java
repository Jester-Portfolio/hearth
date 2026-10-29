package io.github.jesterportfolio.hearth.combat;

import io.github.jesterportfolio.hearth.persistence.ServerProgressAccess.Context;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import static io.github.jesterportfolio.hearth.combat.CombatContent.*;

/** 진행별 owner 경로에서 처리하는 한 보스 도전. DB·인벤토리·개인 퀘스트를 변경하지 않습니다. */
public final class CombatAttempt {
    public enum Phase { ACTIVE,PAUSED,WIPE_WAIT,HOLD,CLOSED }
    public enum Outcome { NONE,VICTORY,REQUIRED_FAILURE,ALL_OFFLINE,RETRY,RETIRED }
    public record Entrant(Context context,Object session,Pose start) {public Entrant{Objects.requireNonNull(context);Objects.requireNonNull(session);Objects.requireNonNull(start);}}
    public record Member(UUID player,boolean online,boolean dead,boolean returning,boolean reviving,boolean protectedReturn,int used,UUID camera) {}
    public record Snapshot(UUID attempt,UUID run,long generation,long revision,Phase phase,Outcome outcome,int difficultyPlayers,int sharedUsed,int reserved,long combatMillis,String error,List<Member> members) {public Snapshot{members=List.copyOf(members);}}
    public record View(String title,String state,String personal,String revival,String camera,long revision) {public List<String> lines(){return List.of(title,state,personal,revival,camera);}}
    public interface Port {
        void freeze(boolean paused);
        void camera(UUID player,UUID target);
        boolean safe(Pose position);
        /** 외부 경계도 적용 직전에 current를 확인해야 합니다. true는 실제 적용 확인을 뜻합니다. */
        CompletionStage<Boolean> revive(UUID player,Pose position,double healthFraction,List<Effect> effects,java.util.function.BooleanSupplier current);
    }
    private static final class PlayerState {
        final UUID id;final Pose start;Object token;boolean online=true,dead,returning,reviving,cameraKnown;Pose last;int used;long protection;UUID camera;
        PlayerState(Entrant e){id=e.context().playerId();start=e.start();last=start;token=e.session();}
    }
    private static final class Request {
        final UUID player;final CompletableFuture<Void> result=new CompletableFuture<>();Request(UUID player){this.player=player;}
    }
    private final UUID id=UUID.randomUUID(),run,world;private final long generation;private final CombatContent content;private final Port port;private final Executor owner;private final Runnable check;private final LongSupplier clock;
    private final Map<UUID,PlayerState> members=new LinkedHashMap<>();private final Map<UUID,Request> requests=new HashMap<>();private Phase phase=Phase.ACTIVE;private Outcome outcome=Outcome.NONE;private long revision,lastClock,elapsed;private int sharedUsed,reserved;private String error;private boolean frozen;private volatile boolean deliveryFailed;
    public CombatAttempt(UUID run,UUID world,long generation,CombatContent content,List<Entrant> online,Port port,Executor owner,Runnable check,LongSupplier clock){
        this.run=Objects.requireNonNull(run);this.world=Objects.requireNonNull(world);this.generation=generation;this.content=content;this.port=port;this.owner=owner;this.check=check;this.clock=clock;check.run();
        if(generation<0||online.isEmpty()||online.size()>32)throw new IllegalArgumentException("Invalid combat roster");for(var e:online){if(!run.equals(e.context().storyRunId())||!world.equals(e.start().world())||members.putIfAbsent(e.context().playerId(),new PlayerState(e))!=null)throw new IllegalArgumentException("Invalid combat owner or duplicate roster");}lastClock=clock.getAsLong();port.freeze(false);
    }
    private PlayerState actor(Context ctx,Object token){check.run();if(!run.equals(ctx.storyRunId()))throw new IllegalStateException("COMBAT_WRONG_RUN");var p=members.get(ctx.playerId());if(p==null||p.token!=token||!p.online)throw new IllegalStateException("COMBAT_STALE_SESSION");return p;}
    private void open(){check.run();if(deliveryFailed)hold("COMBAT_OWNER_DELIVERY_FAILED");if(phase==Phase.CLOSED||phase==Phase.HOLD)throw new IllegalStateException("COMBAT_CLOSED_OR_HOLD");}
    private void time(){check.run();long now=clock.getAsLong();if(now<lastClock)throw new IllegalStateException("COMBAT_CLOCK_REVERSED");if(phase==Phase.ACTIVE)elapsed=Math.addExact(elapsed,now-lastClock);lastClock=now;}
    private boolean normal(PlayerState p){return p.online&&!p.dead&&!p.returning&&!p.reviving&&p.protection<=lastClock;}
    private void hold(String code){if(phase==Phase.CLOSED||phase==Phase.HOLD)return;error=code;phase=Phase.HOLD;revision++;try{port.freeze(true);frozen=true;}catch(RuntimeException ignored){}}
    private void update(){
        if(phase==Phase.CLOSED||phase==Phase.HOLD)return;Phase before=phase;for(var p:members.values())if(p.protection>0&&p.protection<=lastClock){p.protection=0;revision++;}
        if(members.values().stream().noneMatch(p->p.online)){finish(Outcome.ALL_OFFLINE);return;}
        phase=members.values().stream().anyMatch(this::normal)?Phase.ACTIVE:members.values().stream().allMatch(p->p.dead)?Phase.WIPE_WAIT:Phase.PAUSED;
        if(before!=phase)revision++;
        try{boolean stop=phase!=Phase.ACTIVE;if(stop!=frozen){port.freeze(stop);frozen=stop;}reconcileCameras();}catch(RuntimeException e){hold("COMBAT_PORT_FAILED");throw e;}
    }
    private List<UUID> survivors(){return members.values().stream().filter(this::normal).map(p->p.id).sorted().toList();}
    private void camera(PlayerState p,UUID target){if(!p.cameraKnown||!Objects.equals(p.camera,target)){port.camera(p.id,target);p.camera=target;p.cameraKnown=true;revision++;}}
    private void reconcileCameras(){var targets=survivors();for(var p:members.values())if(p.online&&p.dead&&!p.returning)camera(p,targets.contains(p.camera)?p.camera:targets.isEmpty()?null:targets.getFirst());else camera(p,null);}
    public void poll(){time();if(deliveryFailed)hold("COMBAT_OWNER_DELIVERY_FAILED");if(phase!=Phase.CLOSED&&phase!=Phase.HOLD)update();}
    public boolean playable(Context ctx,Object token){check.run();if(phase!=Phase.ACTIVE)return false;try{return normal(actor(ctx,token));}catch(RuntimeException e){return false;}}
    public void died(Context ctx,Object token,Pose position){open();time();var p=actor(ctx,token);if(!world.equals(position.world()))throw new IllegalStateException("COMBAT_WRONG_WORLD");if(p.dead)return;if(p.returning||p.reviving||p.protection>lastClock)throw new IllegalStateException("COMBAT_TRANSITION_PENDING");p.last=position;p.dead=true;p.cameraKnown=false;revision++;update();}
    public void disconnect(Context ctx,Object token,Pose position){time();if(phase==Phase.CLOSED)return;if(!run.equals(ctx.storyRunId()))throw new IllegalStateException("COMBAT_WRONG_RUN");var p=members.get(ctx.playerId());if(p==null||p.token!=token)throw new IllegalStateException("COMBAT_STALE_SESSION");if(!p.online)return;if(!world.equals(position.world()))throw new IllegalStateException("COMBAT_WRONG_WORLD");p.last=position;p.online=false;p.returning=false;revision++;update();}
    public Pose reconnect(Context ctx,Object newToken){open();time();if(!run.equals(ctx.storyRunId())||newToken==null)throw new IllegalStateException("COMBAT_WRONG_RUN");var p=members.get(ctx.playerId());if(p==null||p.online)throw new IllegalStateException("COMBAT_NEW_OR_DUPLICATE_JOIN");if(p.token==newToken)throw new IllegalStateException("COMBAT_STALE_SESSION");p.token=newToken;p.online=true;p.returning=true;revision++;update();return p.last;}
    /** 복원 전 서버 연결이 조회하는 기존 참가자의 위치입니다. 상태/세션을 변경하지 않습니다. */
    public Optional<Pose> returnPosition(Context ctx){check.run();if(!run.equals(ctx.storyRunId()))throw new IllegalStateException("COMBAT_WRONG_RUN");var p=members.get(ctx.playerId());return phase==Phase.CLOSED||phase==Phase.HOLD||p==null||p.online?Optional.empty():Optional.of(p.last);}
    public void returned(Context ctx,Object token){open();time();var p=actor(ctx,token);if(!p.returning)throw new IllegalStateException("COMBAT_NOT_RETURNING");p.returning=false;if(!p.dead)p.protection=Math.addExact(lastClock,content.returnProtectionMillis());revision++;update();}
    public Optional<UUID> watchNext(Context ctx,Object token){open();time();var p=actor(ctx,token);if(!p.dead||p.returning)throw new IllegalStateException("COMBAT_NOT_SPECTATING");var targets=survivors();UUID target=targets.isEmpty()?null:targets.get((targets.indexOf(p.camera)+1)%targets.size());try{camera(p,target);revision++;}catch(RuntimeException e){hold("COMBAT_CAMERA_FAILED");throw e;}return Optional.ofNullable(target);}
    public CompletionStage<Void> revive(Context ctx,Object token,UUID requestId,String confirmedCondition){
        check.run();if(requestId==null)return CompletableFuture.failedFuture(new IllegalStateException("COMBAT_REQUEST_REQUIRED"));var duplicate=requests.get(requestId);if(duplicate!=null){if(!run.equals(ctx.storyRunId())||!duplicate.player.equals(ctx.playerId()))return CompletableFuture.failedFuture(new IllegalStateException("COMBAT_REQUEST_CONFLICT"));try{actor(ctx,token);}catch(RuntimeException e){return CompletableFuture.failedFuture(e);}return duplicate.result;}
        final PlayerState p;final Pose position;
        try{open();time();p=actor(ctx,token);var rule=content.revival();if(!p.dead||p.returning||p.reviving||!rule.enabled()||!rule.condition().equals(confirmedCondition))throw new IllegalStateException("COMBAT_REVIVAL_DENIED");int used=rule.pool()==Pool.SHARED?sharedUsed+reserved:p.used+(p.reviving?1:0);if(used>=rule.limit())throw new IllegalStateException("COMBAT_REVIVAL_LIMIT");position=switch(rule.location()){case DEATH->p.last;case START->p.start;case FIXED->new Pose(world,rule.fixed().x(),rule.fixed().y(),rule.fixed().z(),p.last.yaw(),p.last.pitch());};if(!port.safe(position))throw new IllegalStateException("COMBAT_UNSAFE_REVIVAL");}
        catch(RuntimeException e){return CompletableFuture.failedFuture(e);}
        var request=new Request(p.id);requests.put(Objects.requireNonNull(requestId),request);p.reviving=true;reserved++;revision++;
        java.util.function.BooleanSupplier valid=()->{check.run();return phase!=Phase.CLOSED&&phase!=Phase.HOLD&&p.online&&p.token==token&&p.reviving;};
        CompletionStage<Boolean> work;try{var rule=content.revival();work=Objects.requireNonNull(port.revive(p.id,position,rule.healthFraction(),rule.effects(),valid));}catch(RuntimeException e){work=CompletableFuture.failedFuture(e);}
        work.whenCompleteAsync((ok,failure)->{if(request.result.isDone())return;time();if(!valid.getAsBoolean()||failure!=null||!Boolean.TRUE.equals(ok)){hold("COMBAT_REVIVAL_APPLY_HOLD");request.result.completeExceptionally(new IllegalStateException("COMBAT_REVIVAL_APPLY_HOLD",failure));return;}p.reviving=false;reserved--;p.used++;if(content.revival().pool()==Pool.SHARED)sharedUsed++;p.dead=false;p.protection=0;p.cameraKnown=false;update();request.result.complete(null);},owner).exceptionally(failure->{deliveryFailed=true;request.result.completeExceptionally(failure);return null;});return request.result;
    }
    /** 서버가 검증한 게임 이벤트만 호출합니다. 개인/무관 퀘스트 실패는 required=false입니다. */
    public void failedCondition(boolean required){check.run();if(required&&phase!=Phase.CLOSED){time();finish(Outcome.REQUIRED_FAILURE);}}
    public void victory(){check.run();if(phase!=Phase.CLOSED&&phase!=Phase.HOLD){time();finish(Outcome.VICTORY);}}
    public void finish(Outcome result){check.run();if(result==Outcome.NONE)throw new IllegalArgumentException("Missing combat outcome");if(phase==Phase.CLOSED)return;time();outcome=result;phase=Phase.CLOSED;revision++;try{port.freeze(true);frozen=true;for(var p:members.values())camera(p,null);}catch(RuntimeException e){error="COMBAT_CLOSE_PORT_FAILED";}for(var request:requests.values())if(!request.result.isDone())request.result.completeExceptionally(new IllegalStateException("COMBAT_CLOSED"));}
    public void invalidate(){check.run();if(phase!=Phase.CLOSED)hold("COMBAT_STALE_GENERATION");}
    public CompletionStage<Void> barrier(){check.run();return CompletableFuture.allOf(requests.values().stream().map(r->r.result).toArray(CompletableFuture[]::new)).thenRun(()->{check.run();if(error!=null)throw new IllegalStateException("COMBAT_HOLD");});}
    public Snapshot snapshot(){check.run();return new Snapshot(id,run,generation,revision,phase,outcome,members.size(),sharedUsed,reserved,elapsed,error,members.values().stream().map(p->new Member(p.id,p.online,p.dead,p.returning,p.reviving,p.protection>lastClock,p.used,p.camera)).toList());}
    public View view(Context ctx,Object token){var p=actor(ctx,token);var rule=content.revival();String state=switch(phase){case ACTIVE->"전투 진행 중";case PAUSED->"복귀 대기 · 전투 일시정지";case WIPE_WAIT->"전멸 대기 · 자동 재시작 없음";case HOLD->"전투 처리 보류";case CLOSED->"전투 종료";};String personal=p.dead?"사망 · 관전 중":p.returning?"복귀 위치 적용 대기":p.protection>lastClock?"복귀 보호 · 전투 개입 불가":"생존";int remaining=Math.max(0,rule.limit()-(rule.pool()==Pool.SHARED?sharedUsed+reserved:p.used+(p.reviving?1:0)));return new View(content.title(),state,personal,!rule.enabled()?"부활 불가":(rule.pool()==Pool.SHARED?"공동":"개인")+" 부활 남음: "+remaining,p.camera==null?"관전 대상 없음 · 대기":"생존 동료 관전 중",revision);}
}
