package io.github.jesterportfolio.hearth.escort;

import io.github.jesterportfolio.hearth.quest.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;

/** owner 스레드의 개인 호위. 저장 성공 전 다음 목표를 표시하지 않고, 불명/실패는 해당 호위를 HOLD합니다. */
public final class EscortPlay implements AutoCloseable {
    public interface Actor{ServerProgressAccess.Context context();Object token();UUID world();long generation();boolean current();boolean allowed(String quest);EscortContent.Point position();}
    public interface Body{UUID id();boolean alive();EscortContent.Point position();void move(EscortContent.Point point,double speed);void stop();void remove();}
    public interface Port{Body spawn(Actor actor,EscortContent.Definition definition,EscortContent.Leg leg);}
    public enum Phase{ACTIVE,WAITING,COMPLETING,FAILING,DONE,FAILED,CLOSED,HOLD}
    private record Key(UUID run,UUID player,String quest){}
    private record Starting(Actor actor,CompletableFuture<Attempt> result){}
    public static final class Attempt{
        final Key key;final Actor actor;final UUID world;final long generation;final EscortContent.Definition definition;final EscortContent.Leg leg;final PersonalQuest.Session quest;final QuestStore.Saved expected;final Body body;
        Phase phase=Phase.ACTIVE;int waypoint=1;CompletionStage<Void> pending=CompletableFuture.completedFuture(null);String error;
        Attempt(Key key,Actor actor,EscortContent.Definition definition,EscortContent.Leg leg,PersonalQuest.Session quest,Body body){this.key=key;this.actor=actor;world=actor.world();generation=actor.generation();this.definition=definition;this.leg=leg;this.quest=quest;expected=quest.saved();this.body=body;}
        public Phase phase(){return phase;}public Body body(){return body;}public int objective(){return leg.objective();}public Object session(){return actor.token();}public CompletionStage<Void> barrier(){return pending;}public String error(){return error;}
    }
    private final EscortContent content;private final QuestCatalog catalog;private final PersonalQuest quests;private final Port port;private final Executor owner;private final Runnable check;
    private final Map<Key,Attempt> attempts=new HashMap<>();private final Map<Key,Starting> starting=new HashMap<>();private final Map<UUID,Attempt> bodies=new HashMap<>();private boolean closed;
    public EscortPlay(EscortContent content,QuestCatalog catalog,PersonalQuest quests,Port port,Executor owner,Runnable check){this.content=content;this.catalog=catalog;this.quests=quests;this.port=port;this.owner=owner;this.check=check;}
    private static Key key(Actor actor,String quest){return new Key(actor.context().storyRunId(),actor.context().playerId(),quest);}
    private boolean current(Attempt a){return !closed&&attempts.get(a.key)==a&&a.actor.current()&&a.actor.world().equals(a.world)&&a.actor.generation()==a.generation&&a.actor.allowed(a.definition.quest());}
    public CompletionStage<Attempt> start(Actor actor,String quest){return start(actor,quest,null);}
    public CompletionStage<Attempt> start(Actor actor,String quest,QuestStore.Saved expected){
        check.run();if(closed||!content.quests().containsKey(quest)||!actor.current()||!actor.allowed(quest))return CompletableFuture.failedFuture(new IllegalStateException("ESCORT_UNAVAILABLE"));var key=key(actor,quest);var pending=starting.get(key);if(pending!=null)return pending.actor.token()==actor.token()?pending.result:CompletableFuture.failedFuture(new IllegalStateException("ESCORT_START_BUSY"));
        var old=attempts.get(key);if(old!=null){if((old.phase==Phase.ACTIVE||old.phase==Phase.WAITING)&&old.actor.token()==actor.token()&&current(old)&&!old.quest.held()&&!old.quest.busy()&&old.expected.equals(old.quest.saved())&&(expected==null||expected.equals(old.expected)))return CompletableFuture.completedFuture(old);return CompletableFuture.failedFuture(new IllegalStateException("ESCORT_BUSY_OR_HOLD"));}
        if(attempts.size()+starting.size()>=32)return CompletableFuture.failedFuture(new IllegalStateException("ESCORT_LIMIT"));var result=new CompletableFuture<Attempt>();var job=new Starting(actor,result);starting.put(key,job);
        quests.open(actor.context(),quest,actor.token(),actor::current).thenApplyAsync(q->{
            if(closed||!actor.current()||!actor.allowed(quest)||q.busy()||q.held()||q.saved().phase()!=QuestStore.Phase.ACTIVE||expected!=null&&!expected.equals(q.saved()))throw new IllegalStateException("ESCORT_QUEST_REQUIRED");var definition=content.quests().get(quest);var leg=definition.leg(q.saved().objectiveIndex()).orElseThrow(()->new IllegalStateException("ESCORT_CURRENT_GOAL"));
            if(!leg.contains(actor.position())||actor.position().distance(leg.start())>4)throw new IllegalStateException("ESCORT_START_LOCATION");var world=actor.world();long generation=actor.generation();var before=q.saved();var body=Objects.requireNonNull(port.spawn(actor,definition,leg));if(!actor.current()||!actor.allowed(quest)||!world.equals(actor.world())||generation!=actor.generation()||!before.equals(q.saved())){body.remove();throw new IllegalStateException("ESCORT_STALE_SPAWN");}var attempt=new Attempt(key,actor,definition,leg,q,body);if(bodies.putIfAbsent(body.id(),attempt)!=null){body.remove();throw new IllegalStateException("ESCORT_DUPLICATE_BODY");}attempts.put(key,attempt);return attempt;
        },owner).whenCompleteAsync((value,error)->{starting.remove(key,job);if(error==null)result.complete(value);else result.completeExceptionally(error);},owner);return result;
    }
    public Optional<Attempt> attempt(Actor actor,String quest){check.run();return Optional.ofNullable(attempts.get(key(actor,quest)));}
    public void tick(){check.run();for(var a:List.copyOf(attempts.values())){
        if(a.phase!=Phase.ACTIVE&&a.phase!=Phase.WAITING)continue;try{
        if(!current(a)){stop(a);continue;}if(a.quest.held()){hold(a,"QUEST_HOLD");continue;}if(a.quest.busy()){a.body.stop();a.phase=Phase.WAITING;continue;}
        if(!a.expected.equals(a.quest.saved())){stop(a);continue;}if(!a.body.alive()||!a.leg.contains(a.body.position())){settle(a,false);continue;}
        if(!a.leg.contains(a.actor.position())||a.actor.position().distance(a.body.position())>a.definition.followDistance()){a.body.stop();a.phase=Phase.WAITING;continue;}
        a.phase=Phase.ACTIVE;var target=a.leg.path().get(a.waypoint);
        if(a.body.position().distance(target)<=1.1){if(a.waypoint+1<a.leg.path().size())a.waypoint++;else{if(a.actor.position().distance(a.leg.end())<=2)settle(a,true);else{a.body.stop();a.phase=Phase.WAITING;}continue;}}
        a.body.move(a.leg.path().get(a.waypoint),a.definition.speed());
    }catch(RuntimeException error){hold(a,"ESCORT_BODY_HOLD");}}}
    public boolean ownsBody(UUID body){check.run();return bodies.containsKey(body);}
    public void died(UUID body){check.run();var a=bodies.get(body);if(a!=null&&(a.phase==Phase.ACTIVE||a.phase==Phase.WAITING)){if(current(a))settle(a,false);else stop(a);}}
    private void settle(Attempt a,boolean success){
        if(!current(a)||a.quest.busy()||a.quest.held()||!a.expected.equals(a.quest.saved())){if(a.quest.held())hold(a,"QUEST_HOLD");else stop(a);return;}
        a.phase=success?Phase.COMPLETING:Phase.FAILING;if(!removeBody(a))return;var settled=new CompletableFuture<Void>();a.pending=settled;
        var operation=success?quests.interact(a.quest,catalog.quest(a.definition.quest()),a.definition.reference()):quests.fail(a.quest,catalog.quest(a.definition.quest()),"OBJECTIVE");
        operation.whenCompleteAsync((saved,error)->{if(error!=null){hold(a,"ESCORT_PROGRESS_UNCONFIRMED");settled.completeExceptionally(error);}else if(a.phase==Phase.HOLD){settled.completeExceptionally(new IllegalStateException("ESCORT_CLEANUP_HOLD"));}else{a.phase=success?Phase.DONE:Phase.FAILED;attempts.remove(a.key,a);settled.complete(null);}},owner);
    }
    private boolean removeBody(Attempt a){bodies.remove(a.body.id(),a);boolean clean=true;try{a.body.stop();}catch(RuntimeException error){clean=false;}try{a.body.remove();}catch(RuntimeException error){clean=false;}if(!clean){a.phase=Phase.HOLD;a.error="ESCORT_CLEANUP_HOLD";}return clean;}
    private void hold(Attempt a,String error){a.phase=Phase.HOLD;a.error=error;removeBody(a);}
    private void stop(Attempt a){a.phase=Phase.CLOSED;if(removeBody(a))attempts.remove(a.key,a);}
    public void left(Object session){check.run();for(var a:List.copyOf(attempts.values()))if(a.actor.token()==session){if(a.phase==Phase.ACTIVE||a.phase==Phase.WAITING)stop(a);else removeBody(a);}}
    public CompletionStage<Void> barrier(UUID run){check.run();if(attempts.values().stream().anyMatch(a->a.key.run.equals(run)&&a.phase==Phase.HOLD))return CompletableFuture.failedFuture(new IllegalStateException("ESCORT_HOLD"));var work=new ArrayList<CompletableFuture<?>>();attempts.values().stream().filter(a->a.key.run.equals(run)).forEach(a->work.add(a.pending.toCompletableFuture()));starting.entrySet().stream().filter(e->e.getKey().run.equals(run)).forEach(e->work.add(e.getValue().result));return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));}
    public List<String> lines(Actor actor,String quest){check.run();var a=attempts.get(key(actor,quest));if(a==null)return List.of("현재 접속에서 진행 중인 호위가 없습니다. 저장된 현재 목표의 출발 지점에서 시작하세요.");return List.of(switch(a.phase){case ACTIVE->"호위 대상이 지정 경로를 이동 중입니다.";case WAITING->"호위 대상 가까이에서 지정 구역과 도착 지점을 함께 확인하세요.";case COMPLETING,FAILING->"개인 목표 저장을 확인 중입니다. 중복 시작하지 않습니다.";case HOLD->"호위 저장 상태 확인 필요 · 자동 재시작하지 않습니다.";default->"호위 현장 상태를 다시 확인하세요.";});}
    @Override public void close(){check.run();if(closed)return;closed=true;boolean clean=true;for(var a:List.copyOf(attempts.values()))if(!removeBody(a))clean=false;if(!clean)throw new IllegalStateException("ESCORT_CLEANUP_HOLD");}
}
