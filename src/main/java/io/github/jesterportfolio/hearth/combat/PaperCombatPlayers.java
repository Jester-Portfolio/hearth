package io.github.jesterportfolio.hearth.combat;

import io.github.jesterportfolio.hearth.persistence.ServerProgressAccess.Context;
import io.github.jesterportfolio.hearth.session.*;
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
        private final CombatPlayers players;private final WorldLifecycle world;private final Map<UUID,GameMode> modes;private CombatRetryVote retry;private CombatRestart restart;private final long generation;
        Run(CombatPlayers players,WorldLifecycle world,Map<UUID,GameMode> modes){this.players=players;this.world=world;this.modes=modes;this.generation=world.generation();}
        CombatPlayers players(){return players;}WorldLifecycle world(){return world;}Map<UUID,GameMode> modes(){return modes;}
    }
    private final JavaPlugin plugin;private final PaperSessionAdapter sessions;private final PaperCombatTrials trials;private final Map<UUID,WorldLifecycle> worlds;private final Executor owner;private final BiFunction<Player,PlayerSessions.Session,CompletionStage<Void>> death;private final Function<UUID,CompletionStage<Void>> dataBarrier;
    private final PaperCombatHud hud=new PaperCombatHud();private long hudTick;
    private final Set<UUID> cleanupHolds=new HashSet<>();private final Map<UUID,Run> runs=new HashMap<>();private final Map<UUID,Body> bodies=new HashMap<>();private final BukkitTask timer;private boolean closed;
    public PaperCombatPlayers(JavaPlugin plugin,PaperSessionAdapter sessions,PaperCombatTrials trials,Map<UUID,WorldLifecycle> worlds,Executor owner,BiFunction<Player,PlayerSessions.Session,CompletionStage<Void>> death,Function<UUID,CompletionStage<Void>> dataBarrier){main();if(!plugin.getConfig().getBoolean("combat-player-verification-enabled")||!Boolean.getBoolean("hearth.verification.combatPlayers"))throw new IllegalStateException("Combat player gate missing");this.plugin=plugin;this.sessions=sessions;this.trials=trials;this.worlds=Map.copyOf(worlds);this.owner=owner;this.death=death;this.dataBarrier=dataBarrier;sessions.combat(this);trials.access(this);Bukkit.getPluginManager().registerEvents(this,plugin);timer=Bukkit.getScheduler().runTaskTimer(plugin,this::project,1,1);}
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
        public boolean safe(Pose target){main();World w=Bukkit.getWorld(target.world());if(w==null||!target.world().equals(run.world().mapping().physicalId())||target.y()<=w.getMinHeight()||target.y()+2>=w.getMaxHeight()||!w.getWorldBorder().isInside(location(target)))return false;int y=(int)Math.floor(target.y());for(double dx:new double[]{-0.3,0.3})for(double dz:new double[]{-0.3,0.3}){int x=(int)Math.floor(target.x()+dx),z=(int)Math.floor(target.z()+dz);if(!w.isChunkLoaded(x>>4,z>>4))return false;var floor=w.getBlockAt(x,y-1,z);for(int level=y;level<=(int)Math.floor(target.y()+1.8);level++){var cell=w.getBlockAt(x,level,z);if(!CombatPlayers.safeCells(floor.getType().name(),cell.getType().name(),cell.getType().name(),floor.getType().isSolid(),cell.isPassable(),cell.isPassable()))return false;}}return true;}
        public CompletionStage<Boolean> restore(Pose target,double health,List<Effect> effects,BooleanSupplier valid){main();if(!current()||!valid.getAsBoolean()||!safe(target))return CompletableFuture.completedFuture(false);clearCamera();return sessions.combatMove(player,session,location(target),()->current()&&valid.getAsBoolean()).thenApplyAsync(ok->{if(!Boolean.TRUE.equals(ok)||!current()||!valid.getAsBoolean()||!CombatPlayers.samePosition(target,position()))return false;double restored=CombatPlayers.restoredHealth(Objects.requireNonNull(player.getAttribute(Attribute.MAX_HEALTH)).getValue(),health);player.setGameMode(mode);if(player.getGameMode()!=mode)return false;player.setHealth(restored);for(var effect:effects){var type=Registry.MOB_EFFECT.get(NamespacedKey.minecraft(effect.type().toLowerCase(Locale.ROOT)));if(type==null||!player.addPotionEffect(new PotionEffect(type,effect.ticks(),effect.amplifier())))return false;}return true;},owner);}
    }
    private Body body(Player p){var b=bodies.get(p.getUniqueId());return b!=null&&b.player==p&&b.session==sessions.linked(p)?b:null;}
    private Run known(Player p){var s=sessions.prepared(p);return s==null?null:runs.get(s.context().storyRunId());}
    private boolean formerParticipant(Run run,Player p){return run!=null&&run.players().attempt().snapshot().members().stream().anyMatch(m->m.player().equals(p.getUniqueId()));}
    public boolean actions(Player p){main();var known=known(p);if(known!=null&&(cleanupHolds.contains(known.world().mapping().storyRunId())||known.restart!=null&&known.restart.phase()!=CombatRestart.Phase.STARTED))return false;var b=body(p);if(b!=null)return b.run.players().actions(b);var r=known(p);return !formerParticipant(r,p)||r.players().attempt().snapshot().phase()==CombatAttempt.Phase.CLOSED;}
    public boolean movement(Player p){main();var known=known(p);if(known!=null&&(cleanupHolds.contains(known.world().mapping().storyRunId())||known.restart!=null&&known.restart.phase()!=CombatRestart.Phase.STARTED))return false;var b=body(p);return b==null?actions(p):b.run.players().movement(b);}
    public boolean attack(Player p,Entity target){main();var b=body(p);return b!=null&&b.run.players().actions(b)&&b.run.players().attempt().snapshot().phase()==CombatAttempt.Phase.ACTIVE&&target==trials.mob(b.context().storyRunId());}
    public boolean target(UUID run,Entity target){main();if(!(target instanceof Player p))return false;var b=body(p);return b!=null&&b.context().storyRunId().equals(run)&&b.run.players().actions(b)&&b.run.players().attempt().snapshot().phase()==CombatAttempt.Phase.ACTIVE;}
    public boolean hit(UUID run,Entity source,Entity target){main();return source instanceof Player p&&attack(p,target)&&body(p).context().storyRunId().equals(run)||target instanceof Player q&&target(run,q)&&source==trials.mob(run);}
    public boolean damage(Player p,EntityDamageEvent event){main();var b=body(p);if(event.isCancelled()||b==null||!b.run.players().actions(b)||b.run.players().attempt().snapshot().phase()!=CombatAttempt.Phase.ACTIVE||!(event instanceof EntityDamageByEntityEvent hit)||hit.getDamager()!=trials.mob(b.context().storyRunId()))return false;double amount=event.getFinalDamage();if(!Double.isFinite(amount)||amount<0)return false;if(amount>=p.getHealth()){event.setCancelled(true);try{b.run.players().died(b);death.apply(p,b.session).whenCompleteAsync((v,error)->{if(error!=null)plugin.getLogger().warning("HEARTH_COMBAT_QUEST_DEATH_HOLD");},owner);p.sendMessage(Component.text("전투 사망 · 관전 대기"));}catch(RuntimeException error){b.run.players().attempt().invalidate();plugin.getLogger().warning("HEARTH_COMBAT_DEATH_HOLD");}return false;}return true;}
    @EventHandler(priority=EventPriority.HIGHEST) public void unexpectedDeath(PlayerDeathEvent event){var b=body(event.getPlayer());if(b==null)return;event.setKeepInventory(true);event.getDrops().clear();event.setDroppedExp(0);event.setKeepLevel(true);b.run.players().attempt().invalidate();plugin.getLogger().warning("HEARTH_COMBAT_UNEXPECTED_DEATH_HOLD");}
    public Location destination(Player p,Context ctx,Location original){main();var r=runs.get(ctx.storyRunId());return r==null?original:r.players().returnPosition(ctx).map(PaperCombatPlayers::location).orElse(original);}
    public void ready(Player p,PlayerSessions.Session session){main();var r=runs.get(session.context().storyRunId());if(r==null||r.players().attempt().snapshot().phase()==CombatAttempt.Phase.CLOSED||!formerParticipant(r,p))return;var b=new Body(p,session,r);bodies.put(p.getUniqueId(),b);r.players().rejoin(b);if(r.retry!=null)r.retry.poll();}
    public void leaving(Player p,PlayerSessions.Session session){main();var b=body(p);if(b==null||b.session!=session)return;if(b.run.restart!=null)b.run.restart.close();try{b.run.players().leave(b);if(b.run.retry!=null)b.run.retry.poll();}finally{bodies.remove(p.getUniqueId(),b);}}
    private void project(){main();if(closed||Bukkit.isTickingWorlds())return;hudTick++;for(var r:List.copyOf(runs.values()))try{r.players().project();if(r.retry!=null){r.retry.poll();var receipt=r.retry.view().receipt();if(receipt!=null&&r.restart==null&&trials.content().retry()!=null&&!cleanupHolds.contains(receipt.run()))try{prepareRestart(r,receipt);}catch(RuntimeException error){cleanupHolds.add(receipt.run());plugin.getLogger().warning("HEARTH_COMBAT_RESTART_PREPARE_HOLD");}}}catch(RuntimeException e){r.players().attempt().invalidate();}
        for(var b:List.copyOf(bodies.values())){
            if(b.hudFailed)continue;
            try{if(!b.current()){hud.clear(b.player,b.session,false);continue;}
                var attempt=b.run.players().attempt();var mob=trials.mob(b.context().storyRunId());
                var view=attempt.hud(b.context(),b.session,mob.getHealth(),Objects.requireNonNull(mob.getAttribute(Attribute.MAX_HEALTH)).getValue());
                hud.present(b.player,b.session,attempt.snapshot().attempt(),view,hudTick,b::current);
            }catch(RuntimeException error){b.hudFailed=true;try{hud.clear(b.player,b.session,false);}catch(RuntimeException ignored){}plugin.getLogger().warning("HEARTH_COMBAT_HUD_UNAVAILABLE");}
        }
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
        try{
            if(line.endsWith("watch"))b.run.players().attempt().watchNext(b.context(),b.session);
            else if(line.equals("/hearthcombat retry"))b.run.retry.request(b.context(),b.session);
            else if(line.equals("/hearthcombat vote")){b.run.retry.poll();b.run.retry.lines().forEach(text->p.sendMessage(Component.text(text)));if(b.run.restart!=null)b.run.restart.lines().forEach(text->p.sendMessage(Component.text(text)));}
            else if(CombatRetryVote.input(line).isPresent()){
                var input=CombatRetryVote.input(line).orElseThrow();b.run.retry.cast(b.context(),b.session,input.vote(),input.choice()).whenCompleteAsync((v,error)->{
                    if(!b.current()||bodies.get(p.getUniqueId())!=b)return;
                    if(error!=null)p.sendMessage(Component.text("투표를 반영하지 못했습니다. 마감·중복·접속 변경 또는 처리 보류 상태를 확인해 주세요."));
                    else p.sendMessage(Component.text("재도전 투표 입력을 처리했습니다. 다음 도전의 시작을 뜻하지 않습니다."));
                },owner);
            }else{b.run.players().attempt().view(b.context(),b.session).lines().forEach(text->p.sendMessage(Component.text(text)));if(b.run.restart!=null)b.run.restart.lines().forEach(text->p.sendMessage(Component.text(text)));}
        }catch(RuntimeException e){p.sendMessage(Component.text("전투 요청을 처리할 수 없습니다. 현재 상태나 진행 중인 투표를 확인해 주세요."));}
        return true;
    }
    /** 콘솔의 명시적 격리 시험 시작. 정식 집결/인던 입장 규칙을 대체하지 않습니다. */
    public void startOnline(String profile){main();var first=Objects.requireNonNull(Bukkit.getPlayerExact(profile),"Online fixture required");var selected=Objects.requireNonNull(sessions.prepared(first),"Prepared fixture required");UUID run=selected.context().storyRunId();var world=Objects.requireNonNull(worlds.get(run));if(!Bukkit.getOnlineMode()||!first.isWhitelisted()||runs.containsKey(run))throw new IllegalStateException("COMBAT_LIVE_START_REJECTED");var candidates=Bukkit.getOnlinePlayers().stream().filter(p->{var s=sessions.prepared(p);return s!=null&&s.context().storyRunId().equals(run)&&p.isWhitelisted()&&!p.isDead()&&(p.getGameMode()==GameMode.SURVIVAL||p.getGameMode()==GameMode.ADVENTURE);}).toList();if(!candidates.contains(first)||candidates.stream().anyMatch(p->!p.getItemOnCursor().isEmpty()||(p.getOpenInventory().getType()!=org.bukkit.event.inventory.InventoryType.CRAFTING&&p.getOpenInventory().getType()!=org.bukkit.event.inventory.InventoryType.PLAYER)))throw new IllegalStateException("COMBAT_LIVE_START_REJECTED");var bridge=new CombatPlayers(run,world.mapping().physicalId(),world.generation(),world::generation,owner,PaperCombatPlayers::main);var modes=new HashMap<UUID,GameMode>();candidates.forEach(p->modes.put(p.getUniqueId(),p.getGameMode()));var r=new Run(bridge,world,Map.copyOf(modes));var ports=candidates.stream().map(p->new Body(p,Objects.requireNonNull(sessions.prepared(p)),r)).toList();try{var attempt=trials.start(world,ports.stream().map(p->new CombatAttempt.Entrant(p.context(),p.session,p.position())).toList(),bridge);ports.forEach(p->bodies.put(p.player.getUniqueId(),p));bridge.attach(attempt,new ArrayList<>(ports));attachVote(r);runs.put(run,r);}catch(RuntimeException error){bridge.close();ports.forEach(p->bodies.remove(p.player.getUniqueId(),p));trials.stop(run);throw error;}}
    private void attachVote(Run run){var attempt=run.players().attempt();run.retry=new CombatRetryVote(attempt,owner,PaperCombatPlayers::main,System::nanoTime,java.time.Duration.ofSeconds(30),()->!closed&&run.world().generation()==attempt.snapshot().generation()&&Bukkit.getWorld(run.world().mapping().physicalId())!=null,text->voteNotice(run,text));run.retry.preparationAvailable(trials.content().retry()!=null);}
    private Map<UUID,PlayerSessions.Session> onlinePrepared(UUID run){var current=new HashMap<UUID,PlayerSessions.Session>();for(var p:Bukkit.getOnlinePlayers()){var s=sessions.prepared(p);if(s!=null&&s.context().storyRunId().equals(run))current.put(p.getUniqueId(),s);}return Map.copyOf(current);}
    private void prepareRestart(Run previous,CombatRetryVote.Receipt receipt){
        main();UUID id=receipt.run();var roster=onlinePrepared(id);var nextBridge=new CombatPlayers(id,previous.world().mapping().physicalId(),previous.generation,previous.world()::generation,owner,PaperCombatPlayers::main);
        var modes=new HashMap<UUID,GameMode>();var selected=new ArrayList<Player>();
        for(var entry:roster.entrySet()){
            var p=Objects.requireNonNull(Bukkit.getPlayer(entry.getKey()));var mode=previous.modes().getOrDefault(p.getUniqueId(),p.getGameMode());
            if(!p.isWhitelisted()||p.isDead()||(mode!=GameMode.SURVIVAL&&mode!=GameMode.ADVENTURE)||!p.getItemOnCursor().isEmpty()||(p.getOpenInventory().getType()!=org.bukkit.event.inventory.InventoryType.CRAFTING&&p.getOpenInventory().getType()!=org.bukkit.event.inventory.InventoryType.PLAYER))throw new IllegalStateException("COMBAT_RESTART_PLAYER_NOT_READY");modes.put(p.getUniqueId(),mode);selected.add(p);
        }
        var next=new Run(nextBridge,previous.world(),Map.copyOf(modes));var ports=selected.stream().map(p->new Body(p,Objects.requireNonNull(roster.get(p.getUniqueId())),next)).toList();
        BooleanSupplier current=()->!closed&&runs.get(id)==previous&&previous.world().generation()==previous.generation&&Bukkit.getWorld(previous.world().mapping().physicalId())!=null&&roster.equals(onlinePrepared(id));
        previous.restart=new CombatRestart(previous.players().attempt(),trials.content().retry(),new ArrayList<>(ports),
            ()->sessions.saveRunBoundary(id).thenComposeAsync(v->dataBarrier.apply(id),owner),entrants->{
                previous.retry.close();previous.players().close();
                CombatAttempt attempt=trials.replace(previous.world(),receipt,entrants,nextBridge);
                try{bodies.values().removeIf(b->b.run==previous);ports.forEach(p->bodies.put(p.player.getUniqueId(),p));runs.put(id,next);nextBridge.attach(attempt,new ArrayList<>(ports));attachVote(next);return attempt;}
                catch(RuntimeException error){attempt.invalidate();cleanupHolds.add(id);throw error;}
            },owner,PaperCombatPlayers::main,current);
        selected.forEach(p->p.sendMessage(Component.text("보스만 재도전 준비 · 저장과 복귀 확인 중에는 조작할 수 없습니다.")));
        previous.restart.begin(receipt).whenCompleteAsync((attempt,error)->{
            if(error!=null){cleanupHolds.add(id);plugin.getLogger().warning("HEARTH_COMBAT_RESTART_HOLD");}
            for(var p:selected)if(sessions.prepared(p)==roster.get(p.getUniqueId()))p.sendMessage(Component.text(error==null?"새 보스 도전 시작을 확인했습니다. 시작 명단·난이도·보스 부활 횟수를 새로 확정했습니다.":"재도전 준비 보류 · 자동 재실행하지 않습니다. 관리자 확인이 필요합니다."));
        },owner);
    }
    /** 실제 게임 조건 대신 명시적으로 실행하는 콘솔 시험 입력입니다. 클라이언트 명령으로 열지 않습니다. */
    public CompletionStage<Void> condition(String profile,String condition){main();var b=Objects.requireNonNull(body(Objects.requireNonNull(Bukkit.getPlayerExact(profile))),"Combat fixture required");if(!b.run.players().current(b))return CompletableFuture.failedFuture(new IllegalStateException("COMBAT_SESSION_NOT_READY"));return b.run.players().attempt().revive(b.context(),b.session,UUID.randomUUID(),condition);}
    public void release(String profile){main();var b=Objects.requireNonNull(body(Objects.requireNonNull(Bukkit.getPlayerExact(profile))));if(b.run.players().attempt().snapshot().phase()!=CombatAttempt.Phase.CLOSED||b.run.players().attempt().snapshot().error()!=null)throw new IllegalStateException("COMBAT_STILL_OPEN_OR_HELD");try{b.run.players().leave(b);}finally{bodies.remove(b.player.getUniqueId(),b);}}
    public CompletionStage<Void> barrier(UUID run){main();if(cleanupHolds.contains(run))return CompletableFuture.failedFuture(new IllegalStateException("COMBAT_CLEANUP_HOLD"));var r=runs.get(run);return r==null?CompletableFuture.completedFuture(null):r.players().barrier().thenComposeAsync(v->r.retry==null?CompletableFuture.completedFuture(null):r.retry.barrier(),owner).thenComposeAsync(v->r.restart==null?CompletableFuture.completedFuture(null):r.restart.barrier(),owner);}
    @Override public void close(){main();if(closed)return;for(var entry:runs.entrySet())try{if(entry.getValue().restart!=null)entry.getValue().restart.close();if(entry.getValue().retry!=null)entry.getValue().retry.close();entry.getValue().players().close();}catch(RuntimeException e){cleanupHolds.add(entry.getKey());plugin.getLogger().warning("HEARTH_COMBAT_CLEANUP_HOLD");}closed=true;timer.cancel();try{hud.close();}catch(RuntimeException error){plugin.getLogger().warning("HEARTH_COMBAT_HUD_CLEANUP_FAILED");}HandlerList.unregisterAll(this);sessions.combat(new PaperSessionAdapter.CombatControls(){});trials.access(new PaperCombatTrials.Access(){});bodies.clear();}
}
