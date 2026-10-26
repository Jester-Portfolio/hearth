package io.github.jesterportfolio.hearth.verification;

import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.lifecycle.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.session.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

/** 기존 lifecycle/DB fixture를 재사용하는 제어 포트 시험. Player를 만들거나 인증을 흉내 내지 않습니다. */
public final class SessionVerification implements AutoCloseable {
    private final JavaPlugin plugin;
    private final PostgresProgressStore store;
    private final ServerProgressAccess access;
    private final Map<UUID,WorldLifecycle> worlds;
    private final Executor owner;
    private final PaperInventoryCodec codec;
    private final IntFunction<InventoryPayload> fixture;
    private final PaperInventoryAutosave autosave;
    private final PlayerSessions coordinator;
    private final PaperSessionAdapter adapter;
    private final boolean manual=Boolean.getBoolean("hearth.verification.sessionManual");
    private final Map<String,PlayerSessions.Session> generationProbes=new HashMap<>();
    private final Map<String,Controlled> probePorts=new HashMap<>();
    private final UUID a=new UUID(42,10),b=new UUID(42,11);
    private final ServerProgressAccess.Context a1,a2,b3;
    private Controlled oldOne,one,two,three,cancelled;
    private PlayerSessions.Session s1,s2,s3,oldSession,newSession;
    private CompletionStage<Void> oldClosing,barrier;
    private boolean cycle,expectedHold;
    private long started=System.nanoTime();
    public SessionVerification(JavaPlugin plugin,PostgresProgressStore store,ServerProgressAccess access,Map<UUID,WorldLifecycle> worlds,
            Executor owner,PaperInventoryCodec codec,IntFunction<InventoryPayload> fixture) {
        this.plugin=plugin;this.store=store;this.access=access;this.worlds=new HashMap<>(worlds);this.owner=owner;this.codec=codec;this.fixture=fixture;
        a1=ctx(a,1);a2=ctx(a,2);b3=ctx(b,3);
        autosave=new PaperInventoryAutosave(plugin,List.of(),this::refresh,() -> {
            if (!cycle) {cycle=true;log("AUTOSAVE elapsed_ms="+(System.nanoTime()-started)/1_000_000);}
        });
        coordinator=new PlayerSessions(store,store,owner,SessionVerification::main,p -> codec.decode(p),codec::equivalent,autosave::add,autosave::remove,c -> log("ERROR "+c));
        World waiting=Bukkit.getWorlds().getFirst();
        for (int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)waiting.getBlockAt(x,63,z).setType(Material.STONE,false);
        adapter=new PaperSessionAdapter(plugin,coordinator,owner,codec,new Location(waiting,0.5,64,0.5));
    }
    private ServerProgressAccess.Context ctx(UUID run,int player){return access.forVerifiedServerLink(run,new UUID(49,player));}
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Not main thread");}
    private void log(String s){plugin.getLogger().info("HEARTH_SESSION_"+s);}
    private void check(boolean condition,String label){if(!condition)throw new IllegalStateException(label);log("CHECK PASS "+label);}
    private <T> CompletionStage<T> mainResult(CompletionStage<T> work){return work.handleAsync((v,e)->{main();if(e!=null)throw new CompletionException(e);return v;},owner);}
    private CompletionStage<Void> expect(CompletionStage<?> work,String code){return mainResult(work.handle((v,e)->{if(e==null || !LifecycleFailure.code(e).equals(code))throw new IllegalStateException("Expected "+code);return null;})).thenRun(()->check(true,"rejected_"+code));}
    private CompletionStage<Void> later(Runnable action){var result=new CompletableFuture<Void>();Bukkit.getScheduler().runTaskLater(plugin,()->{try{action.run();result.complete(null);}catch(Exception e){result.completeExceptionally(e);}},1);return result;}
    private CompletionStage<Void> until(BooleanSupplier condition){var f=new CompletableFuture<Void>();long end=System.nanoTime()+45_000_000_000L;Bukkit.getScheduler().runTaskLater(plugin,new Runnable(){public void run(){
        if(f.isDone())return;try{if(condition.getAsBoolean())f.complete(null);else if(System.nanoTime()>end)throw new IllegalStateException("Session wait timeout");else Bukkit.getScheduler().runTaskLater(plugin,this,1);}catch(Exception e){f.completeExceptionally(e);}
    }},1);return f;}
    private void refresh(){coordinator.refreshAll();}
    public CompletionStage<Void> initialize(boolean resume){
        if(manual)return CompletableFuture.completedFuture(null);
        CompletionStage<Void> work=CompletableFuture.completedFuture(null);
        if(!resume)work=mainResult(store.createPlayer(b3,"p3")).thenCompose(v->mainResult(store.createInventory(b3,fixture.apply(7)))).thenAccept(v->{});
        for(var context:List.of(a1,a2,b3)) work=work.thenCompose(v -> linkExisting(context));
        for(int n:List.of(12,13)) {
            UUID run=new UUID(42,n);var context=ctx(run,n);
            // DB 응답 경합만 제어합니다. 이 조정자는 물리 월드를 생성/로드했다는 증거가 아닙니다.
            var mapping=new WorldLifecycle.Mapping(run,new UUID(50,n),"hearth_verify:controlled_"+n,
                worlds.get(a).mapping().directory().resolveSibling("controlled_"+n));
            worlds.put(run,new WorldLifecycle(mapping,new WorldLifecycle.Operations(){
                public CompletionStage<Void> loadAndPrepare(){return CompletableFuture.completedFuture(null);}
                public CompletionStage<Void> quiesce(){return coordinator.barrier(run);}
                public CompletionStage<Void> save(){return CompletableFuture.completedFuture(null);}
                public CompletionStage<Boolean> unload(){return CompletableFuture.completedFuture(true);}
            },owner,SessionVerification::main,(delay,action)->()->{},java.time.Duration.ofSeconds(1),4,state->{}));
            if(!resume)work=work.thenCompose(v->mainResult(store.createRun(run,"generation-boundary")))
                .thenCompose(v->mainResult(store.createPlayer(context,"probe")))
                .thenCompose(v->mainResult(store.createInventory(context,fixture.apply(5)))).thenAccept(v->{});
        }
        return work.thenRun(()->check(true,"fixture_prepared_no_login_provisioning"));
    }
    private Location destination(UUID run){
        main();World w=Objects.requireNonNull(Bukkit.getWorld(NamespacedKey.fromString(worlds.get(run).mapping().worldKey())));
        // 새 격리 fixture의 도착 발판만 준비합니다. 일반 월드 건축/복귀 정책이 아닙니다.
        for(int x=1;x<=(manual?15:3);x++)for(int z=1;z<=(manual?15:3);z++)w.getBlockAt(x,63,z).setType(Material.STONE,false);
        return new Location(w,2.5,64,2.5);
    }
    private final class Controlled implements PlayerSessions.Port {
        final Inventory inventory=Bukkit.createInventory(null,45,Component.text("제어 세션 41슬롯"));
        boolean connected=true;int applies,moves;CompletableFuture<Boolean> movement=CompletableFuture.completedFuture(true);
        public boolean connected(){main();return connected;}
        public InventoryPayload capture(){main();return codec.encode(Arrays.copyOf(inventory.getContents(),41));}
        public void apply(InventoryPayload value){main();ItemStack[] slots=codec.decode(value);for(int i=0;i<41;i++)inventory.setItem(i,slots[i]);applies++;}
        public CompletionStage<Boolean> move(){main();moves++;return movement;}
        void count(int value){inventory.setItem(0,new ItemStack(Material.STONE,value));}
    }
    private PlayerSessions.Session connect(ServerProgressAccess.Context ctx,Controlled port){return coordinator.connect(ctx,worlds.get(ctx.storyRunId()),port);}
    private CompletionStage<Void> verify(ServerProgressAccess.Context ctx,int count,long revision){return mainResult(store.readInventory(ctx)).thenAccept(saved->{
        log("STATE run="+ctx.storyRunId()+" player="+ctx.playerId()+" count="+codec.decode(saved.payload())[0].getAmount()+" revision="+saved.revision());
        check(saved.revision()==revision && Arrays.equals(codec.decode(saved.payload()),codec.decode(fixture.apply(count))),"restored_"+ctx.storyRunId()+"_"+ctx.playerId()+"_count"+count+"_r"+revision);
    });}
    public CompletionStage<Void> run(String phase){
        if(manual)throw new IllegalStateException("자동 실패 시험은 수동 프로필 환경에서 금지합니다");
        return switch(phase){
        case "session-personal-generation" -> generationPending("personal",12);
        case "session-personal-generation-done" -> generationDone("personal",12);
        case "session-inventory-generation" -> generationPending("inventory",13);
        case "session-inventory-generation-done" -> generationDone("inventory",13);
        case "session-basic" -> basic();case "session-race" -> race();case "session-race-done" -> raceDone();
        case "session-prepare-quit" -> preparingQuit();case "session-prepare-done" -> preparingDone();case "session-move-quit" -> movingQuit();
        case "session-restored" -> restored();case "session-move-failure" -> moveFailure();case "session-write-failure" -> writeFailure();
        default -> throw new IllegalArgumentException("Unknown session phase");};}
    private CompletionStage<Void> openAll(){
        one=new Controlled();two=new Controlled();three=new Controlled();s1=connect(a1,one);s2=connect(a2,two);s3=connect(b3,three);
        check(one.applies==0 && two.applies==0 && worlds.get(a).participantCount()==0,"no_apply_or_admission_before_prepare");
        return CompletableFuture.allOf(s1.ready().toCompletableFuture(),s2.ready().toCompletableFuture(),s3.ready().toCompletableFuture())
            .thenRun(()->check(worlds.get(a).participantCount()==2 && worlds.get(b).participantCount()==1,"all_sessions_active_after_move"));
    }
    private CompletionStage<Void> basic(){return openAll().thenCompose(v->{check(codec.equivalent(two.capture(),fixture.apply(5)),"NBT_roundtrip_semantically_unchanged");log("NBT byte_equal="+two.capture().equals(fixture.apply(5)));one.count(31);return until(()->cycle && s1.save().revision()==1);})
        .thenCompose(v->verify(a1,31,1)).thenCompose(v->verify(a2,5,0)).thenCompose(v->verify(b3,7,0))
        .thenRun(()->check(autosave.period().getSeconds()==30 && System.nanoTime()-started>=30_000_000_000L,"real_30_second_session_path"));}
    private CompletionStage<Void> race(){
        one.count(30);coordinator.refresh(s1);s1.save().flush();one.count(29);oldOne=one;oldSession=s1;
        oldClosing=coordinator.disconnect(s1);check(oldClosing==coordinator.disconnect(s1),"duplicate_quit_same_future");one.connected=false;
        one=new Controlled();newSession=connect(a1,one);
        two.count(4);coordinator.refresh(s2);coordinator.disconnect(s2);two.connected=false;
        barrier=coordinator.barrier(a);
        check(!barrier.toCompletableFuture().isDone() && one.applies==0,"all_departing_saves_in_barrier_reconnect_waits");
        return until(()->worlds.get(a).state()==WorldLifecycle.State.QUIESCING).thenRun(()->check(!oldClosing.toCompletableFuture().isDone(),"world_unload_waits_for_quit_save"));
    }
    private CompletionStage<Void> raceDone(){return mainResult(newSession.ready()).thenCompose(v->{s1=newSession;check(one.applies==1 && oldOne.applies==1 && barrier.toCompletableFuture().isDone(),"fresh_session_after_final_save_old_callback_not_applied");return verify(a1,29,3);})
        .thenCompose(v->verify(a2,4,1)).thenRun(()->check(worlds.get(a).participantCount()==1 && worlds.get(b).participantCount()==1,"run_counts_and_other_run_preserved"));}
    private CompletionStage<Void> preparingQuit(){
        cancelled=new Controlled();oldSession=connect(a2,cancelled);
        return later(()->{}).thenRun(()->{
            check(oldSession.state()==PlayerSessions.State.PREPARING,"DB_restore_pending");oldClosing=coordinator.disconnect(oldSession);cancelled.connected=false;
            two=new Controlled();newSession=connect(a2,two);check(two.applies==0,"reconnect_during_prepare_waits");
        });
    }
    private CompletionStage<Void> preparingDone(){return mainResult(newSession.ready()).thenRun(()->{s2=newSession;check(cancelled.applies==0 && two.applies==1 && oldClosing.toCompletableFuture().isDone(),"cancelled_prepare_never_applies");});}
    private CompletionStage<Void> movingQuit(){
        return mainResult(coordinator.disconnect(s2)).thenCompose(v->{
            two.connected=false;cancelled=new Controlled();cancelled.movement=new CompletableFuture<>();oldSession=connect(a2,cancelled);
            return until(()->oldSession.state()==PlayerSessions.State.MOVING);
        }).thenCompose(v->{
            oldClosing=coordinator.disconnect(oldSession);cancelled.connected=false;two=new Controlled();newSession=connect(a2,two);
            check(!oldClosing.toCompletableFuture().isDone(),"quit_during_move_keeps_reservation");
            cancelled.movement.complete(true);return mainResult(newSession.ready());
        }).thenRun(()->{s2=newSession;check(worlds.get(a).participantCount()==2 && two.applies==1,"stale_move_does_not_commit_or_remove_new_session");});
    }
    private CompletionStage<Void> restored(){return openAll().thenCompose(v->verify(a1,29,3)).thenCompose(v->verify(a2,4,1)).thenCompose(v->verify(b3,7,0));}
    private CompletionStage<Void> moveFailure(){return mainResult(coordinator.disconnect(s2)).thenCompose(v->{two.connected=false;two=new Controlled();two.movement=CompletableFuture.completedFuture(false);s2=connect(a2,two);return expect(s2.ready(),"MOVE_FAILED");})
        .thenCompose(v->verify(a2,4,1)).thenRun(()->check(s2.state()==PlayerSessions.State.HOLD && !coordinator.canUnload(a),"move_failure_hold_original_preserved"));}
    private CompletionStage<Void> writeFailure(){one.count(28);coordinator.refresh(s1);return expect(coordinator.disconnect(s1),"23514").thenCompose(v->verify(a1,29,3)).thenRun(()->{
        check(s1.save().dirty() && s1.save().revision()==3 && s1.state()==PlayerSessions.State.HOLD && s2.state()==PlayerSessions.State.HOLD,"write_failure_dirty_revision_HOLD_preserved");
        expectedHold=true;
    }).thenCompose(v -> expect(connect(a1,new Controlled()).ready(),"23514")).thenCompose(v->until(()->worlds.get(a).state()==WorldLifecycle.State.ERROR_HOLD)).thenRun(()->check(worlds.get(b).state()==WorldLifecycle.State.ACTIVE,"A_hold_B_active"));}
    private CompletionStage<Void> generationPending(String stage,int n){
        UUID run=new UUID(42,n);var port=new Controlled();var session=connect(ctx(run,n),port);
        generationProbes.put(stage,session);probePorts.put(stage,port);
        return until(()->stage.equals(session.restoreStage())).thenCompose(v->later(()->{
            check(!session.ready().toCompletableFuture().isDone() && port.applies==0 && port.moves==0,"pending_"+stage+"_before_world_invalidation");
            worlds.get(run).invalidate("injected_"+stage+"_response_epoch");
        }));
    }
    private CompletionStage<Void> generationDone(String stage,int n){
        var session=generationProbes.get(stage);var port=probePorts.get(stage);var world=worlds.get(new UUID(42,n));
        return expect(session.ready(),"STALE_WORLD").thenCompose(v->verify(ctx(new UUID(42,n),n),5,0)).thenRun(()->{
            check(port.applies==0 && port.moves==0 && world.participantCount()==0 && world.pendingCount()==0
                && session.state()==PlayerSessions.State.HOLD && world.state()==WorldLifecycle.State.RECOVERY_REQUIRED,
                stage+"_world_generation_rejects_apply_move_commit_original_preserved");
            check(worlds.get(b).state()==WorldLifecycle.State.ACTIVE,"generation_probe_other_run_active");
        });
    }
    public CompletionStage<Void> barrier(UUID run){return coordinator.barrier(run);}
    public boolean canUnload(UUID run){return coordinator.canUnload(run);}
    public CompletionStage<Void> shutdown(){
        if(manual && !Bukkit.getOnlinePlayers().isEmpty())return CompletableFuture.failedFuture(new LifecycleFailure("MANUAL_PLAYER_STILL_ONLINE"));
        autosave.close();adapter.close();
        CompletionStage<Void> other=s3==null ? CompletableFuture.completedFuture(null) : coordinator.disconnect(s3);
        return mainResult(other).thenCompose(ignored -> coordinator.shutdown().handleAsync((v,error)->{
            if(error!=null && !(expectedHold && "23514".equals(s1.error()) && "MOVE_FAILED".equals(s2.error())
                    && generationProbes.size()==2 && generationProbes.values().stream().allMatch(s->s.state()==PlayerSessions.State.HOLD && "STALE_WORLD".equals(s.error()))
                    && Set.of("23514","MOVE_FAILED","STALE_WORLD").contains(LifecycleFailure.code(error))))throw new CompletionException(error);
            log("DRAIN "+(error==null ? "PASS" : "EXPECTED_HOLD_PRESERVED"));return null;
        },owner));
    }
    private CompletionStage<Void> linkExisting(ServerProgressAccess.Context context) {
        return mainResult(store.readPersonal(context)).thenCompose(v -> mainResult(store.readInventory(context))).thenAccept(saved -> {
            codec.decode(saved.payload());
            adapter.fixture(new PaperSessionAdapter.Link(context,worlds.get(context.storyRunId()),()->destination(context.storyRunId())));
        });
    }
    public void fixturePlayer(String name,boolean existing){
        main();if(!manual)throw new IllegalStateException("실제 프로필은 별도 수동 시험 환경에서만 준비합니다");
        var player=Bukkit.getWhitelistedPlayers().stream().filter(p->name.equals(p.getName())).findFirst().orElseThrow(()->new IllegalArgumentException("Whitelist profile required"));
        var context=access.forVerifiedServerLink(a,player.getUniqueId());
        CompletionStage<?> work=existing ? CompletableFuture.completedFuture(null) : mainResult(store.createPlayer(context,"fixture"))
            .thenCompose(v -> mainResult(store.createInventory(context,fixture.apply(32))));
        work.thenCompose(v -> linkExisting(context)).thenRun(()->log("FIXTURE READY"))
            .exceptionally(error->{log("FIXTURE HOLD "+LifecycleFailure.code(error));return null;});
    }
    public void playerStatus(String name){
        main();if(!manual)throw new IllegalStateException("수동 시험 전용입니다");
        var profile=Bukkit.getWhitelistedPlayers().stream().filter(p->name.equals(p.getName())).findFirst().orElseThrow();
        var context=access.forVerifiedServerLink(a,profile.getUniqueId());
        log("MANUAL "+adapter.status(profile.getUniqueId())+" world_state="+worlds.get(a).state()+" world_epoch="+worlds.get(a).generation());
        mainResult(store.readInventory(context)).thenAccept(saved->log("MANUAL_DB count="+codec.decode(saved.payload())[0].getAmount()+" revision="+saved.revision()))
            .exceptionally(error->{log("MANUAL_DB HOLD "+LifecycleFailure.code(error));return null;});
    }
    @Override public void close(){autosave.close();adapter.close();}
}
