package io.github.jesterportfolio.hearth.combat;

import io.github.jesterportfolio.hearth.persistence.ServerProgressAccess.Context;
import io.github.jesterportfolio.hearth.session.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.lifecycle.WorldLifecycle;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import static io.github.jesterportfolio.hearth.combat.CombatContent.*;
import io.github.jesterportfolio.hearth.combat.CombatContent.Pose;
import io.github.jesterportfolio.hearth.combat.CombatContent.Effect;

/** 인증된 격리 fixture 세션에만 연결하는 실제 Player 적용기. 자동 게임 시작/일반 서버 활성화는 없습니다. */
public final class PaperCombatPlayers implements PaperSessionAdapter.CombatControls,PaperCombatTrials.Access,Listener,AutoCloseable {
    private static final class Run {
        private final CombatPlayers players;private final WorldLifecycle world;private final Map<UUID,GameMode> modes;private final Set<String> checkpointVisited=new HashSet<>();private CompletableFuture<Void> preparing;private CombatDepartures departure;private Set<UUID> checkpointParticipants=Set.of();private long checkpointNext;private CombatCheckpoint checkpoint;private boolean path;private CompletionStage<Void> durableStart=CompletableFuture.completedFuture(null),durableEnd;private CompletionStage<Void> victoryCredit;private CombatRoute route;private CombatGathering gathering;private CombatRetryVote retry;private CombatRestart restart;private final long generation;
        Run(CombatPlayers players,WorldLifecycle world,Map<UUID,GameMode> modes){this.players=players;this.world=world;this.modes=modes;this.generation=world.generation();}
        CombatPlayers players(){return players;}WorldLifecycle world(){return world;}Map<UUID,GameMode> modes(){return modes;}
    }
    public interface Gameplay {default void changed(CombatAttempt previous,CombatAttempt next,boolean path){}default CompletionStage<Void> victory(CombatAttempt battle){return CompletableFuture.completedFuture(null);}}
    public interface Durability {
        default CompletionStage<Void> started(CombatAttempt.Snapshot s,UUID world,CombatContent.Exit exit,Map<UUID,GameMode> modes,UUID parent){return CompletableFuture.completedFuture(null);}
        default CompletionStage<Void> ended(CombatAttempt.Snapshot s){return CompletableFuture.completedFuture(null);}
        default CompletionStage<Void> claim(Context c,UUID attempt,UUID request){return CompletableFuture.completedFuture(null);}default CompletionStage<Void> done(Context c,UUID attempt,UUID request){return CompletableFuture.completedFuture(null);}default CompletionStage<Void> barrier(UUID run){return CompletableFuture.completedFuture(null);}
    }
    private Durability durability=new Durability(){};public void durability(Durability value){durability=value;}
    public boolean knownDurable(Context c,UUID attempt){var r=runs.get(c.storyRunId());return r!=null&&r.players().attempt().snapshot().attempt().equals(attempt)||departures.containsKey(attempt)&&departures.get(attempt).pending(c);}
    private void durableStart(Run r,UUID parent){var state=r.players().attempt().snapshot();if(trials.content().exit()==null)return;trials.durableFreeze(state.run(),true);r.durableStart=durability.started(state,r.world().mapping().physicalId(),trials.content().exit(),r.modes(),parent);r.durableStart.whenCompleteAsync((v,error)->{if(error!=null)cleanupHolds.add(state.run());else if(runs.get(state.run())==r)trials.durableFreeze(state.run(),false);},owner);}
    private Gameplay gameplay=new Gameplay(){};public void gameplay(Gameplay handler){main();gameplay=Objects.requireNonNull(handler);}
    public boolean available(UUID run){main();return !closed&&!runs.containsKey(run)&&!cleanupHolds.contains(run)&&departures.values().stream().noneMatch(d->d.run().equals(run));}
    private final JavaPlugin plugin;private final PaperSessionAdapter sessions;private final PaperCombatTrials trials;private final PaperFieldEncounters fields;private final Map<UUID,WorldLifecycle> worlds;private final Executor owner;private final BiFunction<Player,PlayerSessions.Session,CompletionStage<Void>> death;private final Function<UUID,CompletionStage<Void>> dataBarrier;
    private final QuestStore checkpointQuests;private final CheckpointStore checkpointStore;
    private final PaperCombatHud hud=new PaperCombatHud();private long hudTick;
    private record DepartureJob(UUID run,PlayerSessions.Session session,CombatReturn job) {}
    private final Map<UUID,CombatDepartures> departures=new HashMap<>();private final Map<UUID,DepartureJob> departureJobs=new HashMap<>();
    private final Set<UUID> cleanupHolds=new HashSet<>();private final Map<UUID,Run> runs=new HashMap<>();private final Map<UUID,Body> bodies=new HashMap<>();private final BukkitTask timer;private boolean closed;
    public PaperCombatPlayers(JavaPlugin plugin,PaperSessionAdapter sessions,PaperCombatTrials trials,PaperFieldEncounters fields,QuestStore checkpointQuests,CheckpointStore checkpointStore,Map<UUID,WorldLifecycle> worlds,Executor owner,BiFunction<Player,PlayerSessions.Session,CompletionStage<Void>> death,Function<UUID,CompletionStage<Void>> dataBarrier){main();if(!plugin.getConfig().getBoolean("combat-player-verification-enabled")||!Boolean.getBoolean("hearth.verification.combatPlayers"))throw new IllegalStateException("Combat player gate missing");this.checkpointQuests=checkpointQuests;this.checkpointStore=checkpointStore;this.plugin=plugin;this.sessions=sessions;this.trials=trials;this.fields=fields;
        for(var policy:java.util.stream.Stream.of(trials.content().retry(),trials.content().path()==null?null:trials.content().path().retry()).filter(Objects::nonNull).toList())if(policy.reset()!=null){if(fields==null)throw new IllegalStateException("PATH_RESET_BACKEND_REQUIRED");fields.validateReset(policy.reset());}
        this.worlds=Map.copyOf(worlds);this.owner=owner;this.death=death;this.dataBarrier=dataBarrier;sessions.combat(this);trials.access(this);Bukkit.getPluginManager().registerEvents(this,plugin);timer=Bukkit.getScheduler().runTaskTimer(plugin,this::project,1,1);}
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Not main thread");}
    private static Pose pose(Location l){return new Pose(Objects.requireNonNull(l.getWorld()).getUID(),l.getX(),l.getY(),l.getZ(),l.getYaw(),l.getPitch());}
    private static Location location(Pose p){var w=Objects.requireNonNull(Bukkit.getWorld(p.world()),"Combat world missing");return new Location(w,p.x(),p.y(),p.z(),p.yaw(),p.pitch());}
    private final class Body implements CombatPlayers.PlayerPort {
        final Player player;final PlayerSessions.Session session;final Run run;final GameMode mode;boolean hudFailed;
        Body(Player p,PlayerSessions.Session s,Run run){player=p;session=s;this.run=run;mode=Objects.requireNonNull(run.modes().get(p.getUniqueId()));}
        public Context context(){return session.context();}public Object session(){return session;}public Pose position(){main();return pose(player.getLocation());}
        public boolean current(){main();return !closed&&player.isOnline()&&!player.isDead()&&sessions.prepared(player)==session&&run.world().generation()==run.generation&&player.getWorld().getUID().equals(run.world().mapping().physicalId());}
        public void spectator(UUID target){main();if(!current())throw new IllegalStateException("COMBAT_CAMERA_STALE");sessions.combatCamera(player,session,this::current,()->{player.setGameMode(GameMode.SPECTATOR);if(player.getGameMode()!=GameMode.SPECTATOR)throw new IllegalStateException("COMBAT_SPECTATOR_DENIED");Player selected=null;if(target!=null){var body=bodies.get(target);if(body==null||body.run!=run||!body.current()||!run.players().attempt().playable(body.context(),body.session))throw new IllegalStateException("COMBAT_CAMERA_TARGET_STALE");selected=body.player;}player.setSpectatorTarget(selected);if(player.getSpectatorTarget()!=selected)throw new IllegalStateException("COMBAT_CAMERA_NOT_APPLIED");});}
        public void clearCamera(){main();if(current()&&player.getGameMode()==GameMode.SPECTATOR&&player.getSpectatorTarget()!=null)sessions.combatCamera(player,session,this::current,()->player.setSpectatorTarget(null));}
        public void cleanup(){main();hud.clear(player,session,sessions.prepared(player)==session);if(sessions.linked(player)==session&&player.getWorld().getUID().equals(run.world().mapping().physicalId())){if(player.getGameMode()==GameMode.SPECTATOR)player.setSpectatorTarget(null);player.setGameMode(mode);if(player.getGameMode()!=mode)throw new IllegalStateException("COMBAT_MODE_CLEANUP_DENIED");}}
        public double healthFraction(){main();return player.getHealth()/Objects.requireNonNull(player.getAttribute(Attribute.MAX_HEALTH)).getValue();}
        public boolean safe(Pose target){main();World w=Bukkit.getWorld(target.world());if(w==null||!target.world().equals(run.world().mapping().physicalId())||target.y()<=w.getMinHeight()||target.y()+2>=w.getMaxHeight()||!w.getWorldBorder().isInside(location(target)))return false;int y=(int)Math.floor(target.y());for(double dx:new double[]{-0.3,0.3})for(double dz:new double[]{-0.3,0.3}){int x=(int)Math.floor(target.x()+dx),z=(int)Math.floor(target.z()+dz);if(!w.isChunkLoaded(x>>4,z>>4))return false;var floor=w.getBlockAt(x,y-1,z);for(int level=y;level<=(int)Math.floor(target.y()+1.8);level++){var cell=w.getBlockAt(x,level,z);if(!CombatPlayers.safeCells(floor.getType().name(),cell.getType().name(),cell.getType().name(),floor.getType().isSolid(),cell.isPassable(),cell.isPassable()))return false;}}return true;}
        public CompletionStage<Boolean> restore(Pose target,double health,List<Effect> effects,BooleanSupplier valid){main();if(!current()||!valid.getAsBoolean()||!safe(target))return CompletableFuture.completedFuture(false);clearCamera();return sessions.combatMove(player,session,location(target),()->current()&&valid.getAsBoolean()).thenApplyAsync(ok->{if(!Boolean.TRUE.equals(ok)||!current()||!valid.getAsBoolean()||!CombatPlayers.samePosition(target,position()))return false;double restored=CombatPlayers.restoredHealth(Objects.requireNonNull(player.getAttribute(Attribute.MAX_HEALTH)).getValue(),health);player.setGameMode(mode);if(player.getGameMode()!=mode)return false;player.setHealth(restored);for(var effect:effects){var type=Registry.MOB_EFFECT.get(NamespacedKey.minecraft(effect.type().toLowerCase(Locale.ROOT)));if(type==null||!player.addPotionEffect(new PotionEffect(type,effect.ticks(),effect.amplifier())))return false;}return true;},owner);}
    }
    private Body body(Player p){var b=bodies.get(p.getUniqueId());return b!=null&&b.player==p&&b.session==sessions.linked(p)?b:null;}
    private Run known(Player p){var s=sessions.prepared(p);return s==null?null:runs.get(s.context().storyRunId());}
    private boolean formerParticipant(Run run,Player p){return run!=null&&run.players().attempt().snapshot().members().stream().anyMatch(m->m.player().equals(p.getUniqueId()));}
    private boolean returning(Run run,Player p){return run!=null&&(!run.durableStart.toCompletableFuture().isDone()||run.durableEnd!=null&&!run.durableEnd.toCompletableFuture().isDone())||run!=null&&run.route!=null&&run.route.blocked()||run!=null&&run.preparing!=null&&(!run.preparing.isDone()||run.preparing.isCompletedExceptionally())||run!=null&&run.checkpoint!=null&&run.checkpoint.phase()!=CombatCheckpoint.Phase.DONE||run!=null&&run.gathering!=null&&run.gathering.phase()!=CombatGathering.Phase.STARTED;}
    private CombatDepartures departure(Context context){return departures.values().stream().filter(ledger->ledger.pending(context)).findFirst().orElse(null);}
    private boolean departurePending(Player p){var session=sessions.prepared(p);var ended=session==null?null:departure(session.context());return ended!=null&&ended.pending(session.context())&&(body(p)==null||departureJobs.containsKey(p.getUniqueId()));}
    public boolean actions(Player p){main();if(departurePending(p))return false;var known=known(p);if(known!=null&&(returning(known,p)||cleanupHolds.contains(known.world().mapping().storyRunId())||known.restart!=null&&known.restart.phase()!=CombatRestart.Phase.STARTED))return false;var b=body(p);if(b!=null)return b.run.players().actions(b);var r=known(p);return !formerParticipant(r,p)||r.players().attempt().snapshot().phase()==CombatAttempt.Phase.CLOSED;}
    public boolean travel(Player p){main();var r=known(p);return actions(p)&&(!formerParticipant(r,p)||r.players().attempt().snapshot().phase()==CombatAttempt.Phase.CLOSED);}
    public boolean movement(Player p){main();if(departurePending(p))return false;var known=known(p);if(known!=null&&(returning(known,p)||cleanupHolds.contains(known.world().mapping().storyRunId())||known.restart!=null&&known.restart.phase()!=CombatRestart.Phase.STARTED))return false;var b=body(p);return b==null?actions(p):b.run.players().movement(b);}
    public boolean attack(Player p,Entity target){main();var b=body(p);return b!=null&&actions(p)&&b.run.players().attempt().snapshot().phase()==CombatAttempt.Phase.ACTIVE&&target==trials.mob(b.context().storyRunId());}
    public boolean target(UUID run,Entity target){main();if(!(target instanceof Player p))return false;var b=body(p);return b!=null&&b.context().storyRunId().equals(run)&&actions(p)&&b.run.players().attempt().snapshot().phase()==CombatAttempt.Phase.ACTIVE;}
    public boolean hit(UUID run,Entity source,Entity target){main();return source instanceof Player p&&attack(p,target)&&body(p).context().storyRunId().equals(run)||target instanceof Player q&&target(run,q)&&source==trials.mob(run);}
    public boolean damage(Player p,EntityDamageEvent event){main();var b=body(p);if(event.isCancelled()||b==null||!actions(p)||b.run.players().attempt().snapshot().phase()!=CombatAttempt.Phase.ACTIVE||!(event instanceof EntityDamageByEntityEvent hit)||hit.getDamager()!=trials.mob(b.context().storyRunId()))return false;double amount=event.getFinalDamage();if(!Double.isFinite(amount)||amount<0)return false;if(amount>=p.getHealth()){event.setCancelled(true);try{b.run.players().died(b);death.apply(p,b.session).whenCompleteAsync((v,error)->{if(error!=null)plugin.getLogger().warning("HEARTH_COMBAT_QUEST_DEATH_HOLD");},owner);p.sendMessage(Component.text("전투 사망 · 관전 대기"));}catch(RuntimeException error){b.run.players().attempt().invalidate();plugin.getLogger().warning("HEARTH_COMBAT_DEATH_HOLD");}return false;}return true;}
    @EventHandler(priority=EventPriority.HIGHEST) public void unexpectedDeath(PlayerDeathEvent event){var b=body(event.getPlayer());if(b==null)return;event.setKeepInventory(true);event.getDrops().clear();event.setDroppedExp(0);event.setKeepLevel(true);b.run.players().attempt().invalidate();plugin.getLogger().warning("HEARTH_COMBAT_UNEXPECTED_DEATH_HOLD");}
    public Location destination(Player p,Context ctx,Location original){main();var r=runs.get(ctx.storyRunId());if(r!=null&&trials.content().exit()!=null&&CombatReturn.ended(r.players().attempt().snapshot()))rememberEnded(r);return r==null||departure(ctx)!=null?original:r.players().returnPosition(ctx).map(PaperCombatPlayers::location).orElse(original);}
    public void ready(Player p,PlayerSessions.Session session){main();var r=runs.get(session.context().storyRunId());if(r!=null&&trials.content().exit()!=null&&CombatReturn.ended(r.players().attempt().snapshot()))rememberEnded(r);var ended=departure(session.context());if(ended!=null&&ended.pending(session.context())){resumeDeparture(p,session,ended);return;}if(r==null||r.players().attempt().snapshot().phase()==CombatAttempt.Phase.CLOSED||!formerParticipant(r,p))return;var b=new Body(p,session,r);bodies.put(p.getUniqueId(),b);r.players().rejoin(b);if(r.retry!=null)r.retry.poll();}
    public void leaving(Player p,PlayerSessions.Session session){main();var pending=departureJobs.get(p.getUniqueId());if(pending!=null&&pending.session()==session){pending.job().close();cleanupHolds.add(pending.run());}var b=body(p);if(b==null||b.session!=session)return;if(b.run.route!=null&&b.run.route.blocked()){b.run.route.close();cleanupHolds.add(session.context().storyRunId());}if(b.run.restart!=null)b.run.restart.close();if(b.run.gathering!=null&&b.run.gathering.phase()!=CombatGathering.Phase.STARTED){b.run.gathering.close();cleanupHolds.add(session.context().storyRunId());}try{b.run.players().leave(b);if(b.run.retry!=null)b.run.retry.poll();}finally{bodies.remove(p.getUniqueId(),b);}}
    private void project(){main();if(closed||Bukkit.isTickingWorlds())return;hudTick++;for(var r:List.copyOf(runs.values()))try{if(!r.path&&r.victoryCredit==null&&r.players().attempt().snapshot().outcome()==CombatAttempt.Outcome.VICTORY){r.victoryCredit=r.durableStart.thenComposeAsync(v->gameplay.victory(r.players().attempt()),owner);r.victoryCredit.whenCompleteAsync((v,error)->{if(error!=null){cleanupHolds.add(r.world().mapping().storyRunId());plugin.getLogger().warning("HEARTH_DUNGEON_CREDIT_HOLD");}},owner);}checkpointPoll(r);if(r.path&&r.players().attempt().snapshot().phase()==CombatAttempt.Phase.WIPE_WAIT)r.players().attempt().failedCondition(true);r.players().project();if(CombatReturn.ended(r.players().attempt().snapshot())&&trials.content().exit()!=null&&(departures.containsKey(r.players().attempt().snapshot().attempt())||bodies.values().stream().noneMatch(b->b.run==r))){if(rememberEnded(r)!=null)releaseUnused(r);if(runs.get(r.world().mapping().storyRunId())!=r)continue;}if(r.retry!=null){r.retry.poll();var receipt=r.retry.view().receipt();if(receipt!=null&&r.restart==null&&r.preparing==null&&trials.content().retry()!=null&&!cleanupHolds.contains(receipt.run()))try{prepareRestart(r,receipt);}catch(RuntimeException error){cleanupHolds.add(receipt.run());plugin.getLogger().warning("HEARTH_COMBAT_RESTART_PREPARE_HOLD");}}}catch(RuntimeException e){r.players().attempt().invalidate();}
        for(var b:List.copyOf(bodies.values())){
            if(b.hudFailed)continue;
            try{if(!b.current()){hud.clear(b.player,b.session,false);continue;}
                var attempt=b.run.players().attempt();var mob=trials.mob(b.context().storyRunId());
                var view=attempt.hud(b.context(),b.session,mob.getHealth(),Objects.requireNonNull(mob.getAttribute(Attribute.MAX_HEALTH)).getValue());
                hud.present(b.player,b.session,attempt.snapshot().attempt(),view,hudTick,b::current);
            }catch(RuntimeException error){b.hudFailed=true;try{hud.clear(b.player,b.session,false);}catch(RuntimeException ignored){}plugin.getLogger().warning("HEARTH_COMBAT_HUD_UNAVAILABLE");}
        }
    }
    private void checkpointPoll(Run r){
        if(hudTick<r.checkpointNext||!r.path||r.checkpoint!=null||r.restart!=null||r.gathering!=null||r.route!=null&&r.route.blocked()||cleanupHolds.contains(r.world().mapping().storyRunId())||trials.content().path().checkpoint()==null)return;
        var attempt=r.players().attempt();var state=attempt.snapshot();if(state.phase()!=CombatAttempt.Phase.ACTIVE)return;
        var selected=bodies.values().stream().filter(b->b.run==r&&b.current()).toList();if(selected.size()!=attempt.onlineSessions().size())return;
        var point=trials.content().path().checkpoints().stream().filter(cp->!r.checkpointVisited.contains(cp.id())).filter(cp->selected.stream().anyMatch(b->{var p=b.position();return cp.area().contains(new FieldContent.Point(p.x(),p.y(),p.z()));})).findFirst().orElse(null);if(point==null)return;
        r.checkpointNext=hudTick+40;var id=state.run();r.checkpoint=new CombatCheckpoint(attempt,trials.content().id(),point,new ArrayList<>(selected),checkpointQuests,checkpointStore,()->{trials.freezePath(id,state.attempt());return sessions.saveRunBoundary(id).thenComposeAsync(v->dataBarrier.apply(id),owner);},owner,PaperCombatPlayers::main,()->!closed&&runs.get(id)==r&&r.world().generation()==r.generation);
        var job=r.checkpoint;job.arrive().whenCompleteAsync((ok,error)->{if(error!=null){String code=error.getCause()==null?error.getMessage():error.getCause().getMessage();if("CHECKPOINT_NOT_GATHERED".equals(code)){r.checkpoint=null;}else{cleanupHolds.add(id);attempt.invalidate();plugin.getLogger().warning("HEARTH_CHECKPOINT_HOLD");}}else{r.checkpointVisited.add(point.id());if(point.id().equals(trials.content().path().checkpoint().id()))r.checkpointParticipants=job.participants();r.checkpoint=null;trials.resumePath(id,state.attempt());if(Boolean.TRUE.equals(ok))selected.forEach(b->{if(b.current())b.player.sendMessage(Component.text("체크포인트 도달 · 대상 참가자 전원 회복 및 무료 부활 완료"));});}},owner);
    }
    private void voteNotice(Run run,String text){
        for(var b:List.copyOf(bodies.values()))if(b.run==run&&b.current()){
            b.player.sendMessage(Component.text(text));
            if(run.retry!=null&&run.retry.view().phase()==CombatRetryVote.Phase.OPEN){String ref=run.retry.view().id().toString();
                b.player.sendMessage(Component.text("[찬성]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/hearthcombat vote "+ref+" yes"))
                    .append(Component.text(" [반대]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/hearthcombat vote "+ref+" no")))
                    .append(Component.text(" [기권]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/hearthcombat vote "+ref+" abstain"))));
            }
        }
    }
    public boolean command(Player p,String line){
        main();if(!CombatHud.command(line))return false;
        if(line.equals("/hearthcombat")||line.endsWith(" help")){CombatHud.help().forEach(text->p.sendMessage(Component.text(text)));return true;}
        var b=body(p);if(b==null||!b.run.players().current(b)){p.sendMessage(Component.text("현재 참가 중인 전투가 없거나 세션을 확인할 수 없습니다."));return true;}
        if(b.run.checkpoint!=null&&b.run.checkpoint.phase()!=CombatCheckpoint.Phase.DONE&&!line.equals("/hearthcombat status")){p.sendMessage(Component.text("체크포인트 회복 처리 중이거나 보류 중입니다."));return true;}
        try{
            if(line.equals("/hearthcombat gather")){gather(b);}
            else if(line.equals("/hearthcombat leave")){returnEnded(b);}
            else if(line.endsWith("watch"))b.run.players().attempt().watchNext(b.context(),b.session);
            else if(line.equals("/hearthcombat retry")){if(b.run.path)retryPath(b);else b.run.retry.request(b.context(),b.session);}
            else if(line.equals("/hearthcombat vote")&&b.run.retry!=null){b.run.retry.poll();b.run.retry.lines().forEach(text->p.sendMessage(Component.text(text)));if(b.run.restart!=null)b.run.restart.lines().forEach(text->p.sendMessage(Component.text(text)));}
            else if(CombatRetryVote.input(line).isPresent()&&b.run.retry!=null){
                var input=CombatRetryVote.input(line).orElseThrow();b.run.retry.cast(b.context(),b.session,input.vote(),input.choice()).whenCompleteAsync((v,error)->{
                    if(!b.current()||bodies.get(p.getUniqueId())!=b)return;
                    if(error!=null)p.sendMessage(Component.text("투표를 반영하지 못했습니다. 마감·중복·접속 변경 또는 처리 보류 상태를 확인해 주세요."));
                    else p.sendMessage(Component.text("재도전 투표 입력을 처리했습니다. 다음 도전의 시작을 뜻하지 않습니다."));
                },owner);
            }else{if(b.run.route!=null)b.run.route.lines().forEach(text->p.sendMessage(Component.text(text)));b.run.players().attempt().view(b.context(),b.session).lines().forEach(text->p.sendMessage(Component.text(text)));if(b.run.restart!=null)b.run.restart.lines().forEach(text->p.sendMessage(Component.text(text)));}
        }catch(RuntimeException e){p.sendMessage(Component.text("전투 요청을 처리할 수 없습니다. 현재 상태나 진행 중인 투표를 확인해 주세요."));}
        return true;
    }
    /** 콘솔의 명시적 격리 시험 시작. 정식 집결/인던 입장 규칙을 대체하지 않습니다. */
    public void startOnline(String profile){main();var first=Objects.requireNonNull(Bukkit.getPlayerExact(profile));var session=Objects.requireNonNull(sessions.prepared(first));UUID run=session.context().storyRunId();var selected=new HashMap<UUID,PlayerSessions.Session>();for(var p:Bukkit.getOnlinePlayers()){var linked=sessions.prepared(p);if(linked!=null&&linked.context().storyRunId().equals(run)&&p.isWhitelisted()&&!p.isDead()&&(p.getGameMode()==GameMode.SURVIVAL||p.getGameMode()==GameMode.ADVENTURE))selected.put(p.getUniqueId(),linked);}if(!selected.containsKey(first.getUniqueId()))throw new IllegalStateException("COMBAT_LIVE_START_REJECTED");startExact(run,selected);}
    /** 서버가 비동기 의뢰/현재 세션을 확인한 명단만 받습니다. 클라이언트가 진행 ID를 지정하지 않습니다. */
    public CombatAttempt startExact(UUID run,Map<UUID,PlayerSessions.Session> roster){main();var world=Objects.requireNonNull(worlds.get(run));if(!Bukkit.getOnlineMode()||!available(run)||roster.isEmpty()||roster.size()>32)throw new IllegalStateException("COMBAT_LIVE_START_REJECTED");var candidates=new ArrayList<Player>();for(var entry:roster.entrySet()){var p=Objects.requireNonNull(Bukkit.getPlayer(entry.getKey()));var current=sessions.prepared(p);if(current!=entry.getValue()||!current.context().storyRunId().equals(run)||!p.getWorld().getUID().equals(world.mapping().physicalId())||!p.isWhitelisted()||p.isDead()||departure(current.context())!=null||(p.getGameMode()!=GameMode.SURVIVAL&&p.getGameMode()!=GameMode.ADVENTURE)||!p.getItemOnCursor().isEmpty()||(p.getOpenInventory().getType()!=org.bukkit.event.inventory.InventoryType.CRAFTING&&p.getOpenInventory().getType()!=org.bukkit.event.inventory.InventoryType.PLAYER))throw new IllegalStateException("COMBAT_LIVE_START_REJECTED");candidates.add(p);}var bridge=new CombatPlayers(run,world.mapping().physicalId(),world.generation(),world::generation,owner,PaperCombatPlayers::main);var modes=new HashMap<UUID,GameMode>();candidates.forEach(p->modes.put(p.getUniqueId(),p.getGameMode()));var r=new Run(bridge,world,Map.copyOf(modes));var ports=candidates.stream().map(p->new Body(p,Objects.requireNonNull(sessions.prepared(p)),r)).toList();try{r.path=trials.content().path()!=null;var entrants=ports.stream().map(p->new CombatAttempt.Entrant(p.context(),p.session,p.position())).toList();var attempt=r.path?trials.startPath(world,entrants,bridge):trials.start(world,entrants,bridge);ports.forEach(p->bodies.put(p.player.getUniqueId(),p));bridge.attach(attempt,new ArrayList<>(ports));attachVote(r);runs.put(run,r);attachRoute(r,false);durableStart(r,null);return attempt;}catch(RuntimeException error){bridge.close();ports.forEach(p->bodies.remove(p.player.getUniqueId(),p));trials.stop(run);throw error;}}
    private void attachVote(Run run){if(run.path)return;var attempt=run.players().attempt();run.retry=new CombatRetryVote(attempt,owner,PaperCombatPlayers::main,System::nanoTime,java.time.Duration.ofSeconds(30),()->!closed&&run.world().generation()==attempt.snapshot().generation()&&Bukkit.getWorld(run.world().mapping().physicalId())!=null,text->voteNotice(run,text));run.retry.preparationAvailable(trials.content().retry()!=null);}
    private Map<UUID,PlayerSessions.Session> onlinePrepared(UUID run){var current=new HashMap<UUID,PlayerSessions.Session>();for(var p:Bukkit.getOnlinePlayers()){var s=sessions.prepared(p);if(s!=null&&s.context().storyRunId().equals(run))current.put(p.getUniqueId(),s);}return Map.copyOf(current);}
    private void gather(Body actor){
        main();var previous=actor.run;UUID id=actor.context().storyRunId();if(!previous.path||trials.content().path()==null||cleanupHolds.contains(id))throw new IllegalStateException("COMBAT_GATHERING_NOT_CONFIGURED");
        if(previous.gathering!=null&&previous.gathering.phase()!=CombatGathering.Phase.IDLE){actor.player.sendMessage(Component.text("보스 입장 준비가 진행 중이거나 보류 중입니다."));return;}
        if(previous.route!=null&&!previous.route.atBoss()){advanceRoute(actor);return;}
        var source=previous.players().attempt();var selected=source.onlineSessions();var roster=new HashMap<UUID,PlayerSessions.Session>();var targets=new ArrayList<Player>();var modes=new HashMap<UUID,GameMode>();
        for(var entry:selected.entrySet()){
            var p=Objects.requireNonNull(Bukkit.getPlayer(entry.getKey()));var session=sessions.prepared(p);
            if(session!=entry.getValue()||!p.isWhitelisted()||p.isDead()||!p.getItemOnCursor().isEmpty()||(p.getOpenInventory().getType()!=org.bukkit.event.inventory.InventoryType.CRAFTING&&p.getOpenInventory().getType()!=org.bukkit.event.inventory.InventoryType.PLAYER))throw new IllegalStateException("COMBAT_GATHERING_SESSION_NOT_READY");
            roster.put(p.getUniqueId(),session);targets.add(p);modes.put(p.getUniqueId(),Objects.requireNonNull(previous.modes().get(p.getUniqueId())));
        }
        var nextBridge=new CombatPlayers(id,previous.world().mapping().physicalId(),previous.generation,previous.world()::generation,owner,PaperCombatPlayers::main);var next=new Run(nextBridge,previous.world(),Map.copyOf(modes));next.checkpointParticipants=previous.checkpointParticipants;next.checkpointVisited.addAll(previous.checkpointVisited);
        var ports=targets.stream().map(p->new Body(p,roster.get(p.getUniqueId()),next)).toList();UUID request=UUID.randomUUID();
        BooleanSupplier valid=()->!closed&&runs.get(id)==previous&&previous.world().generation()==previous.generation&&source.onlineSessions().equals(roster)&&roster.entrySet().stream().allMatch(e->{var p=Bukkit.getPlayer(e.getKey());return p!=null&&sessions.prepared(p)==e.getValue();});
        var operation=new CombatGathering(source,trials.content().path(),new ArrayList<>(ports),()->{trials.freezePath(id,source.snapshot().attempt());return sessions.saveRunBoundary(id).thenComposeAsync(v->dataBarrier.apply(id),owner);},entrants->{
            var attempt=trials.enterBoss(previous.world(),source.snapshot().attempt(),request,entrants,nextBridge);
            try{previous.players().close();bodies.values().removeIf(b->b.run==previous);ports.forEach(p->bodies.put(p.player.getUniqueId(),p));runs.put(id,next);nextBridge.attach(attempt,new ArrayList<>(ports));attachVote(next);attachRoute(next,true);gameplay.changed(previous.players().attempt(),attempt,next.path);durableStart(next,previous.players().attempt().snapshot().attempt());return attempt;}
            catch(RuntimeException failure){attempt.invalidate();cleanupHolds.add(id);throw failure;}
        },owner,PaperCombatPlayers::main,valid);
        previous.gathering=operation;operation.begin(request).whenCompleteAsync((attempt,error)->{
            if(error!=null){if(operation.phase()==CombatGathering.Phase.IDLE){if(previous.gathering==operation)previous.gathering=null;}else{cleanupHolds.add(id);source.invalidate();plugin.getLogger().warning("HEARTH_COMBAT_GATHERING_HOLD");}}
            for(var p:targets)if(sessions.prepared(p)==roster.get(p.getUniqueId()))p.sendMessage(Component.text(error==null?"보스 입장 완료 · 온라인 참가 명단과 난이도, 보스 전용 부활 횟수를 확정했습니다.":operation.phase()==CombatGathering.Phase.IDLE?"온라인 생존자 전원이 지정 집결 구역에 모여야 합니다.":"보스 입장 보류 · 관리자 확인이 필요합니다."));
        },owner);
    }
    private void attachRoute(Run run,boolean restarting){if(run.path&&trials.content().path().route()!=null)run.route=new CombatRoute(run.players().attempt(),trials.content().path().route(),restarting,owner,PaperCombatPlayers::main,()->!closed&&runs.get(run.world().mapping().storyRunId())==run&&run.world().generation()==run.generation);}
    private void advanceRoute(Body actor){
        var run=actor.run;var route=run.route;if(route.blocked()||cleanupHolds.contains(actor.context().storyRunId()))throw new IllegalStateException("ROUTE_BUSY");var section=route.section().orElseThrow();
        var ports=bodies.values().stream().filter(b->b.run==run&&b.current()).map(b->(CombatPlayers.PlayerPort)b).toList();var attempt=run.players().attempt();UUID id=actor.context().storyRunId();
        route.advance(section.id(),UUID.randomUUID(),ports,()->{trials.freezePath(id,attempt.snapshot().attempt());return sessions.saveRunBoundary(id).thenComposeAsync(v->dataBarrier.apply(id),owner);},()->section.checkpoint()==null||run.checkpointVisited.contains(section.checkpoint())).whenCompleteAsync((v,error)->{
            if(error!=null){if(route.phase()==CombatRoute.Phase.HOLD){cleanupHolds.add(id);attempt.invalidate();plugin.getLogger().warning("HEARTH_PATH_ROUTE_HOLD");}if(actor.current())actor.player.sendMessage(Component.text(route.phase()==CombatRoute.Phase.HOLD?"길목 이동 보류 · 관리자 확인 필요":"현재 길목의 체크포인트와 생존자 집결을 확인해 주세요."));}
            else{trials.resumePath(id,attempt.snapshot().attempt());run.players().project();for(var p:ports)if(p.current()){var body=(Body)p;body.player.sendMessage(Component.text("길목 구간 이동 완료 · 참가 상태와 부활 사용 횟수를 유지합니다."));route.lines().forEach(text->body.player.sendMessage(Component.text(text)));}}
        },owner);
    }
    private void retryPath(Body actor){
        var r=actor.run;var s=r.players().attempt().snapshot();
        if(!r.path||trials.content().path()==null||trials.content().path().retry()==null||s.phase()!=CombatAttempt.Phase.CLOSED||s.outcome()!=CombatAttempt.Outcome.REQUIRED_FAILURE||s.error()!=null||cleanupHolds.contains(s.run()))throw new IllegalStateException("PATH_RETRY_NOT_READY");
        if(r.restart!=null){r.restart.lines().forEach(line->actor.player.sendMessage(Component.text(line)));return;}
        prepareRestart(r,new CombatRetryVote.Receipt(s.run(),s.attempt(),UUID.randomUUID()),true);
    }
    private void prepareRestart(Run previous,CombatRetryVote.Receipt receipt){prepareRestart(previous,receipt,false);}
    private void prepareRestart(Run previous,CombatRetryVote.Receipt receipt,boolean pathFailure){
        main();if(departures.containsKey(receipt.attempt()))throw new IllegalStateException("COMBAT_DEPARTURE_IN_PROGRESS");var original=pathFailure?trials.content().path().retry():trials.content().retry();var cp=trials.content().path()==null?null:trials.content().path().checkpoint();
        if(!original.scope().equals("FROM_PATH")||cp==null){prepareRestartReady(previous,receipt,pathFailure,onlinePrepared(receipt.run()));return;}
        if(previous.preparing!=null)return;
        var members=previous.players().attempt().snapshot().members().stream().map(CombatAttempt.Member::player).collect(java.util.stream.Collectors.toSet());var candidates=new HashMap<UUID,PlayerSessions.Session>();onlinePrepared(receipt.run()).forEach((id,session)->{if(members.contains(id))candidates.put(id,session);});var captured=Map.copyOf(candidates);var contexts=new HashMap<UUID,Context>();captured.forEach((id,session)->contexts.put(id,session.context()));
        previous.preparing=new CompletableFuture<>();var pending=previous.preparing;
        CheckpointRoster.select(receipt.run(),contexts,checkpointQuests,cp.quest(),owner,()->!closed&&runs.get(receipt.run())==previous&&previous.world().generation()==previous.generation&&members.stream().filter(onlinePrepared(receipt.run())::containsKey).count()==captured.size()&&captured.entrySet().stream().allMatch(e->onlinePrepared(receipt.run()).get(e.getKey())==e.getValue()))
            .whenCompleteAsync((ids,error)->{if(pending.isDone())return;if(error!=null){cleanupHolds.add(receipt.run());pending.completeExceptionally(error);return;}try{var roster=new HashMap<UUID,PlayerSessions.Session>();ids.forEach(id->roster.put(id,captured.get(id)));prepareRestartReady(previous,receipt,pathFailure,Map.copyOf(roster));pending.complete(null);}catch(RuntimeException failure){cleanupHolds.add(receipt.run());pending.completeExceptionally(failure);}},owner).exceptionally(error->{pending.completeExceptionally(error);return null;});
    }
    private void prepareRestartReady(Run previous,CombatRetryVote.Receipt receipt,boolean pathFailure,Map<UUID,PlayerSessions.Session> selectedRoster){
        main();var original=pathFailure?trials.content().path().retry():trials.content().retry();var cp=trials.content().path()==null?null:trials.content().path().checkpoint();boolean checkpointRestart=original.scope().equals("FROM_PATH")&&cp!=null;var policy=checkpointRestart?new Retry(original.scope(),cp.entry().entry(),cp.entry().yaw(),cp.entry().pitch(),1,List.of(),original.reset()):original;UUID id=receipt.run();var roster=selectedRoster;var nextBridge=new CombatPlayers(id,previous.world().mapping().physicalId(),previous.generation,previous.world()::generation,owner,PaperCombatPlayers::main);
        var modes=new HashMap<UUID,GameMode>();var selected=new ArrayList<Player>();
        for(var entry:roster.entrySet()){
            var p=Objects.requireNonNull(Bukkit.getPlayer(entry.getKey()));var mode=previous.modes().getOrDefault(p.getUniqueId(),p.getGameMode());
            if(!p.isWhitelisted()||p.isDead()||(mode!=GameMode.SURVIVAL&&mode!=GameMode.ADVENTURE)||!p.getItemOnCursor().isEmpty()||(p.getOpenInventory().getType()!=org.bukkit.event.inventory.InventoryType.CRAFTING&&p.getOpenInventory().getType()!=org.bukkit.event.inventory.InventoryType.PLAYER))throw new IllegalStateException("COMBAT_RESTART_PLAYER_NOT_READY");modes.put(p.getUniqueId(),mode);selected.add(p);
        }
        var next=new Run(nextBridge,previous.world(),Map.copyOf(modes));next.checkpointParticipants=previous.checkpointParticipants;next.checkpointVisited.addAll(previous.checkpointVisited);next.path=policy.scope().equals("FROM_PATH");var ports=selected.stream().map(p->new Body(p,Objects.requireNonNull(selectedRoster.get(p.getUniqueId())),next)).toList();
        BooleanSupplier current=()->!closed&&runs.get(id)==previous&&previous.world().generation()==previous.generation&&Bukkit.getWorld(previous.world().mapping().physicalId())!=null&&selectedRoster.entrySet().stream().allMatch(e->onlinePrepared(id).get(e.getKey())==e.getValue());
        previous.restart=new CombatRestart(previous.players().attempt(),policy,new ArrayList<>(ports),
            ()->(checkpointRestart?checkpointStore.checkpoint(ports.getFirst().context(),trials.content().id(),cp.quest(),cp.id()).thenAccept(row->{if(row.isEmpty()||!row.get().complete())throw new IllegalStateException("CHECKPOINT_NOT_ACTIVE");}):CompletableFuture.<Void>completedFuture(null)).thenComposeAsync(v->sessions.saveRunBoundary(id),owner).thenComposeAsync(v->dataBarrier.apply(id),owner),
            ()->policy.reset()==null?CompletableFuture.completedFuture(null):fields.reset(id,receipt.request(),policy.reset(),()->CompletableFuture.completedFuture(null),current,owner),entrants->{
                if(previous.retry!=null)previous.retry.close();previous.players().close();
                CombatAttempt attempt=pathFailure?trials.restartPath(previous.world(),receipt,entrants,nextBridge):trials.replace(previous.world(),receipt,entrants,nextBridge);
                try{bodies.values().removeIf(b->b.run==previous);ports.forEach(p->bodies.put(p.player.getUniqueId(),p));runs.put(id,next);nextBridge.attach(attempt,new ArrayList<>(ports));attachVote(next);attachRoute(next,true);gameplay.changed(previous.players().attempt(),attempt,next.path);durableStart(next,previous.players().attempt().snapshot().attempt());return attempt;}
                catch(RuntimeException error){attempt.invalidate();cleanupHolds.add(id);throw error;}
            },owner,PaperCombatPlayers::main,current);
        selected.forEach(p->p.sendMessage(Component.text("등록된 범위로 재도전 준비 · 저장·복귀·구역 정리 중에는 조작할 수 없습니다.")));
        (pathFailure?previous.restart.beginPathFailure(receipt.request()):previous.restart.begin(receipt)).whenCompleteAsync((attempt,error)->{
            if(error!=null){cleanupHolds.add(id);plugin.getLogger().warning("HEARTH_COMBAT_RESTART_HOLD");}
            for(var p:selected)if(sessions.prepared(p)==selectedRoster.get(p.getUniqueId()))p.sendMessage(Component.text(error==null?(next.path?"길목 재도전 시작 · 지정 복귀 지점과 구역 초기화 확인 완료":"새 보스 도전 시작 · 시작 명단·난이도·보스 부활 횟수 확정 완료"):"재도전 준비 보류 · 자동 재실행하지 않습니다. 관리자 확인이 필요합니다."));
        },owner);
    }
    /** 실제 게임 조건 대신 명시적으로 실행하는 콘솔 시험 입력입니다. 클라이언트 명령으로 열지 않습니다. */
    public CompletionStage<Void> condition(String profile,String condition){main();var b=Objects.requireNonNull(body(Objects.requireNonNull(Bukkit.getPlayerExact(profile))),"Combat fixture required");if(!b.run.players().current(b))return CompletableFuture.failedFuture(new IllegalStateException("COMBAT_SESSION_NOT_READY"));return b.run.players().attempt().revive(b.context(),b.session,UUID.randomUUID(),condition);}
    public void release(String profile){main();returnEnded(Objects.requireNonNull(body(Objects.requireNonNull(Bukkit.getPlayerExact(profile)))));}
    private CombatDepartures rememberEnded(Run r){
        UUID run=r.world().mapping().storyRunId();var state=r.players().attempt().snapshot();if(!CombatReturn.ended(state)||trials.content().exit()==null||cleanupHolds.contains(run))throw new IllegalStateException("COMBAT_RETURN_NOT_CONFIGURED_OR_HELD");
        if(r.durableEnd==null){r.durableEnd=r.durableStart.thenComposeAsync(v->durability.ended(state),owner);r.durableEnd.whenCompleteAsync((v,error)->{if(error!=null){cleanupHolds.add(run);return;}var ledger=rememberEnded(r);if(ledger!=null)for(var p:Bukkit.getOnlinePlayers()){var session=sessions.prepared(p);if(session!=null&&ledger.pending(session.context())&&body(p)==null)resumeDeparture(p,session,ledger);}},owner);}
        if(!r.durableEnd.toCompletableFuture().isDone())return null;if(r.durableEnd.toCompletableFuture().isCompletedExceptionally())throw new IllegalStateException("COMBAT_DURABLE_END_HOLD");
        if(r.departure!=null)return r.departure;var existing=departures.get(state.attempt());if(existing!=null){r.departure=existing;return existing;}
        var modes=new HashMap<UUID,String>();r.modes().forEach((id,mode)->modes.put(id,mode.name()));var ledger=new CombatDepartures(state,r.world().mapping().physicalId(),trials.content().exit(),modes);if(departures.values().stream().anyMatch(ledger::overlaps))throw new IllegalStateException("COMBAT_DEPARTURE_CONFLICT");departures.put(state.attempt(),ledger);r.departure=ledger;return ledger;
    }
    private void releaseUnused(Run r){
        UUID run=r.world().mapping().storyRunId();if(runs.get(run)!=r||cleanupHolds.contains(run)||!CombatReturn.ended(r.players().attempt().snapshot())||bodies.values().stream().anyMatch(b->b.run==r)||departureJobs.values().stream().anyMatch(job->job.run().equals(run)))return;
        try{var barriers=new ArrayList<CompletionStage<Void>>();barriers.add(r.players().barrier());if(r.preparing!=null)barriers.add(r.preparing);if(r.checkpoint!=null)barriers.add(r.checkpoint.barrier());if(r.gathering!=null)barriers.add(r.gathering.barrier());if(r.retry!=null)barriers.add(r.retry.barrier());if(r.restart!=null)barriers.add(r.restart.barrier());if(!CombatDepartures.settled(barriers))return;if(r.retry!=null)r.retry.close();r.players().close();trials.releaseEnded(run,r.players().attempt().snapshot().attempt());runs.remove(run,r);}catch(RuntimeException failure){cleanupHolds.add(run);throw failure;}
    }
    private void resumeDeparture(Player player,PlayerSessions.Session session,CombatDepartures ledger){
        UUID run=session.context().storyRunId();var existing=departureJobs.get(player.getUniqueId());if(existing!=null){if(existing.session()==session&&existing.run().equals(run))return;throw new IllegalStateException("COMBAT_DEPARTURE_DUPLICATE");}if(cleanupHolds.contains(run))throw new IllegalStateException("COMBAT_RETURN_HOLD");var world=Objects.requireNonNull(worlds.get(run));var claim=ledger.claim(session.context(),session);
        try{
            if(!world.mapping().physicalId().equals(claim.ticket().world()))throw new IllegalStateException("COMBAT_DEPARTURE_WORLD_CHANGED");
            var bridge=new CombatPlayers(run,world.mapping().physicalId(),world.generation(),world::generation,owner,PaperCombatPlayers::main);var transport=new Run(bridge,world,Map.of(player.getUniqueId(),GameMode.valueOf(claim.ticket().mode())));var port=new Body(player,session,transport);
            beginDeparture(port,ledger,claim,()->port.cleanup());
        }catch(RuntimeException error){ledger.hold(claim);cleanupHolds.add(run);throw error;}
    }
    private void returnEnded(Body b){
        main();var existing=departureJobs.get(b.player.getUniqueId());if(existing!=null){b.player.sendMessage(Component.text("전투 복귀 처리 상태: "+existing.job().phase()));return;}
        var ledger=rememberEnded(b.run);if(ledger==null){b.player.sendMessage(Component.text("전투 종료 기록을 저장 중입니다."));return;}var claim=ledger.claim(b.context(),b.session);
        try{beginDeparture(b,ledger,claim,()->{b.run.players().leave(b);bodies.remove(b.player.getUniqueId(),b);});}
        catch(RuntimeException error){ledger.hold(claim);cleanupHolds.add(b.context().storyRunId());throw error;}
    }
    private void beginDeparture(Body b,CombatDepartures ledger,CombatDepartures.Claim claim,Runnable cleanup){
        UUID request=UUID.randomUUID();UUID id=b.context().storyRunId();if(departureJobs.containsKey(b.player.getUniqueId()))throw new IllegalStateException("COMBAT_DEPARTURE_DUPLICATE");
        var job=new CombatReturn(ledger,claim,b,()->sessions.saveRunBoundary(id).thenComposeAsync(v->dataBarrier.apply(id),owner).thenComposeAsync(v->durability.claim(b.context(),ledger.attempt(),request),owner),()->{cleanup.run();ledger.complete(claim);},owner,PaperCombatPlayers::main,()->!closed&&!cleanupHolds.contains(id)&&departures.get(ledger.attempt())==ledger&&ledger.current(claim,b.context(),b.session)&&b.run.world().generation()==b.run.generation);
        var entry=new DepartureJob(id,b.session,job);departureJobs.put(b.player.getUniqueId(),entry);b.player.sendMessage(Component.text("종료된 전투 복귀 · 저장 확인 중"));
        job.begin().thenComposeAsync(v->durability.done(b.context(),ledger.attempt(),request),owner).whenCompleteAsync((v,error)->{
            if(error!=null){ledger.hold(claim);cleanupHolds.add(id);plugin.getLogger().warning("HEARTH_COMBAT_RETURN_HOLD");}
            else{departureJobs.remove(b.player.getUniqueId(),entry);if(ledger.empty())departures.remove(ledger.attempt(),ledger);var source=runs.get(id);if(source!=null&&CombatReturn.ended(source.players().attempt().snapshot()))try{rememberEnded(source);releaseUnused(source);}catch(RuntimeException failure){cleanupHolds.add(id);plugin.getLogger().warning("HEARTH_COMBAT_CLEANUP_HOLD");}}
            if(sessions.prepared(b.player)==b.session)b.player.sendMessage(Component.text(error==null&&!cleanupHolds.contains(id)?"전투 종료 복귀 완료":"전투 복귀 보류 · 관리자 확인이 필요합니다."));
        },owner).exceptionally(error->{plugin.getLogger().warning("HEARTH_COMBAT_RETURN_DELIVERY_HOLD");return null;});
    }
    public CompletionStage<Void> barrier(UUID run){return barrierLocal(run).thenComposeAsync(v->durability.barrier(run),owner);}
    private CompletionStage<Void> barrierLocal(UUID run){main();if(cleanupHolds.contains(run))return CompletableFuture.failedFuture(new IllegalStateException("COMBAT_CLEANUP_HOLD"));var pending=CompletableFuture.allOf(departureJobs.values().stream().filter(job->job.run().equals(run)).map(job->job.job().barrier().toCompletableFuture()).toArray(CompletableFuture[]::new));var r=runs.get(run);return r==null?pending:pending.thenComposeAsync(v->r.players().barrier(),owner).thenComposeAsync(v->r.preparing==null?CompletableFuture.completedFuture(null):r.preparing,owner).thenComposeAsync(v->r.checkpoint==null?CompletableFuture.completedFuture(null):r.checkpoint.barrier(),owner).thenComposeAsync(v->r.victoryCredit==null?CompletableFuture.completedFuture(null):r.victoryCredit,owner).thenComposeAsync(v->r.route==null?CompletableFuture.completedFuture(null):r.route.barrier(),owner).thenComposeAsync(v->r.gathering==null?CompletableFuture.completedFuture(null):r.gathering.barrier(),owner).thenComposeAsync(v->r.retry==null?CompletableFuture.completedFuture(null):r.retry.barrier(),owner).thenComposeAsync(v->r.restart==null?CompletableFuture.completedFuture(null):r.restart.barrier(),owner);}
    @Override public void close(){main();if(closed)return;departureJobs.values().forEach(entry->entry.job().close());for(var entry:runs.entrySet())try{if(entry.getValue().preparing!=null&&!entry.getValue().preparing.isDone())entry.getValue().preparing.completeExceptionally(new IllegalStateException("CHECKPOINT_RESTART_CLOSED"));if(entry.getValue().route!=null)entry.getValue().route.close();if(entry.getValue().checkpoint!=null)entry.getValue().checkpoint.close();if(entry.getValue().gathering!=null)entry.getValue().gathering.close();if(entry.getValue().restart!=null)entry.getValue().restart.close();if(entry.getValue().retry!=null)entry.getValue().retry.close();entry.getValue().players().close();}catch(RuntimeException e){cleanupHolds.add(entry.getKey());plugin.getLogger().warning("HEARTH_COMBAT_CLEANUP_HOLD");}closed=true;timer.cancel();try{hud.close();}catch(RuntimeException error){plugin.getLogger().warning("HEARTH_COMBAT_HUD_CLEANUP_FAILED");}HandlerList.unregisterAll(this);sessions.combat(new PaperSessionAdapter.CombatControls(){});trials.access(new PaperCombatTrials.Access(){});bodies.clear();}
}
