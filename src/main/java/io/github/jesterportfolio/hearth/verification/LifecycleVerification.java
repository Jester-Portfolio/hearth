package io.github.jesterportfolio.hearth.verification;

import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.lifecycle.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.world.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.postgresql.ds.PGSimpleDataSource;

/** 격리 콘솔 전용. 내부 참가 예약과 Bukkit 인벤토리 객체는 인증 플레이어 시험이 아닙니다. */
public final class LifecycleVerification implements AutoCloseable, Listener {
    private final JavaPlugin plugin;
    private final ConcurrentLinkedQueue<Runnable> deliveries=new ConcurrentLinkedQueue<>();
    private final Executor owner=deliveries::add;
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r -> new Thread(r,"hearth-world-io"),new ThreadPoolExecutor.AbortPolicy());
    private final ServerProgressAccess access=new ServerProgressAccess();
    private final PostgresProgressStore store;
    private final PaperInventoryCodec codec=new PaperInventoryCodec();
    private final Map<UUID,Scope> scopes=new HashMap<>();
    private final BukkitTask pump;
    private final long idleSeconds;
    private Scope a,b;
    private long ticks,waitPolls;
    private boolean busy,closed,drained,cancelUnload;
    private final boolean sessionMode;
    private SessionVerification sessions;
    private CompletionStage<WorldLifecycle.Admission> queued;
    private InventorySaveSession failedProbe;

    public LifecycleVerification(JavaPlugin plugin,Path root) throws Exception {
        this.plugin=plugin; main();
        sessionMode=plugin.getConfig().getBoolean("session-verification-enabled");
        if (sessionMode && !Boolean.getBoolean("hearth.verification.sessions")) throw new IllegalStateException("Session gate missing");
        if (!root.toRealPath().equals(root) || !root.getParent().equals(Path.of("/tmp"))
                || !root.getFileName().toString().startsWith("hearth-s4-11.")
                || !Files.getOwner(root).getName().equals(System.getProperty("user.name"))
                || !Files.readString(root.resolve(".hearth-lifecycle-isolated")).trim().equals("S4-11 isolated test only")) throw new IllegalStateException("Lifecycle root rejected");
        Properties endpoint=new Properties(); try (var in=Files.newInputStream(root.resolve("db-endpoint.properties"))) { endpoint.load(in); }
        var pid=Files.readAllLines(root.resolve("data/postmaster.pid")); int port=Integer.parseInt(endpoint.getProperty("port"));
        if (!Path.of(pid.get(1)).toRealPath().equals(root.resolve("data")) || port!=Integer.parseInt(pid.get(3))
                || !"hearth_s4_11".equals(endpoint.getProperty("database"))
                || !Files.readString(root.resolve(".hearth-s45-db")).trim().equals(endpoint.getProperty("marker"))) throw new IllegalStateException("Lifecycle DB rejected");
        idleSeconds=plugin.getConfig().getLong("lifecycle-test-idle-seconds",0);
        if (idleSeconds!=(Boolean.getBoolean("hearth.verification.sessionManual") ? 10 : 1)) throw new IllegalStateException("Explicit isolated test idle setting required");
        var source=new PGSimpleDataSource(); source.setServerNames(new String[]{"127.0.0.1"}); source.setPortNumbers(new int[]{port});
        source.setDatabaseName("hearth_s4_11"); source.setUser("postgres"); source.setConnectTimeout(2); source.setSocketTimeout(5); source.setPrepareThreshold(0);
        store=new PostgresProgressStore(source,access,f -> log("HEARTH_LIFE_DB_ERROR "+f.operation()+" code="+f.code()));
        pump=Bukkit.getScheduler().runTaskTimer(plugin,() -> { ticks++; for (int i=0;i<200;i++) { var task=deliveries.poll(); if (task==null) break; task.run(); } },1,1);
        Bukkit.getPluginManager().registerEvents(this,plugin);
        log("HEARTH_LIFE_MODULE READY");
    }
    private static void main() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Not main thread"); }
    private void log(String s) { plugin.getLogger().info(s); }
    private void check(boolean ok,String label) { if (!ok) throw new IllegalStateException(label); log("HEARTH_LIFE_CHECK PASS "+label); }
    private <T> CompletableFuture<T> deliver(CompletionStage<T> work) {
        return work.handleAsync((v,e) -> { main(); if (closed) throw new LifecycleFailure("CLOSED"); if (e!=null) throw new CompletionException(e); return v; },owner).toCompletableFuture();
    }
    private CompletionStage<Void> expect(CompletionStage<?> work,String code) {
        return deliver(work.handle((v,e) -> { if (e==null || !LifecycleFailure.code(e).equals(code)) throw new IllegalStateException("Expected "+code+" got "+(e==null ? "success" : LifecycleFailure.code(e))); return null; }))
            .thenRun(() -> check(true,"rejected_"+code));
    }
    private CompletionStage<Void> after(long delay,Runnable action) {
        var f=new CompletableFuture<Void>(); Bukkit.getScheduler().runTaskLater(plugin,() -> { try { action.run(); f.complete(null); } catch (Exception e) { f.completeExceptionally(e); } },delay); return f;
    }
    private CompletionStage<Void> until(BooleanSupplier condition) { return until(condition,200); }
    private CompletionStage<Void> until(BooleanSupplier condition,int maxTicks) {
        var f=new CompletableFuture<Void>(); long end=ticks+maxTicks;
        f.orTimeout(Math.max(10,(maxTicks+40)/20),TimeUnit.SECONDS);
        Bukkit.getScheduler().runTask(plugin,new Runnable() {
            long lastTick=-1;
            public void run() {
                if (f.isDone()) return;
                try {
                    if (closed) throw new LifecycleFailure("CLOSED");
                    if (lastTick>=ticks) throw new LifecycleFailure("SAME_TICK_REPEAT");
                    if (lastTick>=0) log("HEARTH_LIFE_WAIT_TICK previous="+lastTick+" current="+ticks);
                    lastTick=ticks; waitPolls++;
                    if (condition.getAsBoolean()) f.complete(null);
                    else if (ticks>=end) f.completeExceptionally(new LifecycleFailure("WAIT_TIMEOUT"));
                    else Bukkit.getScheduler().runTaskLater(plugin,this,1);
                } catch (Exception e) { f.completeExceptionally(e); }
            }
        }); return f;
    }
    private CompletionStage<Void> waitSafety() {
        long before=ticks;
        return expect(until(() -> false,3),"WAIT_TIMEOUT").thenCompose(v -> {
            long completedPolls=waitPolls;
            check(ticks>=before+3,"wait_repeats_in_later_ticks");
            return after(5,() -> check(waitPolls==completedPolls,"timed_out_wait_no_more_reservations"));
        });
    }
    public void start(String phase) {
        main(); if (busy || closed || drained) { log("HEARTH_LIFE_COMMAND_REJECTED"); return; } busy=true;
        try {
            CompletionStage<Void> work=switch(phase) {
                case "session-prepare" -> { if(!sessionMode) throw new IllegalStateException("Session mode disabled");yield initialize(false); }
                case "session-resume" -> { if(!sessionMode) throw new IllegalStateException("Session mode disabled");yield initialize(true); }
                case "quest-multi-ready", "quest-multi-inflight", "quest-multi-done", "quest-first", "quest-restored", "quest-cycle", "quest-failure", "session-personal-generation", "session-personal-generation-done", "session-inventory-generation", "session-inventory-generation-done", "session-basic", "session-race", "session-race-done", "session-prepare-quit", "session-prepare-done", "session-move-quit", "session-restored", "session-move-failure", "session-write-failure" -> {
                    if (!sessionMode || sessions==null) throw new IllegalStateException("Session fixture not ready"); yield sessions.run(phase);
                }
                case "prepare" -> initialize(false);
                case "resume" -> initialize(true);
                case "wait-safety" -> waitSafety();
                case "stale" -> stale();
                case "idle-cancel" -> idleCancel().thenCompose(v -> idleCancel());
                case "begin-cycle" -> beginCycle();
                case "queue-entry" -> queueEntry();
                case "resume-save" -> resumeSave();
                case "db-failure" -> dbFailure();
                case "boundaries" -> boundaries();
                case "unload-false" -> unloadFalse();
                case "close" -> shutdown();
                default -> throw new IllegalArgumentException("Unknown lifecycle phase");
            };
            work.whenComplete((v,e) -> owner.execute(() -> { busy=false;
                if (e==null) log("HEARTH_LIFE_RESULT PASS "+phase);
                else { Throwable cause=e; while (cause instanceof CompletionException || cause instanceof ExecutionException) cause=cause.getCause(); plugin.getLogger().severe("HEARTH_LIFE_RESULT FAIL "+phase+" code="+LifecycleFailure.code(e)+" detail="+cause.getMessage()); }
            }));
        } catch (Exception e) { busy=false; plugin.getLogger().severe("HEARTH_LIFE_RESULT FAIL "+phase+" code="+LifecycleFailure.code(e)+" detail="+e.getMessage()); }
    }
    private ItemStack[] fixture(int count) {
        var slots=new ItemStack[41]; slots[0]=new ItemStack(Material.STONE,count);
        var sword=new ItemStack(Material.IRON_SWORD); var meta=sword.getItemMeta(); meta.displayName(Component.text("가상 월드 검증 검")); meta.lore(List.of(Component.text("가상 메타데이터")));
        meta.addEnchant(Objects.requireNonNull(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("sharpness"))),2,true); sword.setItemMeta(meta);
        slots[9]=sword; slots[36]=new ItemStack(Material.IRON_BOOTS); slots[40]=new ItemStack(Material.SHIELD); return slots;
    }
    private InventoryPayload payload(int count) { return codec.encode(fixture(count)); }
    private final class Scope implements PaperLifecycleBoundary.Data {
        final WorldBackend.Placement placement;
        final ServerProgressAccess.Context ctx;
        final PaperWorldBackend backend=new PaperWorldBackend();
        final Inventory view=Bukkit.createInventory(null,45,Component.text("월드 수명주기 격리 시험"));
        final Map<String,Integer> calls=new HashMap<>();
        final WorldLifecycle lifecycle;
        final PaperLifecycleBoundary boundary;
        final WorldPersistence persistence;
        InventorySaveSession session;
        String shared;
        long sharedRevision,inventoryRevision;
        int amount;
        long stateAt=System.nanoTime();
        String lastState="OFFLINE";
        String fault;
        CompletableFuture<Void> pause;
        Scope(WorldBackend.Placement p,String hash,boolean resume) {
            placement=p; ctx=access.forVerifiedServerLink(p.storyRunId(),new UUID(49,1));
            boolean isA=p.storyRunId().equals(new UUID(42,10)); shared=isA ? resume && !sessionMode ? "a-saved" : "a" : "b";
            sharedRevision=isA && resume && !sessionMode ? 1 : 0; amount=isA ? resume ? 30 : 32 : 7; inventoryRevision=isA && resume ? 2 : 0;
            boundary=new PaperLifecycleBoundary(plugin,p,hash,backend,io,owner,() -> lifecycleGeneration(),this,this::regions,this::before);
            persistence=new WorldPersistence(new Forward(boundary) {
                @Override public CompletionStage<Void> saveRegions() {
                    if (pause==null) return super.saveRegions();
                    log("HEARTH_LIFE_SAVE_PAUSED"); return pause.thenComposeAsync(v -> super.saveRegions(),owner);
                }
            },owner,LifecycleVerification::main,this::lifecycleGeneration);
            lifecycle=new WorldLifecycle(new WorldLifecycle.Mapping(p.storyRunId(),p.physicalId(),p.key().toString(),p.directory()),persistence,owner,LifecycleVerification::main,
                (delay,action) -> { var task=Bukkit.getScheduler().runTaskLater(plugin,action,Math.max(1,delay.toMillis()/50)); return task::cancel; },
                Duration.ofSeconds(idleSeconds),16,state -> {
                    long now=System.nanoTime(); log("HEARTH_LIFE_TIMING run="+p.storyRunId()+" state="+lastState+" elapsed_ms="+(now-stateAt)/1_000_000); stateAt=now; lastState=state.name();
                    log("HEARTH_LIFE_STATE run="+p.storyRunId()+" state="+state);
                });
        }
        long lifecycleGeneration() { return lifecycle==null ? 0 : lifecycle.generation(); }
        void before(String stage) { main(); calls.merge(stage,1,Integer::sum); if (stage.equals(fault)) throw new LifecycleFailure("INJECTED_"+stage); }
        int calls(String stage) { return calls.getOrDefault(stage,0); }
        World world() { return Objects.requireNonNull(Bukkit.getWorld(placement.key())); }
        InventoryPayload capture() { return codec.encode(Arrays.copyOf(view.getContents(),41)); }
        void change(int count) {
            check(lifecycle.mayChange(),"change_gate_"+placement.key()); view.setItem(0,new ItemStack(Material.STONE,count)); session.markDirty(); amount=count;
        }
        void regions(RegionManager manager) {
            boolean isA=placement.storyRunId().equals(new UUID(42,10));
            check(manager.getRegion("protected").getFlag(Flags.BUILD)==(isA ? StateFlag.State.DENY : StateFlag.State.ALLOW),"region_isolation_"+placement.key());
            if (isA) check(manager.getRegion("build").getPriority()==10 && manager.getRegion("overlap").getPriority()==20,"region_priorities_restored");
        }
        @Override public CompletionStage<Void> restore() {
            if (sessionMode) return verifyShared();
            long epoch=lifecycle.generation();
            return verifyShared().thenCompose(v -> deliver(store.readPersonal(ctx))).thenCompose(personal -> {
                check(personal.revision()==0 && personal.value().equals("p1"),"personal_progress_"+placement.key()); return deliver(store.readInventory(ctx));
            }).thenAccept(saved -> {
                if (lifecycle.generation()!=epoch) throw new LifecycleFailure("STALE_GENERATION");
                check(saved.revision()==inventoryRevision && Arrays.equals(codec.decode(saved.payload()),fixture(amount)),"inventory_restore_"+placement.key()+"_r"+inventoryRevision);
                var items=codec.decode(saved.payload()); for (int i=0;i<41;i++) view.setItem(i,items[i]);
                session=new InventorySaveSession(store,ctx,saved.revision(),this::capture,owner,LifecycleVerification::main,() -> lifecycle.generation()==epoch,code -> log("HEARTH_LIFE_INVENTORY_ERROR "+code));
            });
        }
        @Override public CompletionStage<Void> drain() {
            if (sessionMode) return sessions.barrier(placement.storyRunId());
            return deliver(Objects.requireNonNull(session).closeForLogout()).thenRun(() -> {
                inventoryRevision=session.revision(); check(!session.dirty() && !session.saving(),"inventory_drained_"+placement.key()+"_r"+inventoryRevision);
            });
        }
        @Override public CompletionStage<Void> verifyShared() {
            return deliver(store.readShared(ctx)).thenAccept(state -> check(state.value().equals(shared) && state.revision()==sharedRevision,"shared_progress_"+placement.key()+"_r"+sharedRevision));
        }
        @Override public boolean ownReferencesClear() { return sessionMode ? sessions.canUnload(placement.storyRunId()) : session!=null && !session.dirty() && !session.saving(); }
        @Override public void release() { if(sessions!=null)sessions.releaseQuests(placement.storyRunId());session=null; view.clear(); }
    }
    private static class Forward implements WorldPersistence.Boundary {
        final WorldPersistence.Boundary b;
        Forward(WorldPersistence.Boundary b) { this.b=b; }
        public CompletionStage<Void> verifyStoredMapping(){return b.verifyStoredMapping();} public CompletionStage<Void> loadWorld(){return b.loadWorld();}
        public CompletionStage<Void> restoreRegions(){return b.restoreRegions();} public CompletionStage<Void> verifyProgressAndInventory(){return b.verifyProgressAndInventory();}
        public CompletionStage<Void> drainInventory(){return b.drainInventory();} public CompletionStage<Void> verifyProgress(){return b.verifyProgress();}
        public CompletionStage<Void> saveRegions(){return b.saveRegions();} public CompletionStage<Void> saveWorld(){return b.saveWorld();}
        public CompletionStage<Void> checkUnloadBlockers(){return b.checkUnloadBlockers();} public CompletionStage<Boolean> unloadWorld(){return b.unloadWorld();}
        public void clearWorldReferences(){b.clearWorldReferences();}
    }
    private CompletionStage<Void> initialize(boolean resume) throws Exception {
        Properties map=new Properties(); try (var in=Files.newInputStream(plugin.getDataFolder().toPath().resolve("restart-mapping.properties"))) { map.load(in); }
        for (String key:List.of("restart_a","restart_b")) {
            UUID run=UUID.fromString(map.getProperty(key+".run")),uuid=UUID.fromString(map.getProperty(key+".uuid"));
            NamespacedKey worldKey=new NamespacedKey("hearth_verify",key);
            Path directory=Bukkit.getServer().getLevelDirectory().resolve("dimensions/hearth_verify/"+key).toAbsolutePath().normalize();
            var scope=new Scope(new WorldBackend.Placement(run,worldKey,uuid,directory),map.getProperty(key+".metadataSha256"),resume);
            if (scopes.putIfAbsent(run,scope)!=null) throw new IllegalStateException("Duplicate lifecycle owner");
            if (key.equals("restart_a")) a=scope; else b=scope;
        }
        CompletionStage<Void> init=deliver(store.migrate());
        if (!resume) for (Scope s:List.of(a,b)) {
            init=init.thenCompose(v -> deliver(store.createRun(s.ctx.storyRunId(),s.shared)));
            if(!Boolean.getBoolean("hearth.verification.sessionManual"))init=init.thenCompose(v -> deliver(store.createPlayer(s.ctx,"p1")))
                .thenCompose(v -> deliver(store.createInventory(s.ctx,payload(s.amount)))).thenAccept(saved -> {});
        }
        var a2=access.forVerifiedServerLink(a.ctx.storyRunId(),new UUID(49,2));
        if (!resume && !Boolean.getBoolean("hearth.verification.sessionManual")) init=init.thenCompose(v -> deliver(store.createPlayer(a2,"p2"))).thenCompose(v -> deliver(store.createInventory(a2,payload(5)))).thenAccept(saved -> {});
        if (sessionMode) {
            sessions=new SessionVerification(plugin,store,access,Map.of(a.placement.storyRunId(),a.lifecycle,b.placement.storyRunId(),b.lifecycle),owner,codec,this::payload);
            return init.thenCompose(v -> sessions.initialize(resume));
        }
        return init.thenCompose(v -> {
            UUID request=UUID.randomUUID(); var first=a.lifecycle.reserve(request,a.ctx.playerId());
            check(first==a.lifecycle.reserve(request,a.ctx.playerId()),"duplicate_reservation_single_future");
            var extra=a.lifecycle.reserve(UUID.randomUUID(),new UUID(49,2));
            var other=b.lifecycle.reserve(UUID.randomUUID(),b.ctx.playerId());
            return deliver(first).thenCompose(admission -> {
                check(a.lifecycle.commit(admission),"a_active_after_all_restore"); return deliver(extra);
            }).thenCompose(admission -> { a.lifecycle.cancel(admission.requestId()); return deliver(other); })
                .thenAccept(admission -> check(b.lifecycle.commit(admission),"b_active_after_all_restore"));
        }).thenRun(() -> {
            check(a.calls("load")==1 && b.calls("load")==1,"coalesced_load_independent_A_B"); inspect(a,resume); inspect(b,false);
        });
    }
    private void inspect(Scope s,boolean changed) {
        World world=s.world(); check(world.getUID().equals(s.placement.physicalId()) && world.getKey().equals(s.placement.key()),"physical_identity_"+s.placement.key());
        check(world.getBlockAt(0,64,0).getType()==(s==a ? Material.GOLD_BLOCK : Material.DIAMOND_BLOCK),"original_block_"+s.placement.key());
        if (changed) check(world.getBlockAt(1,64,1).getType()==Material.EMERALD_BLOCK,"changed_block_preserved");
        log("HEARTH_LIFE_SNAPSHOT run="+s.ctx.storyRunId()+" uuid="+world.getUID()+" key="+world.getKey()+" block="+world.getBlockAt(0,64,0).getType()+" shared_revision="+s.sharedRevision+" inventory_revision="+s.inventoryRevision+" amount="+s.amount);
    }
    private CompletionStage<Void> idleCancel() {
        int saves=a.calls("world_save"),loads=a.calls("load"); a.lifecycle.leave(a.ctx.playerId()); check(a.lifecycle.state()==WorldLifecycle.State.IDLE,"last_exit_idle");
        return after(5,() -> {}).thenCompose(v -> deliver(a.lifecycle.reserve(UUID.randomUUID(),a.ctx.playerId())))
            .thenAccept(ticket -> check(a.lifecycle.commit(ticket),"idle_entry_cancel"))
            .thenCompose(v -> after(25,() -> check(a.lifecycle.state()==WorldLifecycle.State.ACTIVE && a.calls("world_save")==saves && a.calls("load")==loads && b.lifecycle.state()==WorldLifecycle.State.ACTIVE,"old_idle_timer_no_unload")));
    }
    private CompletionStage<Void> beginCycle() {
        a.world().getBlockAt(1,64,1).setType(Material.EMERALD_BLOCK,false);
        return deliver(store.saveShared(a.ctx,0,"a-saved")).thenCompose(saved -> {
            a.shared=saved.value(); a.sharedRevision=saved.revision();
            a.change(31); long before=ticks; var inFlight=a.session.flush(); a.change(30); a.pause=new CompletableFuture<>();
            inFlight.whenComplete((v,e) -> owner.execute(() -> { if (e!=null) log("HEARTH_LIFE_BACKGROUND_ERROR "+LifecycleFailure.code(e)); else check(ticks>before,"ticks_during_DB_wait"); }));
            a.lifecycle.leave(a.ctx.playerId());
            return until(() -> a.lifecycle.state()==WorldLifecycle.State.QUIESCING);
        }).thenRun(() -> check(a.session.saving() && a.session.dirty() && a.calls("world_save")==0 && a.calls("unload")==0,"dirty_inflight_blocks_unload"));
    }
    private CompletionStage<Void> queueEntry() {
        check(a.lifecycle.state()==WorldLifecycle.State.SAVING,"save_gate_entered"); queued=a.lifecycle.reserve(UUID.randomUUID(),a.ctx.playerId());
        check(!queued.toCompletableFuture().isDone() && a.lifecycle.readyReservationCount()==0 && a.lifecycle.pendingCount()==1,"entry_waits_for_save_unload");
        return CompletableFuture.completedFuture(null);
    }
    private CompletionStage<Void> resumeSave() {
        a.pause.complete(null); a.pause=null;
        return deliver(queued).thenCompose(ticket -> {
            check(a.lifecycle.commit(ticket),"queued_entry_after_unload_reload");
            check(a.calls("load")==2 && a.calls("unload")==1 && a.inventoryRevision==2 && a.amount==30,"single_cycle_latest_dirty_preserved");
            inspect(a,true); inspect(b,false); return b.verifyShared();
        });
    }
    private CompletionStage<Void> dbFailure() {
        var ctx=access.forVerifiedServerLink(a.ctx.storyRunId(),new UUID(49,2));
        failedProbe=new InventorySaveSession(store,ctx,0,() -> payload(4),owner,LifecycleVerification::main,() -> true,c -> log("HEARTH_LIFE_PROBE_ERROR "+c)); failedProbe.markDirty();
        var pipeline=new WorldPersistence(new Forward(a.boundary) { @Override public CompletionStage<Void> drainInventory() { return failedProbe.closeForLogout(); } },owner,LifecycleVerification::main,a.lifecycle::generation);
        int saves=a.calls("world_save"),unloads=a.calls("unload");
        return expect(pipeline.quiesce().thenCompose(v -> pipeline.save()).thenCompose(v -> pipeline.unload()),"23514")
            .thenCompose(v -> deliver(store.readInventory(ctx))).thenAccept(saved -> {
                check(saved.revision()==0 && Arrays.equals(codec.decode(saved.payload()),fixture(5)) && failedProbe.dirty(),"failed_DB_preserves_original_dirty");
                check(a.calls("world_save")==saves && a.calls("unload")==unloads && b.lifecycle.state()==WorldLifecycle.State.ACTIVE,"failed_DB_pipeline_stops_other_run_active");
            });
    }
    private CompletionStage<Void> boundaries() {
        int saves=a.calls("world_save"),unloads=a.calls("unload"); a.fault="regions_save";
        return expect(a.persistence.save(),"INJECTED_regions_save").thenCompose(v -> {
            check(a.calls("world_save")==saves && a.calls("unload")==unloads,"injected_WG_failure_stops_world_save"); a.fault="world_save";
            return expect(a.persistence.save(),"INJECTED_world_save");
        }).thenCompose(v -> {
            a.fault=null; check(a.calls("unload")==unloads,"injected_world_save_failure_no_unload");
            a.world().addPluginChunkTicket(0,0,plugin); return expect(a.boundary.checkUnloadBlockers(),"UNLOAD_BLOCKERS");
        }).thenCompose(v -> {
            a.world().removePluginChunkTicket(0,0,plugin);
            return after(1,() -> {
                check(!Bukkit.isTickingWorlds(),"mapping_probe_safe_main_point");
                var missing=a.placement.directory().resolveSibling("missing_lifecycle");
                try { new PaperWorldBackend().registerExisting(new WorldBackend.Placement(UUID.randomUUID(),new NamespacedKey("hearth_verify","missing_lifecycle"),UUID.randomUUID(),missing)); throw new AssertionError("Missing accepted"); }
                catch (IllegalStateException good) { check(!Files.exists(missing),"missing_world_no_empty_replacement"); }
                try { new PaperWorldBackend().registerExisting(new WorldBackend.Placement(a.placement.storyRunId(),a.placement.key(),UUID.randomUUID(),a.placement.directory())); throw new AssertionError("UUID accepted"); }
                catch (IllegalStateException good) { check(a.world().getUID().equals(a.placement.physicalId()),"UUID_mismatch_preserves_world"); }
            });
        });
    }
    private CompletionStage<Void> stale() {
        var epoch=new java.util.concurrent.atomic.AtomicLong(1);
        var progress=new CompletableFuture<Void>();
        int saves=a.calls("world_save"),regions=a.calls("regions_save");
        var probe=new WorldPersistence(new Forward(a.boundary) {
            @Override public CompletionStage<Void> verifyProgress() { return progress; }
        },owner,LifecycleVerification::main,epoch::get);
        var pending=probe.save(); epoch.incrementAndGet();
        return after(1,() -> progress.complete(null)).thenCompose(v -> expect(pending,"STALE_GENERATION"))
            .thenRun(() -> check(a.calls("world_save")==saves && a.calls("regions_save")==regions
                && a.lifecycle.state()==WorldLifecycle.State.ACTIVE && b.lifecycle.state()==WorldLifecycle.State.ACTIVE,"stale_pipeline_no_world_state_change"));
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void unloading(WorldUnloadEvent event) {
        if (cancelUnload && a!=null && event.getWorld().getUID().equals(a.placement.physicalId())) { event.setCancelled(true); cancelUnload=false; log("HEARTH_LIFE_UNLOAD_EVENT_CANCELLED"); }
    }
    private CompletionStage<Void> unloadFalse() {
        cancelUnload=true; a.lifecycle.leave(a.ctx.playerId());
        return until(() -> a.lifecycle.state()==WorldLifecycle.State.ERROR_HOLD).thenCompose(v -> {
            check(a.lifecycle.failureCode().equals("UNLOAD_FALSE") && Bukkit.getWorld(a.placement.key())!=null,"actual_unload_false_loaded_ERROR_HOLD");
            inspect(a,true); return expect(a.lifecycle.reserve(UUID.randomUUID(),a.ctx.playerId()),"HELD");
        }).thenCompose(v -> {
            check(b.lifecycle.state()==WorldLifecycle.State.ACTIVE,"A_hold_B_active"); b.change(6); b.lifecycle.leave(b.ctx.playerId());
            return until(() -> b.lifecycle.state()==WorldLifecycle.State.OFFLINE);
        }).thenRun(() -> check(Bukkit.getWorld(b.placement.key())==null && b.inventoryRevision==1 && a.lifecycle.state()==WorldLifecycle.State.ERROR_HOLD,"A_hold_B_save_unload_success"));
    }
    public void fixturePlayer(String name,boolean existing) { if (!sessionMode || sessions==null) throw new IllegalStateException("Session fixture not ready");sessions.fixturePlayer(name,existing); }
    public void playerStatus(String name) { if(!sessionMode || sessions==null)throw new IllegalStateException("Session fixture not ready");sessions.playerStatus(name); }
    private CompletionStage<Void> shutdown() {
        CompletionStage<Void> work=CompletableFuture.completedFuture(null);
        if (sessionMode) work=sessions.shutdown().thenCompose(v -> until(() -> scopes.values().stream().allMatch(s -> s.lifecycle.state()==WorldLifecycle.State.OFFLINE || s.lifecycle.state()==WorldLifecycle.State.ERROR_HOLD),300));
        for (Scope s:List.of(a,b)) if (!sessionMode && s.lifecycle.state()==WorldLifecycle.State.ACTIVE) {
            work=work.thenCompose(v -> s.drain()).thenCompose(v -> s.persistence.save());
        }
        return work.thenCompose(v -> deliver(store.closeAsync())).thenCompose(v -> {
            io.shutdown(); return after(1,() -> { check(io.isTerminated(),"world_IO_pool_closed"); drained=true; });
        });
    }
    @Override public void close() {
        main(); if (closed) return; closed=true; if(sessions!=null)sessions.close(); pump.cancel(); HandlerList.unregisterAll(this);
        io.shutdown();
        log("HEARTH_LIFE_DISABLE_CLOSE "+(drained && io.isTerminated() ? "PASS" : "FAIL"));
        if (!drained) store.closeAsync();
    }
}
