package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.session.*;
import io.github.jesterportfolio.hearth.quest.*;
import io.github.jesterportfolio.hearth.exploration.*;
import io.github.jesterportfolio.hearth.features.*;
import io.github.jesterportfolio.hearth.lifecycle.*;
import io.github.jesterportfolio.hearth.verification.StoryRuntimeVerification;
import io.github.jesterportfolio.hearth.verification.StoryPreparationVerification;
import io.github.jesterportfolio.hearth.administration.*;
import io.github.jesterportfolio.hearth.combat.CombatPlayers;
import io.github.jesterportfolio.hearth.util.ShutdownCleanup;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;

/** 시험 fixture를 생성하지 않는 명시적 스토리 실행. 준비한 월드/고정 계정 연결만 사용합니다. */
public final class StoryRuntime implements AutoCloseable {
    public enum Phase{STARTING,READY,DRAINING,DRAINED,HOLD,CLOSED}
    private final JavaPlugin plugin;private final StoryRuntimeSettings settings;private final StoryContentBundle content;private final ConcurrentLinkedQueue<Runnable> queue=new ConcurrentLinkedQueue<>();private final Executor owner=queue::add;
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"hearth-story-world-io");t.setDaemon(true);return t;});
    private final BukkitTask pump;private final PaperInventoryCodec codec=new PaperInventoryCodec();private final ServerProgressAccess access=new ServerProgressAccess();private final NamespacedKey inventoryOwner;
    private final Map<UUID,Scope> scopes=new LinkedHashMap<>();private final Map<UUID,WorldLifecycle> worlds=new LinkedHashMap<>();
    private volatile Phase phase=Phase.STARTING;private PostgresProgressStore database;private StoryRegistry registry;private RuntimeLease.Lease lease;
    private PaperInventoryAutosave autosave;private PlayerSessions sessions;private PaperSessionAdapter players;private PersonalQuest quests;private QuestJournal journal;private SharedStory shared;private PaperQuestAdapter paperQuests;private PaperStoryAdapter paperStory;private S4Features features;private StoryModules modules;private StoryAdmission admissions;
    private StoryInspection inspection;private PersonalStorage storage;private PaperStoryEffects effects;
    private CompletableFuture<Void> draining;private boolean clean,closed,started,gameplayClosed;
    public StoryRuntime(JavaPlugin plugin)throws Exception{
        this.plugin=plugin;main();settings=StoryRuntimeSettings.parse(Objects.requireNonNull(plugin.getConfig().getConfigurationSection("story-runtime")).getValues(false));
        if(!Bukkit.getOnlineMode()||!Bukkit.hasWhitelist()||!Bukkit.getOnlinePlayers().isEmpty()||!plugin.getConfig().getBoolean("phone-dialog-enabled")||!"ollama".equals(plugin.getConfig().getString("phone-ai-mode")))throw new IllegalStateException("STORY_RUNTIME_AUTH_AI_REQUIRED");
        var directory=plugin.getDataFolder().toPath().toAbsolutePath();if(!directory.toRealPath().equals(directory))throw new IllegalStateException("STORY_RUNTIME_DIRECTORY");
        var file=directory.resolve(settings.contentFile());if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>1024*1024)throw new IllegalStateException("STORY_CONTENT_REQUIRED");
        byte[] bytes=Files.readAllBytes(file);if(!settings.contentHash().equals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))))throw new IllegalStateException("STORY_CONTENT_HASH_CONFLICT");
        var yaml=new YamlConfiguration();yaml.loadFromString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));content=StoryContentBundle.parse(map(yaml));inventoryOwner=new NamespacedKey(plugin,"story_inventory_run");
        pump=Bukkit.getScheduler().runTaskTimer(plugin,this::pump,1,1);Objects.requireNonNull(plugin.getCommand("hearthstoryadmin")).setExecutor((sender,command,label,args)->{console(sender instanceof org.bukkit.command.ConsoleCommandSender,args,sender::sendMessage);return true;});
        try{
            var source=new PGSimpleDataSource();source.setServerNames(new String[]{settings.dbHost()});source.setPortNumbers(new int[]{settings.dbPort()});source.setDatabaseName(settings.dbName());source.setUser(settings.dbUser());String password=System.getenv(settings.passwordEnvironment());if(password==null)throw new IllegalStateException("STORY_PASSWORD_ENVIRONMENT");source.setPassword(password);source.setConnectTimeout(2);source.setSocketTimeout(5);source.setPrepareThreshold(0);
            inspection=new StoryInspection(source,settings,content.scenes().scenes(),this::inspectionRuntime,owner,StoryRuntime::main,f->plugin.getLogger().warning("HEARTH_STORY_INSPECTION_ERROR code="+f.code()));
            Objects.requireNonNull(plugin.getCommand("hearthrecovery")).setExecutor((sender,command,label,args)->{recovery(sender instanceof org.bukkit.command.ConsoleCommandSender,args,sender::sendMessage).exceptionally(error->{owner.execute(()->sender.sendMessage("관리 진단 보류 code="+RecoveryConsole.errorCode(error)));return null;});return true;});
            var claim=RuntimeLease.acquire(directory.resolve("story-runtime.active"),"Hearth story active v1\n"+settings.contentHash()+"\n");if(claim.isEmpty()){hold("UNCLEAN_START");return;}lease=claim.orElseThrow();
            database=new PostgresProgressStore(source,access,f->plugin.getLogger().warning("HEARTH_STORY_DB_ERROR code="+f.code()));registry=new StoryRegistry(database,access);
            for(var run:settings.runs().values()){var scope=new Scope(run);scopes.put(run.binding().run(),scope);worlds.put(run.binding().run(),scope.lifecycle);}
            database.migrate().thenCompose(v->registry.migrate()).thenCompose(v->{CompletionStage<Void> ready=CompletableFuture.completedFuture(null);for(var run:settings.runs().values())ready=ready.thenCompose(x->registry.ensure(run.binding()));return ready;}).thenRunAsync(()->{if(closed||phase!=Phase.STARTING)throw new IllegalStateException("STORY_START_STALE");gameplay();started=true;phase=Phase.READY;plugin.getLogger().info("HEARTH_STORY_RUNTIME_READY; explicit hearthai prepare remains required before inference");},owner).whenCompleteAsync((v,error)->{if(error!=null)hold("STARTUP_FAILED");},owner);
        }catch(Exception failure){hold("STARTUP_FAILED");}
    }
    public static Map<String,Object> map(ConfigurationSection section){var result=new LinkedHashMap<String,Object>();section.getValues(false).forEach((k,v)->result.put(k,v instanceof ConfigurationSection child?map(child):v));return result;}
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("STORY_RUNTIME_MAIN");}
    private void pump(){main();for(int i=0;i<200;i++){var work=queue.poll();if(work==null)break;try{work.run();}catch(RuntimeException error){hold("OWNER_DELIVERY_FAILED");}}}
    private void hold(String code){if(closed)return;phase=Phase.HOLD;plugin.getLogger().warning("HEARTH_STORY_RUNTIME_HOLD code="+code);}
    private void gameplay(){
        autosave=new PaperInventoryAutosave(plugin,List.of(),()->{if(sessions!=null)sessions.refreshAll();},()->{});var featureStore=new FeatureStore(database,access);storage=new PersonalStorage(database,featureStore,owner,StoryRuntime::main,codec::equivalent);
        sessions=new PlayerSessions(database,storage,owner,StoryRuntime::main,codec::decode,codec::equivalent,autosave::add,autosave::remove,code->plugin.getLogger().warning("HEARTH_STORY_SESSION_HOLD code="+code));
        players=new PaperSessionAdapter(plugin,sessions,owner,codec,Bukkit.getWorlds().getFirst().getSpawnLocation());players.interactionReady(()->phase==Phase.READY);players.provisioner(this::provision);
        quests=new PersonalQuest(database,content.quests().questIds(),owner,StoryRuntime::main);quests.requireRules(content.quests().requiredRules());database.requireQuestRules(content.quests().requiredRules());quests.rules(content.rules());database.configureQuestRules(content.rules());
        journal=new QuestJournal(database,quests,content.quests(),owner,StoryRuntime::main);shared=new SharedStory(database,content.shared(),owner,StoryRuntime::main,players::onlineContexts,System::nanoTime,Duration.ofSeconds(30));journal.sharedStory(shared,new StoryAccess(shared));
        effects=new PaperStoryEffects(plugin,content.effects(),settings.runs(),worlds,shared,owner);shared.effects(effects);
        journal.exploration(database,content.exploration(),new CommunityBoundary(players::community,(ctx,online)->shared.condition(ctx,online)));journal.completion(database,content.rules());quests.beforeChapter(run->{journal.invalidateRun(run);return journal.barrier(run);});
        var facts=new HashMap<String,ExplorationContent.Fact>();content.exploration().questFacts().forEach((q,f)->facts.put(q,content.exploration().facts().get(f)));database.configureQuestDiary(facts);
        var rewards=new QuestRewards(content.rules(),database,codec,owner);rewards.changed(journal::invalidateCompletion);
        paperQuests=new PaperQuestAdapter(plugin,players,quests,content.quests(),journal,owner,new HashSet<>(worlds.values().stream().map(w->w.mapping().physicalId()).toList()),content.modules().integrations());paperQuests.completion(content.rules(),rewards,database);paperStory=new PaperStoryAdapter(plugin,players,shared,owner);paperQuests.story(paperStory);
        modules=new StoryModules(plugin,content,database,access,players,sessions,journal,quests,shared,paperQuests,worlds,codec,owner);
        features=new S4Features(plugin,players,sessions,featureStore,storage,codec,journal,quests,content.quests(),content.exploration(),paperQuests,modules.combat,access,owner,content.scenes());modules.features(features);
        var memberBindings=new HashMap<UUID,StoryBinding>();settings.members().forEach((player,run)->memberBindings.put(player,settings.runs().get(run).binding()));admissions=new StoryAdmission(memberBindings,codec.encode(new ItemStack[41]),registry::admit,owner);
    }
    private CompletionStage<PaperSessionAdapter.Link> provision(Player player,BooleanSupplier current){
        main();UUID run=settings.members().get(player.getUniqueId());if(phase!=Phase.READY||run==null||player.isDead()||(player.getGameMode()!=GameMode.SURVIVAL&&player.getGameMode()!=GameMode.ADVENTURE))return CompletableFuture.failedFuture(new IllegalStateException("STORY_ADMISSION_UNAVAILABLE"));
        var context=access.forVerifiedServerLink(run,player.getUniqueId());var before=codec.encode(Arrays.copyOf(player.getInventory().getContents(),41));boolean empty=Arrays.stream(player.getInventory().getContents()).allMatch(i->i==null||i.isEmpty())&&player.getItemOnCursor().isEmpty();String sourceOwner=player.getPersistentDataContainer().get(inventoryOwner,PersistentDataType.STRING);
        BooleanSupplier valid=()->phase==Phase.READY&&current.getAsBoolean()&&Objects.equals(sourceOwner,player.getPersistentDataContainer().get(inventoryOwner,PersistentDataType.STRING))&&codec.equivalent(before,codec.encode(Arrays.copyOf(player.getInventory().getContents(),41)))&&player.getItemOnCursor().isEmpty();
        return admissions.open(context,sourceOwner,empty,valid).thenComposeAsync(saved->loadStory(context),owner).thenApplyAsync(v->{if(!valid.getAsBoolean())throw new IllegalStateException("STORY_ADMISSION_STALE");player.getPersistentDataContainer().set(inventoryOwner,PersistentDataType.STRING,run.toString());var scope=scopes.get(run);return new PaperSessionAdapter.Link(context,scope.lifecycle,scope::entry);},owner);
    }
    public CompletionStage<Void> loadStory(ServerProgressAccess.Context context){
        main();access.requireIssued(context);if(!Objects.equals(settings.members().get(context.playerId()),context.storyRunId())||shared==null)return CompletableFuture.failedFuture(new SecurityException("STORY_MEMBER_REQUIRED"));
        return shared.load(context).thenAcceptAsync(saved->scopes.get(context.storyRunId()).effectContext=context,owner);
    }
    public CompletionStage<Boolean> reconcileStory(ServerProgressAccess.Context context){main();access.requireIssued(context);if(!Objects.equals(settings.members().get(context.playerId()),context.storyRunId())||shared==null)return CompletableFuture.failedFuture(new SecurityException("STORY_MEMBER_REQUIRED"));return shared.reconcile(context);}
    public CompletionStage<Void> recovery(boolean console,String[] args,Consumer<String> output){main();return inspection==null?CompletableFuture.failedFuture(new IllegalStateException("STORY_INSPECTION_UNAVAILABLE")):inspection.command(console,args,output);}
    private RecoveryInspection.Runtime inspectionRuntime(ServerProgressAccess.Context context){
        main();var facts=new TreeMap<String,String>();var holds=new TreeSet<String>();var pending=new TreeSet<String>();UUID run=context.storyRunId();var configured=settings.runs().get(run);facts.put("runtime.phase",phase.name());if(phase==Phase.HOLD)holds.add("STORY_RUNTIME_HOLD");var scope=scopes.get(run);
        facts.put("world.state",scope==null?"NOT_INITIALIZED":scope.lifecycle.state().name());if(scope!=null){facts.put("world.generation",Long.toString(scope.lifecycle.generation()));facts.put("world.error",Objects.toString(scope.lifecycle.failureCode(),"NONE"));if(Set.of(WorldLifecycle.State.ERROR_HOLD,WorldLifecycle.State.RECOVERY_REQUIRED).contains(scope.lifecycle.state()))holds.add("WORLD_HOLD");}
        if(sessions==null)facts.put("session.state","NOT_INITIALIZED");else{var s=sessions.diagnostic(context);facts.put("session.state",s.state());facts.put("inventory.runtime_revision",Long.toString(s.revision()));facts.put("inventory.hold",Objects.toString(s.hold(),"NONE"));if(s.state().equals("HOLD")||s.hold()!=null)holds.add("SESSION_INVENTORY_HOLD");if(s.dirty())pending.add("inventory_dirty");if(s.saving())pending.add("inventory_saving");}
        if(storage!=null){var s=storage.diagnostic(context);facts.put("ender.loaded",Boolean.toString(s.loaded()));facts.put("ender.runtime_revision",Long.toString(s.revision()));if(s.held())holds.add("ENDER_HOLD");if(s.saving())pending.add("ender_saving");}
        if(features!=null){var s=features.scenes.progress.diagnostic(context);facts.put("scene.opened",Integer.toString(s.opened()));if(!s.held().isEmpty())holds.add("SCENE_HOLD");if(!s.pending().isEmpty())pending.add("scene_saving");}
        if(shared!=null){facts.put("story.phase",shared.phase(run).name());facts.put("story.effect",shared.effectStatus(run));if(Set.of("EFFECT_CONFLICT","EFFECT_ERROR").contains(shared.effectStatus(run)))holds.add("WORLD_EFFECT_HOLD");if(Set.of(SharedStory.Phase.HOLD,SharedStory.Phase.RESULT_UNKNOWN).contains(shared.phase(run)))holds.add("SHARED_STORY_HOLD");if(!shared.settled(run))pending.add("shared_story_pending");}
        if(modules!=null&&modules.combat!=null){var combat=modules.combat.diagnostic(context);facts.putAll(combat);if(Boolean.parseBoolean(combat.get("combat.cleanup_hold")))holds.add("COMBAT_HOLD");if(Boolean.parseBoolean(combat.get("combat.boundary_pending")))pending.add("combat_boundary");}
        if(modules!=null&&modules.fields!=null){facts.put("field.restart_hold",Boolean.toString(modules.fields.restartHeld()));if(modules.fields.restartHeld())holds.add("FIELD_RESTART_HOLD");if(modules.fields.engine().held(run))holds.add("FIELD_HOLD");}
        return new RecoveryInspection.Runtime(configured==null?null:configured.binding().worldId(),facts,holds,pending);
    }
    private CompletionStage<Void> barrier(UUID run){return modules.barrier(run).thenComposeAsync(v->features.barrier(run),owner).thenComposeAsync(v->sessions.barrier(run),owner).thenComposeAsync(v->quests.barrier(run),owner).thenComposeAsync(v->journal.barrier(run),owner).thenComposeAsync(v->shared.barrier(run),owner);}
    private boolean settled(UUID run){return modules.settled(run)&&sessions.canUnload(run)&&quests.settled(run)&&journal.settled(run)&&shared.settled(run);}
    private final class Scope implements PaperLifecycleBoundary.Data{
        final StoryRuntimeSettings.Run run;final StoryWorldBackend backend;final WorldLifecycle lifecycle;ServerProgressAccess.Context effectContext;
        Scope(StoryRuntimeSettings.Run run){this.run=run;backend=new StoryWorldBackend(run);backend.verifyPrepared();var p=backend.placement();
            var boundary=new PaperLifecycleBoundary(plugin,p,()->registry.read(run.binding().run()).thenAccept(saved->{if(!saved.equals(run.binding()))throw new IllegalStateException("STORY_BINDING_CONFLICT");}),backend,io,owner,()->lifecycleGeneration(),this,manager->{for(String id:run.regions())if(manager.getRegion(id)==null)throw new IllegalStateException("STORY_REQUIRED_REGION_MISSING");},stage->{});
            var persistence=new WorldPersistence(boundary,owner,StoryRuntime::main,()->lifecycleGeneration());lifecycle=new WorldLifecycle(new WorldLifecycle.Mapping(p.storyRunId(),p.physicalId(),p.key().toString(),p.directory()),persistence,owner,StoryRuntime::main,(delay,action)->{if(!plugin.isEnabled())return ()->{};var task=Bukkit.getScheduler().runTaskLater(plugin,action,Math.max(1,delay.toMillis()/50));return task::cancel;},Duration.ofSeconds(settings.idleSeconds()),settings.capacity(),state->{});
        }
        long lifecycleGeneration(){return lifecycle==null?0:lifecycle.generation();}
        Location entry(){return new Location(Objects.requireNonNull(Bukkit.getWorld(run.binding().worldId())),run.x(),run.y(),run.z(),run.yaw(),run.pitch());}
        public CompletionStage<Void> restore(){var e=entry();return e.getWorld().getChunkAtAsync(e.getBlockX()>>4,e.getBlockZ()>>4).thenAcceptAsync(chunk->{var w=e.getWorld();var floor=w.getBlockAt(e.getBlockX(),e.getBlockY()-1,e.getBlockZ());var feet=w.getBlockAt(e.getBlockX(),e.getBlockY(),e.getBlockZ());var head=w.getBlockAt(e.getBlockX(),e.getBlockY()+1,e.getBlockZ());if(e.getY()<=w.getMinHeight()||e.getY()+2>=w.getMaxHeight()||!w.getWorldBorder().isInside(e)||!CombatPlayers.safeCells(floor.getType().name(),feet.getType().name(),head.getType().name(),floor.getType().isSolid(),feet.isPassable(),head.isPassable()))throw new LifecycleFailure("STORY_ENTRY_UNSAFE");paperQuests.ensure(w);modules.restored(run.binding().run());effects.prepared(run.binding().run());},owner).thenComposeAsync(v->{if(effectContext==null)return CompletableFuture.completedFuture(null);return shared.reconcile(effectContext).thenAcceptAsync(applied->{if(content.effects().required(shared.cached(run.binding().run()))&&!applied)throw new LifecycleFailure("STORY_EFFECT_REVIEW_REQUIRED");},owner);},owner);}
        public CompletionStage<Void> drain(){return barrier(run.binding().run());}
        public CompletionStage<Void> verifyShared(){return registry.read(run.binding().run()).thenAccept(saved->{if(!run.binding().equals(saved))throw new IllegalStateException("STORY_BINDING_CONFLICT");});}
        public boolean ownReferencesClear(){return settled(run.binding().run());}
        public void release(){effects.unloaded(run.binding().run());modules.unloaded(run.binding().run());quests.release(run.binding().run());shared.worldUnloaded(run.binding().run());}
    }
    public Phase phase(){return phase;}public StoryRuntimeSettings settings(){return settings;}public Executor owner(){return owner;}public StoryRegistry registry(){return Objects.requireNonNull(registry);}public ServerProgressAccess access(){return access;}public PlayerSessions sessions(){main();return Objects.requireNonNull(sessions);}public Map<UUID,WorldLifecycle> worlds(){main();return Map.copyOf(worlds);}public StoryModules modules(){main();return Objects.requireNonNull(modules);}public StoryAdmission admissions(){main();return Objects.requireNonNull(admissions);}public PostgresProgressStore database(){return Objects.requireNonNull(database);}
    public CompletionStage<Void> drain(){
        main();if(clean)return CompletableFuture.completedFuture(null);if(draining!=null)return draining;if(!started||phase!=Phase.READY&&phase!=Phase.HOLD)return CompletableFuture.failedFuture(new IllegalStateException("STORY_DRAIN_REVIEW_REQUIRED"));phase=Phase.DRAINING;draining=new CompletableFuture<>();
        try{modules.stopActive();}catch(RuntimeException failure){hold("MODULE_DRAIN_FAILED");draining.completeExceptionally(failure);return draining;}sessions.shutdown().thenComposeAsync(v->{for(var player:List.copyOf(Bukkit.getOnlinePlayers()))player.kick(Component.text("스토리 서버 저장과 종료를 준비합니다."));return awaitOffline(0);},owner).thenRunAsync(this::closeGameplay,owner).thenComposeAsync(v->journal.shutdown(),owner).thenComposeAsync(v->quests.shutdown(),owner).thenComposeAsync(v->shared.shutdown(),owner).thenComposeAsync(v->inspection.shutdown(),owner).thenCompose(v->database.closeAsync()).whenCompleteAsync((v,error)->{if(error!=null){hold("DRAIN_FAILED");draining.completeExceptionally(error);return;}try{lease.clean();clean=true;phase=Phase.DRAINED;plugin.getLogger().info("HEARTH_STORY_RUNTIME_DRAINED PASS");draining.complete(null);}catch(Exception failure){hold("DRAIN_FAILED");draining.completeExceptionally(failure);}},owner);return draining;
    }
    private CompletionStage<Void> awaitOffline(int ticks){boolean done=true;for(var w:worlds.values()){var s=w.state();if(s==WorldLifecycle.State.ERROR_HOLD||s==WorldLifecycle.State.RECOVERY_REQUIRED)return CompletableFuture.failedFuture(new IllegalStateException("STORY_WORLD_DRAIN_HOLD"));if(s!=WorldLifecycle.State.OFFLINE)done=false;}if(done)return CompletableFuture.completedFuture(null);if(ticks>settings.idleSeconds()*20+400)return CompletableFuture.failedFuture(new IllegalStateException("STORY_DRAIN_TIMEOUT"));var next=new CompletableFuture<Void>();Bukkit.getScheduler().runTaskLater(plugin,()->awaitOffline(ticks+1).whenComplete((v,error)->{if(error==null)next.complete(null);else next.completeExceptionally(error);}),1);return next;}
    public void console(boolean console,String[] args,Consumer<String> reply){main();if(!console){reply.accept("스토리 관리는 서버 콘솔 전용입니다.");return;}if(Arrays.equals(args,new String[]{"status"})){reply.accept("스토리 실행 상태="+phase+" 진행="+worlds.size()+" clean="+clean);return;}if(Arrays.equals(args,new String[]{"verify"})){(Boolean.getBoolean("hearth.verification.storyPreparation")?StoryPreparationVerification.story(plugin,this):StoryRuntimeVerification.run(plugin,this)).whenCompleteAsync((v,error)->plugin.getLogger().log(error==null?java.util.logging.Level.INFO:java.util.logging.Level.SEVERE,"HEARTH_STORY_RUNTIME_PROBE "+(error==null?"PASS":"FAIL"),error),owner);return;}if(Arrays.equals(args,new String[]{"drain"})){drain().whenCompleteAsync((v,error)->reply.accept(error==null?"스토리 저장/종료 준비 완료":"스토리 종료 준비 보류 · 원본과 오류 기록을 확인하세요."),owner);return;}reply.accept("hearthstoryadmin status|drain");}
    private void closeGameplay(){if(gameplayClosed)return;gameplayClosed=true;ShutdownCleanup.close(modules,features,paperStory,paperQuests,players,autosave);}
    @Override public void close(){main();if(closed)return;try{if(!clean){phase=Phase.HOLD;plugin.getLogger().warning("HEARTH_STORY_UNDRAINED_HOLD original marker retained");if(sessions!=null)sessions.shutdown();for(var p:List.copyOf(Bukkit.getOnlinePlayers()))p.kick(Component.text("스토리 서버 종료 · 저장 상태 확인이 필요합니다."));}}finally{closed=true;phase=Phase.CLOSED;pump.cancel();try{closeGameplay();}finally{io.shutdown();if(inspection!=null)inspection.shutdown();if(database!=null)database.closeAsync();}}plugin.getLogger().info("HEARTH_STORY_DISABLE_CLOSE "+(clean?"PASS":"HOLD"));}
}
