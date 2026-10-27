package io.github.jesterportfolio.hearth.verification;

import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.lifecycle.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.session.*;
import io.github.jesterportfolio.hearth.quest.*;
import org.bukkit.configuration.file.YamlConfiguration;
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
    private PersonalQuest quests;
    private PaperQuestAdapter questAdapter;
    private QuestContent content;
    private QuestCatalog catalog;
    private PersonalQuest.Session multiOne,multiTwo;
    private CompletionStage<QuestStore.Saved> multiWriteOne,multiWriteTwo;
    private CompletionStage<PersonalQuest.Session> multiReopen;
    private CompletionStage<Void> multiBarrier;
    private final java.util.concurrent.atomic.AtomicBoolean multiValid=new java.util.concurrent.atomic.AtomicBoolean(true);
    private PersonalQuest.Session qa,qb;
    private PersonalQuest.Session autoOne,autoTwo,autoReport,autoOld;
    private CompletionStage<QuestStore.Saved> autoWrite,autoStaleWrite;
    private CompletionStage<PersonalQuest.Session> autoFresh;
    private final java.util.concurrent.atomic.AtomicBoolean autoValid=new java.util.concurrent.atomic.AtomicBoolean(true);
    private Object listToken=new Object();
    private CompletionStage<Map<String,QuestStore.Saved>> pendingList;
    private CompletionStage<Void> listBarrier;
    private long started=System.nanoTime();
    public SessionVerification(JavaPlugin plugin,PostgresProgressStore store,ServerProgressAccess access,Map<UUID,WorldLifecycle> worlds,
            Executor owner,PaperInventoryCodec codec,IntFunction<InventoryPayload> fixture) {
        this.plugin=plugin;this.store=store;this.access=access;this.worlds=new HashMap<>(worlds);this.owner=owner;this.codec=codec;this.fixture=fixture;
        if(plugin.getConfig().getBoolean("quest-verification-enabled")) {
            if(!Boolean.getBoolean("hearth.verification.quests"))throw new IllegalStateException("Quest gate missing");
            catalog=loadCatalog(plugin);
        }
        a1=ctx(a,1);a2=ctx(a,2);b3=ctx(b,3);
        autosave=new PaperInventoryAutosave(plugin,List.of(),this::refresh,() -> {
            if (!cycle) {cycle=true;log("AUTOSAVE elapsed_ms="+(System.nanoTime()-started)/1_000_000);}
        });
        coordinator=new PlayerSessions(store,store,owner,SessionVerification::main,p -> codec.decode(p),codec::equivalent,autosave::add,autosave::remove,c -> log("ERROR "+c));
        World waiting=Bukkit.getWorlds().getFirst();
        for (int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)waiting.getBlockAt(x,63,z).setType(Material.STONE,false);
        adapter=new PaperSessionAdapter(plugin,coordinator,owner,codec,new Location(waiting,0.5,64,0.5));
        if(catalog!=null) {
            content=catalog.all().stream().filter(c->c.id().equals("survey_marker")).findFirst().orElse(catalog.all().iterator().next());
            quests=new PersonalQuest(store,catalog.questIds(),owner,SessionVerification::main);
            questAdapter=new PaperQuestAdapter(plugin,adapter,quests,catalog,owner,
                Set.of(worlds.get(a).mapping().physicalId(),worlds.get(b).mapping().physicalId()));
        }
    }
    private static QuestCatalog loadCatalog(JavaPlugin plugin) {
        java.io.File file=new java.io.File(plugin.getDataFolder(),"quest-demo.yml");
        if(!file.exists())plugin.saveResource("quest-demo.yml",false);
        var yaml=YamlConfiguration.loadConfiguration(file);
        if(yaml.isSet("id") && !yaml.isSet("format")) {
            // 기존 파일을 덮어쓰지 않고 단일 콘텐츠의 옛 형식을 초기 로딩합니다.
            var npc=new HashMap<String,Object>(Objects.requireNonNull(yaml.getConfigurationSection("npc"),"Missing legacy NPC").getValues(false));npc.put("id","survey_guide");
            var quest=new HashMap<String,Object>(Map.of("id",Objects.requireNonNull(yaml.get("id")),"title",Objects.requireNonNull(yaml.get("title"),"Missing legacy content title"),"npc-id","survey_guide",
                "objective",Objects.requireNonNull(yaml.getConfigurationSection("objective"),"Missing legacy goal").getValues(false),
                "text",Objects.requireNonNull(yaml.getConfigurationSection("text"),"Missing legacy dialogue").getValues(false)));
            if(yaml.isSet("completion-mode"))quest.put("completion-mode",yaml.get("completion-mode"));
            return QuestCatalog.parse(List.of(npc),List.of(quest));
        }
        if(!(yaml.get("format") instanceof Number n) || n.doubleValue()!=2 || !(yaml.get("npcs") instanceof List<?> npcs) || !(yaml.get("quests") instanceof List<?> rows))
            throw new IllegalArgumentException("Unsupported or malformed quest content format");
        return QuestCatalog.parse(npcs,rows);
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
        case "quest-auto-prepare" -> autoPrepare();
        case "quest-auto-save-start" -> autoSaveStart();
        case "quest-auto-save-done" -> autoSaveDone();
        case "quest-auto-failure" -> autoFailure();
        case "quest-auto-stale-ready" -> autoStaleReady();
        case "quest-auto-stale-start" -> autoStaleStart();
        case "quest-auto-stale-done" -> autoStaleDone();
        case "quest-info-prepare" -> readFixture().thenCompose(v->infoCheck());
        case "quest-info-check" -> infoCheck();
        case "quest-info-pending-done" -> expectQuestCode(pendingList,"STALE_QUEST_LIST").thenCompose(v->mainResult(listBarrier)).thenCompose(v->infoCheck());
        case "quest-info-error" -> expect(quests.list(a1,s1,()->coordinator.playable(s1)),"42P01");
        case "quest-list-prepare" -> listPrepare();
        case "quest-list-check" -> listCheck();
        case "quest-list-restored" -> openAll().thenCompose(v->listCheck());
        case "quest-list-pending" -> listPending();
        case "quest-list-pending-done" -> expectQuestCode(pendingList,"STALE_QUEST_LIST").thenCompose(v->mainResult(listBarrier)).thenCompose(v->listCheck());
        case "quest-list-error" -> expect(quests.list(a1,s1,()->coordinator.playable(s1)),"42P01");
        case "quest-first" -> questFirst().thenCompose(v->multiFirst());
        case "quest-multi-ready" -> multiReady();
        case "quest-multi-inflight" -> multiInflight();
        case "quest-multi-done" -> multiDone();
        case "quest-restored" -> questRestored();
        case "quest-cycle" -> questCycle();
        case "quest-failure" -> questFailure();
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
    private void npcCheck() {
        for(UUID run:List.of(a,b)) {
            World w=Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId()));
            questAdapter.ensure(w);questAdapter.ensure(w);
            for(QuestContent c:catalog.all())check(questAdapter.count(w,c.npcId())==1 && questAdapter.dialogueAllowed(w,c),"quest_npc_single_interaction_flag_"+run+"_"+c.npcId());
        }
        check(content.contains(13,64,13) && !content.contains(2,64,2),"quest_content_visit_bounds");
    }
    private CompletionStage<Void> questRead(ServerProgressAccess.Context ctx,QuestStore.Phase phase,long revision) {
        return questRead(ctx,content.id(),phase,revision);
    }
    private CompletionStage<Void> questRead(ServerProgressAccess.Context ctx,String id,QuestStore.Phase phase,long revision) {
        return mainResult(store.readQuest(ctx,id)).thenAccept(row->check(row.phase()==phase && row.revision()==revision,"quest_row_"+ctx.storyRunId()+"_"+ctx.playerId()+"_"+id+"_"+phase+"_r"+revision));
    }
    private CompletionStage<Void> rejectQuest(CompletionStage<?> stage) {
        return mainResult(stage.handle((v,e)->{if(e==null)throw new IllegalStateException("Quest accepted invalid operation");return null;})).thenRun(()->check(true,"quest_invalid_or_duplicate_rejected"));
    }
    private CompletionStage<Void> questFirst() {
        if(quests==null)throw new IllegalStateException("Quest disabled");
        return openAll().thenRun(this::npcCheck)
            .thenCompose(v->quests.open(a1,content.id(),s1,()->coordinator.playable(s1)))
            .thenCompose(q->{qa=q;return rejectQuest(quests.advance(q,QuestStore.Phase.REPORT_READY));})
            .thenCompose(v->questRead(a1,QuestStore.Phase.NOT_ACCEPTED,-1))
            .thenCompose(v->{var first=quests.advance(qa,QuestStore.Phase.ACTIVE);var duplicate=rejectQuest(quests.advance(qa,QuestStore.Phase.ACTIVE));return first.thenCombine(duplicate,(row,ignored)->row);})
            .thenCompose(v->quests.advance(qa,QuestStore.Phase.REPORT_READY))
            .thenCompose(v->questRead(a1,QuestStore.Phase.REPORT_READY,1))
            .thenCompose(v->{var first=quests.advance(qa,QuestStore.Phase.COMPLETED);var duplicate=rejectQuest(quests.advance(qa,QuestStore.Phase.COMPLETED));return first.thenCombine(duplicate,(row,ignored)->row);})
            .thenCompose(v->questRead(a1,QuestStore.Phase.COMPLETED,2))
            .thenCompose(v->questRead(a2,QuestStore.Phase.NOT_ACCEPTED,-1))
            .thenCompose(v->questRead(ctx(b,1),QuestStore.Phase.NOT_ACCEPTED,-1))
            .thenCompose(v->quests.open(a2,content.id(),s2,()->coordinator.playable(s2)))
            .thenCompose(q->{qb=q;return quests.advance(q,QuestStore.Phase.ACTIVE);})
            .thenCompose(v->quests.advance(qb,QuestStore.Phase.REPORT_READY))
            .thenCompose(v->questRead(a1,QuestStore.Phase.COMPLETED,2))
            .thenCompose(v->questRead(ctx(b,1),QuestStore.Phase.NOT_ACCEPTED,-1))
            .thenCompose(v->rejectQuest(store.readQuest(ctx(a,99),content.id())))
            .thenCompose(v->rejectQuest(store.advanceQuest(a1,content.id(),new QuestStore.Saved(QuestStore.Phase.ACTIVE,0),QuestStore.Phase.REPORT_READY)))
            .thenRun(()->check(true,"quest_personal_A1_A2_B1_isolation_CAS"));
    }
    private CompletionStage<Void> seedList(ServerProgressAccess.Context c,String id,QuestStore.Phase phase) {
        CompletionStage<QuestStore.Saved> work=mainResult(store.readQuest(c,id));
        for(int step=1;step<=phase.ordinal();step++) {
            final QuestStore.Phase next=QuestStore.Phase.values()[step];
            work=work.thenCompose(row->mainResult(store.advanceQuest(c,id,row,next)));
        }
        return work.thenAccept(row->check(row.phase()==phase,"list_fixture_"+id+"_"+phase));
    }
    private CompletionStage<Void> listPrepare() { return readFixture().thenCompose(v->listCheck()); }
    private CompletionStage<Void> readFixture() {
        return openAll().thenCompose(v->mainResult(store.createPlayer(ctx(b,2),"list-empty")))
            .thenCompose(v->seedList(a1,"survey_marker",QuestStore.Phase.COMPLETED))
            .thenCompose(v->seedList(a1,"path_marker",QuestStore.Phase.ACTIVE))
            .thenCompose(v->seedList(a2,"survey_marker",QuestStore.Phase.REPORT_READY))
            .thenCompose(v->seedList(ctx(b,1),"survey_marker",QuestStore.Phase.ACTIVE));
    }
    private CompletionStage<Map<String,QuestStore.Saved>> listSnapshot(ServerProgressAccess.Context c) {
        CompletionStage<Map<String,QuestStore.Saved>> work=CompletableFuture.completedFuture(new HashMap<>());
        for(String id:new TreeSet<>(catalog.questIds()))work=work.thenCompose(rows->mainResult(store.readQuest(c,id)).thenApply(row->{rows.put(id,row);return rows;}));
        return work;
    }
    private CompletionStage<Void> listOwner(ServerProgressAccess.Context c,Map<String,QuestStore.Saved> expected) {
        BooleanSupplier valid=()->worlds.get(c.storyRunId()).state()==WorldLifecycle.State.ACTIVE;
        return listSnapshot(c).thenCompose(before->{
            check(before.equals(expected),"list_existing_rows_"+c.storyRunId()+"_"+c.playerId());
            return quests.list(c,new Object(),valid).thenCompose(rows->{
                check(rows.equals(expected),"list_owner_isolation_"+c.storyRunId()+"_"+c.playerId());
                var entries=QuestListView.entries(catalog,rows);var lines=QuestListView.lines(entries);
                long count=expected.values().stream().filter(row->row.phase()!=QuestStore.Phase.NOT_ACCEPTED).count();
                check(entries.size()==count,"list_unaccepted_filtered_"+c.storyRunId()+"_"+c.playerId());
                if(count==0)check(lines.equals(List.of("수락한 퀘스트가 없습니다")),"list_normal_empty_exact_message");
                for(var e:entries)check(lines.contains("["+e.status()+"] "+catalog.quest(e.questId()).title()),"list_Korean_display_"+e.status());
                return listSnapshot(c).thenAccept(after->check(before.equals(after),"list_DB_rows_revisions_unchanged_"+c.storyRunId()+"_"+c.playerId()));
            });
        });
    }
    private CompletionStage<Void> listCheck() {
        var missing=new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1);
        return listOwner(a1,Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.COMPLETED,2),"path_marker",new QuestStore.Saved(QuestStore.Phase.ACTIVE,0)))
            .thenCompose(v->listOwner(a2,Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.REPORT_READY,1),"path_marker",missing)))
            .thenCompose(v->listOwner(ctx(b,1),Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.ACTIVE,0),"path_marker",missing)))
            .thenCompose(v->listOwner(ctx(b,2),Map.of("survey_marker",missing,"path_marker",missing)));
    }
    private CompletionStage<Void> infoOwner(ServerProgressAccess.Context c,Map<String,QuestStore.Saved> expected) {
        return listSnapshot(c).thenCompose(before->quests.list(c,new Object(),()->worlds.get(c.storyRunId()).state()==WorldLifecycle.State.ACTIVE).thenCompose(rows->{
            check(rows.equals(expected) && rows.equals(before),"info_owner_scope_"+c.storyRunId()+"_"+c.playerId());
            quests.requireListDisplay(c,rows);
            var entries=QuestListView.entries(catalog,rows);var messages=PaperQuestAdapter.listMessages(catalog,rows);
            if(entries.isEmpty())check(messages.equals(List.of(Component.text("수락한 퀘스트가 없습니다"))) && messages.getFirst().clickEvent()==null,"info_empty_list_has_no_click_target");
            else {
                check(messages.size()==entries.size(),"info_clickable_list_size");
                for(int i=0;i<entries.size();i++) {
                    var event=Objects.requireNonNull(messages.get(i).clickEvent());
                    String command=QuestListView.infoCommand(entries.get(i));
                    check(event.equals(net.kyori.adventure.text.event.ClickEvent.runCommand(command))
                        && QuestListView.infoId(command).orElseThrow().equals(entries.get(i).questId()),"info_list_click_command_"+entries.get(i).questId());
                }
            }
            for(String id:catalog.questIds()) {
                var detail=QuestListView.detail(catalog,rows,id);var components=PaperQuestAdapter.infoMessages(catalog,rows,id);
                if(rows.get(id).phase()==QuestStore.Phase.NOT_ACCEPTED)check(detail.isEmpty() && components.equals(PaperQuestAdapter.infoMessages(catalog,rows,"missing")),"info_unaccepted_unknown_same_response_"+id);
                else {
                    var data=detail.orElseThrow();String key=switch(rows.get(id).phase()){case ACTIVE->"active";case REPORT_READY->"report-ready";case COMPLETED->"completed";default->throw new IllegalStateException();};
                    check(data.quest().title().equals(catalog.quest(id).title()) && data.npc().equals(catalog.quest(id).name()) && data.guidance().equals(catalog.quest(id).text().get(key)),"info_content_stage_"+rows.get(id).phase());
                    check(components.equals(QuestListView.detailLines(detail).stream().map(Component::text).toList()),"info_Paper_components_"+rows.get(id).phase());
                }
            }
            return listSnapshot(c).thenAccept(after->check(before.equals(after),"info_DB_rows_revisions_unchanged_"+c.storyRunId()+"_"+c.playerId()));
        }));
    }
    private CompletionStage<Void> infoCheck() {
        var missing=new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1);
        return infoOwner(a1,Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.COMPLETED,2),"path_marker",new QuestStore.Saved(QuestStore.Phase.ACTIVE,0)))
            .thenCompose(v->infoOwner(a2,Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.REPORT_READY,1),"path_marker",missing)))
            .thenCompose(v->infoOwner(ctx(b,1),Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.ACTIVE,0),"path_marker",missing)))
            .thenCompose(v->infoOwner(ctx(b,2),Map.of("survey_marker",missing,"path_marker",missing)));
    }
    private CompletionStage<Void> listPending() {
        Object captured=listToken;
        pendingList=quests.list(a1,s1,()->listToken==captured && coordinator.playable(s1));listBarrier=quests.barrier(a);
        return later(()->{
            check(!pendingList.toCompletableFuture().isDone() && !listBarrier.toCompletableFuture().isDone(),"list_async_read_barrier_waits_without_main_block");
            check(quests.list(a1,s1,()->true).toCompletableFuture().isCompletedExceptionally(),"list_duplicate_read_limited");
            listToken=new Object();check(true,"list_controlled_session_token_replaced");
        });
    }
    private CompletionStage<QuestStore.Saved> visit(PersonalQuest owner,PersonalQuest.Session session,QuestContent c) {
        return owner.reach(session,c,(c.minX()+c.maxX())/2,c.minY(),(c.minZ()+c.maxZ())/2);
    }
    private CompletionStage<Void> autoPrepare() {
        check(catalog.quest("survey_marker").completionMode()==QuestContent.CompletionMode.REPORT && catalog.quest("path_marker").completionMode()==QuestContent.CompletionMode.AUTOMATIC,"auto_both_content_modes");
        return openAll().thenCompose(v->quests.open(a1,"path_marker",s1,()->coordinator.playable(s1)))
            .thenCompose(q->{autoOne=q;return rejectQuest(visit(quests,q,catalog.quest("path_marker")));})
            .thenCompose(v->quests.advance(autoOne,QuestStore.Phase.ACTIVE))
            .thenCompose(v->rejectQuest(quests.advance(autoOne,QuestStore.Phase.COMPLETED)))
            .thenCompose(v->rejectQuest(quests.reach(autoOne,catalog.quest("path_marker"),2,64,2)))
            .thenCompose(v->quests.open(a2,"path_marker",s2,()->coordinator.playable(s2)))
            .thenCompose(q->{autoTwo=q;return quests.advance(q,QuestStore.Phase.ACTIVE);})
            .thenCompose(v->seedList(ctx(b,1),"path_marker",QuestStore.Phase.REPORT_READY))
            .thenCompose(v->quests.open(ctx(b,1),"path_marker",new Object(),()->true))
            .thenCompose(q->rejectQuest(visit(quests,q,catalog.quest("path_marker"))))
            .thenCompose(v->questRead(ctx(b,1),"path_marker",QuestStore.Phase.REPORT_READY,1))
            .thenCompose(v->quests.open(a1,"survey_marker",s1,()->coordinator.playable(s1)))
            .thenCompose(q->{autoReport=q;return quests.advance(q,QuestStore.Phase.ACTIVE);})
            .thenCompose(v->visit(quests,autoReport,catalog.quest("survey_marker")))
            .thenCompose(v->questRead(a1,"survey_marker",QuestStore.Phase.REPORT_READY,1))
            .thenCompose(v->questRead(a1,"path_marker",QuestStore.Phase.ACTIVE,0))
            .thenCompose(v->questRead(a2,"path_marker",QuestStore.Phase.ACTIVE,0));
    }
    private CompletionStage<Void> autoSaveStart() {
        autoWrite=visit(quests,autoOne,catalog.quest("path_marker"));
        return rejectQuest(visit(quests,autoOne,catalog.quest("path_marker")))
            .thenRun(()->check(!autoWrite.toCompletableFuture().isDone() && autoOne.saved().equals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,0)),"auto_DB_pending_no_completion_or_duplicate"));
    }
    private CompletionStage<Void> autoDisplay() {
        return quests.list(a1,s1,()->coordinator.playable(s1)).thenAccept(rows->{
            quests.requireListDisplay(a1,rows);
            var detail=QuestListView.detail(catalog,rows,"path_marker").orElseThrow();
            check(detail.quest().phase()==QuestStore.Phase.COMPLETED && detail.quest().status().equals("완료")
                && detail.guidance().equals(catalog.quest("path_marker").text().get("completed")),"auto_list_info_completed_after_DB_success");
            check(PaperQuestAdapter.listMessages(catalog,rows).size()==2 && PaperQuestAdapter.infoMessages(catalog,rows,"path_marker").size()==4,"auto_existing_Paper_display_path");
        });
    }
    private CompletionStage<Void> autoSaveDone() {
        return mainResult(autoWrite).thenAccept(row->check(row.equals(new QuestStore.Saved(QuestStore.Phase.COMPLETED,1)),"auto_direct_COMPLETED_revision1"))
            .thenCompose(v->rejectQuest(visit(quests,autoOne,catalog.quest("path_marker"))))
            .thenCompose(v->expect(store.advanceQuest(a1,"path_marker",new QuestStore.Saved(QuestStore.Phase.ACTIVE,0),QuestStore.Phase.COMPLETED),"REVISION_CONFLICT_OR_MISSING"))
            .thenCompose(v->questRead(a1,"path_marker",QuestStore.Phase.COMPLETED,1))
            .thenCompose(v->questRead(a2,"path_marker",QuestStore.Phase.ACTIVE,0))
            .thenCompose(v->questRead(ctx(b,1),"path_marker",QuestStore.Phase.REPORT_READY,1))
            .thenCompose(v->questRead(a1,"survey_marker",QuestStore.Phase.REPORT_READY,1))
            .thenCompose(v->quests.advance(autoReport,QuestStore.Phase.COMPLETED))
            .thenCompose(v->questRead(a1,"survey_marker",QuestStore.Phase.COMPLETED,2))
            .thenCompose(v->autoDisplay());
    }
    private CompletionStage<Void> autoFailure() {
        var probe=new PersonalQuest(store,catalog.questIds(),owner,SessionVerification::main);
        return probe.open(a2,"path_marker",new Object(),()->true).thenCompose(q->expect(visit(probe,q,catalog.quest("path_marker")),"23514")
            .thenCompose(v->rejectQuest(visit(probe,q,catalog.quest("path_marker"))))
            .thenRun(()->check(q.held() && q.saved().equals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,0)),"auto_SQL_failure_ACTIVE_HOLD_no_success"))
            .thenCompose(v->rejectQuest(probe.barrier(a))))
            .thenCompose(v->questRead(a2,"path_marker",QuestStore.Phase.ACTIVE,0))
            .thenCompose(v->questRead(a1,"path_marker",QuestStore.Phase.COMPLETED,1))
            .thenCompose(v->questRead(a1,"survey_marker",QuestStore.Phase.COMPLETED,2));
    }
    private CompletionStage<Void> autoStaleReady() {
        return quests.open(a2,"path_marker",new Object(),autoValid::get).thenAccept(q->autoOld=q);
    }
    private CompletionStage<Void> autoStaleStart() {
        autoStaleWrite=visit(quests,autoOld,catalog.quest("path_marker"));autoValid.set(false);
        autoFresh=quests.open(a2,"path_marker",new Object(),()->true);
        check(!autoStaleWrite.toCompletableFuture().isDone() && !autoFresh.toCompletableFuture().isDone(),"auto_reconnect_waits_inflight_write");
        return CompletableFuture.completedFuture(null);
    }
    private CompletionStage<Void> autoStaleDone() {
        return expectQuestCode(autoStaleWrite,"STALE_QUEST").thenCompose(v->mainResult(autoFresh))
            .thenAccept(q->check(q.saved().equals(new QuestStore.Saved(QuestStore.Phase.COMPLETED,1)) && autoOld.saved().equals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,0)),"auto_old_callback_rejected_new_session_reads_DB"))
            .thenCompose(v->questRead(a2,"path_marker",QuestStore.Phase.COMPLETED,1))
            .thenCompose(v->questRead(ctx(b,1),"path_marker",QuestStore.Phase.REPORT_READY,1))
            .thenCompose(v->autoDisplay());
    }
    private CompletionStage<Void> matrix() {
        return questRead(a1,"survey_marker",QuestStore.Phase.COMPLETED,2)
            .thenCompose(v->questRead(a1,"path_marker",QuestStore.Phase.ACTIVE,0))
            .thenCompose(v->questRead(a2,"survey_marker",QuestStore.Phase.COMPLETED,2))
            .thenCompose(v->questRead(a2,"path_marker",QuestStore.Phase.REPORT_READY,1))
            .thenCompose(v->questRead(ctx(b,1),"survey_marker",QuestStore.Phase.NOT_ACCEPTED,-1))
            .thenCompose(v->questRead(ctx(b,1),"path_marker",QuestStore.Phase.REPORT_READY,1));
    }
    private CompletionStage<Void> multiFirst() {
        check(catalog.questIds().equals(Set.of("survey_marker","path_marker")),"multi_fixture_two_explicit_ids");
        var d=new QuestDialogue(catalog);Object session=new Object();UUID world=worlds.get(a).mapping().physicalId(),entity=UUID.randomUUID();
        var ticket=d.begin(a1.playerId(),session,a,world,entity,"survey_guide","survey_marker");
        var request=QuestDialogue.Request.parse(d.command(ticket,"accept"));
        try {d.authorize(a1.playerId(),session,a,world,entity,new QuestDialogue.Request("accept","path_guide","path_marker",request.nonce()));throw new AssertionError("Wrong link allowed");}
        catch(IllegalArgumentException good){check(true,"multi_wrong_NPC_quest_request_rejected");}
        d.authorize(a1.playerId(),session,a,world,entity,request);
        try {d.authorize(a1.playerId(),session,a,world,entity,request);throw new AssertionError("Duplicate allowed");}
        catch(IllegalArgumentException good){check(true,"multi_duplicate_request_rejected");}
        return questRead(a1,"path_marker",QuestStore.Phase.NOT_ACCEPTED,-1)
            .thenCompose(v->questRead(a2,"path_marker",QuestStore.Phase.NOT_ACCEPTED,-1))
            .thenCompose(v->questRead(ctx(b,1),"path_marker",QuestStore.Phase.NOT_ACCEPTED,-1))
            .thenCompose(v->quests.open(a1,"path_marker",s1,()->coordinator.playable(s1)))
            .thenCompose(q->quests.advance(q,QuestStore.Phase.ACTIVE))
            .thenCompose(v->quests.open(a2,"path_marker",s2,()->coordinator.playable(s2)))
            .thenCompose(q->quests.advance(q,QuestStore.Phase.ACTIVE))
            .thenCompose(v->quests.open(ctx(b,1),"path_marker",new Object(),()->worlds.get(b).state()==WorldLifecycle.State.ACTIVE))
            .thenCompose(q->quests.advance(q,QuestStore.Phase.ACTIVE).thenCompose(v->quests.advance(q,QuestStore.Phase.REPORT_READY)))
            .thenCompose(v->questRead(a1,"survey_marker",QuestStore.Phase.COMPLETED,2))
            .thenCompose(v->questRead(a2,"survey_marker",QuestStore.Phase.REPORT_READY,1))
            .thenCompose(v->questRead(ctx(b,1),"survey_marker",QuestStore.Phase.NOT_ACCEPTED,-1))
            .thenRun(()->check(true,"multi_A1_A2_B1_two_quest_isolation"));
    }
    private CompletionStage<Void> multiReady() {
        return quests.open(a2,"survey_marker",new Object(),multiValid::get).thenCompose(q->{multiOne=q;return quests.open(a2,"path_marker",new Object(),multiValid::get);})
            .thenAccept(q->multiTwo=q);
    }
    private CompletionStage<Void> multiInflight() {
        multiWriteOne=quests.advance(multiOne,QuestStore.Phase.COMPLETED);
        multiWriteTwo=quests.advance(multiTwo,QuestStore.Phase.REPORT_READY);
        multiBarrier=quests.barrier(a);
        check(!multiWriteOne.toCompletableFuture().isDone() && !multiWriteTwo.toCompletableFuture().isDone() && !multiBarrier.toCompletableFuture().isDone(),"multi_two_inflight_barrier_waits");
        multiValid.set(false);
        multiReopen=quests.open(a2,"survey_marker",new Object(),()->true);
        check(!multiReopen.toCompletableFuture().isDone(),"multi_reconnect_waits_old_quest_write");
        return CompletableFuture.completedFuture(null);
    }
    private CompletionStage<Void> expectQuestCode(CompletionStage<?> work,String code) {
        return mainResult(work.handle((v,e)->{
            if(e==null)throw new IllegalStateException("Expected quest rejection");
            while(e instanceof CompletionException || e instanceof ExecutionException)e=e.getCause();
            if(!code.equals(e.getMessage()))throw new IllegalStateException("Unexpected quest rejection");return null;
        })).thenRun(()->check(true,"multi_rejected_"+code));
    }
    private CompletionStage<Void> multiDone() {
        return expectQuestCode(multiWriteOne,"STALE_QUEST").thenCompose(v->expectQuestCode(multiWriteTwo,"STALE_QUEST"))
            .thenCompose(v->mainResult(multiBarrier)).thenCompose(v->mainResult(multiReopen))
            .thenAccept(q->{check(q.saved().phase()==QuestStore.Phase.COMPLETED && multiOne.saved().phase()==QuestStore.Phase.REPORT_READY
                && multiTwo.saved().phase()==QuestStore.Phase.ACTIVE,"multi_stale_callbacks_no_old_memory_apply");})
            .thenCompose(v->matrix());
    }
    private CompletionStage<Void> questRestored() {
        return openAll().thenRun(this::npcCheck).thenCompose(v->matrix())
            .thenCompose(v->quests.open(a1,content.id(),s1,()->coordinator.playable(s1)))
            .thenCompose(q->{qa=q;return rejectQuest(quests.advance(q,QuestStore.Phase.COMPLETED));})
            .thenCompose(v->verify(a1,32,0)).thenCompose(v->verify(a2,5,0)).thenCompose(v->verify(b3,7,0))
            .thenRun(()->check(true,"multi_new_JVM_completed_without_recreation"));
    }
    private CompletionStage<Void> questCycle() {
        UUID physical=worlds.get(a).mapping().physicalId();
        return mainResult(coordinator.disconnect(s1)).thenCompose(v->mainResult(coordinator.disconnect(s2)))
            .thenCompose(v->until(()->worlds.get(a).state()==WorldLifecycle.State.OFFLINE))
            .thenRun(()->check(Bukkit.getWorld(physical)==null && !questAdapter.tracks(physical),"multi_NPC_unloaded_no_world_reference"))
            .thenCompose(v->{one=new Controlled();two=new Controlled();s1=connect(a1,one);s2=connect(a2,two);return mainResult(s1.ready()).thenCompose(x->mainResult(s2.ready()));})
            .thenRun(this::npcCheck).thenCompose(v->matrix())
            .thenCompose(v->quests.open(a1,content.id(),s1,()->coordinator.playable(s1)))
            .thenAccept(q->{qa=q;check(q.saved().phase()==QuestStore.Phase.COMPLETED && q.saved().revision()==2,"multi_reconnect_reload_completed_retained");});
    }
    private CompletionStage<Void> questFailure() {
        var probe=new PersonalQuest(store,catalog.questIds(),owner,SessionVerification::main);
        return probe.open(a2,"path_marker",new Object(),()->true).thenCompose(q->rejectQuest(probe.advance(q,QuestStore.Phase.COMPLETED))
            .thenRun(()->check(q.held() && q.saved().phase()==QuestStore.Phase.REPORT_READY && q.saved().revision()==1,"multi_SQL_failure_HOLD_old_state_preserved"))
            .thenCompose(v->rejectQuest(probe.barrier(a))))
            .thenCompose(v->matrix());
    }
    public CompletionStage<Void> barrier(UUID run){return coordinator.barrier(run).thenComposeAsync(v->quests==null ? CompletableFuture.completedFuture(null):quests.barrier(run),owner);}
    public boolean canUnload(UUID run){return coordinator.canUnload(run) && (quests==null || quests.settled(run));}
    public void releaseQuests(UUID run){if(quests!=null)quests.release(run);}
    public CompletionStage<Void> shutdown(){
        if(manual && !Bukkit.getOnlinePlayers().isEmpty())return CompletableFuture.failedFuture(new LifecycleFailure("MANUAL_PLAYER_STILL_ONLINE"));
        autosave.close();adapter.close();
        CompletionStage<Void> other=s3==null ? CompletableFuture.completedFuture(null) : coordinator.disconnect(s3);
        return mainResult(other).thenCompose(ignored -> coordinator.shutdown().handleAsync((v,error)->{
            if(error!=null && !(expectedHold && "23514".equals(s1.error()) && "MOVE_FAILED".equals(s2.error())
                    && generationProbes.size()==2 && generationProbes.values().stream().allMatch(s->s.state()==PlayerSessions.State.HOLD && "STALE_WORLD".equals(s.error()))
                    && Set.of("23514","MOVE_FAILED","STALE_WORLD").contains(LifecycleFailure.code(error))))throw new CompletionException(error);
            log("DRAIN "+(error==null ? "PASS" : "EXPECTED_HOLD_PRESERVED"));return null;
        },owner)).thenCompose(v->quests==null?CompletableFuture.completedFuture(null):quests.shutdown());
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
        if(quests!=null)for(QuestContent c:catalog.all())mainResult(store.readQuest(context,c.id())).thenAccept(row->log("MANUAL_QUEST id="+c.id()+" phase="+row.phase()+" revision="+row.revision()))
            .exceptionally(error->{log("MANUAL_QUEST HOLD "+LifecycleFailure.code(error));return null;});
        log("MANUAL "+adapter.status(profile.getUniqueId())+" world_state="+worlds.get(a).state()+" world_epoch="+worlds.get(a).generation());
        mainResult(store.readInventory(context)).thenAccept(saved->log("MANUAL_DB count="+codec.decode(saved.payload())[0].getAmount()+" revision="+saved.revision()))
            .exceptionally(error->{log("MANUAL_DB HOLD "+LifecycleFailure.code(error));return null;});
    }
    @Override public void close(){if(questAdapter!=null)questAdapter.close();autosave.close();adapter.close();}
}
