package io.github.jesterportfolio.hearth.verification;

import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.lifecycle.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.session.*;
import io.github.jesterportfolio.hearth.quest.*;
import io.github.jesterportfolio.hearth.exploration.*;
import io.github.jesterportfolio.hearth.story.*;
import io.github.jesterportfolio.hearth.combat.*;
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
    private QuestJournal journal;
    private ExplorationContent explorationContent;
    private final Map<UUID,CommunityBoundary.Condition> explorationConditions=new HashMap<>();
    private SharedStory sharedStory;private PaperStoryAdapter paperStory;private PaperOneBlockEffect blockEffect;private OneBlockEffect.Definition effectDefinition;
    private Map<UUID,Object> storyTestRoster;private final java.util.concurrent.atomic.AtomicLong storyClock=new java.util.concurrent.atomic.AtomicLong();
    private final Map<UUID,ServerProgressAccess.Context> storyActors=new HashMap<>();
    private SharedStory.VoteView storyVote;private CompletionStage<StoryStore.Snapshot> storySaving;private CompletionStage<Void> storyBarrier;
    private int storyNoticeCount;private SharedStory storyFailureProbe;
    private PaperQuestAdapter questAdapter;
    private Playthrough playthrough;
    private final Set<UUID> preparedCourses=new HashSet<>();
    private CompletionStage<Void> exploreWrite,exploreDuplicate,exploreBarrier;
    private Map<String,ExplorationStore.PlaceState> exploreBefore;
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
    private PersonalQuest.Session batchRoute;
    private CompletionStage<QuestStore.Saved> batchStep;
    private CompletionStage<QuestJournal.View> batchUiRead;
    private CompletionStage<Void> batchUiBarrier;
    private final java.util.concurrent.atomic.AtomicBoolean batchValid=new java.util.concurrent.atomic.AtomicBoolean(true);
    private CompletionStage<QuestStore.Saved> prerequisiteWrite,prerequisiteStaleWrite;
    private CompletionStage<PersonalQuest.Session> prerequisiteFresh;
    private PersonalQuest prerequisiteProbe;
    private PersonalQuest.Session prerequisiteHeld,prerequisiteOld;
    private final java.util.concurrent.atomic.AtomicBoolean prerequisiteValid=new java.util.concurrent.atomic.AtomicBoolean(true);
    private Object prerequisiteFreshToken;
    private CompletionStage<Optional<QuestStore.Saved>> entryWrite,entryStaleWrite;
    private CompletionStage<PersonalQuest.Session> entryFresh;
    private final java.util.concurrent.atomic.AtomicBoolean entryValid=new java.util.concurrent.atomic.AtomicBoolean(true);
    private PersonalQuest.Session entryOld;
    private QuestDialogue npcDialogue;
    private QuestDialogue.Ticket npcPendingTicket;
    private PersonalQuest npcProbe;
    private CompletionStage<PersonalQuest.Session> npcPendingRead;
    private QuestRules questRules=QuestRules.empty();private QuestRewards questRewards;
    private PaperFieldEncounters fieldEncounters;
    private PaperCombatTrials combatTrials;private CombatContent combatContent;private final java.util.concurrent.atomic.AtomicLong combatClock=new java.util.concurrent.atomic.AtomicLong();
    private PaperCombatPlayers combatPlayers;
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
        if(plugin.getConfig().getBoolean("combat-state-verification-enabled")){
            var file=new java.io.File(plugin.getDataFolder(),"combat-demo.yml");if(!file.exists())plugin.saveResource("combat-demo.yml",false);
            var yaml=YamlConfiguration.loadConfiguration(file);var definition=new HashMap<String,Object>(yaml.getValues(false));var section=Objects.requireNonNull(yaml.getConfigurationSection("revival"),"Missing combat revival");var revival=new HashMap<String,Object>(section.getValues(false));if(section.isConfigurationSection("fixed"))revival.put("fixed",Objects.requireNonNull(section.getConfigurationSection("fixed")).getValues(false));definition.put("revival",revival);
            combatContent=CombatContent.parse(definition);combatTrials=new PaperCombatTrials(plugin,combatContent,owner,plugin.getConfig().getBoolean("combat-player-verification-enabled")?()->System.nanoTime()/1_000_000:combatClock::get);
        }
        if(plugin.getConfig().getBoolean("field-encounter-verification-enabled")){
            var file=new java.io.File(plugin.getDataFolder(),"field-demo.yml");if(!file.exists())plugin.saveResource("field-demo.yml",false);
            fieldEncounters=new PaperFieldEncounters(plugin,adapter,worlds,FieldContent.parse(YamlConfiguration.loadConfiguration(file).getValues(false)));
        }
        if(catalog!=null) {
            content=catalog.all().stream().filter(c->c.id().equals("survey_marker")).findFirst().orElse(catalog.all().iterator().next());
            quests=new PersonalQuest(store,catalog.questIds(),owner,SessionVerification::main);quests.requireRules(catalog.requiredRules());store.requireQuestRules(catalog.requiredRules());
            journal=new QuestJournal(store,quests,catalog,owner,SessionVerification::main);
            if(plugin.getConfig().getBoolean("exploration-verification-enabled")){
                if(!Boolean.getBoolean("hearth.verification.exploration"))throw new IllegalStateException("Exploration gate missing");
                var file=new java.io.File(plugin.getDataFolder(),"exploration-demo.yml");if(!file.exists())plugin.saveResource("exploration-demo.yml",false);
                explorationContent=ExplorationContent.parse(YamlConfiguration.loadConfiguration(file).getValues(false),catalog);
                var bindings=new HashMap<String,ExplorationContent.Fact>();explorationContent.questFacts().forEach((quest,fact)->bindings.put(quest,explorationContent.facts().get(fact)));store.configureQuestDiary(bindings);
                if(plugin.getConfig().getBoolean("story-verification-enabled")){
                    if(!Boolean.getBoolean("hearth.verification.sharedStory"))throw new IllegalStateException("Shared story gate missing");
                    var storyFile=new java.io.File(plugin.getDataFolder(),"story-demo.yml");if(!storyFile.exists())plugin.saveResource("story-demo.yml",false);
                    var definitions=StoryContent.parse(YamlConfiguration.loadConfiguration(storyFile).getValues(false),catalog,explorationContent);
                    var goals=new HashMap<>(explorationContent.sharedGoals());definitions.stages().values().forEach(stage->{if(goals.putIfAbsent(stage.id(),stage.goal())!=null&&!goals.get(stage.id()).equals(stage.goal()))throw new IllegalStateException("Conflicting shared goal");});
                    explorationContent=new ExplorationContent(explorationContent.regions(),explorationContent.places(),explorationContent.facts(),explorationContent.questFacts(),goals);
                    sharedStory=new SharedStory(store,definitions,owner,SessionVerification::main,ctx->storyTestRoster==null?adapter.onlineContexts(ctx):ctx.storyRunId().equals(a)?Map.copyOf(storyTestRoster):Map.of(b3.playerId(),s3),()->storyTestRoster==null?System.nanoTime():storyClock.get(),java.time.Duration.ofSeconds(30));
                    journal.sharedStory(sharedStory,new StoryAccess(sharedStory));
                }
                journal.exploration(store,explorationContent,new CommunityBoundary(this::storyMembers,(ctx,online)->sharedStory==null?Optional.ofNullable(explorationConditions.get(ctx.storyRunId())):sharedStory.condition(ctx,online)));
            }
            if(plugin.getConfig().getBoolean("quest-completion-verification-enabled")){
                if(!Boolean.getBoolean("hearth.verification.questCompletion")||sharedStory==null)throw new IllegalStateException("Quest completion gate missing");
                var rulesFile=new java.io.File(plugin.getDataFolder(),"quest-rules-demo.yml");if(!rulesFile.exists())plugin.saveResource("quest-rules-demo.yml",false);
                questRules=QuestRules.parse(YamlConfiguration.loadConfiguration(rulesFile).getValues(false),catalog,sharedStory.content());quests.rules(questRules);journal.completion(store,questRules);quests.beforeChapter(run->{journal.invalidateRun(run);return journal.barrier(run);});store.configureQuestRules(questRules);questRewards=new QuestRewards(questRules,store,codec,owner);questRewards.changed(journal::invalidateCompletion);
            }
            questAdapter=new PaperQuestAdapter(plugin,adapter,quests,catalog,journal,owner,
                Set.of(worlds.get(a).mapping().physicalId(),worlds.get(b).mapping().physicalId()));
            if(questRewards!=null)questAdapter.completion(questRules,questRewards,store);
            if(sharedStory!=null){paperStory=new PaperStoryAdapter(plugin,adapter,sharedStory,owner);questAdapter.story(paperStory);}
            if(plugin.getConfig().getBoolean("playthrough-verification-enabled")){
                if(sharedStory==null||!Boolean.getBoolean("hearth.verification.playthrough"))throw new IllegalStateException("Playthrough gate missing");
                var planFile=new java.io.File(plugin.getDataFolder(),"playthrough-demo.yml");if(!planFile.exists())plugin.saveResource("playthrough-demo.yml",false);
                playthrough=Playthrough.parse(YamlConfiguration.loadConfiguration(planFile).getValues(false),catalog,sharedStory.content(),explorationContent);questAdapter.playthrough(playthrough,sharedStory);
            }
            if(plugin.getConfig().getBoolean("world-effect-verification-enabled")){
                if(sharedStory==null||!Boolean.getBoolean("hearth.verification.worldEffect"))throw new IllegalStateException("World effect gate missing");
                var effectFile=new java.io.File(plugin.getDataFolder(),"world-effect-demo.yml");if(!effectFile.exists())plugin.saveResource("world-effect-demo.yml",false);
                effectDefinition=OneBlockEffect.parse(YamlConfiguration.loadConfiguration(effectFile).getValues(false));
                for(String region:effectDefinition.regions())if(sharedStory.content().regions().stream().noneMatch(rule->rule.region().equals(region)&&rule.policy()!=StoryContent.Policy.OPEN&&Objects.equals(rule.reached(),effectDefinition.stage())))throw new IllegalArgumentException("Effect dependency mismatch");
                var metadata=new java.util.Properties();try(var in=java.nio.file.Files.newInputStream(plugin.getDataFolder().toPath().resolve("restart-mapping.properties"))){metadata.load(in);}catch(java.io.IOException e){throw new IllegalStateException("Effect template evidence missing",e);}
                blockEffect=new PaperOneBlockEffect(plugin,effectDefinition,Objects.requireNonNull(worlds.get(effectDefinition.run())),metadata.getProperty("templateVersion"),sharedStory::effectPermit);
                sharedStory.effects(blockEffect);
            }
        }
        if(plugin.getConfig().getBoolean("combat-player-verification-enabled")){if(combatTrials==null)throw new IllegalStateException("Combat trial required");combatPlayers=new PaperCombatPlayers(plugin,adapter,combatTrials,worlds,owner,(p,session)->{log("COMBAT_PLAYER_DEATH");return questAdapter.combatDeath(p,session);});}
    }
    public void combatPlayer(String operation,String profile,String condition){if(combatPlayers==null)throw new IllegalStateException("Combat player mode disabled");switch(operation){case "combat-player-start"->combatPlayers.startOnline(profile);case "combat-player-release"->combatPlayers.release(profile);case "combat-player-revive"->combatPlayers.condition(profile,condition).whenCompleteAsync((v,error)->log("COMBAT_REVIVE "+(error==null?"APPLIED":"HOLD_OR_REJECTED")),owner);default->throw new IllegalArgumentException("Unknown combat fixture operation");}}
    private List<CommunityBoundary.Ref> storyMembers(ServerProgressAccess.Context ctx){var refs=adapter.community(ctx);if(storyTestRoster==null)return refs;return refs.stream().map(ref->new CommunityBoundary.Ref(ref.run(),ref.player(),ref.name(),ctx.storyRunId().equals(a)?storyTestRoster.containsKey(ref.player()):ref.player().equals(b3.playerId()))).toList();}
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
            if(yaml.isSet("requires-completed"))quest.put("requires-completed",yaml.get("requires-completed"));
            if(yaml.isSet("start-mode"))quest.put("start-mode",yaml.get("start-mode"));
            if(yaml.isSet("start-area"))quest.put("start-area",Objects.requireNonNull(yaml.getConfigurationSection("start-area")).getValues(false));
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
    private void prepareCourse(UUID run){
        main();if(playthrough==null)return;
        var mapping=worlds.get(run).mapping();World world=Objects.requireNonNull(Bukkit.getWorld(mapping.physicalId()));
        if(preparedCourses.contains(world.getUID()))return;
        if(Bukkit.isTickingWorlds()||!Set.of(a,b).contains(run)||!world.getKey().toString().equals(mapping.worldKey()))throw new IllegalStateException("Course world mismatch");
        var file=mapping.directory().resolve("hearth-public-course.properties");
        try{
            if(java.nio.file.Files.isSymbolicLink(file))throw new IllegalStateException("Course metadata link refused");
            if(java.nio.file.Files.exists(file)){
                var saved=new Properties();try(var in=java.nio.file.Files.newInputStream(file)){saved.load(in);}
                if(!Set.of("1","2").contains(saved.getProperty("version"))||!run.toString().equals(saved.getProperty("run"))||!world.getUID().toString().equals(saved.getProperty("world")))throw new IllegalStateException("Course metadata mismatch");
            }else{
                var target=world.getBlockAt(7,64,7);if(!target.getType().isAir())throw new IllegalStateException("Existing interaction fixture conflict");
                for(int x=0;x<=40;x++)for(int z=0;z<=40;z++){var type=world.getBlockAt(x,63,z).getType();if(!type.isAir()&&type!=Material.STONE)throw new IllegalStateException("Existing course floor conflict; preserve original");}
                for(int x=0;x<=40;x++)for(int z=0;z<=40;z++)if(world.getBlockAt(x,63,z).getType().isAir())world.getBlockAt(x,63,z).setType(Material.STONE,false);
                var lever=(org.bukkit.block.data.type.Switch)Material.LEVER.createBlockData();lever.setAttachedFace(org.bukkit.block.data.FaceAttachable.AttachedFace.FLOOR);target.setBlockData(lever,false);
                world.getWorldBorder().setCenter(20,20);world.getWorldBorder().setSize(40);
                java.nio.file.Files.writeString(file,"version=2\nrun="+run+"\nworld="+world.getUID()+"\n",java.nio.file.StandardOpenOption.CREATE_NEW);
            }
            preparedCourses.add(world.getUID());
        }catch(java.io.IOException failure){throw new IllegalStateException("Course preparation failed; preserve data",failure);}
    }
    private Location destination(UUID run){
        main();World w=Objects.requireNonNull(Bukkit.getWorld(NamespacedKey.fromString(worlds.get(run).mapping().worldKey())));
        // 새 격리 fixture의 도착 발판만 준비합니다. 일반 월드 건축/복귀 정책이 아닙니다.
        if(playthrough!=null)prepareCourse(run);else for(int x=1;x<=(manual?15:3);x++)for(int z=1;z<=(manual?15:3);z++)w.getBlockAt(x,63,z).setType(Material.STONE,false);
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
        case "effect-prepare" -> effectPrepare();
        case "effect-db-failure" -> effectDbFailure();
        case "effect-db-recover" -> sharedStory.refresh(a1).thenCompose(v->sharedStory.advance(a1,s1,()->true)).thenCompose(v->sharedStory.advance(a1,s1,()->true)).thenCompose(v->sharedStory.openVote(a1,s1,()->true)).thenAccept(vote->storyVote=vote);
        case "effect-pending" -> effectPending();
        case "effect-applied" -> effectApplied();
        case "effect-cycle" -> effectCycle();
        case "effect-resume" -> openAll().thenRun(()->{storyTestRoster=new HashMap<>(Map.of(a1.playerId(),s1,a2.playerId(),s2));sharedStory.rosterChanged(a1);}).thenCompose(v->effectRestored()).thenRun(()->check(blockEffect.inspections()>0&&blockEffect.writes()==0,"effect_new_JVM_inspected_actual_saved_target_no_memory_shortcut"));
        case "effect-conflict" -> effectConflict();
        case "combat-player-state" -> combatPlayerState();
        case "combat-state" -> combatState();
        case "field-encounters" -> fieldEncounters();
        case "branch-progression-start" -> branchProgressionStart();
        case "branch-progression-restored" -> branchProgressionRestored();
        case "quest-completion-start" -> completionStart();
        case "quest-completion-restored" -> completionRestored();
        case "general-npcs-start" -> generalNpcsStart();
        case "general-npcs-restored" -> generalNpcsRestored();
        case "npc-topics-start" -> npcTopicsStart();
        case "npc-topics-restored" -> npcTopicsRestored();
        case "interaction-quest-start" -> interactionQuestStart();
        case "interaction-quest-restored" -> interactionQuestRestore();
        case "npc-pages" -> npcPages();
        case "location-play" -> locationPlay();
        case "playthrough-run" -> playthroughRun();
        case "playthrough-restored" -> playthroughRestored();
        case "navigation-play" -> navigationPlay();
        case "game-information" -> gameInformation();
        case "npc-views" -> npcViews();
        case "interaction-play" -> interactionPlay();
        case "gameplay-guide" -> gameplayGuide();
        case "story-prepare" -> storyPrepare();
        case "story-vote" -> storyVote();
        case "story-persist-start" -> storyPersistStart();
        case "story-persist-done" -> storyPersistDone();
        case "story-cycle" -> storyCycle();
        case "story-resume" -> openAll().thenRun(()->{storyTestRoster=new HashMap<>(Map.of(a1.playerId(),s1,a2.playerId(),s2));sharedStory.rosterChanged(a1);}).thenCompose(v->storyRestored());
        case "story-replay" -> storyReplay();
        case "story-unknown" -> storyUnknown();
        case "story-failure" -> storyFailure();
        case "explore-prepare" -> explorePrepare();
        case "explore-pending" -> explorePending();
        case "explore-discover" -> exploreDiscover();
        case "explore-ui" -> exploreUi();
        case "explore-cycle" -> exploreCycle();
        case "explore-resume" -> openAll().thenRun(this::npcCheck).thenCompose(v->exploreRestored());
        case "explore-complete" -> exploreComplete();
        case "explore-failure" -> exploreFailure();
        case "batch-prepare" -> batchPrepare();
        case "batch-ui" -> batchUi();
        case "batch-cycle" -> batchCycle();
        case "batch-resume" -> batchResume();
        case "batch-complete" -> batchComplete();
        case "batch-notice-failure" -> batchNoticeFailure();
        case "batch-preferences-failure" -> batchPreferencesFailure();
        case "batch-ui-pending" -> batchUiPending();
        case "batch-ui-pending-done" -> expectQuestCode(batchUiRead,"STALE_QUEST_LIST").thenCompose(v->mainResult(batchUiBarrier));
        case "quest-prereq-prepare" -> prerequisitePrepare();
        case "quest-prereq-complete" -> prerequisiteComplete();
        case "quest-prereq-start" -> prerequisiteStart();
        case "quest-prereq-start-done" -> prerequisiteStartDone();
        case "quest-prereq-error-ready" -> prerequisiteErrorReady();
        case "quest-prereq-error" -> prerequisiteError();
        case "quest-prereq-error-done" -> questRead(ctx(b,1),"followup_marker",QuestStore.Phase.NOT_ACCEPTED,-1);
        case "quest-prereq-stale-ready" -> seedList(a2,"survey_marker",QuestStore.Phase.COMPLETED).thenCompose(v->quests.open(a2,"followup_marker",new Object(),prerequisiteValid::get)).thenAccept(q->prerequisiteOld=q);
        case "quest-prereq-stale" -> prerequisiteStale();
        case "quest-prereq-stale-done" -> expectQuestCode(prerequisiteStaleWrite,"STALE_QUEST").thenCompose(v->mainResult(prerequisiteFresh)).thenAccept(q->check(q.saved().phase()==QuestStore.Phase.NOT_ACCEPTED,"prereq_session_change_before_INSERT_no_start"));
        case "quest-prereq-check" -> prerequisiteCheck();
        case "quest-prereq-menu-done" -> expectQuestCode(pendingList,"STALE_QUEST_LIST").thenCompose(v->mainResult(listBarrier));
        case "quest-entry-prepare" -> entryPrepare();
        case "quest-entry-start" -> entryStart();
        case "quest-entry-start-done" -> entryStartDone();
        case "quest-entry-failure" -> entryFailure();
        case "quest-entry-stale-ready" -> quests.open(a2,"entry_marker",new Object(),entryValid::get).thenAccept(q->entryOld=q);
        case "quest-entry-stale" -> entryStale();
        case "quest-entry-stale-done" -> entryStaleDone();
        case "quest-entry-check" -> entryCheck();
        case "quest-npc-check" -> npcChoices();
        case "quest-npc-pending" -> npcPending();
        case "quest-npc-pending-done" -> npcPendingDone();
        case "quest-npc-cycle" -> npcCycle();
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
    private World effectWorld(UUID run){return Objects.requireNonNull(Bukkit.getWorld(NamespacedKey.fromString(worlds.get(run).mapping().worldKey())));}
    private Material effectBlock(UUID run){return effectWorld(run).getBlockAt(effectDefinition.x(),effectDefinition.y(),effectDefinition.z()).getType();}
    private CompletionStage<Void> effectPrepare(){return openAll().thenRun(()->{storyTestRoster=new HashMap<>(Map.of(a1.playerId(),s1,a2.playerId(),s2));sharedStory.rosterChanged(a1);check(effectBlock(a)==Material.GOLD_BLOCK&&effectBlock(b)==Material.DIAMOND_BLOCK,"effect_original_A_B_states_no_unconfirmed_change");})
        .thenCompose(v->seedList(a1,"survey_marker",QuestStore.Phase.COMPLETED)).thenCompose(v->seedList(a2,"survey_marker",QuestStore.Phase.COMPLETED))
        .thenCompose(v->seedList(a1,"path_marker",QuestStore.Phase.COMPLETED)).thenCompose(v->seedList(a2,"path_marker",QuestStore.Phase.COMPLETED));}
    private CompletionStage<Void> effectDbFailure(){return expect(sharedStory.advance(a1,s1,()->true),"23514").thenRun(()->check(sharedStory.phase(a)==SharedStory.Phase.HOLD&&effectBlock(a)==Material.GOLD_BLOCK&&blockEffect.writes()==0&&effectBlock(b)==Material.DIAMOND_BLOCK,"effect_actual_DB_failure_never_mutates_A_or_B"));}
    private CompletionStage<Void> effectPending(){return sharedStory.cast(a1,s1,()->true,storyVote.id(),"route_one").thenCompose(v->{storySaving=sharedStory.cast(a2,s2,()->true,storyVote.id(),"route_one");return later(()->check(!storySaving.toCompletableFuture().isDone()&&effectBlock(a)==Material.GOLD_BLOCK&&blockEffect.writes()==0,"effect_DB_pending_keeps_original_physical_block"));});}
    private CompletionStage<Void> effectApplied(){return mainResult(storySaving).thenCompose(v->sharedStory.barrier(a)).thenAccept(v->check(sharedStory.effectStatus(a).equals("APPLIED")&&effectBlock(a)==Material.EMERALD_BLOCK&&blockEffect.writes()==1&&effectBlock(b)==Material.DIAMOND_BLOCK,"effect_confirmed_A_one_block_B_unchanged"))
        .thenCompose(v->{long before=blockEffect.inspections();var first=sharedStory.reconcile(a1);var duplicate=sharedStory.reconcile(a1);check(first==duplicate,"effect_duplicate_inflight_calls_coalesced");return first.thenAccept(ok->check(ok&&blockEffect.inspections()==before+1&&blockEffect.writes()==1,"effect_already_target_reverified_without_duplicate_write"));})
        .thenCompose(v->journal.load(a1,s1,()->true)).thenAccept(view->check(view.exploration().effectStatus().equals("APPLIED")&&QuestScreens.screen(catalog,view,"story",null,0).stream().anyMatch(row->row.label().equals("세계 효과: 블록 확인 완료")),"effect_GUI_separates_DB_stage_and_checked_block"));}
    private CompletionStage<Void> effectCycle(){UUID physical=worlds.get(a).mapping().physicalId();storyTestRoster.clear();sharedStory.rosterChanged(a1);return mainResult(coordinator.disconnect(s1)).thenCompose(v->mainResult(coordinator.disconnect(s2))).thenCompose(v->until(()->worlds.get(a).state()==WorldLifecycle.State.OFFLINE))
        .thenRun(()->check(Bukkit.getWorld(physical)==null&&!sharedStory.effectStatus(a).equals("APPLIED"),"effect_unload_clears_memory_verification"))
        .thenCompose(v->{one=new Controlled();two=new Controlled();s1=connect(a1,one);s2=connect(a2,two);return mainResult(s1.ready()).thenCompose(x->mainResult(s2.ready()));})
        .thenRun(()->{storyTestRoster.put(a1.playerId(),s1);storyTestRoster.put(a2.playerId(),s2);sharedStory.rosterChanged(a1);}).thenCompose(v->effectRestored());}
    private CompletionStage<Void> effectRestored(){return mainResult(store.readStory(a1)).thenAccept(saved->{check(saved.story().revision()==2&&saved.story().stage().equals("trial_open")&&saved.branches().get("trial_vote").choice().equals("route_one"),"effect_DB_stage_branch_preserved");check(effectBlock(a)==Material.EMERALD_BLOCK&&effectBlock(b)==Material.DIAMOND_BLOCK&&sharedStory.effectStatus(a).equals("APPLIED"),"effect_lifecycle_prepare_rechecked_actual_target");check(paperStory.access().entry(a,21,64,21)&&paperStory.access().content(a,21,64,3)&&paperStory.access().entry(a,2,64,2),"effect_dependent_boundaries_open_only_after_verification");}).thenCompose(v->verify(a1,32,0));}
    private CompletionStage<Void> effectConflict(){long writes=blockEffect.writes();effectWorld(a).getBlockAt(effectDefinition.x(),effectDefinition.y(),effectDefinition.z()).setType(Material.DIAMOND_BLOCK,false);
        return sharedStory.reconcile(a1).thenAccept(ok->{check(!ok&&sharedStory.effectStatus(a).equals("EFFECT_CONFLICT")&&effectBlock(a)==Material.DIAMOND_BLOCK&&blockEffect.writes()==writes,"effect_conflict_preserved_no_overwrite");check(!paperStory.access().entry(a,21,64,21)&&!paperStory.access().content(a,21,64,3)&&paperStory.access().entry(a,2,64,2)&&effectBlock(b)==Material.DIAMOND_BLOCK,"effect_only_dependent_access_closed_public_and_B_unchanged");})
        .thenCompose(v->mainResult(store.readStory(a2))).thenAccept(saved->check(saved.story().revision()==2&&saved.branches().size()==1,"effect_conflict_never_rolls_back_confirmed_DB"))
        .thenCompose(v->{effectWorld(a).getBlockAt(effectDefinition.x(),effectDefinition.y(),effectDefinition.z()).setType(Material.GOLD_BLOCK,false);var pending=sharedStory.reconcile(a1);blockEffect.unloaded(a);sharedStory.worldUnloaded(a);return pending;})
        .thenAccept(ok->check(!ok&&effectBlock(a)==Material.GOLD_BLOCK&&!sharedStory.effectStatus(a).equals("APPLIED"),"effect_stale_prepared_generation_never_writes_or_reports_applied"))
        .thenRun(()->blockEffect.prepared(a)).thenCompose(v->effectCycle()).thenAccept(v->check(blockEffect.writes()==writes+1,"effect_reload_converges_missing_effect_from_expected_state"));}
    private CompletionStage<Void> prepareCombatChunks(){CompletionStage<Void> ready=CompletableFuture.completedFuture(null);for(UUID run:List.of(a,b))ready=ready.thenCompose(done->{var world=Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId()));return mainResult(world.getChunkAtAsync(0,0)).thenAccept(chunk->{world.addPluginChunkTicket(0,0,plugin);check(world.isChunkLoaded(0,0),"combat_controlled_start_chunk_prepared");});});return ready;}
    private final class CombatBody implements CombatPlayers.PlayerPort {
        final PlayerSessions.Session session;CombatContent.Pose position;boolean spectator,failRestore;UUID camera;double health=1;int applications;
        CombatBody(PlayerSessions.Session session,CombatContent.Pose position){this.session=session;this.position=position;}
        public ServerProgressAccess.Context context(){return session.context();}public Object session(){return session;}public CombatContent.Pose position(){return position;}public boolean current(){return coordinator.playable(session);}
        public void spectator(UUID target){spectator=true;camera=target;}public void clearCamera(){camera=null;}public void cleanup(){spectator=false;camera=null;}
        public boolean safe(CombatContent.Pose p){return p.world().equals(position.world());}
        public CompletionStage<Boolean> restore(CombatContent.Pose p,double health,List<CombatContent.Effect> effects,BooleanSupplier valid){applications++;var result=new CompletableFuture<Boolean>();Bukkit.getScheduler().runTaskLater(plugin,()->{if(failRestore||!valid.getAsBoolean()){result.complete(false);return;}this.position=p;this.health=health;spectator=false;result.complete(true);},1);return result;}
    }
    private CompletionStage<Void> combatQuestSetup(){
        CompletionStage<Void> work=CompletableFuture.completedFuture(null);
        for(String id:List.of("trial_checkpoint","trial_keep","trial_beginning"))work=work.thenCompose(v->quests.open(a1,id,s1,()->coordinator.playable(s1))).thenCompose(q->quests.accept(q,catalog.quest(id)).thenCompose(row->quests.reach(q,catalog.quest(id),5,64,31))).thenAccept(row->{});
        for(var ctx:List.of(a2,b3))work=work.thenCompose(v->mainResult(store.advanceQuest(ctx,"trial_keep",new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1),QuestStore.Phase.ACTIVE))).thenAccept(row->{});
        return work;
    }
    private CompletionStage<Void> combatQuestCheck(){CompletionStage<Void> work=CompletableFuture.completedFuture(null);
        for(String id:List.of("trial_checkpoint","trial_keep","trial_beginning"))work=work.thenCompose(v->mainResult(store.readQuest(a1,id))).thenAccept(row->check(row.phase()==QuestStore.Phase.FAILED&&row.revision()==2&&row.objectiveIndex()==(id.equals("trial_beginning")?0:1),"combat_death_persists_failure_policy_"+id));
        for(var ctx:List.of(a2,b3))work=work.thenCompose(v->mainResult(store.readQuest(ctx,"trial_keep"))).thenAccept(row->check(row.phase()==QuestStore.Phase.ACTIVE&&row.revision()==0,"combat_death_other_player_run_unchanged_"+ctx.playerId()));
        return work;
    }
    private CompletionStage<Void> combatQuestDatabaseFailure(){
        QuestStore conflict=new QuestStore(){public CompletionStage<Saved> readQuest(ServerProgressAccess.Context ctx,String id){return store.readQuest(ctx,id);}public CompletionStage<Saved> advanceQuest(ServerProgressAccess.Context ctx,String id,Saved before,Phase phase){return advanceQuest(ctx,id,before,phase,before.objectiveIndex(),0);}public CompletionStage<Saved> advanceQuest(ServerProgressAccess.Context ctx,String id,Saved before,Phase phase,int index,long deadline){return store.advanceQuest(ctx,id,new Saved(before.phase(),before.revision()+1,before.objectiveIndex(),before.deadlineMillis()),phase,index,deadline);}};
        var probe=new PersonalQuest(conflict,catalog.questIds(),owner,SessionVerification::main);probe.rules(questRules);
        return rejectQuest(probe.died(b3,s3,()->coordinator.playable(s3),()->false,catalog)).thenCompose(v->rejectQuest(probe.barrier(b))).thenCompose(v->mainResult(store.readQuest(b3,"trial_keep"))).thenAccept(row->check(row.phase()==QuestStore.Phase.ACTIVE&&row.revision()==0&&coordinator.playable(s1),"combat_quest_real_DB_CAS_failure_HOLD_preserves_original_and_A"));
    }
    private CompletionStage<Void> combatPlayerState(){
        var bridges=new CombatPlayers[2];var actors=new CombatBody[3];final CombatAttempt[] attempts=new CombatAttempt[2];final UUID request=UUID.randomUUID();final CompletionStage<?>[] pending=new CompletionStage<?>[2];
        return openAll().thenCompose(v->combatQuestSetup()).thenCompose(v->prepareCombatChunks()).thenRun(()->{
            check(combatPlayers!=null,"combat_real_Player_adapter_enabled_without_fake_Player");
            for(int i=0;i<2;i++){UUID run=i==0?a:b;var w=worlds.get(run);bridges[i]=new CombatPlayers(run,w.mapping().physicalId(),w.generation(),w::generation,owner,SessionVerification::main);}
            var poseA=new CombatContent.Pose(worlds.get(a).mapping().physicalId(),3,64,3,31,12);var poseB=new CombatContent.Pose(worlds.get(b).mapping().physicalId(),3,64,3,0,0);actors[0]=new CombatBody(s1,poseA);actors[1]=new CombatBody(s2,poseA);actors[2]=new CombatBody(s3,poseB);
            attempts[0]=combatTrials.start(worlds.get(a),List.of(new CombatAttempt.Entrant(a1,s1,poseA),new CombatAttempt.Entrant(a2,s2,poseA)),bridges[0]);bridges[0].attach(attempts[0],List.of(actors[0],actors[1]));
            attempts[1]=combatTrials.start(worlds.get(b),List.of(new CombatAttempt.Entrant(b3,s3,poseB)),bridges[1]);bridges[1].attach(attempts[1],List.of(actors[2]));
            one.count(31);coordinator.refresh(s1);bridges[0].died(actors[0]);check(!bridges[0].actions(actors[0])&&bridges[0].movement(actors[0]),"combat_player_death_blocks_actions_before_projection");bridges[0].project();check(actors[0].spectator&&actors[0].camera.equals(a2.playerId()),"combat_controlled_spectator_camera_and_inventory31_preserved");
        }).thenCompose(v->{var death=quests.died(a1,s1,()->coordinator.playable(s1),()->bridges[0].actions(actors[0]),catalog);check(death==quests.died(a1,s1,()->true,()->false,catalog)&&!quests.barrier(a).toCompletableFuture().isDone(),"combat_death_duplicate_coalesces_and_joins_world_save_barrier");return death;})
        .thenCompose(v->combatQuestCheck()).thenRun(()->{
            check(attempts[0].snapshot().phase()==CombatAttempt.Phase.ACTIVE,"combat_personal_quest_failure_does_not_end_boss_attempt");
            var poseA=actors[0].position;actors[0].position=new CombatContent.Pose(poseA.world(),7.125,-24,8.25,92,-13);bridges[0].leave(actors[0]);check(!actors[0].spectator&&!bridges[0].current(actors[0]),"combat_player_quit_cleans_old_camera_and_binding");
        }).thenCompose(v->mainResult(coordinator.disconnect(s1))).thenCompose(v->verify(a1,31,1))
        .thenCompose(v->{one=new Controlled();s1=connect(a1,one);return mainResult(s1.ready());}).thenRun(()->{
            var exact=bridges[0].returnPosition(a1).orElseThrow();check(exact.y()==-24&&exact.yaw()==92&&exact.pitch()==-13,"combat_return_destination_uses_exact_prior_pose_without_correction");actors[0]=new CombatBody(s1,exact);bridges[0].rejoin(actors[0]);check(actors[0].spectator&&!bridges[0].actions(actors[0])&&attempts[0].snapshot().difficultyPlayers()==2,"combat_dead_new_session_stays_spectator_and_roster_fixed");
            pending[0]=attempts[0].revive(a1,s1,request,"public_rescue");var barrier=bridges[0].barrier();check(!barrier.toCompletableFuture().isDone(),"combat_player_barrier_waits_for_restore_and_counter_confirmation");pending[1]=barrier;
        }).thenCompose(v->combatQuestCheck()).thenCompose(v->mainResult(pending[0])).thenCompose(v->mainResult(pending[1])).thenRun(()->{
            bridges[0].project();check(bridges[0].actions(actors[0])&&!actors[0].spectator&&actors[0].health==0.5&&attempts[0].snapshot().sharedUsed()==1,"combat_controlled_restore_confirmed_before_play_and_budget_commit");check(attempts[0].revive(a1,s1,request,"public_rescue").toCompletableFuture().isDone()&&actors[0].applications==1,"combat_player_duplicate_request_does_not_reapply_restore");
            bridges[1].died(actors[2]);actors[2].failRestore=true;pending[0]=attempts[1].revive(b3,s3,UUID.randomUUID(),"public_rescue");
        }).thenCompose(v->until(()->pending[0].toCompletableFuture().isDone())).thenRun(()->{
            check(pending[0].toCompletableFuture().isCompletedExceptionally()&&attempts[1].snapshot().phase()==CombatAttempt.Phase.HOLD&&attempts[1].snapshot().sharedUsed()==0&&bridges[0].actions(actors[0]),"combat_player_failed_move_HOLD_and_A_independence");
            bridges[0].close();bridges[1].close();combatTrials.stop(a);combatTrials.stop(b);for(UUID run:List.of(a,b))Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId())).removePluginChunkTicket(0,0,plugin);
        }).thenCompose(v->combatQuestDatabaseFailure()).thenCompose(v->verify(a1,31,1)).thenCompose(v->verify(a2,5,0)).thenCompose(v->verify(b3,7,0));
    }
    private static final class CombatActors implements CombatAttempt.Port {
        final Map<UUID,UUID> cameras=new HashMap<>();final CompletableFuture<Boolean> applying=new CompletableFuture<>();java.util.function.BooleanSupplier current;CombatContent.Pose appliedPosition;double appliedHealth;List<CombatContent.Effect> appliedEffects;
        public void freeze(boolean pause){}
        public void camera(UUID player,UUID target){cameras.put(player,target);}
        public boolean safe(CombatContent.Pose pose){return true;}
        public CompletionStage<Boolean> revive(UUID player,CombatContent.Pose position,double health,List<CombatContent.Effect> effects,java.util.function.BooleanSupplier current){this.current=current;appliedPosition=position;appliedHealth=health;appliedEffects=List.copyOf(effects);return applying;}
    }
    private CompletionStage<Void> combatState(){
        var alpha=new CombatActors();var beta=new CombatActors();final CombatAttempt[] trials=new CombatAttempt[2];final Object fresh=new Object();final CompletionStage<?>[] pending=new CompletionStage<?>[2];final UUID request=UUID.randomUUID();
        return openAll().thenCompose(v->prepareCombatChunks()).thenRun(()->{
            var poseA=new CombatContent.Pose(worlds.get(a).mapping().physicalId(),3,64,3,21,14);var poseB=new CombatContent.Pose(worlds.get(b).mapping().physicalId(),3,64,3,0,0);
            trials[0]=combatTrials.start(worlds.get(a),List.of(new CombatAttempt.Entrant(a1,s1,poseA),new CombatAttempt.Entrant(a2,s2,poseA)),alpha);
            trials[1]=combatTrials.start(worlds.get(b),List.of(new CombatAttempt.Entrant(b3,s3,poseB)),beta);var first=trials[0];
            check(first.snapshot().difficultyPlayers()==2&&combatTrials.mob(a).getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getBaseValue()==40&&combatTrials.mob(b).getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getBaseValue()==20,"combat_start_roster_fixed_and_run_scoped_real_mob_health");
            first.died(a1,s1,poseA);check(alpha.cameras.get(a1.playerId()).equals(a2.playerId())&&first.snapshot().phase()==CombatAttempt.Phase.ACTIVE,"combat_dead_observer_selects_surviving_participant");
            var exact=new CombatContent.Pose(poseA.world(),7.25,-12,9.75,91,17);first.disconnect(a2,s2,exact);check(first.snapshot().phase()==CombatAttempt.Phase.PAUSED&&!combatTrials.mob(a).hasAI()&&combatTrials.mob(b).hasAI()&&alpha.cameras.get(a1.playerId())==null,"combat_mixed_offline_pause_freezes_only_A_and_detaches_camera");
            combatClock.addAndGet(1200);first.poll();trials[1].poll();check(first.snapshot().combatMillis()==0&&trials[1].snapshot().combatMillis()==1200,"combat_paused_timer_does_not_advance_other_run_does");
            check(first.reconnect(a2,fresh).equals(exact),"combat_return_pose_and_direction_exact_without_safe_position_correction");first.returned(a2,fresh);check(!first.playable(a2,fresh)&&first.snapshot().phase()==CombatAttempt.Phase.PAUSED,"combat_return_protection_blocks_participation_and_keeps_pause");
            combatClock.addAndGet(3000);first.poll();check(first.playable(a2,fresh)&&combatTrials.mob(a).hasAI()&&alpha.cameras.get(a1.playerId()).equals(a2.playerId()),"combat_protection_clock_expires_while_paused_then_AI_camera_resume");
            first.died(a2,fresh,exact);check(first.snapshot().phase()==CombatAttempt.Phase.WIPE_WAIT&&!combatTrials.mob(a).hasAI(),"combat_all_dead_waits_without_failure_or_automatic_retry");
            pending[0]=first.revive(a1,s1,request,"public_rescue");check(pending[0]==first.revive(a1,s1,request,"public_rescue")&&first.snapshot().reserved()==1&&!pending[0].toCompletableFuture().isDone(),"combat_duplicate_revival_coalesced_and_shared_slot_reserved");
            check(alpha.current.getAsBoolean()&&alpha.appliedPosition.equals(poseA)&&alpha.appliedHealth==0.5&&alpha.appliedEffects.equals(combatContent.revival().effects()),"combat_controlled_revival_payload_contains_start_health_and_effects");alpha.applying.complete(true);
        }).thenCompose(v->mainResult(pending[0])).thenRun(()->{
            var first=trials[0];check(first.snapshot().phase()==CombatAttempt.Phase.ACTIVE&&first.snapshot().sharedUsed()==1&&first.snapshot().reserved()==0&&combatTrials.mob(a).hasAI(),"combat_verified_controlled_revival_consumes_once_and_resumes_AI");
            check(first.view(a2,fresh).personal().contains("관전")&&first.view(a2,fresh).revival().contains("1")&&first.snapshot().difficultyPlayers()==2,"combat_private_status_and_fixed_difficulty_after_death_revival");
            first.failedCondition(false);check(first.snapshot().phase()==CombatAttempt.Phase.ACTIVE,"combat_unrelated_quest_failure_does_not_close_boss");
            var other=trials[1];var pose=new CombatContent.Pose(worlds.get(b).mapping().physicalId(),3,64,3,0,0);other.died(b3,s3,pose);pending[1]=other.revive(b3,s3,UUID.randomUUID(),"public_rescue");other.invalidate();check(!beta.current.getAsBoolean(),"combat_invalidated_generation_rejects_external_apply_guard");beta.applying.complete(true);
        }).thenCompose(v->until(()->pending[1].toCompletableFuture().isDone())).thenRun(()->{
            check(pending[1].toCompletableFuture().isCompletedExceptionally()&&trials[1].snapshot().phase()==CombatAttempt.Phase.HOLD&&trials[1].snapshot().sharedUsed()==0&&trials[0].snapshot().phase()==CombatAttempt.Phase.ACTIVE,"combat_stale_revival_HOLD_preserves_dead_state_and_other_run");
            combatTrials.mob(a).setHealth(0);check(trials[0].snapshot().outcome()==CombatAttempt.Outcome.VICTORY&&alpha.cameras.values().stream().allMatch(Objects::isNull),"combat_actual_mob_death_confirms_once_and_cleans_camera_targets");trials[0].failedCondition(true);check(trials[0].snapshot().outcome()==CombatAttempt.Outcome.VICTORY,"combat_late_failure_cannot_replace_confirmed_result");
            combatTrials.stop(a);combatTrials.stop(b);for(UUID run:List.of(a,b))Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId())).removePluginChunkTicket(0,0,plugin);
        }).thenCompose(v->verify(a1,32,0)).thenCompose(v->verify(a2,5,0)).thenCompose(v->verify(b3,7,0));
    }
    private CompletionStage<Void> fieldEncounters(){return openAll().thenCompose(v->{
        CompletionStage<Void> ready=CompletableFuture.completedFuture(null);
        for(UUID run:List.of(a,b))for(int x:List.of(1,2)){int chunkX=x;ready=ready.thenCompose(done->{var world=Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId()));return mainResult(world.getChunkAtAsync(chunkX,1)).thenAccept(chunk->{world.addPluginChunkTicket(chunkX,1,plugin);check(world.isChunkLoaded(chunkX,1),"field_controlled_spawn_chunk_prepared");});});}return ready;
    }).thenRun(()->{
        var one=fieldEncounters.binding(a);var other=fieldEncounters.binding(b);var from=new FieldContent.Point(24,64,29);var first=new FieldContent.Point(29,64,29);var next=new FieldContent.Point(37,64,29);
        var p1=new FieldEncounters.Presence(one,a1.playerId(),first);var p2=new FieldEncounters.Presence(one,a2.playerId(),new FieldContent.Point(31,64,29));var wrong=new FieldEncounters.Presence(other,b3.playerId(),first);
        check(fieldEncounters.count(a)==0&&fieldEncounters.count(b)==0,"field_no_eager_spawning");
        fieldEncounters.enter(one,a1.playerId(),from,first,List.of(p1,p2,wrong,p1));var snap=fieldEncounters.engine().state(a,"public_field_one").orElseThrow();
        check(snap.players()==2&&snap.difficulty().count()==2&&fieldEncounters.count(a)==2&&fieldEncounters.count(b)==0,"field_spawn_population_unique_and_run_scoped");
        for(var entity:fieldEncounters.entities(a))check(entity.getWorld().getUID().equals(one.world())&&entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getBaseValue()==30&&entity.getAttribute(org.bukkit.attribute.Attribute.ATTACK_DAMAGE).getBaseValue()==3&&!entity.isPersistent(),"field_real_entities_fixed_attributes_and_world");
        fieldEncounters.enter(one,a1.playerId(),from,first,List.of(p1));check(fieldEncounters.count(a)==2&&fieldEncounters.engine().state(a,"public_field_one").orElseThrow().players()==2,"field_reentry_does_not_duplicate_or_rescale");
        fieldEncounters.enter(one,a1.playerId(),from,next,List.of(new FieldEncounters.Presence(one,a1.playerId(),next)));check(fieldEncounters.engine().state(a,"public_field_two").orElseThrow().players()==1&&fieldEncounters.count(a)==3,"field_next_zone_recounts_population");
        fieldEncounters.enter(other,b3.playerId(),from,first,List.of(wrong));check(fieldEncounters.count(b)==1&&fieldEncounters.count(a)==3,"field_other_run_independent_encounter");
        for(UUID id:snap.mobs())((org.bukkit.entity.LivingEntity)Objects.requireNonNull(Bukkit.getEntity(id))).setHealth(0);
        check(fieldEncounters.engine().state(a,"public_field_one").orElseThrow().phase()==FieldEncounters.Phase.CLEARED&&fieldEncounters.count(a)==1,"field_real_entity_deaths_clear_only_matching_encounter");
        fieldEncounters.enter(one,a1.playerId(),from,first,List.of(p1,p2));check(fieldEncounters.count(a)==1,"field_cleared_encounter_not_implicitly_reset");
        var stale=new FieldEncounters.Binding(a,one.world(),one.generation()+1);try{fieldEncounters.enter(stale,a1.playerId(),from,first,List.of(p1));throw new AssertionError("Stale field binding accepted");}catch(IllegalStateException expected){check(expected.getMessage().equals("FIELD_STALE_GENERATION"),"field_stale_binding_rejected");}
        fieldEncounters.retire(a);check(fieldEncounters.count(a)==0&&fieldEncounters.count(b)==1,"field_A_cleanup_preserves_B_entities");try{fieldEncounters.binding(a);throw new AssertionError("Retired field auto reset");}catch(IllegalStateException expected){check(expected.getMessage().equals("FIELD_RETIRED"),"field_retired_encounter_never_auto_respawns");}
        try{new PaperFieldEncounters(plugin,adapter,worlds,FieldContent.parse(YamlConfiguration.loadConfiguration(new java.io.File(plugin.getDataFolder(),"field-demo.yml")).getValues(false)));throw new AssertionError("Existing field scope recreated");}catch(IllegalStateException expected){check(expected.getMessage().equals("FIELD_RESTART_REVIEW_REQUIRED"),"field_existing_scope_marker_prevents_automatic_recreation");}
        for(UUID run:List.of(a,b))for(int x:List.of(1,2))Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId())).removePluginChunkTicket(x,1,plugin);
    }).thenCompose(v->mainResult(coordinator.disconnect(s1))).thenCompose(v->mainResult(coordinator.disconnect(s2)))
        .thenCompose(v->until(()->worlds.get(a).state()==WorldLifecycle.State.OFFLINE))
        .thenRun(()->check(fieldEncounters.count(a)==0&&fieldEncounters.count(b)==1,"field_world_unload_removes_only_A_references"))
        .thenCompose(v->{one=new Controlled();s1=connect(a1,one);return mainResult(s1.ready());})
        .thenRun(()->{check(Bukkit.getWorld(worlds.get(a).mapping().physicalId())!=null&&fieldEncounters.count(a)==0,"field_existing_world_reload_does_not_respawn_retired_encounters");fieldEncounters.retire(b);check(fieldEncounters.count(b)==0,"field_all_owned_entities_removed");})
        .thenCompose(v->verify(a1,32,0)).thenCompose(v->verify(a2,5,0));}
    private CompletionStage<Void> branchProgressionStart(){return openAll().thenRun(()->{storyTestRoster=new HashMap<>(Map.of(a1.playerId(),s1));sharedStory.rosterChanged(a1);})
        .thenCompose(v->journal.load(a1,s1,()->true)).thenAccept(view->check(view.exploration().blockedQuests().containsAll(Set.of("branch_one_review","branch_two_review"))&&view.completion().chapters().isEmpty(),"branch_unconfirmed_quests_and_future_chapters_hidden"))
        .thenCompose(v->expect(store.advanceQuest(a1,"branch_one_review",new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1),QuestStore.Phase.ACTIVE),"QUEST_BRANCH_LOCKED"))
        .thenCompose(v->quests.open(a1,"branch_one_review",s1,()->true)).thenCompose(q->{qa=q;return expect(quests.accept(q,catalog.quest("branch_one_review")),"QUEST_BRANCH_LOCKED");}).thenRun(()->check(!qa.held(),"branch_expected_locked_start_does_not_create_HOLD"))
        .thenCompose(v->finishCourseQuest(a1,s1,"survey_marker")).thenCompose(v->sharedStory.advance(a1,s1,()->true)).thenCompose(v->sharedStory.advance(a1,s1,()->true))
        .thenCompose(v->finishCourseQuest(a1,s1,"path_marker")).thenCompose(v->sharedStory.openVote(a1,s1,()->true))
        .thenCompose(vote->sharedStory.cast(a1,s1,()->true,vote.id(),"route_one"))
        .thenCompose(v->expect(store.advanceQuest(a1,"branch_two_review",new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1),QuestStore.Phase.ACTIVE),"QUEST_BRANCH_LOCKED"))
        .thenCompose(v->finishCourseQuest(a1,s1,"branch_one_review"))
        .thenCompose(v->journal.load(a1,s1,()->true)).thenAccept(view->{
            check(!view.exploration().blockedQuests().contains("branch_one_review")&&view.exploration().blockedQuests().contains("branch_two_review"),"branch_confirmed_line_only_in_public_quest_views");
            check(view.completion().rewards().get("branch_one_review").claimable()&&QuestScreens.screen(catalog,view,"rewards",null,0).stream().anyMatch(b->"branch_one_review".equals(b.quest())),"branch_pending_reward_list_and_claim_button");
            var chapter=view.completion().chapter("public_branch_review").orElseThrow();check(chapter.parts().size()==1&&chapter.parts().getFirst().quest().equals("branch_one_review")&&!view.completion().toString().contains("branch_two_review"),"branch_chapter_DTO_excludes_other_line");
            check(QuestScreens.screen(catalog,view,"chapter-detail","public_branch_review",0).stream().anyMatch(b->b.action().equals("chapter-confirm"))&&QuestScreens.screen(catalog,view,"chapter-confirm","public_branch_review",0).stream().anyMatch(b->b.action().equals("quest-chapter-skip")),"branch_chapter_detail_and_explicit_skip_confirmation");
            var old=view;journal.invalidateCompletion(a1);try{journal.requireView(a1,old);throw new IllegalStateException("Stale metadata accepted");}catch(IllegalStateException expected){check(expected.getMessage().equals("STALE_COMPLETION_VIEW"),"branch_stale_completion_projection_rejected");}
        })
        .thenCompose(v->mainResult(store.readRewards(a1))).thenCompose(before->journal.load(a1,s1,()->true).thenCompose(view->mainResult(store.readRewards(a1))).thenAccept(after->check(before.equals(after),"branch_reward_and_chapter_queries_are_read_only")))
        .thenCompose(v->quests.skip(a2,s2,()->true,store,questRules.chapters().get("public_branch_review")))
        .thenCompose(v->questRead(a2,"branch_one_review",QuestStore.Phase.SKIPPED,0)).thenCompose(v->questRead(a2,"branch_two_review",QuestStore.Phase.NOT_ACCEPTED,-1))
        .thenCompose(v->journal.load(a2,s2,()->true)).thenAccept(view->{
            check(view.completion().chapter("public_branch_review").orElseThrow().skipped()&&!view.completion().rewards().get("branch_one_review").claimable(),"branch_personal_skip_and_undecided_reward_status");
            check(view.completion().chapter("public_review").orElseThrow().unaccepted()==3&&QuestScreens.screen(catalog,view,"chapter-detail","public_review",0).stream().noneMatch(b->b.label().contains("체크포인트")),"branch_chapter_does_not_disclose_unaccepted_quest_titles");
        }).thenCompose(v->questRead(ctx(b,1),"branch_one_review",QuestStore.Phase.NOT_ACCEPTED,-1));}
    private CompletionStage<Void> branchProgressionRestored(){return openAll().thenRun(()->{storyTestRoster=new HashMap<>(Map.of(a1.playerId(),s1,a2.playerId(),s2));sharedStory.rosterChanged(a1);})
        .thenCompose(v->journal.load(a1,s1,()->true)).thenCompose(before->{check(before.completion().rewards().get("branch_one_review").claimable(),"branch_new_JVM_restores_pending_reward_and_selected_chapter");return questRewards.claim(coordinator,s1,"branch_one_review").thenRun(()->{try{journal.requireView(a1,before);throw new IllegalStateException("Old payout view accepted");}catch(IllegalStateException expected){check(expected.getMessage().equals("STALE_COMPLETION_VIEW"),"branch_reward_commit_invalidates_old_projection");}});})
        .thenCompose(v->verify(a1,33,1)).thenCompose(v->questRewards.claim(coordinator,s1,"branch_one_review")).thenCompose(v->verify(a1,33,1))
        .thenCompose(v->journal.load(a1,s1,()->true)).thenAccept(view->{check(view.completion().rewards().get("branch_one_review").status().equals("GRANTED")&&!view.completion().rewards().get("branch_one_review").claimable()&&QuestScreens.screen(catalog,view,"rewards",null,0).stream().noneMatch(b->"branch_one_review".equals(b.quest())),"branch_paid_reward_removed_from_pending_list");})
        .thenCompose(v->journal.load(a2,s2,()->true)).thenAccept(view->check(view.completion().chapter("public_branch_review").orElseThrow().skipped()&&view.completion().rewards().get("branch_one_review").status().equals("POLICY_PENDING"),"branch_new_JVM_preserves_skip_and_pending_policy"))
        .thenCompose(v->journal.load(ctx(b,1),new Object(),()->true)).thenAccept(view->check(view.completion().chapters().isEmpty()&&view.completion().rewards().isEmpty()&&view.states().get("branch_one_review").phase()==QuestStore.Phase.NOT_ACCEPTED,"branch_other_run_chapters_rewards_and_progress_unchanged"));}
    private CompletionStage<Void> failAndRetry(String id,int expected){var c=catalog.quest(id);final PersonalQuest.Session[] current={null};return quests.open(a1,id,s1,()->true).thenCompose(q->{current[0]=q;return quests.accept(q,c);})
        .thenCompose(v->quests.reach(current[0],c,5,64,31)).thenCompose(v->quests.reach(current[0],c,9,64,31)).thenCompose(v->quests.fail(current[0],c,"OBJECTIVE"))
        .thenCompose(row->{check(row.phase()==QuestStore.Phase.FAILED&&row.objectiveIndex()==expected,"completion_failure_policy_"+id);return expectQuestCode(quests.fail(current[0],c,"OBJECTIVE"),"QUEST_FAILURE_NOT_ALLOWED");})
        .thenCompose(v->quests.retry(current[0],c)).thenCompose(v->{CompletionStage<?> work=CompletableFuture.completedFuture(null);for(int index=expected;index<3;index++){int x=5+index*4;work=work.thenCompose(done->quests.reach(current[0],c,x,64,31));}return work;})
        .thenCompose(v->quests.advance(current[0],QuestStore.Phase.COMPLETED)).thenAccept(row->check(row.phase()==QuestStore.Phase.COMPLETED,"completion_retry_flow_"+id));}
    private CompletionStage<Void> completionStart(){return openAll()
        .thenCompose(v->expect(quests.skip(a2,s2,()->true,store,questRules.chapters().get("public_review")),"CHAPTER_NOT_PASSED"))
        .thenCompose(v->failAndRetry("trial_checkpoint",1)).thenCompose(v->failAndRetry("trial_keep",2)).thenCompose(v->failAndRetry("trial_beginning",0))
        .thenCompose(v->quests.open(a1,"trial_timeout",s1,()->true)).thenCompose(q->{qa=q;return quests.accept(q,catalog.quest("trial_timeout"));})
        .thenCompose(v->journal.track(a1,s1,()->true,"trial_timeout",true)).thenAccept(view->check(view.journal().tracked().contains("trial_timeout"),"completion_timed_quest_tracking_preserves_deadline_contract"))
        .thenCompose(v->until(()->qa.saved().phase()==QuestStore.Phase.FAILED)).thenRun(()->check(qa.saved().deadlineMillis()==0,"completion_Paper_scheduler_timeout_persisted"))
        .thenCompose(v->journal.track(a1,s1,()->true,"trial_timeout",false)).thenCompose(v->journal.track(a1,s1,()->true,"trial_timeout",true)).thenAccept(view->check(view.journal().tracked().contains("trial_timeout"),"completion_failed_quest_tracking_remains_consistent"))
        .thenCompose(v->mainResult(store.rewardStatus(a1,"trial_checkpoint"))).thenAccept(status->check(status.equals("PENDING"),"completion_reward_pending_registered_with_quest_completion"))
        .thenCompose(v->expect(store.grantReward(a1,"trial_checkpoint",questRules.rule("trial_checkpoint").reward().fingerprint(),99,one.capture()),"REVISION_CONFLICT"))
        .thenCompose(v->{one.count(20);coordinator.refresh(s1);return questRewards.claim(coordinator,s1,"trial_checkpoint");})
        .thenCompose(v->verify(a1,23,2)).thenCompose(v->questRewards.claim(coordinator,s1,"trial_checkpoint")).thenCompose(v->verify(a1,23,2))
        .thenRun(()->{var full=new ItemStack[41];for(int i=0;i<36;i++)full[i]=new ItemStack(Material.DIRT,64);var before=codec.encode(full);try{questRewards.add(before,questRules.rule("trial_checkpoint").reward());throw new IllegalStateException("Full inventory accepted");}catch(StorageFailure expected){check(expected.code().equals("INVENTORY_FULL")&&codec.equivalent(before,codec.encode(full)),"completion_full_inventory_no_drop_or_partial_change");}})
        .thenCompose(v->finishCourseQuest(b3,s3,"trial_checkpoint"))
        .thenCompose(v->mainResult(store.readInventory(b3))).thenCompose(before->{
            var probe=new InventorySaveSession(store,b3,before.revision(),before::payload,owner,SessionVerification::main,()->true,code->log("EXPECTED_REWARD_HOLD "+code));
            return expect(probe.transaction(saved->store.grantReward(b3,"trial_checkpoint","wrong-fingerprint",saved.revision(),saved.payload()),saved->{throw new IllegalStateException("Rejected reward applied");}),"REWARD_POLICY_MISMATCH")
                .thenCompose(done->expect(probe.flush(),"REWARD_POLICY_MISMATCH"))
                .thenCompose(done->mainResult(store.readInventory(b3))).thenAccept(after->check(after.equals(before)&&probe.holdCode()!=null,"completion_real_DB_reward_rejection_preserves_inventory_and_clean_HOLD"));
        })
        .thenCompose(v->mainResult(store.rewardStatus(b3,"trial_checkpoint"))).thenAccept(status->check(status.equals("PENDING"),"completion_failed_reward_keeps_pending_ledger"))
        .thenCompose(v->finishCourseQuest(a1,s1,"survey_marker")).thenRun(()->storyTestRoster=new HashMap<>(Map.of(a1.playerId(),s1)))
        .thenCompose(v->sharedStory.advance(a1,s1,()->true)).thenCompose(v->sharedStory.advance(a1,s1,()->true))
        .thenCompose(v->quests.skip(a2,s2,()->true,store,questRules.chapters().get("public_review")))
        .thenCompose(v->questRead(a2,"trial_checkpoint",QuestStore.Phase.SKIPPED,0)).thenCompose(v->questRead(a2,"path_marker",QuestStore.Phase.NOT_ACCEPTED,-1))
        .thenCompose(v->mainResult(store.rewardStatus(a2,"trial_checkpoint"))).thenAccept(status->check(status.equals("POLICY_PENDING"),"completion_skip_reward_policy_not_invented"))
        .thenCompose(v->mainResult(store.storyConditions(a1,Set.of(a2.playerId()),Set.of("trial_checkpoint")))).thenAccept(met->check(Boolean.TRUE.equals(met.get(a2.playerId())),"completion_skip_satisfies_personal_next_stage_condition"))
        .thenCompose(v->mainResult(store.readStory(a1))).thenAccept(saved->check(saved.story().stage().equals("trial_vote")&&saved.completed().equals(Set.of("trial_arrival"))&&saved.branches().isEmpty(),"completion_skip_does_not_reapply_or_rewind_shared_story"))
        .thenCompose(v->questRead(ctx(b,1),"trial_checkpoint",QuestStore.Phase.NOT_ACCEPTED,-1));}
    private CompletionStage<Void> completionRestored(){return openAll().thenRun(()->{storyTestRoster=new HashMap<>(Map.of(a1.playerId(),s1,a2.playerId(),s2));sharedStory.rosterChanged(a1);}).thenCompose(v->verify(a1,23,2))
        .thenCompose(v->questRead(a1,"trial_checkpoint",QuestStore.Phase.COMPLETED,7)).thenCompose(v->questRead(a1,"trial_timeout",QuestStore.Phase.FAILED,1))
        .thenCompose(v->mainResult(store.rewardStatus(a1,"trial_checkpoint"))).thenAccept(status->check(status.equals("GRANTED"),"completion_new_JVM_reward_grant_restored"))
        .thenCompose(v->questRewards.claim(coordinator,s1,"trial_checkpoint")).thenCompose(v->verify(a1,23,2))
        .thenCompose(v->mainResult(store.readChapters(a2))).thenAccept(rows->check(rows.containsKey("public_review"),"completion_new_JVM_chapter_skip_restored"))
        .thenCompose(v->quests.skip(a2,s2,()->true,store,questRules.chapters().get("public_review"))).thenCompose(v->questRead(a2,"trial_checkpoint",QuestStore.Phase.SKIPPED,0))
        .thenCompose(v->journal.load(a2,s2,()->true)).thenAccept(view->{check(QuestListView.entries(catalog,view.states()).stream().filter(q->q.questId().equals("trial_checkpoint")).allMatch(q->q.status().contains("스킵")),"completion_UI_skip_not_actual_completion");check(view.exploration().facts().stream().allMatch(ExplorationStore.Fact::shared),"completion_skip_does_not_grant_personal_secrets");})
        .thenCompose(v->expect(questRewards.claim(coordinator,s2,"trial_checkpoint"),"REWARD_NOT_ELIGIBLE"))
        .thenRun(()->check(coordinator.playable(s2),"completion_ineligible_reward_preserves_playable_session"));}
    private void generalNpcCheck(){var w=Bukkit.getWorld(worlds.get(a).mapping().physicalId());questAdapter.ensure(w);questAdapter.ensure(w);
        var actor=catalog.npc("field_researcher");check(catalog.forNpc(actor.npcId()).isEmpty()&&questAdapter.count(w,actor.npcId())==1&&questAdapter.dialogueAllowed(w,actor),"general_NPC_single_entity_no_fake_quest_and_WG_interaction");}
    private CompletionStage<Void> generalNpcsStart(){return openAll().thenRun(()->{prepareCourse(a);prepareCourse(b);generalNpcCheck();})
        .thenCompose(v->finishCourseQuest(a1,s1,"survey_marker")).thenCompose(v->finishCourseQuest(a1,s1,"contact_route"))
        .thenCompose(v->quests.open(a1,"research_route",s1,()->true)).thenCompose(q->{qa=q;return quests.accept(q,catalog.quest("research_route"));})
        .thenCompose(v->journal.load(a1,s1,()->true)).thenAccept(view->{check(NpcQuestView.build(catalog,"field_researcher",view.states(),c->true).marker()==NpcQuestView.Marker.NONE,"general_NPC_no_quest_marker");
            check(NpcContacts.forFact(catalog,view,"P:research_clue").isEmpty(),"general_unknown_record_has_no_contact_link");
            var d=new QuestDialogue(catalog);var w=Bukkit.getWorld(worlds.get(a).mapping().physicalId());questAdapter.ensure(w);var actor=w.getEntitiesByClass(org.bukkit.entity.Villager.class).stream().filter(e->e.getX()>10).findFirst().orElseThrow();
            check(d.options(a1.playerId(),s1,a,w.getUID(),actor.getUniqueId(),"field_researcher").isEmpty(),"general_dialogue_without_quest_tickets");var scope=d.conversation(a1.playerId());
            var page=d.beginPages(a1.playerId(),s1,a,w.getUID(),actor.getUniqueId(),"field_researcher").orElseThrow();check(PaperQuestAdapter.pageMessages(catalog,d,page).size()>1&&d.current(scope),"general_pages_bound_to_current_conversation");d.clearWorld(w.getUID());check(!d.current(scope),"general_old_empty_dialogue_invalidated_on_unload");})
        .thenCompose(v->journal.interactPlace(a1,s1,()->true,"npc:field_researcher",10.5,64,5.5))
        .thenCompose(v->journal.topic(a1,s1,()->true,"field_researcher","research_clue"))
        .thenCompose(v->quests.interact(qa,catalog.quest("research_route"),"npc:field_researcher"))
        .thenCompose(v->journal.load(a1,s1,()->true)).thenAccept(view->{check(view.states().get("research_route").objectiveIndex()==1&&view.states().get("research_route").revision()==1,"general_NPC_objective_saved_at_step_one");
            check(view.exploration().place("research_corner").filter(p->p.discovered()&&!p.hubUnlocked()).isPresent(),"general_personal_place_discovery_not_hub_unlock");
            check(NpcContacts.resolve(catalog,view,"research_clue:field_researcher").isPresent()&&QuestScreens.screen(catalog,view,"fact","P:research_clue",0).stream().anyMatch(b->b.action().equals("npc-contact")),"general_diary_contact_button_verified_from_own_fact");})
        .thenCompose(v->generalNpcCycle());}
    private CompletionStage<Void> generalNpcCycle(){UUID physical=worlds.get(a).mapping().physicalId();return mainResult(coordinator.disconnect(s1)).thenCompose(v->mainResult(coordinator.disconnect(s2)))
        .thenCompose(v->until(()->worlds.get(a).state()==WorldLifecycle.State.OFFLINE)).thenRun(()->check(!questAdapter.tracks(physical)&&Bukkit.getWorld(physical)==null,"general_idle_unload_clears_NPC_world_references"))
        .thenCompose(v->{one=new Controlled();two=new Controlled();s1=connect(a1,one);s2=connect(a2,two);return mainResult(s1.ready()).thenCompose(x->mainResult(s2.ready()));})
        .thenRun(this::generalNpcCheck);}
    private CompletionStage<Void> generalNpcsRestored(){return openAll().thenRun(this::generalNpcCheck)
        .thenCompose(v->quests.open(a1,"research_route",s1,()->true)).thenCompose(q->{qa=q;check(q.saved().objectiveIndex()==1&&q.saved().revision()==1,"general_new_JVM_restores_NPC_objective_index");return quests.reach(q,catalog.quest("research_route"),17,64,7);})
        .thenCompose(v->quests.advance(qa,QuestStore.Phase.COMPLETED)).thenCompose(v->questRead(a1,"research_route",QuestStore.Phase.COMPLETED,3))
        .thenCompose(v->journal.interactPlace(a1,s1,()->true,"npc:field_researcher",10.5,64,5.5)).thenCompose(v->journal.topic(a1,s1,()->true,"field_researcher","research_clue"))
        .thenAccept(view->{check(view.exploration().events().stream().filter(e->e.id().equals("research_clue")).count()==1&&view.journal().notices().stream().filter(n->n.type().equals("PLACE")&&n.quest().equals("research_corner")).count()==1,"general_repeat_interaction_no_duplicate_clue_or_discovery_notice");
            check(NpcContacts.resolve(catalog,view,"research_clue:field_researcher").isPresent(),"general_new_JVM_restores_personal_diary_contact");})
        .thenCompose(v->journal.load(a2,s2,()->true)).thenAccept(view->check(view.exploration().facts().isEmpty()&&NpcContacts.resolve(catalog,view,"research_clue:field_researcher").isEmpty(),"general_other_player_no_fact_or_contact"))
        .thenCompose(v->journal.load(ctx(b,1),new Object(),()->true)).thenAccept(view->check(view.exploration().facts().isEmpty()&&view.states().get("research_route").phase()==QuestStore.Phase.NOT_ACCEPTED,"general_other_run_progress_and_fact_isolated"));}
    private CompletionStage<Void> npcTopicsStart(){return openAll()
        .thenCompose(v->journal.load(a1,s1,()->true)).thenAccept(view->{
            var visible=catalog.availableTopics("survey_guide",view.states(),id->journal.questAllowed(a1,id));
            check(visible.size()==1&&visible.getFirst().id().equals("personal_rules"),"topics_locked_labels_and_answers_not_offered");
        })
        .thenCompose(v->expectQuestCode(journal.topic(a1,s1,()->true,"survey_guide","facility_clue"),"TOPIC_LOCKED"))
        .thenCompose(v->journal.topic(a1,s1,()->true,"survey_guide","personal_rules"))
        .thenAccept(view->check(view.exploration().facts().isEmpty()&&view.journal().notices().isEmpty(),"topics_general_answer_no_diary_or_notification"))
        .thenCompose(v->finishCourseQuest(a1,s1,"survey_marker"))
        .thenCompose(v->journal.load(a1,s1,()->true)).thenCompose(before->{
            check(catalog.availableTopics("survey_guide",before.states(),id->true).size()==2,"topics_personal_completion_unlocks_question");
            var d=new QuestDialogue(catalog);World w=Bukkit.getWorld(worlds.get(a).mapping().physicalId());questAdapter.ensure(w);var npc=w.getEntitiesByClass(org.bukkit.entity.Villager.class).iterator().next();
            var options=d.topicOptions(a1.playerId(),s1,a,w.getUID(),npc.getUniqueId(),"survey_guide",catalog.availableTopics("survey_guide",before.states(),id->true));
            check(PaperQuestAdapter.topicMessages(catalog,d,options).size()==2,"topics_Paper_chat_components_current_allowed_questions");
            var t=options.stream().filter(x->x.topic().equals("facility_clue")).findFirst().orElseThrow();
            d.authorizeTopic(a1.playerId(),s1,a,w.getUID(),npc.getUniqueId(),t.npc(),t.topic(),t.nonce());
            try{d.authorizeTopic(a1.playerId(),s1,a,w.getUID(),npc.getUniqueId(),t.npc(),t.topic(),t.nonce());throw new IllegalStateException("Duplicate topic accepted");}catch(IllegalArgumentException expected){check(true,"topics_duplicate_nonce_rejected");}
            return journal.topic(a1,s1,()->d.current(t),t.npc(),t.topic()).thenCompose(view->journal.topic(a1,s1,()->d.current(t),t.npc(),t.topic()))
                .thenAccept(view->{check(view.exploration().facts().stream().filter(f->f.id().equals("facility_clue")&&!f.shared()).count()==1&&view.exploration().events().stream().filter(e->e.id().equals("facility_clue")).count()==1,"topics_important_answer_one_personal_fact_and_chronicle");
                    check(before.journal().equals(view.journal())&&before.states().equals(view.states()),"topics_answers_do_not_change_quests_or_notifications");
                    check(QuestScreens.screen(catalog,view,"PERSON",null,0).stream().anyMatch(b->b.quest()!=null&&b.quest().equals("P:facility_clue")),"topics_smartphone_personal_diary_link");d.clearWorld(w.getUID());check(!d.current(t),"topics_world_unload_invalidates_question_tokens");});
        });}
    private CompletionStage<Void> npcTopicsRestored(){return openAll()
        .thenCompose(v->journal.load(a1,s1,()->true)).thenAccept(view->{check(view.exploration().facts().stream().anyMatch(f->f.id().equals("facility_clue"))&&view.exploration().events().stream().filter(e->e.id().equals("facility_clue")).count()==1,"topics_new_JVM_personal_fact_and_chronicle_restored");
            check(QuestScreens.screen(catalog,view,"fact","P:facility_clue",0).stream().anyMatch(b->b.label().contains("안내원")),"topics_restored_diary_details_public_to_owner");})
        .thenCompose(v->journal.load(a2,s2,()->true)).thenAccept(view->check(view.exploration().facts().isEmpty()&&catalog.availableTopics("survey_guide",view.states(),id->true).size()==1,"topics_P2_no_private_answer_or_fact"))
        .thenCompose(v->journal.load(ctx(b,1),new Object(),()->true)).thenAccept(view->check(view.exploration().facts().isEmpty(),"topics_other_run_private_fact_isolation"))
        .thenCompose(v->finishCourseQuest(a2,s2,"survey_marker"))
        .thenCompose(v->mainResult(store.recordFact(a2,new ExplorationContent.Fact("facility_clue","PERSON","보존할 기존 기록","기존 요약","기존 상세",1,true),false)))
        .thenCompose(v->{var probe=new QuestJournal(store,quests,catalog,owner,SessionVerification::main);probe.exploration(store,explorationContent,new CommunityBoundary(c->List.of(),(c,ids)->Optional.empty()));
            return expect(probe.topic(a2,s2,()->true,"survey_guide","facility_clue"),"FACT_VERSION_CONFLICT").thenRun(()->check(!probe.settled(a)&&probe.barrier(a).toCompletableFuture().isCompletedExceptionally()&&probe.settled(b),"topics_DB_conflict_preserves_HOLD_and_other_run"));})
        .thenCompose(v->mainResult(store.readExploration(a2))).thenAccept(raw->check(raw.facts().size()==1&&raw.facts().getFirst().title().equals("보존할 기존 기록")&&raw.events().size()==1,"topics_DB_failure_preserves_original_fact_and_history"))
        .thenCompose(v->journal.load(a1,s1,()->true)).thenAccept(view->check(view.exploration().facts().stream().anyMatch(f->f.id().equals("facility_clue")&&!f.title().equals("보존할 기존 기록")),"topics_failure_other_player_record_unchanged"));}
    private CompletionStage<Void> interactionQuestStart(){return openAll().thenRun(()->{prepareCourse(a);prepareCourse(b);questAdapter.ensure(Bukkit.getWorld(worlds.get(a).mapping().physicalId()));})
        .thenCompose(v->finishCourseQuest(a1,s1,"survey_marker"))
        .thenCompose(v->quests.open(a1,"contact_route",s1,()->true)).thenCompose(q->{qa=q;return quests.accept(q,catalog.quest("contact_route"));})
        .thenCompose(v->expectQuestCode(quests.reach(qa,catalog.quest("contact_route"),3.5,64,5.5),"QUEST_GOAL"))
        .thenCompose(v->quests.interact(qa,catalog.quest("contact_route"),"npc:survey_guide"))
        .thenCompose(v->questRead(a1,"contact_route",QuestStore.Phase.ACTIVE,1)).thenRun(()->{
            var world=Bukkit.getWorld(worlds.get(a).mapping().physicalId());var lever=(org.bukkit.block.data.type.Switch)world.getBlockAt(7,64,7).getBlockData();lever.setPowered(true);world.getBlockAt(7,64,7).setBlockData(lever,false);check(true,"interaction_quest_first_target_saved");});}
    private CompletionStage<Void> interactionQuestRestore(){return openAll().thenRun(()->{prepareCourse(a);prepareCourse(b);check(((org.bukkit.block.data.type.Switch)Bukkit.getWorld(worlds.get(a).mapping().physicalId()).getBlockAt(7,64,7).getBlockData()).isPowered(),"interaction_lever_preserved_new_JVM");})
        .thenCompose(v->quests.open(a1,"contact_route",s1,()->true)).thenCompose(q->{qa=q;check(q.saved().objectiveIndex()==1&&q.saved().revision()==1,"interaction_quest_index_restored");return expectQuestCode(quests.interact(q,catalog.quest("contact_route"),"npc:survey_guide"),"QUEST_INTERACTION");})
        .thenCompose(v->quests.interact(qa,catalog.quest("contact_route"),"block:7:64:7:LEVER"))
        .thenCompose(v->quests.reach(qa,catalog.quest("contact_route"),13,64,25)).thenCompose(v->quests.advance(qa,QuestStore.Phase.COMPLETED))
        .thenCompose(v->questRead(a1,"contact_route",QuestStore.Phase.COMPLETED,4)).thenCompose(v->questRead(a2,"contact_route",QuestStore.Phase.NOT_ACCEPTED,-1)).thenCompose(v->questRead(ctx(b,1),"contact_route",QuestStore.Phase.NOT_ACCEPTED,-1));}
    private CompletionStage<Void> npcPages(){return openAll().thenCompose(v->mainResult(store.readJournal(a1))).thenCompose(before->{
        var d=new QuestDialogue(catalog);World w=Bukkit.getWorld(worlds.get(a).mapping().physicalId());questAdapter.ensure(w);var entity=w.getEntitiesByClass(org.bukkit.entity.Villager.class).iterator().next();
        var options=d.options(a1.playerId(),s1,a,w.getUID(),entity.getUniqueId(),"survey_guide");var page=d.beginPages(a1.playerId(),s1,a,w.getUID(),entity.getUniqueId(),"survey_guide").orElseThrow();
        check(catalog.pages("survey_guide").size()==3,"pages_content_loaded");
        for(int i=0;i<3;i++){check(!PaperQuestAdapter.pageMessages(catalog,d,page).isEmpty(),"pages_component_"+i);if(i<2)page=d.turn(a1.playerId(),s1,a,w.getUID(),entity.getUniqueId(),"survey_guide",page.nonce(),"next").orElseThrow();}
        d.turn(a1.playerId(),s1,a,w.getUID(),entity.getUniqueId(),"survey_guide",page.nonce(),"close");check(!d.current(options.getFirst()),"pages_close_clears_quest_tokens");
        return mainResult(store.readJournal(a1)).thenAccept(after->check(after.equals(before),"pages_read_only_journal"));
    });}
    private CompletionStage<Void> locationPlay(){
        return openAll().thenCompose(v->quests.open(a1,content.id(),s1,()->true)).thenCompose(q->{qa=q;return quests.accept(q,content);})
            .thenCompose(v->journal.load(a1,s1,()->true)).thenCompose(view->{
                var detail=QuestListView.detail(catalog,view.states(),content.id()).orElseThrow();check(detail.guidance().contains("정확한 위치"),"location_detail_exact");
                check(GameInformation.Query.parse("/hearthinfo quests").isPresent(),"location_information_command");
                var route=catalog.quest("route_marker");check(route.detailGuidance(new QuestStore.Saved(QuestStore.Phase.ACTIVE,0,0)).contains("정확한 위치"),"location_first_stage_exact");
                check(route.detailGuidance(new QuestStore.Saved(QuestStore.Phase.ACTIVE,1,1)).contains("탐색 구역"),"location_second_stage_area");
                var hidden=route.detailGuidance(new QuestStore.Saved(QuestStore.Phase.ACTIVE,2,2));check(hidden.contains("위치 비공개")&&!hidden.contains("21.0"),"location_final_stage_hidden");
                var screen=new PaperQuestUI.Screen(a1.playerId(),s1,QuestScreens.screen(catalog,view,"detail",content.id(),0),"detail",content.id(),0);check(screen.getInventory().getSize()==54,"location_phone_detail");
                return questRead(a1,QuestStore.Phase.ACTIVE,0).thenCompose(v->journal.information(a1,s1,()->true)).thenAccept(info->check(info.quests().stream().anyMatch(q->q.guidance().contains("정확한 위치")),"location_public_information"));
            }).thenCompose(v->quests.reach(qa,content,13,64,13)).thenCompose(v->journal.load(a1,s1,()->true)).thenAccept(view->{check(!QuestListView.detail(catalog,view.states(),content.id()).orElseThrow().guidance().contains("정확한 위치"),"location_report_hides_previous_target");});
    }
    private CompletionStage<Void> finishCourseQuest(ServerProgressAccess.Context ctx,Object token,String id){
        var c=catalog.quest(id);final PersonalQuest.Session[] session=new PersonalQuest.Session[1];
        return quests.open(ctx,id,token,()->true).thenCompose(q->{session[0]=q;if(c.startMode()==QuestContent.StartMode.NPC)return quests.accept(q,c).thenAccept(x->{});
            var area=c.startArea();return quests.enter(ctx,c,token,()->true,0,64,0,(area.minX()+area.maxX())/2,(area.minY()+area.maxY())/2,(area.minZ()+area.maxZ())/2).thenAccept(x->check(x.isPresent(),"course_automatic_start_"+id));
        }).thenCompose(v->{CompletionStage<?> work=CompletableFuture.completedFuture(null);
            for(var goal:c.goals()){var area=goal.area();work=work.thenCompose(x->goal.interaction()==null?quests.reach(session[0],c,(area.minX()+area.maxX())/2,(area.minY()+area.maxY())/2,(area.minZ()+area.maxZ())/2):quests.interact(session[0],c,goal.interaction()));}
            return work.thenCompose(x->session[0].saved().phase()==QuestStore.Phase.REPORT_READY?quests.advance(session[0],QuestStore.Phase.COMPLETED):CompletableFuture.completedFuture(session[0].saved()));
        }).thenAccept(row->check(row.phase()==QuestStore.Phase.COMPLETED,"course_quest_completed_"+id));
    }
    private CompletionStage<Playthrough.Guide> courseGuide(ServerProgressAccess.Context ctx,Object token){return journal.load(ctx,token,()->true).thenApply(view->playthrough.guide(catalog,view,sharedStory.cached(ctx.storyRunId()),sharedStory.phase(ctx.storyRunId())));}
    private CompletionStage<Void> playthroughRun(){
        return openAll().thenRun(()->{prepareCourse(a);prepareCourse(b);storyTestRoster=new HashMap<>(Map.of(a1.playerId(),s1,a2.playerId(),s2));sharedStory.rosterChanged(a1);})
            .thenCompose(v->courseGuide(a1,s1)).thenAccept(guide->check("survey_marker".equals(guide.talkQuest()),"course_first_personal_task"))
            .thenCompose(v->finishCourseQuest(a1,s1,"survey_marker"))
            .thenCompose(v->expectQuestCode(sharedStory.advance(a1,s1,()->true),"CONDITIONS_UNMET"))
            .thenCompose(v->questRead(a2,"survey_marker",QuestStore.Phase.NOT_ACCEPTED,-1))
            .thenCompose(v->finishCourseQuest(a2,s2,"survey_marker"))
            .thenCompose(v->sharedStory.advance(a1,s1,()->true)).thenCompose(v->sharedStory.advance(a1,s1,()->true))
            .thenCompose(v->finishCourseQuest(a1,s1,"path_marker")).thenCompose(v->finishCourseQuest(a2,s2,"path_marker"))
            .thenCompose(v->sharedStory.openVote(a1,s1,()->true)).thenCompose(vote->{storyVote=vote;return sharedStory.cast(a1,s1,()->true,vote.id(),"route_one");})
            .thenRun(()->check(!Boolean.TRUE.equals(sharedStory.cached(a).story().explorationOpen()),"course_before_majority_stays_locked"))
            .thenCompose(v->sharedStory.cast(a2,s2,()->true,storyVote.id(),"route_one"))
            .thenRun(()->check(Boolean.TRUE.equals(sharedStory.cached(a).story().explorationOpen()),"course_shared_exploration_unlocked"))
            .thenCompose(v->finishCourseQuest(a1,s1,"route_marker"))
            .thenCompose(v->finishCourseQuest(a1,s1,"route_followup")).thenCompose(v->finishCourseQuest(a1,s1,"contact_route")).thenCompose(v->finishCourseQuest(a1,s1,"research_route"))
            .thenCompose(v->journal.enterPlaces(a1,s1,()->true,0,64,0,21,64,21))
            .thenCompose(v->sharedStory.advance(a1,s1,()->true)).thenCompose(v->courseGuide(a1,s1))
            .thenAccept(guide->{check(guide.complete(),"course_all_guidance_completed");World world=Bukkit.getWorld(worlds.get(a).mapping().physicalId());check(world.getBlockAt(37,63,37).getType()==Material.STONE,"course_far_goal_walkable");world.getBlockAt(10,63,10).setType(Material.BRICKS,false);})
            .thenCompose(v->courseGuide(a2,s2)).thenAccept(guide->check(!guide.complete(),"course_personal_steps_not_shared"))
            .thenCompose(v->questRead(ctx(b,1),"survey_marker",QuestStore.Phase.NOT_ACCEPTED,-1))
            .thenCompose(v->journal.load(a1,s1,()->true)).thenAccept(view->{check(view.exploration().facts().stream().anyMatch(f->f.id().equals("route_record")),"course_personal_diary_recorded");check(view.exploration().place("survey_place").filter(p->p.discovered()).isPresent(),"course_personal_discovery_recorded");check(!view.journal().notices().isEmpty(),"course_personal_notices_recorded");});
    }
    private CompletionStage<Void> playthroughRestored(){
        return openAll().thenRun(()->{prepareCourse(a);prepareCourse(b);storyTestRoster=new HashMap<>(Map.of(a1.playerId(),s1,a2.playerId(),s2));sharedStory.rosterChanged(a1);
            World world=Bukkit.getWorld(worlds.get(a).mapping().physicalId());check(world.getBlockAt(10,63,10).getType()==Material.BRICKS,"course_existing_player_floor_preserved");check(world.getWorldBorder().getSize()==40,"course_border_restored");check(Bukkit.getWorld(worlds.get(b).mapping().physicalId()).getBlockAt(10,63,10).getType()==Material.STONE,"course_other_world_floor_unchanged");})
            .thenCompose(v->courseGuide(a1,s1)).thenAccept(guide->check(guide.complete(),"course_new_JVM_guidance_restored"))
            .thenCompose(v->courseGuide(a2,s2)).thenAccept(guide->check(!guide.complete(),"course_new_JVM_personal_isolation"))
            .thenCompose(v->journal.load(a1,s1,()->true)).thenAccept(view->{check(view.exploration().place("survey_place").filter(p->p.discovered()).isPresent(),"course_new_JVM_discovery_restored");check(view.exploration().facts().stream().anyMatch(f->f.id().equals("route_record")),"course_new_JVM_diary_restored");check(view.states().get("route_marker").revision()==4&&view.states().get("route_followup").revision()==2,"course_new_JVM_multi_step_revisions");});
    }
    private CompletionStage<Void> navigationPlay(){
        return openAll().thenCompose(v->quests.open(a1,content.id(),s1,()->coordinator.playable(s1))).thenCompose(q->{qa=q;return quests.accept(q,content);})
            .thenCompose(v->{var area=content.goals().getFirst().area();return quests.reach(qa,content,(area.minX()+area.maxX())/2,(area.minY()+area.maxY())/2,(area.minZ()+area.maxZ())/2);})
            .thenCompose(v->journal.track(a1,s1,()->coordinator.playable(s1),content.id(),true)).thenCompose(view->{
                check(view.states().get(content.id()).phase()==QuestStore.Phase.REPORT_READY,"navigation_report_ready");var inventory=one.capture();
                for(String page:List.of("quests","reporting","tracked","completed","notices")){
                    var buttons=QuestScreens.screen(catalog,view,page,null,0);var screen=new PaperQuestUI.Screen(a1.playerId(),s1,buttons,page,null,0);
                    check(screen.getInventory().getSize()==54&&buttons.size()==buttons.stream().map(QuestScreens.Button::slot).distinct().count(),"navigation_screen_"+page);
                }
                check(QuestScreens.screen(catalog,view,"detail",content.id(),0).stream().anyMatch(b->b.action().equals("npc-dialogue")),"navigation_report_conversation_link");
                check(codec.equivalent(one.capture(),inventory),"navigation_inventory_unchanged");
                return questRead(a1,QuestStore.Phase.REPORT_READY,1);
            }).thenCompose(v->quests.advance(qa,QuestStore.Phase.COMPLETED)).thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1)))
            .thenCompose(view->{check(!view.journal().tracked().contains(content.id()),"navigation_completed_tracking_cleared");check(QuestListView.filtered(catalog,view.states(),view.journal().tracked(),view.exploration().blockedQuests(),QuestListView.Filter.REPORTING).isEmpty(),"navigation_completed_removed_from_report_list");
                return questRead(a1,QuestStore.Phase.COMPLETED,2).thenCompose(v->questRead(a2,QuestStore.Phase.NOT_ACCEPTED,-1)).thenCompose(v->questRead(ctx(b,1),QuestStore.Phase.NOT_ACCEPTED,-1));
            });
    }
    private CompletionStage<Void> gameInformation(){
        return mainResult(store.readJournal(a1)).thenCompose(before->journal.information(a1,s1,()->coordinator.playable(s1)).thenCompose(info->{
            for(String section:List.of("summary","quests","map","diary","story"))check(!info.lines(new GameInformation.Query(section,1)).isEmpty(),"information_section_"+section);
            check(!info.toString().contains(a.toString())&&!info.toString().contains(a1.playerId().toString()),"information_no_owner_identifiers");
            var previous=sharedStory.cached(a);
            return mainResult(store.readStory(a1)).thenCompose(after->{check(after.equals(previous),"information_shared_revision_unchanged");return mainResult(store.readJournal(a1));}).thenAccept(after->check(after.equals(before),"information_notices_preferences_tracking_unchanged"));
        }));
    }
    private CompletionStage<Void> npcViews(){
        return quests.list(a1,s1,()->coordinator.playable(s1)).thenAccept(rows->{
            quests.requireListDisplay(a1,rows);World w=Bukkit.getWorld(worlds.get(a).mapping().physicalId());
            var before=one.capture();
            for(var c:catalog.npcs()){
                var view=NpcQuestView.build(catalog,c.npcId(),rows,q->journal.questAllowed(a1,q.id()));
                check(view.options().stream().noneMatch(q->!journal.questAllowed(a1,q.quest())),"npc_view_restricted_options_hidden");
            }
            for(var marker:List.of(NpcQuestView.Marker.AVAILABLE,NpcQuestView.Marker.REPORT_READY)){
                var e=PaperQuestAdapter.markerEntity(w,new Location(w,3.5,66.2,5.5),marker);
                check(!e.isVisibleByDefault()&&!e.isPersistent(),"npc_marker_private_nonpersistent_"+marker);e.remove();check(!e.isValid(),"npc_marker_removed_"+marker);
            }
            check(codec.equivalent(one.capture(),before),"npc_view_inventory_unchanged");
            check(w.getEntitiesByClass(org.bukkit.entity.Villager.class).stream().filter(e->e.getPersistentDataContainer().has(new NamespacedKey(plugin,"quest_demo_npc"))).allMatch(e->!e.isVisibleByDefault()),"npc_default_hidden_until_authorized");
            questAdapter.ensure(w);for(var c:catalog.npcs())check(questAdapter.count(w,c.npcId())==1,"npc_no_duplicate_after_projection");
        });
    }
    private CompletionStage<Void> interactionPlay(){
        var beforeB=new InventoryStore.Stored[1];
        return openAll().thenCompose(v->mainResult(store.readInventory(ctx(b,1)))).thenCompose(v->{beforeB[0]=v;
            check(PlayerInteractionRules.inventory(new PlayerInteractionRules.InventoryRequest(true,true,true,true,9,"NUMBER_KEY","HOTBAR_SWAP",0)),"interaction_hotbar_policy_allowed");
            check(!PlayerInteractionRules.inventory(new PlayerInteractionRules.InventoryRequest(false,true,true,true,9,"NUMBER_KEY","HOTBAR_SWAP",0)),"interaction_unprepared_denied");
            check(!PlayerInteractionRules.inventory(new PlayerInteractionRules.InventoryRequest(true,false,true,true,9,"NUMBER_KEY","HOTBAR_SWAP",0)),"interaction_GUI_and_containers_denied");
            check(PlayerInteractionRules.facility("OAK_DOOR")&&!PlayerInteractionRules.facility("CHEST"),"interaction_facility_policy");
            var initial=one.inventory.getItem(0);one.inventory.setItem(0,one.inventory.getItem(9));one.inventory.setItem(9,initial);
            var offhand=one.inventory.getItem(40);one.inventory.setItem(40,one.inventory.getItem(9));one.inventory.setItem(9,offhand);
            var expected=one.capture();coordinator.refresh(s1);check(s1.save().dirty(),"interaction_existing_dirty_path");
            return s1.save().flush().thenCompose(x->mainResult(store.readInventory(a1))).thenCompose(saved->{
                check(codec.equivalent(saved.payload(),expected)&&saved.revision()==1,"interaction_hotbar_offhand_slots_and_metadata_saved");
                return verify(a2,5,0).thenCompose(x->mainResult(store.readInventory(ctx(b,1)))).thenCompose(afterB->{check(afterB.equals(beforeB[0]),"interaction_other_run_inventory_unchanged");return coordinator.disconnect(s1);}).thenCompose(x->{
                    one=new Controlled();s1=connect(a1,one);return s1.ready();
                }).thenRun(()->check(codec.equivalent(one.capture(),expected)&&s1.save().revision()==1,"interaction_reconnect_restores_slot_changes"));
            });
        });
    }
    private CompletionStage<Void> gameplayGuide(){
        var before=sharedStory.cached(a);var lines=StoryGuide.lines(sharedStory.content(),before,sharedStory.phase(a));
        check(sharedStory.content().guideNpcs().contains("survey_guide"),"guide_content_npc_link");
        check(lines.stream().anyMatch(line->"/hearthphone".equals(line.command())),"guide_phone_chat_link");
        check(lines.stream().noneMatch(line->line.text().contains("survey_marker")||line.text().contains("path_marker")),"guide_private_condition_ids_hidden");
        return journal.load(a1,s1,()->coordinator.playable(s1)).thenCompose(view->{
            var buttons=QuestScreens.screen(catalog,view,"story",null,0);
            check(buttons.stream().anyMatch(button->button.action().equals("story-guide")),"guide_gui_chat_only");
            return mainResult(store.readStory(a1));
        }).thenAccept(saved->{main();check(saved.equals(before),"guide_read_only_revision_unchanged");});
    }
    private CompletionStage<Void> storyPrepare(){var legacy=ctx(new UUID(42,99),99);return openAll().thenRun(()->{storyTestRoster=new HashMap<>(Map.of(a1.playerId(),s1,a2.playerId(),s2));sharedStory.rosterChanged(a1);sharedStory.rosterChanged(b3);})
        .thenCompose(v->mainResult(store.readExploration(legacy))).thenAccept(data->check(data.story().revision()==3 && data.story().stage().equals("public_test_stage") && data.places().get("public_village").hub(),"story_V005_rows_preserved_after_V006"))
        .thenCompose(v->seedList(a1,"survey_marker",QuestStore.Phase.COMPLETED))
        .thenCompose(v->expectQuestCode(sharedStory.advance(a1,s1,()->true),"CONDITIONS_UNMET"))
        .thenRun(()->check(sharedStory.cached(a).story().stage()==null && !paperStory.access().entry(a,21,64,21) && paperStory.access().entry(a,2,64,2) && !paperStory.access().content(a,21,64,3),"story_P2_blocks_first_stage_ENTRY_CONTENT_OPEN_separate"))
        .thenCompose(v->seedList(a2,"survey_marker",QuestStore.Phase.COMPLETED))
        .thenCompose(v->{var first=sharedStory.advance(a1,s1,()->true);var duplicate=expectQuestCode(sharedStory.advance(a2,s2,()->true),"STORY_BUSY_OR_HOLD");return first.thenCombine(duplicate,(s,x)->s);})
        .thenAccept(s->check(s.story().stage().equals("trial_arrival")&&s.story().revision()==0,"story_single_initial_CAS_shared_state"))
        .thenCompose(v->sharedStory.advance(a1,s1,()->true)).thenAccept(s->check(s.completed().contains("trial_arrival")&&s.story().stage().equals("trial_vote"),"story_completion_history_and_next_stage"))
        .thenCompose(v->seedList(a1,"path_marker",QuestStore.Phase.COMPLETED))
        .thenCompose(v->expectQuestCode(sharedStory.openVote(a1,s1,()->true),"CONDITIONS_UNMET"))
        .thenCompose(v->seedList(a2,"path_marker",QuestStore.Phase.COMPLETED))
        .thenCompose(v->seedList(a1,"route_marker",QuestStore.Phase.ACTIVE))
        .thenCompose(v->journal.load(a1,s1,()->true)).thenAccept(view->{check(view.exploration().conditions().registered()&&view.exploration().conditions().lines().size()==2,"story_online_conditions_public_booleans_only");check(view.exploration().blockedQuests().contains("route_marker")&&!QuestScreens.screen(catalog,view,"quests",null,0).toString().contains(catalog.quest("route_marker").title())&&view.exploration().place("survey_place").isEmpty(),"story_locked_accepted_quest_and_map_content_hidden");storyNoticeCount=view.journal().notices().size();});}
    private CompletionStage<Void> storyVote(){return sharedStory.openVote(a1,s1,()->true).thenCompose(vote->{storyVote=vote;check(vote.remainingSeconds()==30 && vote.targets()==2,"story_30sec_two_online_targets");return sharedStory.cast(a1,s1,()->true,vote.id(),"route_one");})
        .thenCompose(v->expectQuestCode(sharedStory.cast(a1,s1,()->true,storyVote.id(),"route_one"),"DUPLICATE_VOTE"))
        .thenCompose(v->sharedStory.cast(a2,s2,()->true,storyVote.id(),"route_two"))
        .thenRun(()->{storyClock.addAndGet(30_000_000_000L);sharedStory.poll(a1);check(sharedStory.vote(a).orElseThrow().state().equals("UNDECIDED")&&sharedStory.cached(a).branches().isEmpty(),"story_tie_timeout_no_automatic_branch");})
        .thenCompose(v->sharedStory.openVote(a1,s1,()->true)).thenAccept(vote->{storyVote=vote;storyTestRoster.remove(a2.playerId());sharedStory.rosterChanged(a1);})
        .thenCompose(v->until(()->sharedStory.phase(a)==SharedStory.Phase.OPEN))
        .thenCompose(v->expectQuestCode(sharedStory.cast(a1,s1,()->true,storyVote.id(),"route_one"),"STALE_VOTE"))
        .thenRun(()->{storyTestRoster.put(a2.playerId(),s2);sharedStory.rosterChanged(a1);})
        .thenCompose(v->until(()->sharedStory.phase(a)==SharedStory.Phase.OPEN)).thenRun(()->{var current=sharedStory.vote(a).orElseThrow();check(!current.id().equals(storyVote.id())&&current.targets()==2,"story_OPEN_roster_change_new_vote_id_old_input_rejected");storyVote=current;});}
    private CompletionStage<Void> storyPersistStart(){return sharedStory.cast(a1,s1,()->true,storyVote.id(),"route_one").thenCompose(v->{storySaving=sharedStory.cast(a2,s2,()->true,storyVote.id(),"route_one");storyBarrier=sharedStory.barrier(a);return later(()->{
        check(!storySaving.toCompletableFuture().isDone()&&!storyBarrier.toCompletableFuture().isDone()&&sharedStory.cached(a).story().stage().equals("trial_vote")&&!paperStory.access().entry(a,21,64,21),"story_DB_pending_no_branch_or_exploration_apply_main_tick_alive");storyTestRoster.remove(a2.playerId());sharedStory.rosterChanged(a1);check(sharedStory.phase(a)==SharedStory.Phase.PERSISTING,"story_INPUT_CLOSED_disconnect_keeps_frozen_result");});});}
    private CompletionStage<Void> storyPersistDone(){return mainResult(storySaving).thenCompose(v->mainResult(storyBarrier)).thenAccept(v->{check(sharedStory.cached(a).story().revision()==2&&sharedStory.cached(a).branches().get("trial_vote").choice().equals("route_one")&&Boolean.TRUE.equals(sharedStory.cached(a).story().explorationOpen()),"story_committed_branch_revision2_shared_exploration_open");check(paperStory.access().entry(a,21,64,21)&&paperStory.access().content(a,21,64,3)&&!paperStory.access().entry(b,21,64,21),"story_region_cache_A_unlocked_B_unchanged");storyTestRoster.put(a2.playerId(),s2);sharedStory.rosterChanged(a1);})
        .thenCompose(v->mainResult(store.readStory(a2))).thenAccept(s->check(s.equals(sharedStory.cached(a)),"story_P1_P2_same_confirmed_shared_result"))
        .thenCompose(v->mainResult(store.readJournal(a1))).thenAccept(data->check(data.notices().size()==storyNoticeCount,"story_no_community_or_diary_personal_notifications"))
        .thenCompose(v->journal.enterPlaces(a1,s1,()->true,-10,64,-10,2,64,2)).thenCompose(v->journal.enterPlaces(a1,s1,()->true,-10,64,-10,21,64,21))
        .thenCompose(v->journal.load(a1,s1,()->true)).thenAccept(view->{check(view.exploration().branches().equals(List.of("공개 경로 하나"))&&view.exploration().facts().stream().filter(ExplorationStore.Fact::shared).count()==4&&view.exploration().events().stream().filter(ExplorationStore.Event::shared).count()==4,"story_confirmed_branch_shared_diary_chronicle_once");
            var before=one.capture();for(String page:List.of("quests","branches","map","diary","STORY","CHRONICLE","community","story","conditions")){var rows=QuestScreens.screen(catalog,view,page,null,0);var screen=new PaperQuestUI.Screen(a1.playerId(),s1,rows,page,null,0);check(screen.getInventory().getSize()==54&&codec.equivalent(before,one.capture()),"story_Paper_GUI_41slot_unchanged_"+page);}check(view.exploration().place("survey_place").isPresent()&&!view.exploration().blockedQuests().contains("route_marker"),"story_unlocked_map_and_quest_projection_refresh");})
        .thenCompose(v->mainResult(store.readQuest(a1,"route_marker"))).thenAccept(row->check(row.equals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,0,0)),"story_shared_open_does_not_complete_personal_quest"))
        .thenCompose(v->mainResult(store.readStory(b3))).thenAccept(s->check(s.story().stage()==null&&s.branches().isEmpty(),"story_run_B_no_shared_changes"));}
    private CompletionStage<Void> storyCycle(){UUID physical=worlds.get(a).mapping().physicalId();storyTestRoster.clear();sharedStory.rosterChanged(a1);return mainResult(coordinator.disconnect(s1)).thenCompose(v->mainResult(coordinator.disconnect(s2))).thenCompose(v->until(()->worlds.get(a).state()==WorldLifecycle.State.OFFLINE))
        .thenRun(()->check(Bukkit.getWorld(physical)==null,"story_idle_unload_saved_barrier_complete"))
        .thenCompose(v->{one=new Controlled();two=new Controlled();s1=connect(a1,one);s2=connect(a2,two);return mainResult(s1.ready()).thenCompose(x->mainResult(s2.ready()));})
        .thenRun(()->{storyTestRoster.put(a1.playerId(),s1);storyTestRoster.put(a2.playerId(),s2);sharedStory.rosterChanged(a1);check(Bukkit.getWorld(physical)!=null,"story_existing_world_UUID_reloaded");}).thenCompose(v->storyRestored());}
    private CompletionStage<Void> storyRestored(){return journal.load(a1,s1,()->true).thenAccept(view->check(view.exploration().story().stage().equals("trial_open")&&view.exploration().story().revision()==2&&view.exploration().branches().equals(List.of("공개 경로 하나"))&&view.exploration().facts().stream().filter(ExplorationStore.Fact::shared).count()==4&&view.exploration().place("public_village").orElseThrow().hubUnlocked()&&view.states().get("route_marker").equals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,0,0)),"story_reentry_new_JVM_shared_branch_diary_personal_state_restored"))
        .thenCompose(v->mainResult(store.readStory(a2))).thenAccept(s->check(s.completed().equals(Set.of("trial_arrival","trial_vote"))&&s.story().revision()==2,"story_completed_stage_history_persisted_no_recreation"))
        .thenCompose(v->mainResult(store.readExploration(a2))).thenAccept(d->check(d.places().isEmpty(),"story_shared_exploration_not_personal_visit_or_hub"))
        .thenCompose(v->verify(a1,32,0));}
    private StoryStore.Request storyReplayRequest(UUID id,String choice){var c=sharedStory.content().stage("trial_vote");var selected=c.choices().stream().filter(x->x.id().equals(choice)).findFirst().orElseThrow();var target=sharedStory.content().stage(selected.next());return new StoryStore.Request(id,1,c.id(),target.id(),target.title(),true,choice,selected.label(),Set.of(a1.playerId(),a2.playerId()),Set.of("path_marker"),List.of(target.fact(),selected.fact()));}
    private CompletionStage<Void> storyReplay(){return mainResult(store.readStory(a1)).thenCompose(saved->{UUID id=saved.branches().get("trial_vote").request();return mainResult(store.commitStory(a2,storyReplayRequest(id,"route_one"),()->true)).thenAccept(s->check(s.story().revision()==2,"story_same_request_id_idempotent_no_revision_change"))
        .thenCompose(v->expect(store.commitStory(a1,storyReplayRequest(id,"route_two"),()->true),"REQUEST_MISMATCH"))
        .thenCompose(v->expect(store.commitStory(a1,storyReplayRequest(UUID.randomUUID(),"route_two"),()->true),"REVISION_CONFLICT"))
        .thenCompose(v->expect(store.commitStory(b3,storyReplayRequest(id,"route_one"),()->true),"REVISION_CONFLICT"))
        .thenCompose(v->expect(store.saveStory(a1,saved.story(),"trial_vote","이전 단계",false),"ENGINE_OWNED_STORY"));});}
    private final class StoryProbeStore implements StoryStore{
        boolean unknownBefore,unknownAfter;
        public CompletionStage<Snapshot> readStory(ServerProgressAccess.Context ctx){return store.readStory(ctx);}
        public CompletionStage<Map<UUID,Boolean>> storyConditions(ServerProgressAccess.Context ctx,Set<UUID> ps,Set<String> qs){return store.storyConditions(ctx,ps,qs);}
        public CompletionStage<Optional<String>> storyReceipt(ServerProgressAccess.Context ctx,UUID id){return store.storyReceipt(ctx,id);}
        public CompletionStage<Snapshot> commitStory(ServerProgressAccess.Context ctx,Request request,java.util.function.BooleanSupplier fence){if(unknownBefore)return CompletableFuture.failedFuture(new StorageFailure("story_probe","COMMIT_UNKNOWN"));return store.commitStory(ctx,request,fence).thenCompose(s->unknownAfter&&request.choice()!=null?CompletableFuture.failedFuture(new StorageFailure("story_probe","COMMIT_UNKNOWN")):CompletableFuture.completedFuture(s));}
    }
    private SharedStory storyProbe(StoryStore port,ServerProgressAccess.Context ctx,Object token){return new SharedStory(port,sharedStory.content(),owner,SessionVerification::main,c->Map.of(ctx.playerId(),token),storyClock::get,java.time.Duration.ofSeconds(30));}
    private CompletionStage<Void> storyUnknown(){var missing=ctx(new UUID(42,12),12);var confirmed=ctx(new UUID(42,13),13);Object token=new Object();var beforePort=new StoryProbeStore();beforePort.unknownBefore=true;var before=storyProbe(beforePort,missing,token);var afterPort=new StoryProbeStore();afterPort.unknownAfter=true;var after=storyProbe(afterPort,confirmed,token);int[] applied={0};after.effects((r,id,s)->{applied[0]++;return CompletableFuture.completedFuture(false);});
        return seedList(missing,"survey_marker",QuestStore.Phase.COMPLETED).thenCompose(v->before.load(missing))
            .thenCompose(v->expect(before.advance(missing,token,()->true),"COMMIT_UNKNOWN"))
            .thenCompose(v->expectQuestCode(before.resolve(missing),"RESULT_STILL_UNKNOWN"))
            .thenCompose(v->expectQuestCode(before.advance(missing,token,()->true),"STORY_BUSY_OR_HOLD"))
            .thenRun(()->check(before.phase(missing.storyRunId())==SharedStory.Phase.RESULT_UNKNOWN&&before.barrier(missing.storyRunId()).toCompletableFuture().isCompletedExceptionally(),"story_absent_receipt_stays_unknown_no_new_branch"))
            .thenCompose(v->seedList(confirmed,"survey_marker",QuestStore.Phase.COMPLETED)).thenCompose(v->seedList(confirmed,"path_marker",QuestStore.Phase.COMPLETED))
            .thenCompose(v->after.load(confirmed)).thenCompose(v->after.advance(confirmed,token,()->true)).thenCompose(v->after.advance(confirmed,token,()->true)).thenRun(()->applied[0]=0)
            .thenCompose(v->after.openVote(confirmed,token,()->true)).thenCompose(vote->expect(after.cast(confirmed,token,()->true,vote.id(),"route_one"),"COMMIT_UNKNOWN"))
            .thenRun(()->check(after.cached(confirmed.storyRunId()).story().stage().equals("trial_vote")&&applied[0]==0,"story_committed_SQL_unknown_response_no_early_cache_or_effect"))
            .thenCompose(v->after.resolve(confirmed)).thenAccept(saved->check(saved.branches().get("trial_vote").choice().equals("route_one")&&applied[0]==1&&after.effectStatus(confirmed.storyRunId()).equals("NOT_IMPLEMENTED"),"story_same_request_receipt_positive_resolve_once_not_real_world_effect"))
            .thenCompose(v->expectQuestCode(after.resolve(confirmed),"NO_UNKNOWN_REQUEST"));}
    private CompletionStage<Void> storyFailure(){var ctx=ctx(new UUID(42,14),14);Object token=new Object();storyFailureProbe=storyProbe(store,ctx,token);return mainResult(store.createRun(ctx.storyRunId(),"story-failure-only"))
        .thenCompose(v->mainResult(store.createPlayer(ctx,"story-failure-only"))).thenCompose(v->seedList(ctx,"survey_marker",QuestStore.Phase.COMPLETED)).thenCompose(v->storyFailureProbe.load(ctx))
        .thenCompose(v->expect(storyFailureProbe.advance(ctx,token,()->true),"23514"))
        .thenRun(()->check(storyFailureProbe.phase(ctx.storyRunId())==SharedStory.Phase.HOLD&&storyFailureProbe.barrier(ctx.storyRunId()).toCompletableFuture().isCompletedExceptionally(),"story_SQL_failure_probe_HOLD_preserved"))
        .thenCompose(v->mainResult(store.readStory(ctx))).thenAccept(s->check(s.story().stage()==null&&s.branches().isEmpty()&&s.completed().isEmpty(),"story_SQL_failure_no_false_stage_branch_or_completion"))
        .thenCompose(v->mainResult(store.readExploration(ctx))).thenAccept(d->check(d.facts().isEmpty()&&d.events().isEmpty(),"story_SQL_failure_no_partial_shared_diary"))
        .thenCompose(v->mainResult(store.readStory(a1))).thenAccept(s->check(s.story().revision()==2&&s.branches().size()==1,"story_failed_run_other_run_A_unchanged"))
        .thenCompose(v->mainResult(store.readStory(b3))).thenAccept(s->check(s.story().stage()==null,"story_failed_run_other_run_B_unchanged"));}
    private CompletionStage<QuestJournal.View> exploreView(ServerProgressAccess.Context ctx,Object token){return journal.load(ctx,token,()->true);}
    private CompletionStage<Void> exploreEntry(double x,double z){return journal.enterPlaces(a1,s1,()->coordinator.playable(s1),-10,64,-10,x,64,z);}
    private ExplorationContent.Fact sharedFact(){return new ExplorationContent.Fact("shared_test_event","STORY","공개 공동 기록","합성 공유 사건","실제 스토리 엔진이 아닌 격리 저장 인터페이스 시험입니다.",1,true);}
    private ExplorationContent.Fact updatedGuide(){var f=explorationContent.facts().get("guide_record");return new ExplorationContent.Fact(f.id(),f.category(),f.title(),"공개 새 조사 사실","공개 합성 사실 버전 2입니다.",2,true);}
    private CompletionStage<Void> explorePrepare(){
        if(explorationContent==null)throw new IllegalStateException("Exploration disabled");
        var legacy=ctx(new UUID(42,99),99);
        return openAll().thenRun(this::npcCheck)
            .thenCompose(v->mainResult(store.readQuest(legacy,"survey_marker"))).thenAccept(row->check(row.equals(new QuestStore.Saved(QuestStore.Phase.COMPLETED,2,0)),"explore_V004_upgrade_legacy_quest_unchanged"))
            .thenCompose(v->mainResult(store.readJournal(legacy))).thenAccept(data->check(!data.preferences().hudEnabled() && data.preferences().minimapEnabled() && data.preferences().questMarker() && data.preferences().npcMarker() && data.preferences().playerMarker() && data.preferences().placeMarker() && data.notices().size()==1 && data.notices().getFirst().type().equals("QUEST"),"explore_V005_preserves_legacy_HUD_notice_new_flags_default_true"))
            .thenCompose(v->exploreView(a1,s1)).thenAccept(view->{var e=view.exploration();String visible=e.places().toString();
                check(e.regions().size()==1 && e.places().size()==2 && !visible.contains("survey_place") && !visible.contains("21.0") && !visible.contains("37.0") && !visible.contains("공개 추가 조사 지점"),"explore_PUBLIC_QUESTION_HIDDEN_safe_DTO_coordinates");
                check(e.place("marker_question").orElseThrow().name().equals("?") && e.place("marker_question").orElseThrow().location().contains("28.0") && !e.place("public_village").orElseThrow().visited() && !e.place("public_village").orElseThrow().hubUnlocked(),"explore_map_publication_not_visit_or_hub_unlock");
                check(e.members().size()==2 && e.members().stream().allMatch(m->Set.of(a1.playerId(),a2.playerId()).contains(m.player())) && e.members().stream().anyMatch(CommunityBoundary.Member::self) && e.story().stage()==null && e.nextGoal()==null && !e.conditions().registered(),"explore_verified_partial_roster_same_run_no_invented_stage");
            });
    }
    private CompletionStage<Void> explorePending(){
        exploreWrite=exploreEntry(2,2);exploreDuplicate=exploreEntry(2,2);exploreBarrier=journal.barrier(a);
        return later(()->check(!exploreWrite.toCompletableFuture().isDone() && !exploreDuplicate.toCompletableFuture().isDone() && !exploreBarrier.toCompletableFuture().isDone(),"explore_discovery_coalesced_pending_in_save_barrier_main_tick_alive"));
    }
    private CompletionStage<Void> exploreDiscover(){return mainResult(exploreWrite).thenCompose(v->mainResult(exploreDuplicate)).thenCompose(v->mainResult(exploreBarrier))
        .thenCompose(v->exploreView(a1,s1)).thenAccept(view->check(view.exploration().place("public_village").orElseThrow().hubUnlocked() && view.journal().notices().size()==1 && view.exploration().facts().size()==1 && view.exploration().events().size()==1,"explore_first_verified_visit_atomic_discovery_notice_diary_hub"))
        .thenCompose(v->exploreEntry(2,2)).thenCompose(v->mainResult(store.readExploration(a1))).thenAccept(data->{check(data.places().get("public_village").revision()==1,"explore_repeated_visit_no_revision_change");})
        .thenCompose(v->exploreEntry(21,21)).thenCompose(v->exploreEntry(3.5,5.5))
        .thenCompose(v->mainResult(store.readExploration(a1))).thenAccept(data->check(data.places().get("guide_place").visited()!=null && data.places().get("guide_place").discovered()==null,"explore_interaction_place_visit_separate_from_discovery"))
        .thenCompose(v->journal.interactPlace(a1,s1,()->true,"npc:wrong",3.5,64,5.5))
        .thenCompose(v->journal.interactPlace(a1,s1,()->true,"npc:survey_guide",3.5,64,5.5))
        .thenCompose(v->journal.interactPlace(a1,s1,()->true,"npc:survey_guide",3.5,64,5.5))
        .thenCompose(v->mainResult(store.recordFact(a1,updatedGuide(),false))).thenAccept(changed->check(changed,"explore_new_fact_version_updates_detail"))
        .thenCompose(v->mainResult(store.recordFact(a1,sharedFact(),true))).thenAccept(changed->check(changed,"explore_shared_writer_interface_fixture_only"))
        .thenCompose(v->mainResult(store.saveStory(a1,new ExplorationStore.Story(null,null,null,-1),"public_test_stage","공개 합성 단계",true)))
        .thenCompose(v->exploreView(a1,s1)).thenAccept(view->check(view.exploration().places().size()==4 && "공개 시험 기록을 확인하세요.".equals(view.exploration().nextGoal()) && view.exploration().facts().size()==4 && view.exploration().events().size()==5 && view.journal().notices().size()==3 && view.journal().notices().stream().allMatch(n->n.type().equals("PLACE")) && !view.exploration().conditions().registered(),"explore_3_discoveries_4_current_facts_5_history_no_diary_or_shared_notice"))
        .thenCompose(v->exploreView(a2,s2)).thenAccept(view->check(view.exploration().places().size()==2 && view.exploration().facts().size()==1 && view.exploration().facts().getFirst().shared() && view.journal().notices().isEmpty() && Boolean.TRUE.equals(view.exploration().story().explorationOpen()),"explore_P2_shared_fact_story_only_personal_discovery_notice_isolated"))
        .thenCompose(v->exploreView(ctx(b,1),new Object())).thenAccept(view->check(view.exploration().places().size()==2 && view.exploration().facts().isEmpty() && view.exploration().story().stage()==null && view.exploration().nextGoal()==null && view.journal().notices().isEmpty(),"explore_same_player_run_B_isolated"));}
    private CompletionStage<Void> exploreUi(){
        CompletionStage<?> work=CompletableFuture.completedFuture(null);
        for(String key:List.of("hud","minimap","quest_marker","npc_marker","player_marker","place_marker"))work=work.thenCompose(v->journal.setting(a1,s1,()->true,key,false));
        return work.thenCompose(v->exploreView(a1,s1)).thenAccept(view->{var before=one.capture();
            for(String page:List.of("home","map","region","place","diary","STORY","PERSON","EXPLORATION","CHRONICLE","fact","event","community","members","story","conditions","settings","notices")){
                String id=switch(page){case "region"->"survey_valley";case "place"->"survey_place";case "fact"->"P:guide_record";case "event"->"P:guide_record:1";default->null;};
                var rows=QuestScreens.screen(catalog,view,page,id,0);var screen=new PaperQuestUI.Screen(a1.playerId(),s1,rows,page,id,0);
                check(screen.getInventory().getSize()==54 && screen.getInventory().getHolder()==screen && codec.equivalent(before,one.capture()),"explore_Paper_GUI_54_slots_inventory_unchanged_"+page);
            }
            check(QuestScreens.screen(catalog,view,"notices",null,0).stream().filter(row->row.action().equals("place-notice")).count()==3,"explore_notification_map_detail_links");
            check(QuestScreens.screen(catalog,view,"story",null,0).stream().anyMatch(row->row.label().equals("다음 공유 목표: 공개 시험 기록을 확인하세요.")),"explore_registered_next_goal_GUI_only_current_shared_stage");
            check(!view.journal().preferences().hudEnabled() && !view.journal().preferences().minimapEnabled() && !view.journal().preferences().questMarker() && !view.journal().preferences().npcMarker() && !view.journal().preferences().playerMarker() && !view.journal().preferences().placeMarker(),"explore_six_personal_flags_off");
        }).thenCompose(v->journal.readPlaceNotice(a1,s1,()->true,"survey_place"))
        .thenCompose(v->journal.readAll(a1,s1,()->true)).thenAccept(view->check(view.journal().notices().stream().allMatch(QuestJournalStore.Notice::read),"explore_place_notice_read_one_and_all"))
        .thenCompose(v->mainResult(store.readExploration(a1))).thenAccept(data->exploreBefore=data.places());
    }
    private CompletionStage<Void> exploreCycle(){UUID physical=worlds.get(a).mapping().physicalId();return mainResult(coordinator.disconnect(s1)).thenCompose(v->mainResult(coordinator.disconnect(s2)))
        .thenCompose(v->until(()->worlds.get(a).state()==WorldLifecycle.State.OFFLINE)).thenRun(()->check(Bukkit.getWorld(physical)==null && !questAdapter.tracks(physical),"explore_idle_unload_old_NPC_refs_cleared"))
        .thenCompose(v->{one=new Controlled();two=new Controlled();s1=connect(a1,one);s2=connect(a2,two);return mainResult(s1.ready()).thenCompose(x->mainResult(s2.ready()));})
        .thenRun(this::npcCheck).thenCompose(v->exploreRestored());}
    private CompletionStage<Void> exploreRestored(){return exploreView(a1,s1).thenAccept(view->check(view.exploration().facts().size()==4 && view.exploration().events().size()==5 && view.journal().notices().size()==3 && view.journal().notices().stream().allMatch(QuestJournalStore.Notice::read) && !view.journal().preferences().hudEnabled() && !view.journal().preferences().placeMarker() && view.exploration().place("public_village").orElseThrow().hubUnlocked() && view.exploration().story().revision()==0 && "공개 시험 기록을 확인하세요.".equals(view.exploration().nextGoal()),"explore_restore_places_facts_history_notices_flags_shared_state"))
        .thenCompose(v->mainResult(store.readExploration(a1))).thenAccept(data->{if(exploreBefore!=null)check(data.places().equals(exploreBefore),"explore_read_and_setting_preserve_place_revisions");})
        .thenCompose(v->verify(a1,32,0));}
    private CompletionStage<Void> exploreComplete(){var c=catalog.quest("route_marker");return exploreEntry(37,37)
        .thenCompose(v->quests.open(a1,c.id(),s1,()->true)).thenCompose(q->{batchRoute=q;return quests.accept(q,c);})
        .thenCompose(v->batchGoal(quests,batchRoute,c,0)).thenCompose(v->batchGoal(quests,batchRoute,c,1)).thenCompose(v->batchGoal(quests,batchRoute,c,2)).thenCompose(v->quests.advance(batchRoute,QuestStore.Phase.COMPLETED))
        .thenCompose(v->exploreView(a1,s1)).thenAccept(view->check(view.exploration().facts().size()==5 && view.exploration().events().size()==6 && view.journal().notices().size()==9 && view.journal().notices().stream().filter(n->n.type().equals("PLACE")).count()==4 && view.states().get("route_marker").phase()==QuestStore.Phase.COMPLETED,"explore_quest_COMPLETED_configured_story_fact_no_extra_diary_alert"))
        .thenCompose(v->mainResult(store.recordFact(a1,updatedGuide(),false))).thenAccept(changed->check(!changed,"explore_identical_fact_version_no_duplicate"))
        .thenCompose(v->mainResult(store.recordFact(a2,sharedFact(),true))).thenAccept(changed->check(!changed,"explore_shared_event_same_version_no_duplicate"))
        .thenCompose(v->expect(store.saveStory(a2,new ExplorationStore.Story(null,null,null,-1),"other_stage","다른 시험",false),"REVISION_CONFLICT"))
        .thenCompose(v->exploreView(a2,s2)).thenAccept(view->check(view.exploration().facts().size()==1 && view.journal().notices().isEmpty() && view.states().get("route_marker").phase()==QuestStore.Phase.NOT_ACCEPTED,"explore_quest_diary_notice_P2_isolation"))
        .thenCompose(v->{explorationConditions.put(a,new CommunityBoundary.Condition("public_test_stage",Map.of()));return exploreView(a1,s1);}).thenAccept(view->check(view.exploration().conditions().registered() && view.exploration().conditions().lines().equals(List.of("현재 접속 검사 대상 없음")),"explore_condition_provider_only_current_online_targets_controlled_no_fake_players"));}
    private CompletionStage<Void> exploreFailure(){
        var probe=new QuestJournal(store,quests,catalog,owner,SessionVerification::main);probe.exploration(store,explorationContent,new CommunityBoundary(adapter::community,(c,ids)->Optional.empty()));
        return expect(probe.enterPlaces(a2,s2,()->true,-10,64,-10,21,64,21),"23514")
            .thenRun(()->check(!probe.settled(a) && probe.barrier(a).toCompletableFuture().isCompletedExceptionally(),"explore_failed_discovery_HOLD_save_barrier_preserved"))
            .thenCompose(v->mainResult(store.readExploration(a2))).thenAccept(data->check(data.places().isEmpty() && data.facts().size()==1 && data.facts().getFirst().shared() && data.events().size()==1,"explore_diary_event_failure_rolls_back_place_fact_timeline"))
            .thenCompose(v->mainResult(store.readJournal(a2))).thenAccept(data->check(data.notices().isEmpty(),"explore_failed_discovery_no_false_notice_success"))
            .thenCompose(v->exploreView(a1,s1)).thenAccept(view->check(view.exploration().facts().size()==5 && view.journal().notices().size()==9,"explore_failure_other_player_existing_values_unchanged"))
            .thenCompose(v->exploreView(ctx(b,1),new Object())).thenAccept(view->check(view.exploration().facts().isEmpty() && view.journal().notices().isEmpty(),"explore_failure_other_run_unaffected"));
    }
    private CompletionStage<QuestStore.Saved> batchGoal(PersonalQuest owner,PersonalQuest.Session q,QuestContent c,int index){var area=c.goals().get(index).area();return owner.reach(q,c,(area.minX()+area.maxX())/2,area.minY(),(area.minZ()+area.maxZ())/2);}
    private CompletionStage<Void> batchPrepare(){
        var legacy=ctx(new UUID(42,99),99);
        return openAll().thenRun(this::npcCheck)
            .thenCompose(v->mainResult(store.readQuest(legacy,"survey_marker"))).thenAccept(row->check(row.equals(new QuestStore.Saved(QuestStore.Phase.COMPLETED,2,0)),"batch_V003_upgrade_preserves_legacy_row"))
            .thenCompose(v->mainResult(store.readJournal(legacy))).thenAccept(data->check(data.notices().isEmpty() && data.tracked().isEmpty(),"batch_no_legacy_notification_backfill"))
            .thenCompose(v->quests.open(a1,"route_marker",s1,()->true)).thenCompose(q->{batchRoute=q;return quests.accept(q,catalog.quest("route_marker"));})
            .thenCompose(v->quests.open(a2,"route_marker",s2,()->true)).thenCompose(q->quests.accept(q,catalog.quest("route_marker")))
            .thenCompose(v->quests.open(a1,"survey_marker",s1,()->true)).thenCompose(q->quests.accept(q,catalog.quest("survey_marker")))
            .thenCompose(v->journal.track(a1,s1,()->true,"route_marker",true))
            .thenCompose(v->journal.track(a1,s1,()->true,"survey_marker",true))
            .thenAccept(view->check(view.journal().tracked().size()==2 && journal.hudLines(view).size()==2,"batch_multiple_tracking_and_HUD"))
            .thenCompose(v->rejectQuest(journal.track(a1,s1,()->true,"route_followup",true)))
            .thenCompose(v->batchGoal(quests,batchRoute,catalog.quest("route_marker"),0))
            .thenAccept(row->check(row.equals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,1,1)),"batch_first_step_saved_index1"))
            .thenCompose(v->rejectQuest(batchGoal(quests,batchRoute,catalog.quest("route_marker"),0)))
            .thenCompose(v->expect(store.advanceQuest(a1,"route_marker",new QuestStore.Saved(QuestStore.Phase.ACTIVE,0,0),QuestStore.Phase.ACTIVE,1),"REVISION_CONFLICT_OR_MISSING"))
            .thenCompose(v->journal.readNotice(a1,s1,()->true,"route_marker",0))
            .thenCompose(v->journal.readAll(a1,s1,()->true))
            .thenCompose(v->journal.hud(a1,s1,()->true,false))
            .thenAccept(view->check(view.journal().notices().size()==3 && view.journal().notices().stream().allMatch(io.github.jesterportfolio.hearth.persistence.QuestJournalStore.Notice::read) && journal.hudLines(view).isEmpty(),"batch_notifications_unique_read_all_HUD_off"));
    }
    private CompletionStage<Void> batchUi(){return journal.load(a1,s1,()->true).thenAccept(view->{
        var before=one.capture();check(QuestScreens.screen(catalog,view,"home",null,0).size()==6,"batch_six_phone_menus");
        var rows=QuestScreens.screen(catalog,view,"detail","route_marker",0);
        check(rows.stream().anyMatch(b->b.label().contains("[2/3]")) && rows.stream().anyMatch(b->b.action().equals("track")),"batch_GUI_current_step_and_track_button");
        var screen=new PaperQuestUI.Screen(a1.playerId(),s1,rows,"detail","route_marker",0);
        check(screen.getInventory().getSize()==54 && screen.getInventory().getHolder()==screen && codec.equivalent(before,one.capture()),"batch_real_Paper_GUI_inventory_holder_no_41slot_change");
        var enabled=new QuestJournal.View(view.states(),new io.github.jesterportfolio.hearth.persistence.QuestJournalStore.Journal(view.journal().tracked(),new io.github.jesterportfolio.hearth.persistence.QuestJournalStore.Preferences(true,0),view.journal().notices()));
        check(PaperQuestUI.hudBars(journal.hudLines(enabled)).size()==2,"batch_Paper_BossBar_factory_multiple_lines");
        for(String page:List.of("map","diary","community"))check(QuestScreens.screen(catalog,view,page,null,0).stream().anyMatch(b->b.label().startsWith("미구현")),"batch_no_fake_data_"+page);
        check(QuestScreens.screen(catalog,view,"notices",null,0).stream().filter(b->b.action().equals("notice")).count()==3,"batch_notice_GUI_persistent_rows");
    });}
    private CompletionStage<Void> batchCycle(){
        UUID physical=worlds.get(a).mapping().physicalId();
        return mainResult(coordinator.disconnect(s1)).thenCompose(v->mainResult(coordinator.disconnect(s2)))
            .thenCompose(v->until(()->worlds.get(a).state()==WorldLifecycle.State.OFFLINE))
            .thenRun(()->check(Bukkit.getWorld(physical)==null && !questAdapter.tracks(physical),"batch_world_offline_old_NPC_refs_cleared"))
            .thenCompose(v->{one=new Controlled();two=new Controlled();s1=connect(a1,one);s2=connect(a2,two);return mainResult(s1.ready()).thenCompose(x->mainResult(s2.ready()));})
            .thenRun(this::npcCheck).thenCompose(v->batchRestored());
    }
    private CompletionStage<Void> batchRestored(){return quests.open(a1,"route_marker",s1,()->true).thenAccept(q->{batchRoute=q;check(q.saved().equals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,1,1)),"batch_restored_current_objective");})
        .thenCompose(v->journal.load(a1,s1,()->true)).thenAccept(view->check(view.journal().tracked().equals(Set.of("route_marker","survey_marker")) && !view.journal().preferences().hudEnabled() && view.journal().notices().size()==3,"batch_restored_tracking_settings_notifications"));}
    private CompletionStage<Void> batchResume(){return openAll().thenRun(this::npcCheck).thenCompose(v->batchRestored()).thenCompose(v->verify(a1,32,0));}
    private CompletionStage<Void> batchComplete(){
        var c=catalog.quest("route_marker");var follow=catalog.quest("route_followup");
        return batchGoal(quests,batchRoute,c,1).thenCompose(v->batchGoal(quests,batchRoute,c,2))
            .thenAccept(row->check(row.equals(new QuestStore.Saved(QuestStore.Phase.REPORT_READY,3,2)),"batch_final_goal_report_wait_index2"))
            .thenCompose(v->quests.advance(batchRoute,QuestStore.Phase.COMPLETED))
            .thenCompose(v->journal.load(a1,s1,()->true)).thenAccept(view->check(view.journal().tracked().equals(Set.of("survey_marker")),"batch_completed_tracking_removed_atomically"))
            .thenCompose(v->entry(quests,a1,follow,s1,()->true,false))
            .thenCompose(v->journal.track(a1,s1,()->true,follow.id(),true))
            .thenCompose(v->quests.open(a1,follow.id(),s1,()->true)).thenCompose(q->batchGoal(quests,q,follow,0).thenCompose(x->batchGoal(quests,q,follow,1)))
            .thenAccept(row->check(row.equals(new QuestStore.Saved(QuestStore.Phase.COMPLETED,2,1)),"batch_followup_unlock_auto_start_multistep_auto_complete"))
            .thenCompose(v->journal.hud(a1,s1,()->true,true))
            .thenAccept(view->check(journal.hudLines(view).size()==1 && view.journal().notices().size()==9 && view.journal().tracked().equals(Set.of("survey_marker")),"batch_UI_latest_state_tracking_cleanup_nine_unique_notices"))
            .thenCompose(v->journal.readAll(a1,s1,()->true)).thenAccept(view->check(view.journal().notices().stream().allMatch(io.github.jesterportfolio.hearth.persistence.QuestJournalStore.Notice::read),"batch_mark_all_after_completion"))
            .thenCompose(v->journal.load(a2,s2,()->true)).thenAccept(view->check(view.states().get("route_marker").equals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,0,0)) && view.journal().tracked().isEmpty() && view.journal().preferences().hudEnabled() && view.journal().notices().size()==1,"batch_other_player_isolated"))
            .thenCompose(v->journal.load(ctx(b,1),new Object(),()->true)).thenAccept(view->check(view.states().get("route_marker").phase()==QuestStore.Phase.NOT_ACCEPTED && view.journal().notices().isEmpty() && view.journal().tracked().isEmpty(),"batch_same_player_other_run_isolated"));
    }
    private CompletionStage<Void> batchNoticeFailure(){
        var probe=new PersonalQuest(store,catalog.questIds(),owner,SessionVerification::main);
        return probe.open(a2,"route_marker",new Object(),()->true).thenCompose(q->expect(batchGoal(probe,q,catalog.quest("route_marker"),0),"23514")
            .thenRun(()->check(q.held() && q.saved().equals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,0,0)),"batch_notice_failure_HOLD_original_step")))
            .thenCompose(v->mainResult(store.readQuest(a2,"route_marker"))).thenAccept(row->check(row.equals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,0,0)),"batch_transaction_rollback_quest_when_notice_INSERT_fails"))
            .thenCompose(v->mainResult(store.readJournal(a2))).thenAccept(data->check(data.notices().size()==1,"batch_no_partial_or_duplicate_notification"));
    }
    private CompletionStage<Void> batchPreferencesFailure(){
        var probe=new QuestJournal(store,quests,catalog,owner,SessionVerification::main);
        return expect(probe.hud(a2,s2,()->true,false),"23514").thenRun(()->check(!probe.settled(a),"batch_settings_failure_UI_HOLD"))
            .thenCompose(v->mainResult(store.readJournal(a2))).thenAccept(data->check(data.preferences().hudEnabled() && data.preferences().revision()==-1,"batch_settings_failure_original_default_preserved"));
    }
    private CompletionStage<Void> batchUiPending(){
        batchUiRead=journal.load(a1,s1,batchValid::get);batchUiBarrier=journal.barrier(a);
        return later(()->{check(!batchUiRead.toCompletableFuture().isDone() && !batchUiBarrier.toCompletableFuture().isDone(),"batch_UI_read_async_in_shutdown_barrier");
            check(journal.load(a1,s1,()->true).toCompletableFuture().isCompletedExceptionally(),"batch_duplicate_UI_read_limited");batchValid.set(false);});
    }
    private QuestContent prerequisiteContent(boolean npc,boolean automatic){
        var c=catalog.quest("followup_marker");
        return new QuestContent(c.id(),c.title(),c.npcId(),c.name(),c.x(),c.y(),c.z(),c.minX(),c.maxX(),c.minY(),c.maxY(),c.minZ(),c.maxZ(),
            automatic?QuestContent.CompletionMode.AUTOMATIC:c.completionMode(),npc?QuestContent.StartMode.NPC:c.startMode(),npc?null:c.startArea(),c.requiresCompleted(),c.text());
    }
    private CompletionStage<Void> prerequisiteMenu(ServerProgressAccess.Context context,Object token,boolean visible){
        return listSnapshot(context).thenCompose(before->quests.list(context,token,()->true).thenCompose(rows->{
            quests.requireListDisplay(context,rows);var d=new QuestDialogue(catalog);UUID world=worlds.get(context.storyRunId()).mapping().physicalId();
            var options=d.options(context.playerId(),token,context.storyRunId(),world,UUID.randomUUID(),"survey_guide");
            var expected=options.stream().filter(t->QuestListView.available(catalog.quest(t.quest()),rows)).map(t->Component.text("["+catalog.quest(t.quest()).title()+"]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(d.command(t,"select")))).toList();
            var messages=PaperQuestAdapter.selectionMessages(catalog,d,options,rows);
            check(messages.equals(expected) && messages.size()==(visible?4:3) && QuestListView.available(catalog.quest("followup_marker"),rows)==visible,"prereq_filtered_NPC_choices_"+context.storyRunId()+"_"+context.playerId()+"_visible"+visible);
            return listSnapshot(context).thenAccept(after->check(before.equals(after),"prereq_menu_DB_rows_revisions_unchanged"));
        }));
    }
    private CompletionStage<Void> prerequisitePrepare(){
        return openAll().thenRun(this::npcCheck).thenCompose(v->prerequisiteMenu(a1,s1,false))
            .thenCompose(v->quests.open(a1,"followup_marker",s1,()->true)).thenCompose(q->expectQuestCode(quests.accept(q,prerequisiteContent(true,false)),"QUEST_LOCKED"))
            .thenCompose(v->entry(quests,a1,catalog.quest("followup_marker"),s1,()->true,false))
            .thenAccept(row->check(row.isEmpty(),"prereq_locked_area_no_start"))
            .thenCompose(v->questRead(a1,"followup_marker",QuestStore.Phase.NOT_ACCEPTED,-1))
            .thenCompose(v->questRead(a2,"followup_marker",QuestStore.Phase.NOT_ACCEPTED,-1))
            .thenCompose(v->questRead(ctx(b,1),"followup_marker",QuestStore.Phase.NOT_ACCEPTED,-1));
    }
    private CompletionStage<Void> prerequisiteComplete(){
        return quests.open(a1,"survey_marker",s1,()->true).thenCompose(q->quests.accept(q,catalog.quest("survey_marker")).thenCompose(v->visit(quests,q,catalog.quest("survey_marker"))).thenCompose(v->quests.advance(q,QuestStore.Phase.COMPLETED)))
            .thenCompose(v->prerequisiteMenu(a1,s1,true)).thenCompose(v->prerequisiteMenu(a2,s2,false)).thenCompose(v->prerequisiteMenu(ctx(b,1),new Object(),false));
    }
    private CompletionStage<Void> prerequisiteStart(){
        return quests.open(a1,"followup_marker",s1,()->true).thenCompose(q->{
            prerequisiteWrite=quests.accept(q,prerequisiteContent(true,false));
            return expectQuestCode(quests.accept(q,prerequisiteContent(true,false)),"QUEST_BUSY_OR_HOLD");
        }).thenRun(()->check(!prerequisiteWrite.toCompletableFuture().isDone() && !quests.barrier(a).toCompletableFuture().isDone(),"prereq_read_pending_no_success_and_barrier_waits"));
    }
    private CompletionStage<Void> prerequisiteStartDone(){return mainResult(prerequisiteWrite)
        .thenAccept(row->check(row.equals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,0)),"prereq_NPC_start_revision0"))
        .thenCompose(v->quests.open(a1,"followup_marker",s1,()->true)).thenCompose(q->visit(quests,q,catalog.quest("followup_marker")).thenCompose(v->quests.advance(q,QuestStore.Phase.COMPLETED)))
        .thenCompose(v->questRead(a1,"followup_marker",QuestStore.Phase.COMPLETED,2));}
    private CompletionStage<Void> prerequisiteErrorReady(){
        prerequisiteProbe=new PersonalQuest(store,catalog.questIds(),owner,SessionVerification::main);
        return prerequisiteProbe.open(ctx(b,1),"followup_marker",new Object(),()->true).thenAccept(q->prerequisiteHeld=q);
    }
    private CompletionStage<Void> prerequisiteError(){return expect(prerequisiteProbe.accept(prerequisiteHeld,prerequisiteContent(true,false)),"42P01")
        .thenCompose(v->expect(quests.list(a2,s2,()->true),"42P01"))
        .thenRun(()->check(prerequisiteHeld.held() && prerequisiteHeld.saved().phase()==QuestStore.Phase.NOT_ACCEPTED,"prereq_query_failure_HOLD_no_unlock_no_INSERT"));}
    private CompletionStage<Void> prerequisiteStale(){
        prerequisiteStaleWrite=quests.accept(prerequisiteOld,prerequisiteContent(true,false));prerequisiteValid.set(false);prerequisiteFreshToken=new Object();
        prerequisiteFresh=quests.open(a2,"followup_marker",prerequisiteFreshToken,()->true);
        check(!prerequisiteStaleWrite.toCompletableFuture().isDone() && !prerequisiteFresh.toCompletableFuture().isDone(),"prereq_new_session_waits_for_old_query");return CompletableFuture.completedFuture(null);
    }
    private CompletionStage<Void> prerequisiteCheck(){
        var context=ctx(b,1);var c=catalog.quest("followup_marker");Object token=new Object();
        return entry(quests,a2,c,prerequisiteFreshToken,()->true,false).thenAccept(row->check(row.orElseThrow().equals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,0)),"prereq_automatic_start_after_fresh_entry"))
            .thenCompose(v->entry(quests,a2,c,prerequisiteFreshToken,()->true,false)).thenAccept(row->check(row.isEmpty(),"prereq_started_reentry_no_duplicate"))
            .thenCompose(v->entry(quests,a2,c,prerequisiteFreshToken,()->true,true))
            .thenCompose(v->quests.open(a2,c.id(),prerequisiteFreshToken,()->true)).thenCompose(q->quests.advance(q,QuestStore.Phase.COMPLETED))
            .thenCompose(v->entry(quests,context,c,token,()->true,false)).thenAccept(row->check(row.isEmpty(),"prereq_other_run_still_locked"))
            .thenCompose(v->seedList(context,"survey_marker",QuestStore.Phase.COMPLETED))
            .thenCompose(v->entry(quests,context,prerequisiteContent(false,true),token,()->true,false))
            .thenCompose(v->entry(quests,context,prerequisiteContent(false,true),token,()->true,true))
            .thenAccept(row->check(row.orElseThrow().equals(new QuestStore.Saved(QuestStore.Phase.COMPLETED,1)),"prereq_automatic_completion_revision1"))
            .thenCompose(v->questRead(a1,c.id(),QuestStore.Phase.COMPLETED,2))
            .thenCompose(v->questRead(a2,c.id(),QuestStore.Phase.COMPLETED,2))
            .thenCompose(v->questRead(context,c.id(),QuestStore.Phase.COMPLETED,1))
            .thenCompose(v->prerequisiteMenu(a1,s1,true));
    }
    private CompletionStage<Optional<QuestStore.Saved>> entry(PersonalQuest owner,ServerProgressAccess.Context ctx,QuestContent c,Object token,BooleanSupplier valid,boolean goal){
        return owner.enter(ctx,c,token,valid,2,64,2,goal?(c.minX()+c.maxX())/2:(c.startArea().minX()+c.startArea().maxX())/2,64,goal?(c.minZ()+c.maxZ())/2:(c.startArea().minZ()+c.startArea().maxZ())/2);
    }
    private CompletionStage<Void> entryPrepare(){
        return openAll().thenRun(this::npcCheck)
            .thenCompose(v->quests.open(a1,"entry_marker",s1,()->coordinator.playable(s1)))
            .thenCompose(q->rejectQuest(quests.accept(q,catalog.quest("entry_marker"))))
            .thenCompose(v->questRead(a1,"entry_marker",QuestStore.Phase.NOT_ACCEPTED,-1))
            .thenCompose(v->entry(quests,a1,catalog.quest("entry_marker"),s1,()->true,true))
            .thenAccept(row->check(row.isEmpty(),"entry_goal_before_start_no_progress"))
            .thenCompose(v->questRead(a1,"survey_marker",QuestStore.Phase.NOT_ACCEPTED,-1))
            .thenCompose(v->questRead(a1,"path_marker",QuestStore.Phase.NOT_ACCEPTED,-1))
            .thenCompose(v->questRead(a2,"entry_marker",QuestStore.Phase.NOT_ACCEPTED,-1))
            .thenCompose(v->questRead(ctx(b,1),"entry_marker",QuestStore.Phase.NOT_ACCEPTED,-1))
            .thenRun(()->{
                var d=new QuestDialogue(catalog);var w=worlds.get(a).mapping().physicalId();
                var tickets=d.options(a1.playerId(),s1,a,w,UUID.randomUUID(),"survey_guide");
                check(PaperQuestAdapter.selectionMessages(catalog,d,tickets).size()==3,"entry_existing_NPC_three_choices");
                var t=tickets.stream().filter(x->x.quest().equals("entry_marker")).findFirst().orElseThrow();
                d.authorize(a1.playerId(),s1,a,w,t.entity(),QuestDialogue.Request.parse(d.command(t,"select")));
                var message=PaperQuestAdapter.dialogueMessages(catalog.quest(t.quest()),new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1),d,t).getFirst();
                check(message.children().isEmpty(),"entry_NPC_no_accept_button");
            });
    }
    private CompletionStage<Void> entryStart(){
        entryWrite=entry(quests,a1,catalog.quest("entry_marker"),s1,()->coordinator.playable(s1),false);
        return entry(quests,a1,catalog.quest("entry_marker"),s1,()->true,false).thenAccept(row->{
            check(row.isEmpty() && !entryWrite.toCompletableFuture().isDone() && !quests.barrier(a).toCompletableFuture().isDone(),"entry_pending_no_success_duplicate_bounded_barrier_waits");
        });
    }
    private CompletionStage<Void> entryStartDone(){
        return mainResult(entryWrite).thenAccept(row->check(row.orElseThrow().equals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,0)),"entry_start_DB_success_revision0"))
            .thenCompose(v->entry(quests,a1,catalog.quest("entry_marker"),s1,()->true,false))
            .thenAccept(row->check(row.isEmpty(),"entry_reentry_no_duplicate_write"))
            .thenCompose(v->questRead(a1,"entry_marker",QuestStore.Phase.ACTIVE,0))
            .thenCompose(v->entry(quests,a1,catalog.quest("entry_marker"),s1,()->true,true))
            .thenAccept(row->check(row.orElseThrow().equals(new QuestStore.Saved(QuestStore.Phase.REPORT_READY,1)),"entry_report_completion_waits_for_NPC"))
            .thenCompose(v->quests.open(a1,"entry_marker",s1,()->true))
            .thenCompose(q->quests.advance(q,QuestStore.Phase.COMPLETED))
            .thenCompose(v->questRead(a1,"entry_marker",QuestStore.Phase.COMPLETED,2));
    }
    private CompletionStage<Void> entryFailure(){
        var probe=new PersonalQuest(store,catalog.questIds(),owner,SessionVerification::main);var context=ctx(b,1);Object token=new Object();
        return probe.open(context,"entry_marker",token,()->true).thenCompose(q->expect(entry(probe,context,catalog.quest("entry_marker"),token,()->true,false),"23514")
            .thenRun(()->check(q.held() && q.saved().equals(new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1)),"entry_insert_failure_HOLD_no_start_success"))
            .thenCompose(v->rejectQuest(entry(probe,context,catalog.quest("entry_marker"),token,()->true,false))))
            .thenCompose(v->questRead(context,"entry_marker",QuestStore.Phase.NOT_ACCEPTED,-1))
            .thenCompose(v->questRead(a1,"entry_marker",QuestStore.Phase.COMPLETED,2));
    }
    private CompletionStage<Void> entryStale(){
        // 위 준비 단계와 같은 token 대신, 준비된 세션을 직접 시작해 DB 대기 경계를 만듭니다.
        entryStaleWrite=quests.start(entryOld,catalog.quest("entry_marker"),6,64,9).thenApply(Optional::of);
        entryValid.set(false);entryFresh=quests.open(a2,"entry_marker",new Object(),()->true);
        check(!entryStaleWrite.toCompletableFuture().isDone() && !entryFresh.toCompletableFuture().isDone(),"entry_new_session_waits_pending_insert");
        return CompletableFuture.completedFuture(null);
    }
    private CompletionStage<Void> entryStaleDone(){return expectQuestCode(entryStaleWrite,"STALE_QUEST").thenCompose(v->mainResult(entryFresh))
        .thenAccept(q->check(q.saved().equals(new QuestStore.Saved(QuestStore.Phase.ACTIVE,0)) && entryOld.saved().phase()==QuestStore.Phase.NOT_ACCEPTED,"entry_old_response_no_start_message_new_session_reads_confirmed_row"));}
    private CompletionStage<Void> entryCheck(){
        var c=catalog.quest("entry_marker");
        var automatic=new QuestContent(c.id(),c.title(),c.npcId(),c.name(),c.x(),c.y(),c.z(),c.minX(),c.maxX(),c.minY(),c.maxY(),c.minZ(),c.maxZ(),QuestContent.CompletionMode.AUTOMATIC,c.startMode(),c.startArea(),c.requiresCompleted(),c.text());
        Object token=new Object();var context=ctx(b,1);
        return entry(quests,context,automatic,token,()->true,false)
            .thenAccept(row->check(row.orElseThrow().phase()==QuestStore.Phase.ACTIVE,"entry_auto_completion_variant_starts_only"))
            .thenCompose(v->entry(quests,context,automatic,token,()->true,true))
            .thenAccept(row->check(row.orElseThrow().equals(new QuestStore.Saved(QuestStore.Phase.COMPLETED,1)),"entry_automatic_completion_variant_revision1"))
            .thenCompose(v->entry(quests,context,automatic,token,()->true,false)).thenAccept(row->check(row.isEmpty(),"entry_completed_reentry_no_write"))
            .thenCompose(v->quests.open(a1,"survey_marker",s1,()->true)).thenCompose(q->quests.accept(q,catalog.quest("survey_marker")).thenCompose(x->visit(quests,q,catalog.quest("survey_marker"))).thenCompose(x->quests.advance(q,QuestStore.Phase.COMPLETED)))
            .thenCompose(v->quests.open(a1,"path_marker",s1,()->true)).thenCompose(q->quests.accept(q,catalog.quest("path_marker")).thenCompose(x->visit(quests,q,catalog.quest("path_marker"))))
            .thenCompose(v->questRead(a1,"survey_marker",QuestStore.Phase.COMPLETED,2))
            .thenCompose(v->questRead(a1,"path_marker",QuestStore.Phase.COMPLETED,1))
            .thenCompose(v->questRead(a1,"entry_marker",QuestStore.Phase.COMPLETED,2))
            .thenCompose(v->questRead(a2,"entry_marker",QuestStore.Phase.ACTIVE,0))
            .thenCompose(v->questRead(context,"entry_marker",QuestStore.Phase.COMPLETED,1))
            .thenCompose(v->quests.list(a1,s1,()->true)).thenAccept(rows->{quests.requireListDisplay(a1,rows);check(PaperQuestAdapter.listMessages(catalog,rows).size()==3 && PaperQuestAdapter.infoMessages(catalog,rows,"entry_marker").size()==4,"entry_list_info_three_accepted_states");});
    }
    private void npcChunks(boolean hold){
        for(UUID run:List.of(a,b)){
            World w=Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId()));
            if(hold){w.addPluginChunkTicket(0,0,plugin);questAdapter.ensure(w);}else w.removePluginChunkTicket(0,0,plugin);
        }
    }
    private UUID npcEntity(UUID run){
        World w=Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId()));
        log("NPC_FIXTURE run="+run+" chunk_loaded="+w.isChunkLoaded(0,0)+" count="+questAdapter.count(w,"survey_guide"));
        check(catalog.npcs().size()==1 && questAdapter.count(w,"survey_guide")==1,"npc_one_entity_for_two_quests");
        return w.getEntitiesByClass(org.bukkit.entity.Villager.class).iterator().next().getUniqueId();
    }
    private CompletionStage<Void> npcChoices(){
        npcChunks(true);
        return later(()->{}).thenCompose(v->npcChoicesLoaded()).whenCompleteAsync((v,error)->npcChunks(false),owner);
    }
    private CompletionStage<Void> npcChoicesLoaded(){
        npcCheck();UUID world=worlds.get(a).mapping().physicalId(),entity=npcEntity(a);var d=new QuestDialogue(catalog);
        var tickets=d.options(a1.playerId(),s1,a,world,entity,"survey_guide");
        var messages=PaperQuestAdapter.selectionMessages(catalog,d,tickets);
        check(tickets.size()==catalog.forNpc("survey_guide").size() && messages.size()==tickets.size(),"npc_two_quest_chat_choices");
        for(int i=0;i<tickets.size();i++)check(messages.get(i).clickEvent().equals(net.kyori.adventure.text.event.ClickEvent.runCommand(d.command(tickets.get(i),"select"))),"npc_choice_command_"+tickets.get(i).quest());
        var first=tickets.getFirst();
        try {d.authorize(a1.playerId(),s1,a,world,entity,new QuestDialogue.Request("select",first.npc(),tickets.getLast().quest(),first.nonce()));throw new AssertionError("Forged sibling accepted");}
        catch(IllegalArgumentException expected){check(true,"npc_sibling_quest_forgery_rejected");}
        var selected=d.authorize(a1.playerId(),s1,a,world,entity,QuestDialogue.Request.parse(d.command(first,"select")));
        var action=d.action(selected,"report");
        try {d.authorize(a1.playerId(),s1,a,world,entity,new QuestDialogue.Request("accept",action.npc(),action.quest(),action.nonce()));throw new AssertionError("Forged action accepted");}
        catch(IllegalArgumentException expected){check(true,"npc_action_nonce_is_bound");}
        var request=QuestDialogue.Request.parse(d.command(action,"report"));d.authorize(a1.playerId(),s1,a,world,entity,request);
        try {d.authorize(a1.playerId(),s1,a,world,entity,request);throw new AssertionError("Duplicate action accepted");}
        catch(IllegalArgumentException expected){check(d.current(tickets.getLast()),"npc_duplicate_rejected_sibling_still_current");}
        CompletionStage<Void> work=CompletableFuture.completedFuture(null);
        for(var context:List.of(a1,a2,ctx(b,1))){
            Object token=context.equals(a1)?s1:context.equals(a2)?s2:new Object();
            work=work.thenCompose(v->listSnapshot(context).thenCompose(before->{
                var dialogue=new QuestDialogue(catalog);UUID physical=worlds.get(context.storyRunId()).mapping().physicalId(),id=npcEntity(context.storyRunId());
                var options=dialogue.options(context.playerId(),token,context.storyRunId(),physical,id,"survey_guide");
                CompletionStage<Void> views=CompletableFuture.completedFuture(null);
                for(var t:options)views=views.thenCompose(x->{
                    dialogue.authorize(context.playerId(),token,context.storyRunId(),physical,id,QuestDialogue.Request.parse(dialogue.command(t,"select")));
                    return quests.open(context,t.quest(),token,()->true).thenAccept(q->{
                        var saved=quests.requireDialogue(q);var c=catalog.quest(t.quest());var lines=PaperQuestAdapter.dialogueMessages(c,saved,dialogue,t);
                        String key=switch(saved.phase()){case NOT_ACCEPTED->"offer";case ACTIVE->"active";case REPORT_READY->"report-ready";case COMPLETED->"completed";case FAILED,SKIPPED->null;};
                        check(((net.kyori.adventure.text.TextComponent)lines.getFirst()).content().equals(key==null?c.guidance(saved):c.text().get(key)),"npc_personal_dialogue_"+context.storyRunId()+"_"+context.playerId()+"_"+t.quest()+"_"+saved.phase());
                    });
                });
                return views.thenCompose(x->listSnapshot(context)).thenAccept(after->check(before.equals(after),"npc_selection_rows_revisions_unchanged"));
            }));
        }
        return work;
    }
    private CompletionStage<Void> npcPending(){
        npcChunks(true);
        UUID world=worlds.get(a).mapping().physicalId(),entity=npcEntity(a);Object token=new Object();npcDialogue=new QuestDialogue(catalog);
        npcPendingTicket=npcDialogue.options(a1.playerId(),token,a,world,entity,"survey_guide").getFirst();
        npcDialogue.authorize(a1.playerId(),token,a,world,entity,QuestDialogue.Request.parse(npcDialogue.command(npcPendingTicket,"select")));
        npcProbe=new PersonalQuest(store,catalog.questIds(),owner,SessionVerification::main);
        npcPendingRead=npcProbe.open(a1,npcPendingTicket.quest(),token,()->true);
        return later(()->{
            check(!npcPendingRead.toCompletableFuture().isDone(),"npc_selection_read_waits_async");
            npcDialogue.options(a1.playerId(),new Object(),a,world,entity,"survey_guide");
            check(!npcDialogue.current(npcPendingTicket),"npc_old_selection_invalid_after_session_conversation_change");
        });
    }
    private CompletionStage<Void> npcPendingDone(){return mainResult(npcPendingRead).thenAccept(q->{
        try {PaperQuestAdapter.dialogueMessages(catalog.quest(npcPendingTicket.quest()),npcProbe.requireDialogue(q),npcDialogue,npcPendingTicket);throw new AssertionError("Stale dialogue displayed");}
        catch(IllegalArgumentException expected){check(true,"npc_late_response_no_display");}
    }).thenCompose(v->npcProbe.shutdown()).whenCompleteAsync((v,error)->npcChunks(false),owner);}
    private CompletionStage<Void> npcCycle(){
        UUID physical=worlds.get(a).mapping().physicalId();
        return mainResult(coordinator.disconnect(s1)).thenCompose(v->mainResult(coordinator.disconnect(s2)))
            .thenCompose(v->until(()->worlds.get(a).state()==WorldLifecycle.State.OFFLINE))
            .thenRun(()->check(Bukkit.getWorld(physical)==null && !questAdapter.tracks(physical),"npc_unload_clears_world_entity_references"))
            .thenCompose(v->{one=new Controlled();two=new Controlled();s1=connect(a1,one);s2=connect(a2,two);return mainResult(s1.ready()).thenCompose(x->mainResult(s2.ready()));})
            .thenRun(this::npcCheck)
            .thenCompose(v->questRead(a1,"survey_marker",QuestStore.Phase.COMPLETED,2))
            .thenCompose(v->questRead(a1,"path_marker",QuestStore.Phase.COMPLETED,1))
            .thenCompose(v->questRead(a2,"path_marker",QuestStore.Phase.COMPLETED,1));
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
        check(catalog.questIds().containsAll(Set.of("survey_marker","path_marker")),"multi_fixture_two_explicit_ids");
        var d=new QuestDialogue(catalog);Object session=new Object();UUID world=worlds.get(a).mapping().physicalId(),entity=UUID.randomUUID();
        var selected=d.options(a1.playerId(),session,a,world,entity,"survey_guide").stream().filter(t->t.quest().equals("survey_marker")).findFirst().orElseThrow();
        d.authorize(a1.playerId(),session,a,world,entity,QuestDialogue.Request.parse(d.command(selected,"select")));
        var ticket=d.action(selected,"accept");
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
    public CompletionStage<Void> barrier(UUID run){return coordinator.barrier(run).thenComposeAsync(v->combatPlayers==null?CompletableFuture.completedFuture(null):combatPlayers.barrier(run),owner).thenComposeAsync(v->quests==null ? CompletableFuture.completedFuture(null):quests.barrier(run).thenCompose(v2->journal.barrier(run)).thenCompose(v2->sharedStory==null?CompletableFuture.completedFuture(null):sharedStory.barrier(run)),owner);}
    public boolean canUnload(UUID run){return coordinator.canUnload(run) && (quests==null || quests.settled(run) && journal.settled(run) && (sharedStory==null||sharedStory.settled(run)));}
    public CompletionStage<Void> restoreStoryWorld(UUID run){if(blockEffect==null)return CompletableFuture.completedFuture(null);blockEffect.prepared(run);var context=ctx(run,1);return sharedStory.load(context).thenComposeAsync(s->sharedStory.reconcile(context),owner).thenAccept(ok->{});}
    public void releaseQuests(UUID run){if(quests!=null)quests.release(run);if(blockEffect!=null){blockEffect.unloaded(run);sharedStory.worldUnloaded(run);}}
    public CompletionStage<Void> shutdown(){
        if(manual && !Bukkit.getOnlinePlayers().isEmpty())return CompletableFuture.failedFuture(new LifecycleFailure("MANUAL_PLAYER_STILL_ONLINE"));
        if(combatPlayers!=null)combatPlayers.close();if(combatTrials!=null)combatTrials.close();if(fieldEncounters!=null)fieldEncounters.close();if(paperStory!=null)paperStory.close();if(questAdapter!=null)questAdapter.stopUi();autosave.close();adapter.close();
        CompletionStage<Void> other=s3==null ? CompletableFuture.completedFuture(null) : coordinator.disconnect(s3);
        return mainResult(other).thenCompose(ignored -> coordinator.shutdown().handleAsync((v,error)->{
            if(error!=null && !(expectedHold && "23514".equals(s1.error()) && "MOVE_FAILED".equals(s2.error())
                    && generationProbes.size()==2 && generationProbes.values().stream().allMatch(s->s.state()==PlayerSessions.State.HOLD && "STALE_WORLD".equals(s.error()))
                    && Set.of("23514","MOVE_FAILED","STALE_WORLD").contains(LifecycleFailure.code(error))))throw new CompletionException(error);
            log("DRAIN "+(error==null ? "PASS" : "EXPECTED_HOLD_PRESERVED"));return null;
        },owner)).thenCompose(v->journal==null?CompletableFuture.completedFuture(null):journal.shutdown()).thenCompose(v->quests==null?CompletableFuture.completedFuture(null):quests.shutdown()).thenCompose(v->sharedStory==null?CompletableFuture.completedFuture(null):sharedStory.shutdown());
    }
    private CompletionStage<Void> linkExisting(ServerProgressAccess.Context context) {
        return mainResult(store.readPersonal(context)).thenCompose(v -> mainResult(store.readInventory(context))).thenCompose(saved->(sharedStory==null?CompletableFuture.completedFuture(null):sharedStory.load(context)).thenApply(v->saved)).thenAccept(saved -> {
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
    @Override public void close(){if(combatPlayers!=null)combatPlayers.close();if(combatTrials!=null)combatTrials.close();if(fieldEncounters!=null)fieldEncounters.close();if(questAdapter!=null)questAdapter.close();autosave.close();adapter.close();}
}
