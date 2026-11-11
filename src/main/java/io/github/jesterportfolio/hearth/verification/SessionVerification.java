package io.github.jesterportfolio.hearth.verification;

import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.features.*;
import io.github.jesterportfolio.hearth.crafting.*;
import io.github.jesterportfolio.hearth.gathering.*;
import io.github.jesterportfolio.hearth.lifecycle.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.session.*;
import io.github.jesterportfolio.hearth.quest.*;
import io.github.jesterportfolio.hearth.exploration.*;
import io.github.jesterportfolio.hearth.story.*;
import io.github.jesterportfolio.hearth.combat.*;
import org.bukkit.configuration.file.YamlConfiguration;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import io.github.jesterportfolio.hearth.escort.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

/** 기존 lifecycle/DB fixture를 재사용하는 제어 포트 시험. Player를 만들거나 인증을 흉내 내지 않습니다. */
public final class SessionVerification implements AutoCloseable {
    private io.github.jesterportfolio.hearth.administration.RecoveryConsole recoveryConsole;
    private io.github.jesterportfolio.hearth.administration.InspectionStore inspectionStore;
    private CompletionStage<Void> manualStatus=CompletableFuture.completedFuture(null);
    private final Map<String,String> manualQuestStatus=new HashMap<>();
    private ManualS4Support manualS4;
    private FeatureStore featureStore;private PersonalStorage personalStorage;private S4Features features;
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
    private io.github.jesterportfolio.hearth.escort.PaperEscort escort;
    private PaperCrafting crafting;private CraftingQuest craftingQuests;private PreparationGuide preparation;private CourseRoute courseRoute;private boolean publicCourse;private GatheringFacilities gatheringFacilities;private Set<String> gatheringObjectiveQuests=Set.of();
    private boolean craftingDelay,craftingCaptured;
    private CompletableFuture<Void> craftingGate;
    private InventoryStore craftingStore(){return new InventoryStore(){
        public CompletionStage<Stored> createInventory(ServerProgressAccess.Context c,InventoryPayload p){return store.createInventory(c,p);}
        public CompletionStage<Stored> readInventory(ServerProgressAccess.Context c){return store.readInventory(c);}
        public CompletionStage<Stored> saveInventory(ServerProgressAccess.Context c,long r,InventoryPayload p){var result=store.saveInventory(c,r,p);if(craftingDelay&&c==a1){craftingDelay=false;return result.thenComposeAsync(saved->{craftingCaptured=true;return craftingGate.thenApply(v->saved);},owner);}return result;}
    };}
    private EconomyVerification economy;
    private PersonalQuest quests;
    private QuestJournal journal;
    private ExplorationContent explorationContent;
    private io.github.jesterportfolio.hearth.travel.PaperTravel paperTravel;
    private final Map<UUID,CommunityBoundary.Condition> explorationConditions=new HashMap<>();
    private SharedStory sharedStory;private PaperStoryAdapter paperStory;private PaperOneBlockEffect blockEffect;private OneBlockEffect.Definition effectDefinition;
    private Map<UUID,Object> storyTestRoster;private final java.util.concurrent.atomic.AtomicLong storyClock=new java.util.concurrent.atomic.AtomicLong();
    private final Map<UUID,ServerProgressAccess.Context> storyActors=new HashMap<>();
    private SharedStory.VoteView storyVote;private CompletionStage<StoryStore.Snapshot> storySaving;private CompletionStage<Void> storyBarrier;
    private int storyNoticeCount;private SharedStory storyFailureProbe;private StoryGuide.Action oldGuideAction;
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
    private PaperDungeonPlay dungeonPlay;private DungeonPlay.Definition dungeonDefinition;
    private PaperGathering gathering;
    private CompletableFuture<Boolean> gatheringEntryGate;private boolean gatheringEntryBlocked;private CompletionStage<Void> gatheringPendingEntry;
    private final java.util.concurrent.atomic.AtomicLong gatheringClock=new java.util.concurrent.atomic.AtomicLong(1000);
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
        InventoryStore inventoryBackend=plugin.getConfig().getBoolean("crafting-verification-enabled")?craftingStore():store;
        if(plugin.getConfig().getBoolean("s4-features-enabled")){if(!Boolean.getBoolean("hearth.verification.s4Features"))throw new IllegalStateException("S4_FEATURE_GATE");featureStore=new FeatureStore(store,access);personalStorage=new PersonalStorage(inventoryBackend,featureStore,owner,SessionVerification::main,codec::equivalent);inventoryBackend=personalStorage;}
        coordinator=new PlayerSessions(store,inventoryBackend,owner,SessionVerification::main,p -> codec.decode(p),codec::equivalent,autosave::add,autosave::remove,c -> log("ERROR "+c));
        World waiting=Bukkit.getWorlds().getFirst();
        for (int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)waiting.getBlockAt(x,63,z).setType(Material.STONE,false);
        adapter=new PaperSessionAdapter(plugin,coordinator,owner,codec,new Location(waiting,0.5,64,0.5));
        if(plugin.getConfig().getBoolean("combat-state-verification-enabled")){
            var filename=plugin.getConfig().getString("combat-content-file","combat-demo.yml");if(!Set.of("combat-demo.yml","combat-path-demo.yml","combat-route-demo.yml","dungeon-play-combat.yml","combat-pattern-demo.yml","combat-area-demo.yml","combat-s4-demo.yml").contains(filename))throw new IllegalArgumentException("Unsupported combat fixture file");var file=new java.io.File(plugin.getDataFolder(),filename);if(!file.exists())plugin.saveResource(filename,false);
            var yaml=YamlConfiguration.loadConfiguration(file);var definition=combatMap(yaml);
            combatContent=CombatContent.parse(definition);combatTrials=new PaperCombatTrials(plugin,combatContent,owner,plugin.getConfig().getBoolean("combat-player-verification-enabled")?()->System.nanoTime()/1_000_000:combatClock::get);
        }
        if(plugin.getConfig().getBoolean("field-encounter-verification-enabled")){
            var file=new java.io.File(plugin.getDataFolder(),"field-demo.yml");if(!file.exists())plugin.saveResource("field-demo.yml",false);
            var yaml=YamlConfiguration.loadConfiguration(file);var definition=new HashMap<String,Object>(yaml.getValues(false));if(yaml.isConfigurationSection("reset"))definition.put("reset",Objects.requireNonNull(yaml.getConfigurationSection("reset")).getValues(false));
            fieldEncounters=new PaperFieldEncounters(plugin,adapter,worlds,FieldContent.parse(definition));
        }
        if(catalog!=null) {
            content=catalog.all().stream().filter(c->c.id().equals("survey_marker")).findFirst().orElse(catalog.all().iterator().next());
            quests=new PersonalQuest(store,catalog.questIds(),owner,SessionVerification::main);quests.requireRules(catalog.requiredRules());store.requireQuestRules(catalog.requiredRules());
            journal=new QuestJournal(store,quests,catalog,owner,SessionVerification::main);
            if(plugin.getConfig().getBoolean("exploration-verification-enabled")){
                if(!Boolean.getBoolean("hearth.verification.exploration"))throw new IllegalStateException("Exploration gate missing");
                var file=new java.io.File(plugin.getDataFolder(),"exploration-demo.yml");if(!file.exists())plugin.saveResource("exploration-demo.yml",false);
                var explorationRoot=new HashMap<String,Object>(YamlConfiguration.loadConfiguration(file).getValues(false));
                if(plugin.getConfig().getBoolean("fast-travel-verification-enabled")){var tf=new java.io.File(plugin.getDataFolder(),"fast-travel-demo.yml");if(!tf.exists())plugin.saveResource("fast-travel-demo.yml",false);var extra=YamlConfiguration.loadConfiguration(tf).getList("extra-places");var places=new ArrayList<Object>((List<?>)explorationRoot.get("places"));if(extra!=null)places.addAll(extra);explorationRoot.put("places",places);}
                if(plugin.getConfig().getBoolean("crafting-quest-verification-enabled")){var extra=YamlConfiguration.loadConfiguration(new java.io.File(plugin.getDataFolder(),"crafting-quest-demo.yml"));for(String key:List.of("places","journal")){var all=new ArrayList<Object>((List<?>)explorationRoot.get(key));all.addAll(Objects.requireNonNull(extra.getList(key)));explorationRoot.put(key,all);}}
                if(plugin.getConfig().getBoolean("gathering-workshop-verification-enabled")){var extra=YamlConfiguration.loadConfiguration(new java.io.File(plugin.getDataFolder(),"gathering-workshop-course.yml"));for(String key:List.of("places","journal")){var all=new ArrayList<Object>((List<?>)explorationRoot.get(key));all.addAll(Objects.requireNonNull(extra.getList(key)));explorationRoot.put(key,all);}}
                explorationContent=ExplorationContent.parse(explorationRoot,catalog);
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
                var rulesRoot=new HashMap<String,Object>(YamlConfiguration.loadConfiguration(rulesFile).getValues(false));if(plugin.getConfig().getBoolean("escort-verification-enabled")){var extra=new java.io.File(plugin.getDataFolder(),"escort-demo.yml");if(!extra.exists())plugin.saveResource("escort-demo.yml",false);var extraRules=Objects.requireNonNull(YamlConfiguration.loadConfiguration(extra).getConfigurationSection("quest-rules"));var list=new ArrayList<Object>((List<?>)rulesRoot.get("quests"));list.addAll(Objects.requireNonNull(extraRules.getList("quests")));rulesRoot.put("quests",list);}questRules=QuestRules.parse(rulesRoot,catalog,sharedStory.content());quests.rules(questRules);journal.completion(store,questRules);quests.beforeChapter(run->{journal.invalidateRun(run);return journal.barrier(run);});store.configureQuestRules(questRules);questRewards=new QuestRewards(questRules,store,codec,owner);questRewards.changed(journal::invalidateCompletion);
            }
            questAdapter=new PaperQuestAdapter(plugin,adapter,quests,catalog,journal,owner,
                Set.of(worlds.get(a).mapping().physicalId(),worlds.get(b).mapping().physicalId()));
            if(questRewards!=null)questAdapter.completion(questRules,questRewards,store);
            if(sharedStory!=null){paperStory=new PaperStoryAdapter(plugin,adapter,sharedStory,owner);questAdapter.story(paperStory);}
            if(plugin.getConfig().getBoolean("playthrough-verification-enabled")){
                if(sharedStory==null||!Boolean.getBoolean("hearth.verification.playthrough"))throw new IllegalStateException("Playthrough gate missing");
                var filename=plugin.getConfig().getString("playthrough-content-file","playthrough-demo.yml");if(!Set.of("playthrough-demo.yml","public-course-demo.yml").contains(filename))throw new IllegalArgumentException("PLAYTHROUGH_CONTENT_FILE");publicCourse=filename.equals("public-course-demo.yml");if(publicCourse&&List.of("gathering-workshop-verification-enabled","gathering-facility-verification-enabled","dungeon-play-verification-enabled","crafting-quest-verification-enabled","play-preparation-verification-enabled").stream().anyMatch(key->!plugin.getConfig().getBoolean(key)))throw new IllegalStateException("PUBLIC_COURSE_DEPENDENCIES");var planFile=new java.io.File(plugin.getDataFolder(),filename);if(!planFile.exists())plugin.saveResource(filename,false);
                var plan=new HashMap<String,Object>(YamlConfiguration.loadConfiguration(planFile).getValues(false));if(!publicCourse&&plugin.getConfig().getBoolean("crafting-quest-verification-enabled")){var extra=YamlConfiguration.loadConfiguration(new java.io.File(plugin.getDataFolder(),"crafting-quest-demo.yml"));var steps=new ArrayList<Object>(Objects.requireNonNull(extra.getList("steps")));steps.addAll((List<?>)plan.get("steps"));plan.put("steps",steps);}if(!publicCourse&&plugin.getConfig().getBoolean("gathering-workshop-verification-enabled")){var extra=YamlConfiguration.loadConfiguration(new java.io.File(plugin.getDataFolder(),"gathering-workshop-course.yml"));var steps=new ArrayList<Object>(Objects.requireNonNull(extra.getList("steps")));steps.addAll((List<?>)plan.get("steps"));plan.put("steps",steps);}if(publicCourse&&plugin.getConfig().getBoolean("escort-verification-enabled")){var steps=new ArrayList<Object>((List<?>)plan.get("steps"));steps.add(4,Map.of("id","courier","kind","QUEST","reference","public_courier","title","개인 전달자 호위","hint","현재 개인 목표의 출발 지점에서 호위를 시작하고 함께 도착한 뒤 보고하세요."));plan.put("steps",steps);}playthrough=Playthrough.parse(plan,catalog,sharedStory.content(),explorationContent);questAdapter.playthrough(playthrough,sharedStory);
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
        if(plugin.getConfig().getBoolean("crafting-verification-enabled")){var cf=new java.io.File(plugin.getDataFolder(),"crafting-demo.yml");if(!cf.exists())plugin.saveResource("crafting-demo.yml",false);crafting=new PaperCrafting(plugin,adapter,worlds,craftContent(cf),codec,(ctx,f)->sharedStory==null||new StoryAccess(sharedStory).content(ctx.storyRunId(),f.x(),f.y(),f.z()),owner);if(plugin.getConfig().getBoolean("crafting-quest-verification-enabled")){if(quests==null||journal==null)throw new IllegalStateException("CRAFT_QUEST_DEPENDENCIES");craftingQuests=new CraftingQuest(quests,catalog,crafting.content,owner,SessionVerification::main);crafting.quests(craftingQuests,journal::questAllowed);crafting.journey(questAdapter::showJourney);crafting.orders((player,facility,current)->{var session=Objects.requireNonNull(adapter.current(player));return craftingOrders(session,facility,()->adapter.current(player)==session&&current.getAsBoolean());},questAdapter::showQuest);}}
        if(plugin.getConfig().getBoolean("fast-travel-verification-enabled")){if(journal==null||explorationContent==null)throw new IllegalStateException("Travel exploration required");var tf=new java.io.File(plugin.getDataFolder(),"fast-travel-demo.yml");paperTravel=new io.github.jesterportfolio.hearth.travel.PaperTravel(plugin,adapter,journal,worlds,io.github.jesterportfolio.hearth.travel.TravelContent.parse(YamlConfiguration.loadConfiguration(tf).getValues(false),explorationContent),owner);}
        if(plugin.getConfig().getBoolean("gathering-verification-enabled")){
            var filename=plugin.getConfig().getString("gathering-content-file","gathering-demo.yml");if(!Set.of("gathering-demo.yml","gathering-workshop-demo.yml").contains(filename))throw new IllegalArgumentException("GATHERING_CONTENT_FILE");var file=new java.io.File(plugin.getDataFolder(),filename);if(!file.exists())plugin.saveResource(filename,false);
            gathering=new PaperGathering(plugin,store,access,adapter,worlds,GatheringContent.parse(combatMap(YamlConfiguration.loadConfiguration(file))),owner,manual?System::currentTimeMillis:gatheringClock::get,run->adapter.saveRunBoundary(run).thenComposeAsync(v->quests==null?CompletableFuture.completedFuture(null):quests.barrier(run).thenComposeAsync(x->journal.barrier(run),owner).thenComposeAsync(x->sharedStory==null?CompletableFuture.completedFuture(null):sharedStory.barrier(run),owner),owner));
        }
        if(plugin.getConfig().getBoolean("gathering-workshop-verification-enabled")){var extra=YamlConfiguration.loadConfiguration(new java.io.File(plugin.getDataFolder(),"gathering-workshop-course.yml"));if(gathering==null||craftingQuests==null||!gathering.content.id().equals(extra.getString("gathering-id")))throw new IllegalStateException("GATHERING_COURSE_REFERENCE");gatheringObjectiveQuests=extra.getMapList("quests").stream().map(row->Objects.requireNonNull((String)row.get("id"))).collect(java.util.stream.Collectors.toUnmodifiableSet());}
        if(plugin.getConfig().getBoolean("combat-player-verification-enabled")){if(combatTrials==null)throw new IllegalStateException("Combat trial required");combatPlayers=new PaperCombatPlayers(plugin,adapter,combatTrials,fieldEncounters,store,store,worlds,owner,(p,session)->{log("COMBAT_PLAYER_DEATH");return questAdapter.combatDeath(p,session);},run->quests==null?CompletableFuture.completedFuture(null):quests.barrier(run).thenComposeAsync(v->journal.barrier(run),owner).thenComposeAsync(v->sharedStory==null?CompletableFuture.completedFuture(null):sharedStory.barrier(run),owner));}
        if(plugin.getConfig().getBoolean("dungeon-play-verification-enabled")){if(combatPlayers==null||quests==null)throw new IllegalStateException("DUNGEON_DEPENDENCIES");var f=new java.io.File(plugin.getDataFolder(),"dungeon-play.yml");if(!f.exists())plugin.saveResource("dungeon-play.yml",false);dungeonDefinition=DungeonPlay.Definition.parse(combatMap(YamlConfiguration.loadConfiguration(f)));dungeonPlay=new PaperDungeonPlay(plugin,dungeonDefinition,catalog,quests,journal,adapter,combatPlayers,combatContent,worlds,owner,run->coordinator.saveRunBoundary(run).thenComposeAsync(v->quests.barrier(run),owner).thenComposeAsync(v->journal.barrier(run),owner));questAdapter.dungeon(dungeonDefinition.npc(),dungeonDefinition.quest(),dungeonPlay::enter,dungeonPlay::guide);questAdapter.dungeonObjectives((player,view)->facilityDungeonObjectives(Objects.requireNonNull(adapter.current(player)),view,dungeonPlay.information(player)));}

        if(plugin.getConfig().getBoolean("gathering-facility-verification-enabled")){
            if(gathering==null||questAdapter==null||!Boolean.getBoolean("hearth.verification.gatheringFacilities"))throw new IllegalStateException("GATHERING_FACILITY_DEPENDENCIES");
            var file=new java.io.File(plugin.getDataFolder(),"gathering-facility-demo.yml");if(!file.exists())plugin.saveResource("gathering-facility-demo.yml",false);gatheringFacilities=GatheringFacilities.parse(combatMap(YamlConfiguration.loadConfiguration(file)),gathering.content);
            for(var site:gatheringFacilities.sites())if(crafting!=null&&crafting.content.facilities().values().stream().anyMatch(f->f.x()==site.x()&&f.y()==site.y()&&f.z()==site.z()))throw new IllegalArgumentException("GATHERING_CRAFT_FACILITY_CONFLICT");
            gathering.facilities(gatheringFacilities,(ctx,site)->sharedStory==null||new StoryAccess(sharedStory).content(ctx.storyRunId(),site.x(),site.y(),site.z()),questAdapter::showJourney,(player,current,back)->{var session=Objects.requireNonNull(adapter.current(player));questAdapter.showFacility(player,view->facilityGatheringObjectives(session,view,gathering.information(player)),()->adapter.current(player)==session&&current.getAsBoolean(),back);});
        }

        if(plugin.getConfig().getBoolean("play-preparation-verification-enabled")){
            if(!Boolean.getBoolean("hearth.verification.preparation")||crafting==null||gathering==null||dungeonPlay==null||journal==null)throw new IllegalStateException("PREPARATION_DEPENDENCIES");
            var file=new java.io.File(plugin.getDataFolder(),"play-preparation-demo.yml");if(!file.exists())plugin.saveResource("play-preparation-demo.yml",false);
            preparation=PreparationGuide.parse(combatMap(YamlConfiguration.loadConfiguration(file)),catalog,crafting.content,explorationContent,gathering.content,dungeonDefinition);
            crafting.preparationGuide(questAdapter::showPreparation);
            if(publicCourse){var root=combatMap(YamlConfiguration.loadConfiguration(new java.io.File(plugin.getDataFolder(),"public-course-demo.yml")));courseRoute=CourseRoute.parse(root,playthrough,catalog,preparation,explorationContent);questAdapter.courseRoute((player,view)->{var session=Objects.requireNonNull(adapter.current(player));var page=courseRouteView(session,view,codec.capture(player.getInventory()),dungeonDefinition.entry().contains(player.getX(),player.getY(),player.getZ()),gathering.information(player),dungeonPlay.information(player));if(adapter.current(player)!=session)throw new IllegalStateException("STALE_COURSE_ROUTE_SESSION");return page;});}
            questAdapter.preparation((player,view)->{var session=Objects.requireNonNull(adapter.current(player),"PREPARATION_SESSION_REQUIRED");var result=preparationView(session,view,codec.capture(player.getInventory()),dungeonDefinition.entry().contains(player.getX(),player.getY(),player.getZ()),gathering.information(player),dungeonPlay.information(player));if(adapter.current(player)!=session)throw new IllegalStateException("STALE_PREPARATION");return result;});
        }
        if(plugin.getConfig().getBoolean("escort-verification-enabled")){
            if(!Boolean.getBoolean("hearth.verification.escort")||questRules==null||questRewards==null||questAdapter==null)throw new IllegalStateException("ESCORT_DEPENDENCIES");var file=new java.io.File(plugin.getDataFolder(),"escort-demo.yml");if(!file.exists())plugin.saveResource("escort-demo.yml",false);var definitions=io.github.jesterportfolio.hearth.escort.EscortContent.parse(YamlConfiguration.loadConfiguration(file).getValues(false),catalog,questRules);escort=new io.github.jesterportfolio.hearth.escort.PaperEscort(plugin,adapter,journal,quests,catalog,this.worlds,definitions,owner);questAdapter.escort(definitions.quests().keySet(),escort::command,escort::show);
        }
        if(featureStore!=null){if(journal==null||explorationContent==null||questAdapter==null)throw new IllegalStateException("S4_FEATURE_DEPENDENCIES");features=new S4Features(plugin,adapter,coordinator,featureStore,personalStorage,codec,journal,quests,catalog,explorationContent,questAdapter,combatPlayers,access,owner);adapter.travelRestriction(p->{var session=adapter.prepared(p);return session!=null&&(fieldEncounters==null||fieldEncounters.entities(session.context().storyRunId()).stream().filter(e->e instanceof org.bukkit.entity.Mob).map(e->(org.bukkit.entity.Mob)e).noneMatch(e->e.getTarget()==p));});}
        if(features!=null){inspectionStore=new io.github.jesterportfolio.hearth.administration.InspectionStore(store,access);recoveryConsole=new io.github.jesterportfolio.hearth.administration.RecoveryConsole(inspectionStore::prepare,adapter::verifiedContexts,this::inspectionRuntime,features.sceneContent.scenes(),owner,SessionVerification::main,System::nanoTime);}
        if(plugin.getConfig().getBoolean("manual-s4-enabled")){if(!manual||features==null||features.anchoredHud==null||gatheringFacilities==null||paperTravel==null||escort==null||dungeonPlay==null)throw new IllegalStateException("MANUAL_S4_DEPENDENCIES");manualS4=new ManualS4Support(plugin,adapter,owner);for(var w:this.worlds.values())if(Bukkit.getWorld(w.mapping().physicalId())!=null){prepareCourse(w.mapping().storyRunId());prepareManualS4(w.mapping().storyRunId());}}

    }
    private CourseRoute.Page courseRouteView(PlayerSessions.Session session,QuestJournal.View view,InventoryPayload inventory,boolean atEntry,List<String> gatheringInfo,List<String> dungeonInfo){
        var prepared=preparationView(session,view,inventory,atEntry,gatheringInfo,dungeonInfo);
        return courseRoute.build(playthrough,catalog,view,sharedStory.cached(session.context().storyRunId()),sharedStory.phase(session.context().storyRunId()),prepared);
    }
    private FacilityObjectives.Board facilityGatheringObjectives(PlayerSessions.Session session,QuestJournal.View view,List<String> status){
        main();if(!coordinator.playable(session))throw new IllegalStateException("FACILITY_SESSION_REQUIRED");journal.requireView(session.context(),view);
        return FacilityObjectives.gathering(catalog,view,gathering.content,gatheringObjectiveQuests,status);
    }
    private FacilityObjectives.Board facilityDungeonObjectives(PlayerSessions.Session session,QuestJournal.View view,List<String> status){
        main();if(!coordinator.playable(session))throw new IllegalStateException("FACILITY_SESSION_REQUIRED");journal.requireView(session.context(),view);
        return FacilityObjectives.dungeon(catalog,view,dungeonDefinition,status);
    }
    private PreparationGuide.Page preparationView(PlayerSessions.Session session,QuestJournal.View view,InventoryPayload inventory,boolean atEntry,List<String> gatheringInfo,List<String> dungeonInfo){
        main();if(!coordinator.playable(session))throw new IllegalStateException("PREPARATION_SESSION_REQUIRED");journal.requireView(session.context(),view);
        var materials=new HashMap<String,List<CraftingMaterials.Need>>();var plans=new HashMap<String,CraftingPreparation.Plan>();
        for(var entry:preparation.entries())if(entry.kind()==PreparationGuide.Kind.CRAFTING&&view.exploration().place(entry.place()).isPresent()){
            for(String recipe:entry.recipes())materials.put(recipe,crafting.planner.materials(crafting.content.recipes().get(recipe),inventory));
            var selected=entry.recipes().stream().map(id->new CraftingBatch.Selection(id,1)).toList();
            plans.put(entry.id(),crafting.planner.preparation(Objects.requireNonNull(Bukkit.getWorld(worlds.get(session.context().storyRunId()).mapping().physicalId())),crafting.content,crafting.content.facilities().get(entry.reference()),selected,inventory));
        }
        return preparation.build(catalog,crafting.content,view,new PreparationGuide.Facts(materials,gatheringInfo,dungeonInfo,atEntry,session.save().dirty(),session.save().saving(),plans));
    }
    public void clientEnvironment(String mode){if(manualS4==null||!Set.of("ui","combat").contains(mode))throw new IllegalStateException("MANUAL_S4_DISABLED");if(mode.equals("ui")&&combatPlayers!=null&&adapter.verifiedContexts().stream().anyMatch(c->!combatPlayers.diagnostic(c).get("combat.phase").equals("UNTRACKED")))throw new IllegalStateException("CLIENT_ACTIVE_COMBAT");manualS4.environment(mode.equals("combat"));}
    public void clientPack(String profile){if(manualS4==null)throw new IllegalStateException("MANUAL_S4_DISABLED");manualS4.pack(profile);}
    public void gatheringFixture(String profile){if(gathering==null)throw new IllegalStateException("Gathering disabled");gathering.fixture(Objects.requireNonNull(Bukkit.getPlayerExact(profile)));}
    public CompletionStage<Void> recoveryAdministration(boolean console,String[] args,java.util.function.Consumer<String> output){if(recoveryConsole==null)return CompletableFuture.failedFuture(new IllegalStateException("RECOVERY_INSPECTION_UNAVAILABLE"));return recoveryConsole.command(console,args,output);}
    private io.github.jesterportfolio.hearth.administration.RecoveryInspection.Runtime inspectionRuntime(ServerProgressAccess.Context context){
        main();var facts=new TreeMap<String,String>();var holds=new TreeSet<String>();var pending=new TreeSet<String>();var world=worlds.get(context.storyRunId());UUID physical=null;
        if(world==null)facts.put("world.state","UNREGISTERED");else{physical=world.mapping().physicalId();facts.put("world.id",physical.toString());facts.put("world.state",world.state().name());facts.put("world.generation",Long.toString(world.generation()));facts.put("world.error",Objects.toString(world.failureCode(),"NONE"));if(world.state()==WorldLifecycle.State.ERROR_HOLD)holds.add("WORLD_HOLD");}
        var session=coordinator.diagnostic(context);facts.put("session.id",Objects.toString(session.connection(),"NONE"));facts.put("session.state",session.state());facts.put("session.connected",Boolean.toString(session.connected()));facts.put("inventory.runtime_revision",Long.toString(session.revision()));facts.put("inventory.dirty",Boolean.toString(session.dirty()));facts.put("inventory.saving",Boolean.toString(session.saving()));facts.put("session.error",Objects.toString(session.error(),"NONE"));facts.put("inventory.hold",Objects.toString(session.hold(),"NONE"));
        if(session.state().equals("HOLD")||session.hold()!=null)holds.add("SESSION_INVENTORY_HOLD");if(session.dirty())pending.add("inventory_dirty");if(session.saving())pending.add("inventory_saving");
        if(features!=null){var ender=personalStorage.diagnostic(context);facts.put("ender.loaded",Boolean.toString(ender.loaded()));facts.put("ender.runtime_revision",Long.toString(ender.revision()));if(ender.held())holds.add("ENDER_HOLD");if(ender.saving())pending.add("ender_saving");var scenes=features.scenes.progress.diagnostic(context);facts.put("scene.opened",Integer.toString(scenes.opened()));facts.put("scene.held",scenes.held().toString());facts.put("scene.pending",scenes.pending().toString());if(!scenes.held().isEmpty())holds.add("SCENE_HOLD");if(!scenes.pending().isEmpty())pending.add("scene_saving");}
        if(combatPlayers!=null){var combat=combatPlayers.diagnostic(context);facts.putAll(combat);if(Boolean.parseBoolean(combat.get("combat.cleanup_hold")))holds.add("COMBAT_HOLD");if(Boolean.parseBoolean(combat.get("combat.boundary_pending")))pending.add("combat_boundary");}
        if(fieldEncounters!=null){facts.put("field.restart_hold",Boolean.toString(fieldEncounters.restartHeld()));facts.put("field.suspended",Boolean.toString(fieldEncounters.engine().suspended(context.storyRunId())));facts.put("field.hold",Boolean.toString(fieldEncounters.engine().held(context.storyRunId())));if(fieldEncounters.engine().held(context.storyRunId()))holds.add("FIELD_HOLD");if(fieldEncounters.restartHeld())holds.add("FIELD_RESTART_HOLD");}
        return new io.github.jesterportfolio.hearth.administration.RecoveryInspection.Runtime(physical,facts,holds,pending);
    }
    public void combatPlayer(String operation,String profile,String condition){if(combatPlayers==null)throw new IllegalStateException("Combat player mode disabled");switch(operation){case "combat-player-start"->combatPlayers.startOnline(profile);case "combat-player-release"->combatPlayers.release(profile);case "combat-player-revive"->combatPlayers.condition(profile,condition).whenCompleteAsync((v,error)->log("COMBAT_REVIVE "+(error==null?"APPLIED":"HOLD_OR_REJECTED")),owner);default->throw new IllegalArgumentException("Unknown combat fixture operation");}}
    private List<CommunityBoundary.Ref> storyMembers(ServerProgressAccess.Context ctx){var refs=adapter.community(ctx);if(storyTestRoster==null)return refs;return refs.stream().map(ref->new CommunityBoundary.Ref(ref.run(),ref.player(),ref.name(),ctx.storyRunId().equals(a)?storyTestRoster.containsKey(ref.player()):ref.player().equals(b3.playerId()))).toList();}
    private CraftContent craftContent(java.io.File file){var content=CraftContent.parse(YamlConfiguration.loadConfiguration(file).getValues(false));if(plugin.getConfig().getBoolean("gathering-workshop-verification-enabled")){var extra=YamlConfiguration.loadConfiguration(new java.io.File(plugin.getDataFolder(),"gathering-workshop-course.yml"));content=content.extended(extra.getString("facility-id"),Objects.requireNonNull(extra.getList("recipes")));}return content;}
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
        var combined=new ArrayList<Object>(rows);
        for(var entry:Map.of("dungeon-play-verification-enabled","dungeon-quest-demo.yml","crafting-quest-verification-enabled","crafting-quest-demo.yml","gathering-workshop-verification-enabled","gathering-workshop-course.yml").entrySet())if(plugin.getConfig().getBoolean(entry.getKey())){var extra=new java.io.File(plugin.getDataFolder(),entry.getValue());if(!extra.exists())plugin.saveResource(entry.getValue(),false);combined.addAll(Objects.requireNonNull(YamlConfiguration.loadConfiguration(extra).getList("quests")));}
        var actors=new ArrayList<Object>(npcs);
        if(plugin.getConfig().getBoolean("playthrough-verification-enabled")&&"public-course-demo.yml".equals(plugin.getConfig().getString("playthrough-content-file"))){var extra=new java.io.File(plugin.getDataFolder(),"public-course-demo.yml");if(!extra.exists())plugin.saveResource("public-course-demo.yml",false);var additions=YamlConfiguration.loadConfiguration(extra);if(additions.isSet("npcs"))actors.addAll(Objects.requireNonNull(additions.getList("npcs"),"Invalid public course NPC definitions"));}
        if(plugin.getConfig().getBoolean("escort-verification-enabled")){var extra=new java.io.File(plugin.getDataFolder(),"escort-demo.yml");if(!extra.exists())plugin.saveResource("escort-demo.yml",false);combined.addAll(Objects.requireNonNull(YamlConfiguration.loadConfiguration(extra).getList("quests")));}
        return QuestCatalog.parse(actors,combined);
    }

    private ServerProgressAccess.Context ctx(UUID run,int player){return access.forVerifiedServerLink(run,new UUID(49,player));}
    private static Map<String,Object> combatMap(org.bukkit.configuration.ConfigurationSection section){var data=new HashMap<String,Object>();for(String key:section.getKeys(false)){Object value=section.get(key);data.put(key,value instanceof org.bukkit.configuration.ConfigurationSection child?combatMap(child):value);}return data;}
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
                for(int x=0;x<=40;x++)for(int z=0;z<=40;z++)if((gathering==null||!gathering.content.area().contains(x,63,z))&&world.getBlockAt(x,63,z).getType().isAir())world.getBlockAt(x,63,z).setType(Material.STONE,false);
                var lever=(org.bukkit.block.data.type.Switch)Material.LEVER.createBlockData();lever.setAttachedFace(org.bukkit.block.data.FaceAttachable.AttachedFace.FLOOR);target.setBlockData(lever,false);
                world.getWorldBorder().setCenter(20,20);world.getWorldBorder().setSize(40);
                java.nio.file.Files.writeString(file,"version=2\nrun="+run+"\nworld="+world.getUID()+"\n",java.nio.file.StandardOpenOption.CREATE_NEW);
            }
            preparedCourses.add(world.getUID());
        }catch(java.io.IOException failure){throw new IllegalStateException("Course preparation failed; preserve data",failure);}
    }
    private void prepareManualS4(UUID run){
        var blocks=new HashMap<com.sk89q.worldedit.math.BlockVector3,Material>();
        for(var f:crafting.content.facilities().values())blocks.put(com.sk89q.worldedit.math.BlockVector3.at(f.x(),f.y(),f.z()),Material.CRAFTING_TABLE);
        for(var f:gatheringFacilities.sites())blocks.put(com.sk89q.worldedit.math.BlockVector3.at(f.x(),f.y(),f.z()),Material.STONE_BUTTON);
        for(var f:paperTravel.content.stations().values()){var p=f.facility();blocks.put(com.sk89q.worldedit.math.BlockVector3.at((int)p.x(),(int)p.y(),(int)p.z()),Objects.requireNonNull(Material.matchMaterial(f.material())));}
        manualS4.prepare(worlds.get(run).mapping(),Map.copyOf(blocks));
    }
    private Location destination(UUID run){
        main();World w=Objects.requireNonNull(Bukkit.getWorld(NamespacedKey.fromString(worlds.get(run).mapping().worldKey())));
        // 새 격리 fixture의 도착 발판만 준비합니다. 일반 월드 건축/복귀 정책이 아닙니다.
        if(playthrough!=null){prepareCourse(run);if(manualS4!=null)prepareManualS4(run);}else for(int x=1;x<=(manual?15:3);x++)for(int z=1;z<=(manual?15:3);z++)w.getBlockAt(x,63,z).setType(Material.STONE,false);
        return new Location(w,2.5,64,manualS4==null?2.5:1.5);
    }
    private final class Controlled implements PlayerSessions.Port,TemporaryInventory.Port {
        final Inventory inventory=Bukkit.createInventory(null,45,Component.text("제어 세션 41슬롯"));
        final Inventory inputs=Bukkit.createInventory(null,9,Component.text("제어 임시 제작 입력"));int matrixSize;
        ItemStack cursor;boolean connected=true;int applies,moves;CompletableFuture<Boolean> movement=CompletableFuture.completedFuture(true);
        public boolean connected(){main();return connected;}
        public InventoryPayload capture(){main();return new TemporaryInventory(codec).capture(this);}
        public ItemStack[] slots(){return Arrays.copyOf(inventory.getContents(),41);}public ItemStack cursor(){return cursor;}public void cursor(ItemStack value){cursor=value;}public ItemStack[] matrix(){return Arrays.copyOf(inputs.getContents(),matrixSize);}public void clearMatrix(){inputs.clear();}
        public void apply(InventoryPayload value){main();ItemStack[] slots=codec.decode(value);for(int i=0;i<41;i++)inventory.setItem(i,slots[i]);applies++;}
        public void settle(){new TemporaryInventory(codec).settle(this);}
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
        case "economy-start", "economy-resume", "economy-desk", "economy-report", "economy-supply", "economy-flow-start", "economy-flow-restored", "economy-quote", "economy-merchants" -> {if(economy!=null)throw new IllegalStateException("Economy already initialized");adapter.close();autosave.close();try{economy=new EconomyVerification(plugin,store,owner,codec,(phase.equals("economy-resume")||phase.equals("economy-flow-restored")));}catch(Exception e){throw new CompletionException(e);}yield switch(phase){case "economy-supply"->economy.runSupply();case "economy-flow-start","economy-flow-restored"->economy.runFlows();case "economy-quote"->economy.runQuote();case "economy-desk"->economy.runDesk();case "economy-report"->economy.runReport();case "economy-merchants"->economy.runMerchants();default->economy.run();};}
        case "effect-prepare" -> effectPrepare();
        case "effect-db-failure" -> effectDbFailure();
        case "effect-db-recover" -> sharedStory.refresh(a1).thenCompose(v->sharedStory.advance(a1,s1,()->true)).thenCompose(v->sharedStory.advance(a1,s1,()->true)).thenCompose(v->sharedStory.openVote(a1,s1,()->true)).thenAccept(vote->storyVote=vote);
        case "effect-pending" -> effectPending();
        case "effect-applied" -> effectApplied();
        case "effect-cycle" -> effectCycle();
        case "effect-resume" -> openAll().thenRun(()->{storyTestRoster=new HashMap<>(Map.of(a1.playerId(),s1,a2.playerId(),s2));sharedStory.rosterChanged(a1);}).thenCompose(v->effectRestored()).thenRun(()->check(blockEffect.inspections()>0&&blockEffect.writes()==0,"effect_new_JVM_inspected_actual_saved_target_no_memory_shortcut"));
        case "effect-conflict" -> effectConflict();
        case "combat-player-state" -> combatPlayerState();
        case "boss-pattern-state" -> bossPatternState();
        case "boss-area-state" -> bossAreaState();
        case "combat-departure-state" -> combatDepartureState();
        case "checkpoint-restored" -> openAll().thenCompose(v->mainResult(store.checkpoint(a1,"public_boss_control","survey_marker","public_field_one"))).thenCompose(row->{check(row.isPresent()&&row.get().complete(),"checkpoint_new_JVM_existing_activation_restored");return mainResult(store.checkpoint(a1,"public_boss_control","survey_marker","public_rest_two")).thenCompose(second->{check(second.isPresent()&&second.get().complete(),"checkpoint_new_JVM_second_activation_restored");return verify(a1,30,2);});});
        case "dungeon-play-state" -> dungeonPlayState();
        case "combat-route-state" -> combatRouteState();
        case "combat-path-state" -> combatPathState();
        case "combat-state" -> combatState();
        case "field-encounters" -> fieldEncounters();
        case "branch-progression-start" -> branchProgressionStart();
        case "branch-progression-restored" -> branchProgressionRestored();
        case "quest-completion-start" -> completionStart();
        case "completion-navigation" -> completionNavigation();
        case "escort-play" -> escortPlay();
        case "s4-features-restored" -> openAll().thenRun(()->check(fieldEncounters.restartHeld()&&fieldEncounters.count(a)==0&&fieldEncounters.count(b)==0,"s4_new_JVM_field_original_marker_HOLD_no_respawn")).thenCompose(v->featureVerification().restored(a1,a2)).thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(row->check(codec.equivalent(row.payload(),one.capture())&&craftingCount(row.payload(),Material.WOODEN_PICKAXE)==1&&craftingCount(row.payload(),Material.STONE_PICKAXE)==1,"s4_new_JVM_public_course_tools_preserved")).thenRun(()->offlineEnvironment(true));
        case "public-course-start" -> publicCourseStart();
        case "public-course-supply" -> gatheringCraftingPlay(true);
        case "public-course-vote" -> publicCourseVote();
        case "public-course-dungeon" -> dungeonPlayState(true);
        case "public-course-finish" -> publicCourseFinish();
        case "quest-completion-restored" -> completionRestored();
        case "general-npcs-start" -> generalNpcsStart();
        case "general-npcs-restored" -> generalNpcsRestored();
        case "npc-topics-start" -> npcTopicsStart();
        case "npc-topics-restored" -> npcTopicsRestored();
        case "interaction-quest-start" -> interactionQuestStart();
        case "interaction-quest-restored" -> interactionQuestRestore();
        case "npc-pages" -> npcPages();
        case "npc-dialogue-book" -> npcTopicsStart().thenCompose(v->npcTopicsIsolation()).thenCompose(v->npcPagesPrepared()).thenCompose(v->plugin.getConfig().getBoolean("phone-dialog-enabled",false)?readerIntegration():CompletableFuture.completedFuture(null));
        case "location-play" -> locationPlay();
        case "gathering-start" -> gatheringPlay(false);
        case "gathering-restored" -> gatheringPlay(true);
        case "gathering-facility-play" -> gatheringFacilityPlay();
        case "preparation-play" -> preparationPlay();
        case "gathering-crafting-play" -> gatheringCraftingPlay();
        case "crafting-quest-play" -> craftingQuestPlay();
        case "crafting-play" -> craftingPlay();
        case "travel-play" -> travelPlay();
        case "playthrough-run" -> playthroughRun();
        case "playthrough-restored" -> playthroughRestored();
        case "navigation-play" -> navigationPlay();
        case "game-information" -> gameInformation();
        case "npc-views" -> npcViews();
        case "inventory-workspace-play" -> inventoryWorkspacePlay();
        case "inventory-workspace-restored" -> inventoryWorkspaceRestored();
        case "interaction-play" -> interactionPlay();
        case "interaction-write-failure" -> interactionWriteFailure();
        case "gameplay-guide" -> gameplayGuide();
        case "guide-safety" -> openAll().thenCompose(v->storyFailure());
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
        case "recovery-inspection" -> recoveryInspection();
        case "session-basic" -> basic();case "session-race" -> race();case "session-race-done" -> raceDone();
        case "session-prepare-quit" -> preparingQuit();case "session-prepare-done" -> preparingDone();case "session-move-quit" -> movingQuit();
        case "session-restored" -> restored();case "session-move-failure" -> moveFailure();case "session-write-failure" -> writeFailure();
        default -> throw new IllegalArgumentException("Unknown session phase");};}
    /** 실제 블록/구역/DB, 이동만 제어 포트입니다. 실제 Player 접속은 만들지 않습니다. */
    private final class DungeonActor implements DungeonPlay.Actor {
        final PlayerSessions.Session session;final WorldLifecycle world;final long generation;boolean frozen;
        DungeonActor(PlayerSessions.Session s){session=s;world=worlds.get(s.context().storyRunId());generation=world.generation();}
        public ServerProgressAccess.Context context(){return session.context();}public Object token(){return session;}public UUID world(){return world.mapping().physicalId();}public long generation(){return generation;}public boolean current(){return coordinator.playable(session)&&world.generation()==generation;}public boolean atEntry(){return current();}public void freeze(boolean value){frozen=value;}
    }
    private CompletionStage<Void> dungeonPlayState(){return dungeonPlayState(false);}
    private CompletionStage<Void> dungeonPlayState(boolean joined){
        var expectedSource=new InventoryPayload[1];var expectedRevision=new long[1];var attempts=new CombatAttempt[2];var bridges=new CombatPlayers[2];var actors=new CombatBody[3];var entryActors=new DungeonActor[4];var controller=new DungeonPlay[1];var route=new CombatRoute[1];var visited=new HashSet<String>();var world=worlds.get(a);var def=dungeonDefinition;var quest=catalog.quest(def.quest());var fourthContext=ctx(a,4);var fourth=new Controlled();var fourthSession=new PlayerSessions.Session[1];
        return (joined?CompletableFuture.<Void>completedFuture(null):openAll()).thenCompose(v->prepareCombatChunks()).thenCompose(v->prepareRetryFields())
            .thenCompose(v->mainResult(store.createPlayer(fourthContext,"dungeon-unaccepted"))).thenCompose(v->mainResult(store.createInventory(fourthContext,fixture.apply(3)))).thenCompose(v->{fourthSession[0]=connect(fourthContext,fourth);return mainResult(fourthSession[0].ready());})
            .thenRun(()->{entryActors[0]=new DungeonActor(s1);entryActors[1]=new DungeonActor(s2);entryActors[2]=new DungeonActor(s3);entryActors[3]=new DungeonActor(fourthSession[0]);actors[0]=new CombatBody(s1,new CombatContent.Pose(world.mapping().physicalId(),3.5,64,3.5,0,0));actors[1]=new CombatBody(s2,actors[0].position);var wb=worlds.get(b);actors[2]=new CombatBody(s3,new CombatContent.Pose(wb.mapping().physicalId(),3.5,64,3.5,0,0));
                controller[0]=new DungeonPlay(def,quest,quests,new DungeonPlay.Boundary(){public List<DungeonPlay.Actor> nearby(UUID run){return List.of(entryActors[0],entryActors[1],entryActors[3]);}public boolean available(UUID run){return attempts[0]==null;}public boolean allowed(DungeonPlay.Actor actor){return journal.questAllowed(actor.context(),def.quest());}public CompletionStage<Void> save(UUID run){return coordinator.saveRunBoundary(run).thenComposeAsync(x->quests.barrier(run),owner);}public CombatAttempt start(List<DungeonPlay.Actor> selected){check(selected.size()==2&&selected.stream().noneMatch(p->p.context().playerId().equals(fourthContext.playerId()))&&!s1.save().dirty(),"dungeon_exact_active_quest_roster_after_inventory_save");bridges[0]=new CombatPlayers(a,world.mapping().physicalId(),world.generation(),world::generation,owner,SessionVerification::main);attempts[0]=combatTrials.startPath(world,selected.stream().map(p->new CombatAttempt.Entrant(p.context(),p.token(),actors[p.context().playerId().equals(a1.playerId())?0:1].position)).toList(),bridges[0]);bridges[0].attach(attempts[0],List.of(actors[0],actors[1]));return attempts[0];}},owner,SessionVerification::main);
                check(QuestScreens.npcDungeon(List.of(),catalog,Map.of(def.quest(),new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1)),def.npc(),def.npc(),def.quest()).stream().noneMatch(button->button.action().equals("npc-dungeon")),"dungeon_unaccepted_NPC_entry_button_absent");})
            .thenCompose(v->facilityObjectivesCheck(true,null,controller[0].lines(entryActors[0])))
            .thenCompose(v->rejectQuest(controller[0].enter(entryActors[0],def.npc(),()->true))).thenRun(()->check(attempts[0]==null,"dungeon_no_quest_cannot_spawn_combat"))
            .thenCompose(v->quests.open(a1,def.quest(),s1,()->coordinator.playable(s1))).thenCompose(q->quests.accept(q,quest)).thenCompose(v->quests.open(a2,def.quest(),s2,()->coordinator.playable(s2))).thenCompose(q->quests.accept(q,quest))
            .thenCompose(v->quests.open(b3,def.quest(),s3,()->coordinator.playable(s3))).thenCompose(q->quests.accept(q,quest))
            .thenCompose(v->quests.open(a1,def.quest(),s1,()->coordinator.playable(s1))).thenCompose(q->rejectQuest(quests.reach(q,quest,3,64,3)).thenCompose(x->rejectQuest(quests.interact(q,quest,"npc:"+def.npc()))))
            .thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenAccept(view->{check(QuestScreens.npcDungeon(List.of(),catalog,view.states(),def.npc(),def.npc(),def.quest()).stream().anyMatch(button->button.action().equals("npc-dungeon")),"dungeon_accepted_NPC_entry_button_and_personal_quest_view");if(joined)check(one.inventory.addItem(new ItemStack(Material.STONE,1)).isEmpty(),"public_course_source_save_keeps_existing_slots");else one.count(31);coordinator.refresh(s1);expectedSource[0]=one.capture();expectedRevision[0]=s1.save().revision()+1;})
            .thenCompose(v->facilityObjectivesCheck(true,FacilityObjectives.Kind.DUNGEON,controller[0].lines(entryActors[0])))
            .thenCompose(v->publicCourse?publicRouteCheck("dungeon","dungeon_entry"):CompletableFuture.completedFuture(null))
            .thenCompose(v->controller[0].enter(entryActors[0],def.npc(),()->true)).thenRun(()->{check(!entryActors[0].frozen&&!entryActors[1].frozen&&attempts[0].snapshot().difficultyPlayers()==2,"dungeon_admission_releases_owned_locks_and_excludes_unaccepted_player");var wb=worlds.get(b);bridges[1]=new CombatPlayers(b,wb.mapping().physicalId(),wb.generation(),wb::generation,owner,SessionVerification::main);attempts[1]=combatTrials.start(wb,List.of(new CombatAttempt.Entrant(b3,s3,actors[2].position)),bridges[1]);bridges[1].attach(attempts[1],List.of(actors[2]));route[0]=new CombatRoute(attempts[0],combatContent.path().route(),false,owner,SessionVerification::main,()->entryActors[0].current()&&entryActors[1].current());})
            .thenCompose(v->joined?verifyPublicDungeonSource(expectedSource[0],expectedRevision[0]):verify(a1,31,1)).thenCompose(v->{CompletionStage<Void> flow=CompletableFuture.completedFuture(null);for(var section:combatContent.path().route().sections()){var cp=combatContent.path().checkpoints().stream().filter(p->p.id().equals(section.checkpoint())).findFirst().orElseThrow();flow=flow.thenCompose(x->routeCheckpoint(attempts[0],actors,cp,visited)).thenCompose(x->{for(var p:List.of(actors[0],actors[1]))p.position=new CombatContent.Pose(world.mapping().physicalId(),(section.gathering().minX()+section.gathering().maxX())/2,64,(section.gathering().minZ()+section.gathering().maxZ())/2,0,0);return route[0].advance(section.id(),UUID.randomUUID(),List.of(actors[0],actors[1]),()->coordinator.saveRunBoundary(a),()->visited.contains(section.checkpoint()));});}return flow;})
            .thenCompose(v->{check(route[0].atBoss(),"dungeon_both_sections_reach_terminal_gate");var source=attempts[0];return retryEnterBoss(attempts,bridges,actors).thenRun(()->controller[0].changed(source,attempts[0],false));})
            .thenRun(()->combatTrials.mob(a).setHealth(0)).thenCompose(v->until(()->attempts[0].snapshot().outcome()==CombatAttempt.Outcome.VICTORY))
            .thenCompose(v->{var result=controller[0].victory(attempts[0],List.of(entryActors[0],entryActors[1]));check(result==controller[0].victory(attempts[0],List.of(entryActors[0],entryActors[1])),"dungeon_actual_entity_death_and_duplicate_victory_coalesced");return result;})
            .thenCompose(v->mainResult(store.readQuest(a1,def.quest()))).thenAccept(row->check(row.phase()==QuestStore.Phase.REPORT_READY&&row.revision()==1,"dungeon_victory_real_PG_REPORT_READY_revision1"))
            .thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenAccept(view->{var item=io.github.jesterportfolio.hearth.phone.PhoneQuestLinks.quests(catalog,view).stream().filter(q->q.id().equals(def.quest())).findFirst().orElseThrow();check(item.reporting()&&QuestBookView.accepted(catalog,view.states(),view.exploration().blockedQuests(),def.quest()).orElseThrow().pages().stream().anyMatch(p->p.contains("보고")),"dungeon_phone_and_book_reflect_saved_reporting_state");var screen=new PaperQuestUI.Screen(a1.playerId(),s1,QuestScreens.screen(catalog,view,"detail",def.quest(),0),"detail",def.quest(),0);check(screen.getInventory().getSize()==54&&(joined?codec.equivalent(one.capture(),expectedSource[0]):one.inventory.getItem(0).getAmount()==31),"dungeon_native_GUI_uses_existing_inventory_protection_data");})
            .thenCompose(v->facilityObjectivesCheck(true,FacilityObjectives.Kind.REPORT,controller[0].lines(entryActors[0])))
            .thenCompose(v->publicCourse?publicRouteCheck("dungeon",null):CompletableFuture.completedFuture(null))
            .thenCompose(v->quests.open(a1,def.quest(),s1,()->coordinator.playable(s1))).thenCompose(q->quests.advance(q,QuestStore.Phase.COMPLETED))
            .thenCompose(v->facilityObjectivesCheck(true,null,controller[0].lines(entryActors[0])))
            .thenCompose(v->facilityObjectivesIsolation(true,s2,false)).thenCompose(v->facilityObjectivesIsolation(true,s3,false))
            .thenCompose(v->mainResult(store.readQuest(a2,def.quest()))).thenAccept(row->check(row.phase()==QuestStore.Phase.REPORT_READY&&row.revision()==1,"dungeon_P1_report_does_not_report_for_P2"))
            .thenCompose(v->mainResult(store.readQuest(fourthContext,def.quest()))).thenAccept(row->check(row.phase()==QuestStore.Phase.NOT_ACCEPTED,"dungeon_unaccepted_player_no_completion_row"))
            .thenCompose(v->dungeonCreditFailure(entryActors[2],attempts[1]))
            .thenRun(()->{route[0].close();for(var bridge:bridges)bridge.close();for(UUID run:List.of(a,b)){combatTrials.stop(run);var loaded=Bukkit.getWorld(worlds.get(run).mapping().physicalId());for(int[] c:new int[][]{{0,0},{1,1},{2,1}})loaded.removePluginChunkTicket(c[0],c[1],plugin);}})
            .thenCompose(v->mainResult(coordinator.disconnect(s1))).thenCompose(v->mainResult(coordinator.disconnect(s2))).thenCompose(v->mainResult(coordinator.disconnect(fourthSession[0]))).thenCompose(v->until(()->world.state()==WorldLifecycle.State.OFFLINE)).thenRun(()->{controller[0].unloaded(a);check(!controller[0].registered(attempts[0]),"dungeon_unloaded_releases_completed_actor_and_old_battle_references");}).thenCompose(v->{one=new Controlled();s1=connect(a1,one);return mainResult(s1.ready());})
            .thenCompose(v->quests.open(a1,def.quest(),s1,()->coordinator.playable(s1))).thenAccept(q->check(q.saved().phase()==QuestStore.Phase.COMPLETED&&q.saved().revision()==2,"dungeon_controlled_reconnect_restores_personal_report_completion"))
            .thenCompose(v->facilityObjectivesCheck(true,null,controller[0].lines(new DungeonActor(s1))))
            .thenCompose(v->joined?verifyPublicDungeonSource(expectedSource[0],expectedRevision[0]):verify(a1,31,1)).thenCompose(v->controller[0].barrier(a)).whenCompleteAsync((v,error)->{if(controller[0]!=null)controller[0].close();for(var bridge:bridges)if(bridge!=null)bridge.close();for(UUID run:List.of(a,b)){combatTrials.stop(run);var w=Bukkit.getWorld(worlds.get(run).mapping().physicalId());if(w!=null&&entryActors[run.equals(a)?0:2]!=null&&worlds.get(run).generation()==entryActors[run.equals(a)?0:2].generation())for(int[] c:new int[][]{{0,0},{1,1},{2,1}})w.removePluginChunkTicket(c[0],c[1],plugin);}},owner);
    }
    private CompletionStage<Void> dungeonCreditFailure(DungeonActor actor,CombatAttempt other){
        var def=dungeonDefinition;var quest=catalog.quest(def.quest());QuestStore failing=new QuestStore(){public CompletionStage<Saved> readQuest(ServerProgressAccess.Context c,String id){return store.readQuest(c,id);}public CompletionStage<Saved> advanceQuest(ServerProgressAccess.Context c,String id,Saved expected,Phase next){return store.advanceQuest(c,id,new Saved(expected.phase(),expected.revision()+1,expected.objectiveIndex()),next);}};var progress=new PersonalQuest(failing,Set.of(def.quest()),owner,SessionVerification::main);
        var probe=new DungeonPlay(def,quest,progress,new DungeonPlay.Boundary(){public List<DungeonPlay.Actor> nearby(UUID run){return List.of(actor);}public boolean available(UUID run){return true;}public boolean allowed(DungeonPlay.Actor a){return true;}public CompletionStage<Void> save(UUID run){return CompletableFuture.completedFuture(null);}public CombatAttempt start(List<DungeonPlay.Actor> selected){return dungeonControlledBattle(actor,combatContent.path().combat());}},owner,SessionVerification::main);
        return probe.enter(actor,def.npc(),()->true).thenCompose(path->{path.finish(CombatAttempt.Outcome.RETIRED);var boss=dungeonControlledBattle(actor,combatContent);probe.changed(path,boss,false);boss.victory();return rejectQuest(probe.victory(boss,List.of(actor)));}).thenCompose(v->mainResult(store.readQuest(actor.context(),def.quest()))).thenAccept(row->{check(row.phase()==QuestStore.Phase.ACTIVE&&row.revision()==0&&other.snapshot().phase()==CombatAttempt.Phase.ACTIVE,"dungeon_real_DB_credit_failure_preserves_B_progress_and_real_battle");probe.close();});
    }
    private CombatAttempt dungeonControlledBattle(DungeonActor actor,CombatContent content){return new CombatAttempt(actor.context().storyRunId(),actor.world(),actor.generation(),content,List.of(new CombatAttempt.Entrant(actor.context(),actor.token(),new CombatContent.Pose(actor.world(),3.5,64,3.5,0,0))),new CombatAttempt.Port(){public void freeze(boolean b){}public void camera(UUID p,UUID t){}public boolean safe(CombatContent.Pose p){return true;}public CompletionStage<Boolean> revive(UUID p,CombatContent.Pose target,double health,List<CombatContent.Effect> effects,BooleanSupplier valid){return CompletableFuture.completedFuture(valid.getAsBoolean());}},owner,SessionVerification::main,()->100);}

    private final class GatheringActor implements GatheringDungeon.Member {
        final PlayerSessions.Session session;GatheringActor(PlayerSessions.Session session){this.session=session;}
        public UUID player(){return session.context().playerId();}public Object session(){return session;}public boolean current(){return coordinator.playable(session);}
    }
    private PaperGatheringArea.People gatheringPeople(UUID run){return new PaperGatheringArea.People(){
        public void notice(String text){log("GATHERING_NOTICE "+text);}
        public CompletionStage<Void> save(List<GatheringDungeon.Member> members){return coordinator.saveRunBoundary(run).thenComposeAsync(v->quests.barrier(run),owner);}
        public CompletionStage<Void> evacuate(List<GatheringDungeon.Member> members,BooleanSupplier valid){check(valid.getAsBoolean(),"gathering_controlled_evacuation_generation_checked");return CompletableFuture.completedFuture(null);}
        public CompletionStage<Boolean> enter(GatheringDungeon.Member member,long generation,BooleanSupplier valid){if(gatheringEntryGate!=null&&run.equals(a)){gatheringEntryBlocked=true;return gatheringEntryGate;}return CompletableFuture.completedFuture(valid.getAsBoolean()&&member.current());}
    };}
    private Material gatheredResource(UUID run){return Bukkit.getWorld(worlds.get(run).mapping().physicalId()).getBlockAt(33,64,1).getType();}
    private void gatheringBlocks(UUID run,Material resource){var w=Bukkit.getWorld(worlds.get(run).mapping().physicalId());check(w.getBlockAt(32,63,0).getType()==Material.STONE&&gatheredResource(run)==resource,"gathering_actual_blocks_"+run+"_"+resource);var manager=com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer().get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(w));var region=manager.getRegion("hearth_gather_"+gathering.content.id());check(region!=null&&region.getPriority()==30&&region.getFlag(com.sk89q.worldguard.protection.flags.Flags.BUILD)==com.sk89q.worldguard.protection.flags.StateFlag.State.ALLOW,"gathering_native_WorldGuard_region_"+run);}
    private CompletionStage<Void> gatheringPlay(boolean restored){
        var bindings=new PaperGathering.Binding[2];var actor=new GatheringActor[3];var savedId=worlds.get(a).mapping().physicalId();
        return openAll().thenCompose(v->gathering.bind(a1,!restored,gatheringPeople(a))).thenAccept(bd->bindings[0]=bd)
            .thenCompose(v->gathering.bind(b3,!restored,gatheringPeople(b))).thenAccept(bd->bindings[1]=bd)
            .thenCompose(v->{actor[0]=new GatheringActor(s1);actor[1]=new GatheringActor(s2);actor[2]=new GatheringActor(s3);var d=bindings[0].dungeon();var other=bindings[1].dungeon();
                if(restored){gatheringBlocks(a,Material.IRON_ORE);gatheringBlocks(b,Material.IRON_ORE);check(d.snapshot().generation()==4&&d.snapshot().template().equals("ore_v1")&&!d.snapshot().emptyReset()&&other.snapshot().generation()==1,"gathering_new_JVM_ledger_and_old_template_restored_without_create");return verify(a1,30,1).thenCompose(x->d.enter(actor[0])).thenRun(()->check(d.snapshot().generation()==4&&d.snapshot().template().equals("ore_v1"),"gathering_new_template_not_applied_to_running_generation")).thenCompose(x->d.leave(actor[0])).thenCompose(x->d.enter(actor[0])).thenRun(()->{check(d.snapshot().generation()==5&&d.snapshot().template().equals("ore_v2"),"gathering_template_switch_only_at_reset_boundary");gatheringBlocks(a,Material.COPPER_ORE);gatheringBlocks(b,Material.IRON_ORE);}).thenCompose(x->d.leave(actor[0])).thenCompose(x->gatheringSqlFailure(other));}
                return d.enter(actor[0]).thenCompose(x->d.enter(actor[1])).thenCompose(x->other.enter(actor[2])).thenRun(()->{check(d.occupants()==2&&d.snapshot().generation()==1&&other.snapshot().generation()==1,"gathering_same_run_shared_other_run_separate");gatheringBlocks(a,Material.IRON_ORE);gatheringBlocks(b,Material.IRON_ORE);})
                    .thenCompose(x->d.leave(actor[0])).thenCompose(x->d.enter(actor[0])).thenRun(()->check(d.snapshot().generation()==1,"gathering_partial_exit_keeps_current_resources"))
                    .thenCompose(x->d.leave(actor[0])).thenCompose(x->{gatheringEntryGate=new CompletableFuture<>();gatheringPendingEntry=d.enter(actor[0]);return until(()->gatheringEntryBlocked);}).thenRun(()->{Bukkit.getWorld(savedId).getBlockAt(33,64,1).setType(Material.AIR,false);one.count(30);coordinator.refresh(s1);gatheringClock.set(d.snapshot().nextReset()-1000);})
                    .thenCompose(x->d.pulse()).thenRun(()->gatheringClock.set(d.snapshot().nextReset())).thenCompose(x->{var reset=d.pulse();check(!reset.toCompletableFuture().isDone()&&d.snapshot().generation()==1&&gatheredResource(a)==Material.AIR,"gathering_actual_area_untouched_while_old_entry_move_pending");gatheringEntryGate.complete(true);gatheringEntryGate=null;return rejectQuest(gatheringPendingEntry).thenCompose(y->reset);}).thenRun(()->{check(d.snapshot().generation()==2&&d.occupants()==0&&!s1.save().dirty(),"gathering_periodic_reset_evicts_controlled_members_after_actual_save");gatheringBlocks(a,Material.IRON_ORE);check(other.snapshot().generation()==1,"gathering_A_reset_preserves_B_generation");})
                    .thenCompose(x->verify(a1,30,1)).thenCompose(x->d.enter(actor[0])).thenRun(()->check(d.snapshot().generation()==2,"gathering_periodic_fresh_generation_not_reset_again_on_entry"))
                    .thenCompose(x->d.leave(actor[0])).thenCompose(x->d.enter(actor[0])).thenRun(()->check(d.snapshot().generation()==3,"gathering_all_exit_then_entry_resets_once"))
                    .thenCompose(x->d.leave(actor[0])).thenCompose(x->{UUID id=UUID.randomUUID();return d.reset(id).thenCompose(y->d.reset(id));}).thenRun(()->check(d.snapshot().generation()==4&&!d.snapshot().emptyReset(),"gathering_empty_periodic_refresh_and_duplicate_request_no_extra_generation"))
                    .thenCompose(x->other.leave(actor[2]));
            }).thenCompose(v->restored?CompletableFuture.completedFuture(null):mainResult(coordinator.disconnect(s1)).thenCompose(x->mainResult(coordinator.disconnect(s2))).thenCompose(x->until(()->worlds.get(a).state()==WorldLifecycle.State.OFFLINE))
                .thenRun(()->check(Bukkit.getWorld(savedId)==null,"gathering_idle_unload_releases_owned_chunk_tickets"))
                .thenCompose(x->{one=new Controlled();two=new Controlled();s1=connect(a1,one);s2=connect(a2,two);return mainResult(s1.ready()).thenCompose(y->mainResult(s2.ready()));})
                .thenCompose(x->gathering.bind(a1,false,gatheringPeople(a))).thenAccept(bd->{check(bd.dungeon().snapshot().generation()==4&&worlds.get(a).mapping().physicalId().equals(savedId),"gathering_same_JVM_reload_existing_UUID_and_ledger");gatheringBlocks(a,Material.IRON_ORE);}))
            .thenCompose(v->verify(a1,30,1)).thenRun(()->check(true,restored?"gathering_second_JVM_existing_inventory_and_new_template_boundary":"gathering_first_JVM_ready_for_restart"));
    }
    private CompletionStage<Void> gatheringSqlFailure(GatheringDungeon other){
        var c=gathering.content;var probeContent=new GatheringContent("public_failure_probe",c.area(),c.entrance(),c.spawn(),c.intervalMillis(),c.warningMillis(),c.active(),c.templates());
        var repo=new PostgresGatheringStore(store,access,a1,probeContent.id());var t=c.templates().get(c.active());var initial=new GatheringStore.State(a,probeContent.id(),worlds.get(a).mapping().physicalId(),0,0,c.area().hash(),t.version(),t.hash(),GatheringStore.Phase.OPEN,null,false,1000000);var before=other.snapshot();
        GatheringDungeon.Boundary boundary=new GatheringDungeon.Boundary(){public boolean current(){return true;}public void notice(String s){}public CompletionStage<Void> prepare(GatheringContent.Template t,UUID id,BooleanSupplier valid){return CompletableFuture.completedFuture(null);}public CompletionStage<Void> save(List<GatheringDungeon.Member> m){throw new AssertionError("stale DB row must reject before save");}public CompletionStage<Void> evacuate(List<GatheringDungeon.Member> m,BooleanSupplier v){throw new AssertionError();}public CompletionStage<Void> apply(GatheringContent.Template t,BooleanSupplier v){throw new AssertionError();}public CompletionStage<Void> flush(){throw new AssertionError();}public CompletionStage<Void> acknowledge(UUID id){throw new AssertionError();}public CompletionStage<Boolean> enter(GatheringDungeon.Member m,long g,BooleanSupplier v){throw new AssertionError();}};
        return mainResult(repo.create(initial)).thenCompose(row->{var next=new GatheringStore.State(a,row.dungeon(),row.world(),1,0,row.area(),row.template(),row.hash(),row.phase(),null,false,row.nextReset());var wrongWorld=new GatheringStore.State(a,row.dungeon(),UUID.randomUUID(),1,0,row.area(),row.template(),row.hash(),row.phase(),null,false,row.nextReset());try{repo.save(row,wrongWorld);throw new AssertionError("mapping mutation accepted");}catch(SecurityException expected){check(true,"gathering_physical_mapping_cannot_be_reassigned_by_CAS");}
            var first=repo.save(row,next).handle((v,e)->e==null);var duplicate=repo.save(row,next).handle((v,e)->e==null);
            return mainResult(first.thenCombine(duplicate,(x,y)->x!=y)).thenCompose(won->{check(won,"gathering_real_DB_same_revision_concurrent_write_has_one_winner");var stale=new GatheringDungeon(probeContent,repo,row,boundary,owner,SessionVerification::main,gatheringClock::get);return rejectQuest(stale.reset(UUID.randomUUID())).thenCompose(v->mainResult(repo.read())).thenAccept(actual->{check(actual.equals(next)&&stale.snapshot().phase()==GatheringStore.Phase.ERROR_HOLD&&stale.snapshot().generation()==0&&other.snapshot().equals(before),"gathering_real_DB_CAS_failure_original_and_other_run_preserved");stale.close();});});});
    }

    private CompletionStage<Void> travelPlay(){return openAll().thenCompose(v->{
        var world=Objects.requireNonNull(Bukkit.getWorld(worlds.get(a).mapping().physicalId()));for(var station:paperTravel.content.stations().values()){var p=station.facility();world.getBlockAt((int)p.x(),(int)p.y(),(int)p.z()).setType(Material.CHISELED_STONE_BRICKS,false);}
        return journal.enterPlaces(a1,s1,()->coordinator.playable(s1),0,64,0,2,64,1.5);
    }).thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenAccept(view->check(view.exploration().place("public_village").filter(p->p.visited()&&p.hubUnlocked()).isPresent()&&view.exploration().place("public_outpost").filter(ExplorationView.Place::hubUnlocked).isEmpty(),"travel_direct_source_visit_only_unlocks_source"))
        .thenCompose(v->journal.enterPlaces(a1,s1,()->coordinator.playable(s1),2,64,1.5,11.5,64,10.5))
        .thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenCompose(view->{var port=new TravelPort(s1,one);var t=paperTravel.travel;var choices=t.destinations(view.exploration(),port,"village_station");check(!journal.travelAllowed(a1,"survey_place"),"travel_shared_region_remains_restricted");check(!io.github.jesterportfolio.hearth.travel.FastTravel.pageAvailable(1,1),"travel_empty_arrow_page_not_allowed");check(choices.size()==1&&choices.getFirst().id().equals("outpost_station"),"travel_personal_destination_after_direct_visit");var screen=io.github.jesterportfolio.hearth.travel.PaperTravel.previewDestinations(choices,0);check(screen.getItem(0).getType()==Material.COMPASS,"travel_native_destination_items");
            one.inventory.setItem(0,new ItemStack(Material.STONE,29));coordinator.refresh(s1);check(s1.save().dirty(),"travel_source_dirty_before_save");UUID request=UUID.randomUUID();var first=t.start(port,"village_station","outpost_station",request);check(first==t.start(port,"village_station","outpost_station",request)&&port.moves==0,"travel_duplicate_and_before_save_no_move");return mainResult(first).thenRun(()->check(port.moves==1&&port.position.equals(paperTravel.content.get("outpost_station").arrival())&&!port.frozen&&!s1.save().dirty(),"travel_source_saved_before_controlled_move"));})
        .thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(saved->check(codec.decode(saved.payload())[0].getAmount()==29&&saved.revision()==1,"travel_real_DB_source_inventory_revision"))
        .thenCompose(v->journal.load(a2,s2,()->coordinator.playable(s2))).thenAccept(view->check(view.exploration().place("public_outpost").filter(ExplorationView.Place::hubUnlocked).isEmpty(),"travel_P2_not_auto_unlocked"))
        .thenCompose(v->journal.load(b3,s3,()->coordinator.playable(s3))).thenAccept(view->check(view.exploration().place("public_village").filter(ExplorationView.Place::hubUnlocked).isEmpty(),"travel_B_not_auto_unlocked"))
        .thenCompose(v->travelWriteFailure()).thenCompose(v->travelStale()).thenCompose(v->mainResult(coordinator.disconnect(s1))).thenCompose(v->mainResult(coordinator.disconnect(s2)))
        .thenCompose(v->until(()->worlds.get(a).state()==WorldLifecycle.State.OFFLINE)).thenCompose(v->{one=new Controlled();s1=connect(a1,one);return mainResult(s1.ready());})
        .thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenAccept(view->check(view.exploration().place("public_outpost").filter(p->p.visited()&&p.hubUnlocked()).isPresent()&&one.inventory.getItem(0).getAmount()==29&&Bukkit.getWorld(worlds.get(a).mapping().physicalId()).getBlockAt(11,64,11).getType()==Material.CHISELED_STONE_BRICKS,"travel_existing_world_and_personal_hubs_restored"));}
    private final class TravelPort implements io.github.jesterportfolio.hearth.travel.FastTravel.Port {
        final PlayerSessions.Session session;final Controlled actor;boolean frozen;int moves;long extraGeneration;io.github.jesterportfolio.hearth.travel.TravelContent.Point position=paperTravel.content.get("village_station").arrival();CompletableFuture<Void> saving;
        TravelPort(PlayerSessions.Session session,Controlled actor){this.session=session;this.actor=actor;}
        public ServerProgressAccess.Context context(){return session.context();}public Object session(){return session;}private WorldLifecycle lifecycle(){return worlds.get(context().storyRunId());}public UUID world(){return lifecycle().mapping().physicalId();}public long generation(){return lifecycle().generation()+extraGeneration;}public io.github.jesterportfolio.hearth.travel.TravelContent.Point position(){return position;}public boolean current(){return coordinator.playable(session);}public boolean eligible(){return true;}public boolean access(io.github.jesterportfolio.hearth.travel.TravelContent.Station s){return journal.travelAllowed(context(),s.place());}public boolean facility(io.github.jesterportfolio.hearth.travel.TravelContent.Station s){var p=s.facility();return Bukkit.getWorld(world()).getBlockAt((int)p.x(),(int)p.y(),(int)p.z()).getType()==Material.CHISELED_STONE_BRICKS;}public void freeze(boolean value){frozen=value;}
        public CompletionStage<ExplorationView> read(){return journal.load(context(),session,this::current).thenApply(QuestJournal.View::exploration);}public CompletionStage<Void> save(){return saving==null?coordinator.saveBoundary(session):saving;}public CompletionStage<Void> prepare(io.github.jesterportfolio.hearth.travel.TravelContent.Station s,BooleanSupplier valid){return mainResult(Bukkit.getWorld(world()).getChunkAtAsync(0,0)).thenAccept(c->{if(!valid.getAsBoolean())throw new IllegalStateException("TRAVEL_STALE");});}public CompletionStage<Boolean> move(io.github.jesterportfolio.hearth.travel.TravelContent.Station s,BooleanSupplier valid){if(!valid.getAsBoolean())return CompletableFuture.completedFuture(false);moves++;position=s.arrival();return CompletableFuture.completedFuture(true);}public void release(){}
    }
    private CompletionStage<Void> travelWriteFailure(){return mainResult(store.readInventory(a1)).thenCompose(before->{var p=new TravelPort(s1,one);p.saving=store.saveInventory(a1,0,fixture.apply(1)).thenApply(v->(Void)null).toCompletableFuture();var t=new io.github.jesterportfolio.hearth.travel.FastTravel(paperTravel.content,owner,SessionVerification::main);return rejectQuest(t.start(p,"village_station","outpost_station",UUID.randomUUID())).thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(after->{check(before.equals(after)&&p.moves==0&&!p.frozen&&t.phase(p)==io.github.jesterportfolio.hearth.travel.FastTravel.Phase.HOLD,"travel_real_DB_revision_failure_preserves_original_and_no_move");t.close();});});}
    private CompletionStage<Void> travelStale(){var p=new TravelPort(s1,one);p.saving=new CompletableFuture<>();var t=new io.github.jesterportfolio.hearth.travel.FastTravel(paperTravel.content,owner,SessionVerification::main);var result=t.start(p,"village_station","outpost_station",UUID.randomUUID());return until(()->t.phase(p)==io.github.jesterportfolio.hearth.travel.FastTravel.Phase.SAVING).thenCompose(v->{p.extraGeneration++;p.saving.complete(null);return rejectQuest(result);}).thenRun(()->{check(p.moves==0&&t.phase(p)==io.github.jesterportfolio.hearth.travel.FastTravel.Phase.HOLD&&!p.frozen,"travel_stale_generation_after_real_read_rejects_apply");t.close();});}
    private CompletionStage<Void> openAll(){
        one=new Controlled();two=new Controlled();three=new Controlled();s1=connect(a1,one);s2=connect(a2,two);s3=connect(b3,three);
        check(one.applies==0 && two.applies==0 && worlds.get(a).participantCount()==0,"no_apply_or_admission_before_prepare");
        return CompletableFuture.allOf(s1.ready().toCompletableFuture(),s2.ready().toCompletableFuture(),s3.ready().toCompletableFuture())
            .thenRun(()->check(worlds.get(a).participantCount()==2 && worlds.get(b).participantCount()==1,"all_sessions_active_after_move"));
    }
    private CompletionStage<Void> recoveryInspection(){
        return openAll().thenCompose(v->new RecoveryInspectionVerification(plugin,store,featureStore,inspectionStore,recoveryConsole,features.recovery,features.sceneContent.scenes(),n->ctx(a,n),c->adapter.fixture(new PaperSessionAdapter.Link(c,worlds.get(a),()->destination(a))),this::inspectionRuntime,owner,this::check,worlds.get(a).mapping().physicalId()).run())
            .thenCompose(v->verify(a1,32,0)).thenCompose(v->verify(a2,5,0)).thenCompose(v->verify(b3,7,0));
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
        public double healthFraction(){return health;}
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
    private CompletionStage<CombatRetryVote.Receipt> combatRetryCheck(CombatAttempt attempt,CombatPlayers bridge,CombatBody[] actors){
        var clock=new java.util.concurrent.atomic.AtomicLong(100);var world=worlds.get(a);var notices=new ArrayList<String>();
        var vote=new CombatRetryVote(attempt,owner,SessionVerification::main,clock::get,java.time.Duration.ofSeconds(30),()->world.generation()==attempt.snapshot().generation(),notices::add);
        var oldMob=combatTrials.mob(a).getUniqueId();var first=vote.request(a1,s1);
        return vote.cast(a1,s1,first.id(),CombatRetryVote.Choice.YES).thenRun(()->{
            check(vote.view().phase()==CombatRetryVote.Phase.OPEN&&attempt.snapshot().outcome()==CombatAttempt.Outcome.NONE,"combat_retry_half_votes_never_close_or_recreate_boss");
            clock.addAndGet(java.time.Duration.ofSeconds(30).toNanos());vote.poll();check(vote.view().phase()==CombatRetryVote.Phase.UNDECIDED,"combat_retry_controlled_30s_timeout_no_automatic_retry");
        }).thenCompose(v->{var obsolete=vote.request(a1,s1);bridge.leave(actors[1]);vote.poll();var next=vote.view();
            check(!next.id().equals(obsolete.id())&&next.targets()==1&&next.yes()==0&&attempt.snapshot().difficultyPlayers()==2,"combat_retry_online_change_revotes_without_rescaling_existing_boss");
            return rejectQuest(vote.cast(a1,s1,obsolete.id(),CombatRetryVote.Choice.YES)).thenCompose(x->{var decision=vote.cast(a1,s1,next.id(),CombatRetryVote.Choice.YES);check(vote.view().phase()==CombatRetryVote.Phase.INPUT_CLOSED&&!vote.barrier().toCompletableFuture().isDone(),"combat_retry_input_closed_and_barrier_before_terminal_confirmation");return decision;});
        }).thenAccept(confirmed->{
            check(confirmed.phase()==CombatRetryVote.Phase.RETRY_REQUESTED&&confirmed.receipt()!=null&&confirmed.receipt().attempt().equals(attempt.snapshot().attempt())&&attempt.snapshot().outcome()==CombatAttempt.Outcome.RETRY,"combat_retry_unique_receipt_ends_only_existing_attempt");
            long revision=attempt.snapshot().revision();check(vote.request(a1,s1).receipt().equals(confirmed.receipt())&&attempt.snapshot().revision()==revision,"combat_retry_duplicate_request_does_not_apply_again");
            check(combatTrials.mob(a).getUniqueId().equals(oldMob)&&!combatTrials.mob(a).hasAI()&&notices.stream().anyMatch(t->t.contains("아직 연결되지 않아 대기")),"combat_retry_actual_mob_frozen_no_false_next_attempt_spawn");
            vote.close();
        }).thenCompose(v->verify(a1,31,1)).thenCompose(v->verify(a2,5,0)).thenCompose(v->verify(b3,7,0)).thenApply(v->vote.view().receipt());
    }
    private CompletionStage<Void> combatRestartCheck(CombatRetryVote.Receipt receipt,CombatAttempt[] attempts,CombatPlayers[] bridges,CombatBody[] actors){
        var old=attempts[0];var previousBridge=bridges[0];var world=worlds.get(a);var nextBridge=new CombatPlayers(a,world.mapping().physicalId(),world.generation(),world::generation,owner,SessionVerification::main);
        var oldMob=combatTrials.mob(a).getUniqueId();one.count(30);coordinator.refresh(s1);s1.save().flush();one.count(29);coordinator.refresh(s1);
        var restart=new CombatRestart(old,combatContent.retry(),List.of(actors[0]),()->coordinator.saveRunBoundary(a).thenComposeAsync(v->quests.barrier(a),owner),roster->{
            previousBridge.close();var next=combatTrials.replace(world,receipt,roster,nextBridge);nextBridge.attach(next,List.of(actors[0]));bridges[0]=nextBridge;attempts[0]=next;return next;
        },owner,SessionVerification::main,()->world.generation()==old.snapshot().generation()&&coordinator.playable(s1));
        var work=restart.begin(receipt);check(work==restart.begin(receipt)&&!restart.barrier().toCompletableFuture().isDone(),"combat_restart_duplicate_joins_one_operation_and_save_barrier");
        return work.thenAccept(next->{
            var state=next.snapshot();check(!state.attempt().equals(old.snapshot().attempt())&&state.difficultyPlayers()==1&&state.sharedUsed()==0&&state.reserved()==0&&state.members().stream().allMatch(m->m.used()==0&&!m.dead()),"combat_restart_new_online_roster_difficulty_and_revive_budget");
            check(!combatTrials.mob(a).getUniqueId().equals(oldMob)&&combatTrials.mob(a).getHealth()==20&&Bukkit.getEntity(oldMob)==null&&bridges[0].actions(actors[0])&&actors[0].health==0.5&&CombatPlayers.samePosition(actors[0].position,combatContent.retry().pose(world.mapping().physicalId())),"combat_restart_actual_boss_replaced_only_after_controlled_return_confirmed");
            var roster=List.of(new CombatAttempt.Entrant(a1,s1,actors[0].position));var same=combatTrials.replace(world,receipt,roster,nextBridge);check(same==next&&same.snapshot().attempt().equals(next.snapshot().attempt()),"combat_restart_backend_duplicate_receipt_never_spawns_second_boss");restart.close();
        }).thenCompose(v->verify(a1,29,3)).thenCompose(v->mainResult(store.readInventory(a1))).thenCompose(before->{
            var failed=new io.github.jesterportfolio.hearth.inventory.InventorySaveSession(store,a1,before.revision()+1,before::payload,owner,SessionVerification::main,()->true,code->log("COMBAT_RESTART_EXPECTED_SAVE_FAILURE "+code));failed.markDirty();
            var probe=new CombatRestart(old,combatContent.retry(),List.of(actors[0]),failed::flush,roster->{throw new AssertionError("Failed DB save must not replace boss");},owner,SessionVerification::main,()->true);
            var currentBoss=combatTrials.mob(a).getUniqueId();return rejectQuest(probe.begin(receipt)).thenCompose(v->rejectQuest(probe.barrier())).thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(after->{
                check(after.equals(before)&&failed.dirty()&&failed.holdCode()!=null&&probe.phase()==CombatRestart.Phase.HOLD&&combatTrials.mob(a).getUniqueId().equals(currentBoss),"combat_restart_real_DB_CAS_failure_preserves_inventory_dirty_and_existing_boss");probe.close();
            });
        }).thenCompose(v->verify(a2,5,0)).thenCompose(v->verify(b3,7,0));
    }
    private CompletionStage<Void> prepareRetryFields(){
        CompletionStage<Void> work=CompletableFuture.completedFuture(null);
        for(UUID run:List.of(a,b))for(int x:List.of(1,2)){int cx=x;work=work.thenCompose(v->mainResult(Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId())).getChunkAtAsync(cx,1)).thenAccept(chunk->chunk.addPluginChunkTicket(plugin)));}
        return work.thenRun(()->{var out=new FieldContent.Point(24,64,29);var first=new FieldContent.Point(29,64,29);var second=new FieldContent.Point(37,64,29);var alpha=fieldEncounters.binding(a);var beta=fieldEncounters.binding(b);
            fieldEncounters.enter(alpha,a1.playerId(),out,first,List.of(new FieldEncounters.Presence(alpha,a1.playerId(),first)));
            fieldEncounters.enter(alpha,a1.playerId(),out,second,List.of(new FieldEncounters.Presence(alpha,a1.playerId(),second)));
            fieldEncounters.enter(beta,b3.playerId(),out,first,List.of(new FieldEncounters.Presence(beta,b3.playerId(),first)));
        });
    }
    private CompletionStage<Void> retryPathFailures(CombatAttempt source,CombatBody[] actors){
        return mainResult(store.readInventory(a1)).thenCompose(before->{var failed=new InventorySaveSession(store,a1,before.revision()+1,before::payload,owner,SessionVerification::main,()->true,code->{});failed.markDirty();var mob=combatTrials.mob(a).getUniqueId();int applied=actors[0].applications;
            var probe=new CombatRestart(source,combatContent.path().retry(),List.of(actors[0],actors[1]),failed::flush,()->{throw new AssertionError("Failed save reset field");},roster->{throw new AssertionError("Failed save started path");},owner,SessionVerification::main,()->true);
            return rejectQuest(probe.beginPathFailure(UUID.randomUUID())).thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(after->{check(before.equals(after)&&failed.dirty()&&probe.phase()==CombatRestart.Phase.HOLD&&actors[0].applications==applied&&fieldEncounters.count(a)==2&&combatTrials.mob(a).getUniqueId().equals(mob),"path_retry_DB_CAS_failure_blocks_return_reset_and_creation");probe.close();});
        }).thenCompose(v->{var mob=combatTrials.mob(a).getUniqueId();var ids=fieldEncounters.entities(a).stream().map(Entity::getUniqueId).toList();var probe=new CombatRestart(source,combatContent.path().retry(),List.of(actors[0],actors[1]),()->CompletableFuture.completedFuture(null),()->CompletableFuture.failedFuture(new IllegalStateException("INJECTED_RESET_FAILURE")),roster->{throw new AssertionError("Reset failed but new path created");},owner,SessionVerification::main,()->true);
            return rejectQuest(probe.beginPathFailure(UUID.randomUUID())).thenRun(()->{check(probe.phase()==CombatRestart.Phase.HOLD&&fieldEncounters.entities(a).stream().map(Entity::getUniqueId).toList().equals(ids)&&combatTrials.mob(a).getUniqueId().equals(mob),"path_retry_controlled_reset_failure_preserves_world_refs_and_old_mob");probe.close();});
        });
    }
    private CompletionStage<Void> retryPathStep(boolean failedPath,CombatRetryVote.Receipt receipt,CombatAttempt[] attempts,CombatPlayers[] bridges,CombatBody[] actors){
        var source=attempts[0];var world=worlds.get(a);var original=failedPath?combatContent.path().retry():combatContent.retry();var cp= combatContent.path().checkpoint();var policy=cp==null?original:new CombatContent.Retry(original.scope(),cp.entry().entry(),cp.entry().yaw(),cp.entry().pitch(),1,List.of(),original.reset());var nextBridge=new CombatPlayers(a,world.mapping().physicalId(),world.generation(),world::generation,owner,SessionVerification::main);
        var oldMob=combatTrials.mob(a).getUniqueId();var otherMob=combatTrials.mob(b).getUniqueId();var first=fieldEncounters.engine().state(a,"public_field_one");var other=fieldEncounters.entities(b).stream().map(Entity::getUniqueId).toList();
        var valid=(BooleanSupplier)()->world.generation()==source.snapshot().generation()&&coordinator.playable(s1)&&coordinator.playable(s2);
        var job=new CombatRestart(source,policy,List.of(actors[0],actors[1]),()->coordinator.saveRunBoundary(a).thenComposeAsync(v->quests.barrier(a),owner),
            ()->{check(CombatPlayers.samePosition(policy.pose(world.mapping().physicalId()),actors[0].position)&&CombatPlayers.samePosition(policy.pose(world.mapping().physicalId()),actors[1].position),"path_retry_both_moves_confirmed_before_reset");return fieldEncounters.reset(a,receipt.request(),policy.reset(),()->CompletableFuture.completedFuture(null),valid,owner);},roster->{
                var next=failedPath?combatTrials.restartPath(world,receipt,roster,nextBridge):combatTrials.replace(world,receipt,roster,nextBridge);bridges[0].close();nextBridge.attach(next,List.of(actors[0],actors[1]));return next;
            },owner,SessionVerification::main,valid);
        var work=failedPath?job.beginPathFailure(receipt.request()):job.begin(receipt);check(work==(failedPath?job.beginPathFailure(receipt.request()):job.begin(receipt)),"path_retry_duplicate_joins_same_preparation");
        return work.thenAccept(next->{attempts[0]=next;bridges[0]=nextBridge;
            check(job.phase()==CombatRestart.Phase.STARTED&&(combatContent.path().checkpoint()==null||actors[0].health==1&&actors[1].health==1)&&next.snapshot().difficultyPlayers()==2&&next.snapshot().sharedUsed()==0&&next.snapshot().members().stream().allMatch(m->!m.dead()&&m.used()==0)&&combatTrials.mob(a).getHealth()==20&&Bukkit.getEntity(oldMob)==null,"path_retry_fresh_path_mob_roster_and_counters_after_confirmed_reset");
            check(first.equals(fieldEncounters.engine().state(a,"public_field_one"))&&fieldEncounters.engine().state(a,"public_field_two").isEmpty()&&other.equals(fieldEncounters.entities(b).stream().map(Entity::getUniqueId).toList())&&combatTrials.mob(b).getUniqueId().equals(otherMob),"path_retry_checkpoint_prior_zone_and_other_run_preserved");
            var roster=List.of(new CombatAttempt.Entrant(a1,s1,actors[0].position),new CombatAttempt.Entrant(a2,s2,actors[1].position));check((failedPath?combatTrials.restartPath(world,receipt,roster,nextBridge):combatTrials.replace(world,receipt,roster,nextBridge))==next,"path_retry_backend_duplicate_receipt_no_second_path");
            var binding=fieldEncounters.binding(a);var point=new FieldContent.Point(37,64,29);fieldEncounters.enter(binding,a1.playerId(),new FieldContent.Point(24,64,29),point,List.of(new FieldEncounters.Presence(binding,a1.playerId(),point)));check(fieldEncounters.count(a)==2,"path_retry_new_entry_respawns_only_reset_zone");job.close();
        });
    }
    private CompletionStage<Void> retryEnterBoss(CombatAttempt[] attempts,CombatPlayers[] bridges,CombatBody[] actors){
        var source=attempts[0];var world=worlds.get(a);actors[0].position=new CombatContent.Pose(world.mapping().physicalId(),3.5,64,3.5,0,0);source.died(a2,s2,actors[1].position);
        var nextBridge=new CombatPlayers(a,world.mapping().physicalId(),world.generation(),world::generation,owner,SessionVerification::main);UUID request=UUID.randomUUID();
        var job=new CombatGathering(source,combatContent.path(),List.of(actors[0],actors[1]),()->{combatTrials.freezePath(a,source.snapshot().attempt());return coordinator.saveRunBoundary(a);},roster->{var next=combatTrials.enterBoss(world,source.snapshot().attempt(),request,roster,nextBridge);bridges[0].close();nextBridge.attach(next,List.of(actors[0],actors[1]));return next;},owner,SessionVerification::main,()->world.generation()==source.snapshot().generation()&&coordinator.playable(s1)&&coordinator.playable(s2));
        return job.begin(request).thenAccept(next->{attempts[0]=next;bridges[0]=nextBridge;check(next.snapshot().difficultyPlayers()==2&&next.snapshot().sharedUsed()==0&&combatTrials.mob(a).getHealth()==40&&next.snapshot().members().stream().noneMatch(m->m.dead()),"path_retry_regather_confirms_new_boss_and_separate_budget");job.close();});
    }
    private CompletionStage<Void> checkpointCheck(CombatAttempt source,CombatBody[] actors){
        CompletionStage<Void> work=CompletableFuture.completedFuture(null);for(var point:combatContent.path().checkpoints())work=work.thenCompose(v->{
            for(var actor:List.of(actors[0],actors[1]))actor.position=new CombatContent.Pose(source.snapshot().run().equals(a)?worlds.get(a).mapping().physicalId():worlds.get(b).mapping().physicalId(),(point.area().minX()+point.area().maxX())/2,64,(point.area().minZ()+point.area().maxZ())/2,0,0);
            return checkpointCheckPoint(source,actors,point);
        });return work;
    }
    private CompletionStage<Void> checkpointCheckPoint(CombatAttempt source,CombatBody[] actors,CombatContent.Checkpoint point){
        return mainResult(store.readQuest(a1,point.quest())).thenCompose(row->row.phase()==QuestStore.Phase.NOT_ACCEPTED?seedList(a1,point.quest(),QuestStore.Phase.ACTIVE):CompletableFuture.completedFuture(null)).thenCompose(v->mainResult(store.readQuest(a2,point.quest()))).thenCompose(row->row.phase()==QuestStore.Phase.NOT_ACCEPTED?seedList(a2,point.quest(),QuestStore.Phase.ACTIVE):CompletableFuture.completedFuture(null)).thenCompose(v->{
            source.died(a2,s2,actors[1].position);actors[0].health=0.25;actors[1].health=0.2;
            var job=new CombatCheckpoint(source,combatContent.id(),point,List.of(actors[0],actors[1]),store,store,()->coordinator.saveRunBoundary(a),owner,SessionVerification::main,()->coordinator.playable(s1)&&coordinator.playable(s2));
            var work=job.arrive();check(work==job.arrive(),"checkpoint_duplicate_inflight_coalesced");return work.thenCompose(ok->{check(ok&&actors[0].health==1&&actors[1].health==1&&source.snapshot().members().stream().noneMatch(CombatAttempt.Member::dead)&&source.snapshot().sharedUsed()==0,"checkpoint_real_DB_activation_full_health_free_revival_no_budget_charge");return mainResult(store.checkpoint(a1,combatContent.id(),point.quest(),point.id()));}).thenCompose(saved->{
                check(saved.isPresent()&&saved.get().complete(),"checkpoint_persistent_activation_confirmed");return rejectQuest(store.checkpointWrite(a1,combatContent.id(),point.quest(),point.id(),UUID.randomUUID(),false)).thenCompose(ignored->mainResult(store.checkpoint(b3,combatContent.id(),point.quest(),point.id())));
            }).thenAccept(other->check(other.isEmpty(),"checkpoint_other_run_read_completed_without_cross_write"));
        });
    }
    private CompletionStage<Void> routeCheckpoint(CombatAttempt source,CombatBody[] actors,CombatContent.Checkpoint point,Set<String> visited){
        CompletionStage<Void> work=CompletableFuture.completedFuture(null);for(var ctx:List.of(a1,a2))work=work.thenCompose(v->mainResult(store.readQuest(ctx,point.quest()))).thenCompose(row->row.phase()==QuestStore.Phase.NOT_ACCEPTED?seedList(ctx,point.quest(),QuestStore.Phase.ACTIVE):CompletableFuture.completedFuture(null));
        return work.thenCompose(v->{for(var actor:List.of(actors[0],actors[1]))actor.position=new CombatContent.Pose(worlds.get(a).mapping().physicalId(),(point.area().minX()+point.area().maxX())/2,64,(point.area().minZ()+point.area().maxZ())/2,0,0);source.died(a2,s2,actors[1].position);actors[0].health=.4;actors[1].health=.2;int used=source.snapshot().sharedUsed();
            var job=new CombatCheckpoint(source,combatContent.id(),point,List.of(actors[0],actors[1]),store,store,()->coordinator.saveRunBoundary(a),owner,SessionVerification::main,()->coordinator.playable(s1)&&coordinator.playable(s2));return job.arrive().thenCompose(ok->{check(ok&&actors[0].health==1&&actors[1].health==1&&source.snapshot().members().stream().noneMatch(CombatAttempt.Member::dead)&&source.snapshot().sharedUsed()==used,"route_checkpoint_full_health_free_revive_preserves_spent_budget_"+point.id());return mainResult(store.checkpoint(a1,combatContent.id(),point.quest(),point.id()));}).thenAccept(row->{check(row.isPresent()&&row.get().complete(),"route_checkpoint_real_PG_complete_"+point.id());visited.add(point.id());});});
    }
    private CompletionStage<Void> combatRouteState(){
        var attempts=new CombatAttempt[2];var bridges=new CombatPlayers[2];var actors=new CombatBody[3];var route=new CombatRoute[1];var visited=new HashSet<String>();var world=worlds.get(a);var original=new UUID[2];
        return openAll().thenCompose(v->prepareCombatChunks()).thenCompose(v->prepareRetryFields()).thenRun(()->{
            actors[0]=new CombatBody(s1,new CombatContent.Pose(world.mapping().physicalId(),3.5,64,3.5,0,0));actors[1]=new CombatBody(s2,actors[0].position);var wb=worlds.get(b);actors[2]=new CombatBody(s3,new CombatContent.Pose(wb.mapping().physicalId(),3.5,64,3.5,0,0));
            bridges[0]=new CombatPlayers(a,world.mapping().physicalId(),world.generation(),world::generation,owner,SessionVerification::main);bridges[1]=new CombatPlayers(b,wb.mapping().physicalId(),wb.generation(),wb::generation,owner,SessionVerification::main);
            attempts[0]=combatTrials.startPath(world,List.of(new CombatAttempt.Entrant(a1,s1,actors[0].position),new CombatAttempt.Entrant(a2,s2,actors[1].position)),bridges[0]);bridges[0].attach(attempts[0],List.of(actors[0],actors[1]));attempts[1]=combatTrials.start(wb,List.of(new CombatAttempt.Entrant(b3,s3,actors[2].position)),bridges[1]);bridges[1].attach(attempts[1],List.of(actors[2]));
            original[0]=attempts[0].snapshot().attempt();original[1]=combatTrials.mob(a).getUniqueId();route[0]=new CombatRoute(attempts[0],combatContent.path().route(),false,owner,SessionVerification::main,()->world.generation()==attempts[0].snapshot().generation()&&coordinator.playable(s1)&&coordinator.playable(s2));
            check(route[0].index()==0&&!route[0].atBoss(),"route_initial_section_and_no_implicit_boss_entry");attempts[0].died(a2,s2,actors[1].position);
        }).thenCompose(v->attempts[0].revive(a2,s2,UUID.randomUUID(),"public_path_rescue"))
          .thenCompose(v->routeCheckpoint(attempts[0],actors,combatContent.path().checkpoints().getFirst(),visited))
          .thenCompose(v->{for(var actor:List.of(actors[0],actors[1]))actor.position=new CombatContent.Pose(world.mapping().physicalId(),3.5,64,3.5,0,0);return routeSaveFailure(attempts[0],actors,route[0]);})
          .thenCompose(v->{one.count(31);coordinator.refresh(s1);actors[0].health=.7;var stage=route[0].section().orElseThrow();UUID id=UUID.randomUUID();var snapshot=attempts[0].snapshot();var work=route[0].advance(stage.id(),id,List.of(actors[0],actors[1]),()->{combatTrials.freezePath(a,snapshot.attempt());return coordinator.saveRunBoundary(a).thenComposeAsync(x->quests.barrier(a),owner);},()->visited.contains(stage.checkpoint()));check(work==route[0].advance(stage.id(),id,List.of(actors[0],actors[1]),()->CompletableFuture.completedFuture(null),()->true)&&!route[0].barrier().toCompletableFuture().isDone(),"route_duplicate_coalesced_and_save_barrier_waits");return work.thenRun(()->{check(route[0].index()==1&&sameCombatProgress(attempts[0].snapshot(),snapshot)&&combatTrials.mob(a).getUniqueId().equals(original[1])&&actors[0].health==.7&&attempts[0].snapshot().sharedUsed()==1,"route_first_move_preserves_attempt_mob_health_and_spent_budget");combatTrials.resumePath(a,snapshot.attempt());});})
          .thenCompose(v->verify(a1,31,1)).thenCompose(v->routeCheckpoint(attempts[0],actors,combatContent.path().checkpoints().get(1),visited))
          .thenCompose(v->{one.count(30);coordinator.refresh(s1);var stage=route[0].section().orElseThrow();return route[0].advance(stage.id(),UUID.randomUUID(),List.of(actors[0],actors[1]),()->{combatTrials.freezePath(a,original[0]);return coordinator.saveRunBoundary(a);},()->visited.contains(stage.checkpoint()));})
          .thenRun(()->{check(route[0].atBoss()&&attempts[0].snapshot().attempt().equals(original[0])&&attempts[0].snapshot().sharedUsed()==1&&attempts[1].snapshot().phase()==CombatAttempt.Phase.ACTIVE&&combatTrials.mob(b).getHealth()==20,"route_terminal_gate_no_new_path_budget_and_other_run_unchanged");route[0].close();})
          .thenCompose(v->verify(a1,30,2)).thenCompose(v->retryEnterBoss(attempts,bridges,actors))
          .thenRun(()->attempts[0].finish(CombatAttempt.Outcome.RETRY))
          .thenCompose(v->retryPathStep(false,new CombatRetryVote.Receipt(a,attempts[0].snapshot().attempt(),UUID.randomUUID()),attempts,bridges,actors))
          .thenCompose(v->{var restarted=new CombatRoute(attempts[0],combatContent.path().route(),true,owner,SessionVerification::main,()->coordinator.playable(s1)&&coordinator.playable(s2));check(restarted.index()==1&&restarted.section().orElseThrow().id().equals("public_second_path"),"route_restart_uses_authored_section_without_latest_checkpoint_guess");var stage=restarted.section().orElseThrow();return mainResult(store.checkpoint(a1,combatContent.id(),"survey_marker",stage.checkpoint())).thenCompose(row->restarted.advance(stage.id(),UUID.randomUUID(),List.of(actors[0],actors[1]),()->coordinator.saveRunBoundary(a),()->row.isPresent()&&row.get().complete())).thenRun(()->{check(restarted.atBoss(),"route_restarted_section_can_rejoin_final_boss_gate");restarted.close();});})
          .thenCompose(v->verify(a1,30,2)).thenCompose(v->verify(a2,5,0)).thenCompose(v->verify(b3,7,0))
          .thenCompose(v->mainResult(store.checkpoint(b3,combatContent.id(),"survey_marker","public_rest_two"))).thenAccept(row->check(row.isEmpty(),"route_checkpoint_data_not_shared_with_other_run"))
          .whenCompleteAsync((v,error)->{for(var bridge:bridges)if(bridge!=null)bridge.close();for(UUID run:List.of(a,b)){combatTrials.stop(run);var w=Bukkit.getWorld(worlds.get(run).mapping().physicalId());if(w!=null){w.removePluginChunkTicket(0,0,plugin);w.removePluginChunkTicket(1,1,plugin);w.removePluginChunkTicket(2,1,plugin);}}},owner);
    }
    /** 정상적으로 증가하는 전투 시간 외 모든 상태·소유권·횟수·참가 기록을 비교합니다. */
    private static boolean sameCombatProgress(CombatAttempt.Snapshot a,CombatAttempt.Snapshot b){return a.attempt().equals(b.attempt())&&a.run().equals(b.run())&&a.generation()==b.generation()&&a.revision()==b.revision()&&a.phase()==b.phase()&&a.outcome()==b.outcome()&&a.difficultyPlayers()==b.difficultyPlayers()&&a.sharedUsed()==b.sharedUsed()&&a.reserved()==b.reserved()&&Objects.equals(a.error(),b.error())&&a.members().equals(b.members());}
    private CompletionStage<Void> routeSaveFailure(CombatAttempt source,CombatBody[] actors,CombatRoute active){return mainResult(store.readInventory(a1)).thenCompose(before->{var bad=new InventorySaveSession(store,a1,before.revision()+1,before::payload,owner,SessionVerification::main,()->true,code->{});bad.markDirty();var probe=new CombatRoute(source,combatContent.path().route(),false,owner,SessionVerification::main,()->true);int applied=actors[0].applications;var prior=source.snapshot();return rejectQuest(probe.advance(probe.section().orElseThrow().id(),UUID.randomUUID(),List.of(actors[0],actors[1]),bad::flush,()->true)).thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(after->{check(before.equals(after)&&bad.dirty()&&probe.phase()==CombatRoute.Phase.HOLD&&probe.index()==0&&active.index()==0&&sameCombatProgress(source.snapshot(),prior)&&actors[0].applications==applied,"route_real_PG_CAS_failure_keeps_inventory_dirty_original_attempt_and_section");probe.close();});});}

    private CompletionStage<Void> combatPathState(){
        var attempts=new CombatAttempt[2];var bridges=new CombatPlayers[2];var actors=new CombatBody[3];
        return openAll().thenCompose(v->prepareCombatChunks()).thenCompose(v->prepareRetryFields()).thenRun(()->{
            check(combatContent.retry().scope().equals("FROM_PATH")&&combatContent.path().retry().reset()!=null,"path_retry_external_fixture_has_checkpoint_and_reset_policy");
            var wa=worlds.get(a);var wb=worlds.get(b);actors[0]=new CombatBody(s1,new CombatContent.Pose(wa.mapping().physicalId(),3.5,64,3.5,0,0));actors[1]=new CombatBody(s2,new CombatContent.Pose(wa.mapping().physicalId(),3.5,64,3.5,0,0));actors[2]=new CombatBody(s3,new CombatContent.Pose(wb.mapping().physicalId(),3.5,64,3.5,0,0));
            bridges[0]=new CombatPlayers(a,wa.mapping().physicalId(),wa.generation(),wa::generation,owner,SessionVerification::main);bridges[1]=new CombatPlayers(b,wb.mapping().physicalId(),wb.generation(),wb::generation,owner,SessionVerification::main);
            attempts[0]=combatTrials.startPath(wa,List.of(new CombatAttempt.Entrant(a1,s1,actors[0].position),new CombatAttempt.Entrant(a2,s2,actors[1].position)),bridges[0]);bridges[0].attach(attempts[0],List.of(actors[0],actors[1]));attempts[1]=combatTrials.start(wb,List.of(new CombatAttempt.Entrant(b3,s3,actors[2].position)),bridges[1]);bridges[1].attach(attempts[1],List.of(actors[2]));
        }).thenCompose(v->checkpointCheck(attempts[0],actors)).thenRun(()->{
            attempts[0].died(a1,s1,actors[0].position);attempts[0].died(a2,s2,actors[1].position);check(attempts[0].snapshot().phase()==CombatAttempt.Phase.WIPE_WAIT,"path_retry_source_all_dead");attempts[0].failedCondition(true);one.count(31);coordinator.refresh(s1);
        }).thenCompose(v->retryPathFailures(attempts[0],actors))
        .thenCompose(v->retryPathStep(true,new CombatRetryVote.Receipt(a,attempts[0].snapshot().attempt(),UUID.randomUUID()),attempts,bridges,actors))
        .thenCompose(v->verify(a1,31,1)).thenCompose(v->retryEnterBoss(attempts,bridges,actors))
        .thenCompose(v->{attempts[0].died(a2,s2,actors[1].position);return attempts[0].revive(a2,s2,UUID.randomUUID(),"public_rescue");})
        .thenCompose(v->{var vote=new CombatRetryVote(attempts[0],owner,SessionVerification::main,System::nanoTime,java.time.Duration.ofSeconds(30),()->true,text->{});var state=vote.request(a1,s1);return vote.cast(a1,s1,state.id(),CombatRetryVote.Choice.YES).thenCompose(x->vote.cast(a2,s2,state.id(),CombatRetryVote.Choice.YES)).thenCompose(decision->{check(attempts[0].snapshot().outcome()==CombatAttempt.Outcome.RETRY&&attempts[0].snapshot().sharedUsed()==1,"path_retry_boss_majority_confirmed_with_spent_budget");one.count(30);coordinator.refresh(s1);return retryPathStep(false,decision.receipt(),attempts,bridges,actors);}).whenComplete((x,e)->vote.close());})
        .thenCompose(v->verify(a1,30,2)).thenCompose(v->retryEnterBoss(attempts,bridges,actors))
        .thenCompose(v->verify(a2,5,0)).thenCompose(v->verify(b3,7,0)).thenRun(()->{
            bridges[0].close();bridges[1].close();combatTrials.stop(a);combatTrials.stop(b);
            for(UUID run:List.of(a,b)){var w=Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId()));w.removePluginChunkTicket(0,0,plugin);for(int x:List.of(1,2))w.removePluginChunkTicket(x,1,plugin);}
        });
    }
    private CompletionStage<Void> combatPlayerState(){
        var bridges=new CombatPlayers[2];var actors=new CombatBody[3];final CombatAttempt[] attempts=new CombatAttempt[2];final UUID request=UUID.randomUUID();final CompletionStage<?>[] pending=new CompletionStage<?>[2];
        return openAll().thenCompose(v->features==null||features.anchoredHud==null?CompletableFuture.completedFuture(null):features.anchoredHud.ready().thenRun(()->features.anchoredHud.verify(this::check))).thenRun(()->{if(features!=null)check(plugin.getConfig().getBoolean("betterhud-enabled")== (features.anchoredHud!=null),"hud_optional_backend_matches_configuration");HudVerification.run(this::check);}).thenCompose(v->combatQuestSetup()).thenCompose(v->journal.track(a1,s1,()->coordinator.playable(s1),"trial_keep",true)).thenAccept(view->check(journal.hudLines(view).size()==1&&!journal.hudLines(view).getFirst().contains("실패"),"combat_initial_quest_HUD_tracks_actual_active_goal")).thenCompose(v->prepareCombatChunks()).thenRun(()->{
            check(combatPlayers!=null,"combat_real_Player_adapter_enabled_without_fake_Player");
            for(int i=0;i<2;i++){UUID run=i==0?a:b;var w=worlds.get(run);bridges[i]=new CombatPlayers(run,w.mapping().physicalId(),w.generation(),w::generation,owner,SessionVerification::main);}
            var poseA=new CombatContent.Pose(worlds.get(a).mapping().physicalId(),3,64,3,31,12);var poseB=new CombatContent.Pose(worlds.get(b).mapping().physicalId(),3,64,3,0,0);actors[0]=new CombatBody(s1,poseA);actors[1]=new CombatBody(s2,poseA);actors[2]=new CombatBody(s3,poseB);
            attempts[0]=combatTrials.start(worlds.get(a),List.of(new CombatAttempt.Entrant(a1,s1,poseA),new CombatAttempt.Entrant(a2,s2,poseA)),bridges[0]);bridges[0].attach(attempts[0],List.of(actors[0],actors[1]));
            attempts[1]=combatTrials.start(worlds.get(b),List.of(new CombatAttempt.Entrant(b3,s3,poseB)),bridges[1]);bridges[1].attach(attempts[1],List.of(actors[2]));
            var healthBoss=combatTrials.mob(a);healthBoss.setHealth(30);var beforeHud=attempts[0].snapshot();
            var bossView=attempts[0].hud(a1,s1,healthBoss.getHealth(),Objects.requireNonNull(healthBoss.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)).getValue());
            var actualBar=PaperCombatHud.create(bossView);check(actualBar.progress()==0.75f&&bossView.visible()&&bossView.action().contains("공동 부활 남음: 2")&&beforeHud.equals(attempts[0].snapshot()),"combat_HUD_real_mob_health_BossBar_factory_and_readonly_counts");
            check(PaperQuestUI.hudBars(List.of("본인 시험 퀘스트")).size()==1&&actualBar.color()==net.kyori.adventure.bossbar.BossBar.Color.RED,"combat_HUD_independent_bar_preserves_quest_bar_factory");
            var updates=new CombatHud.Delivery();check(updates.next(bossView,1).notice()&&!updates.next(bossView,2).notice(),"combat_HUD_same_state_chat_not_repeated");
            one.count(31);coordinator.refresh(s1);bridges[0].died(actors[0]);check(!bridges[0].actions(actors[0])&&bridges[0].movement(actors[0]),"combat_player_death_blocks_actions_before_projection");bridges[0].project();check(actors[0].spectator&&actors[0].camera.equals(a2.playerId()),"combat_controlled_spectator_camera_and_inventory31_preserved");
        }).thenCompose(v->{var death=quests.died(a1,s1,()->coordinator.playable(s1),()->bridges[0].actions(actors[0]),catalog);check(death==quests.died(a1,s1,()->true,()->false,catalog)&&!quests.barrier(a).toCompletableFuture().isDone(),"combat_death_duplicate_coalesces_and_joins_world_save_barrier");return death;})
        .thenCompose(v->combatQuestCheck()).thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenAccept(view->{
            check(view.states().get("trial_keep").phase()==QuestStore.Phase.FAILED&&journal.hudLines(view).size()==1&&journal.hudLines(view).getFirst().contains("실패"),"combat_readonly_connected_HUD_load_while_actions_blocked_no_stale_failed_goal");
            check(!bridges[0].actions(actors[0])&&attempts[0].hud(a1,s1,30,40).action().contains("관전"),"combat_HUD_read_does_not_unlock_spectator_actions");
        }).thenRun(()->{
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
            check(attempts[1].hud(b3,s3,20,20).action().startsWith("전투 처리 보류")&&!attempts[0].hud(a1,s1,30,40).action().contains("보류"),"combat_HUD_HOLD_and_other_run_feedback_isolated");
        }).thenRun(()->{bridges[0].died(actors[0]);bridges[0].project();check(actors[0].spectator,"combat_restart_source_can_include_dead_spectator");}).thenCompose(v->combatRetryCheck(attempts[0],bridges[0],actors)).thenCompose(receipt->combatRestartCheck(receipt,attempts,bridges,actors)).thenCompose(v->combatExitCheck(attempts,bridges,actors)).thenCompose(v->combatGatheringCheck(attempts,bridges,actors)).thenCompose(v->fieldResetCheck()).thenRun(()->{
            bridges[0].close();bridges[1].close();check(!attempts[0].hud(a1,s1,30,40).visible()&&attempts[0].view(a1,s1).state().contains("격리 시험 종료"),"combat_HUD_closed_hides_bar_and_explains_outcome");combatTrials.stop(a);combatTrials.stop(b);for(UUID run:List.of(a,b))Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId())).removePluginChunkTicket(0,0,plugin);
        }).thenCompose(v->combatQuestDatabaseFailure()).thenCompose(v->verify(a1,28,4)).thenCompose(v->verify(a2,5,0)).thenCompose(v->verify(b3,7,0));
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
    private static FieldContent fieldDefinition(java.io.File file){var yaml=YamlConfiguration.loadConfiguration(file);var map=new HashMap<String,Object>(yaml.getValues(false));if(yaml.isConfigurationSection("reset"))map.put("reset",Objects.requireNonNull(yaml.getConfigurationSection("reset")).getValues(false));return FieldContent.parse(map);}
    private static final class PatternDamageProbe implements org.bukkit.event.Listener {
        org.bukkit.entity.Entity source,target;boolean cancel;int delivered,cancelled;
        @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.HIGHEST,ignoreCancelled=true)
        public void cancel(org.bukkit.event.entity.EntityDamageByEntityEvent event){if(event.getDamager()==source&&event.getEntity()==target&&cancel){cancelled++;event.setCancelled(true);}}
        @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR,ignoreCancelled=true)
        public void observed(org.bukkit.event.entity.EntityDamageByEntityEvent event){if(event.getDamager()==source&&event.getEntity()==target)delivered++;}
    }
    private org.bukkit.entity.Zombie patternTarget(org.bukkit.World world,double x,double y,double z){
        return world.spawn(new org.bukkit.Location(world,x,y,z),org.bukkit.entity.Zombie.class,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM,false,mob->{mob.setAI(false);mob.setGravity(false);mob.setPersistent(false);mob.setRemoveWhenFarAway(false);mob.setCanPickupItems(false);Objects.requireNonNull(mob.getEquipment()).setHelmet(new ItemStack(Material.IRON_HELMET));Objects.requireNonNull(mob.getAttribute(org.bukkit.attribute.Attribute.KNOCKBACK_RESISTANCE)).setBaseValue(1);Objects.requireNonNull(mob.getAttribute(org.bukkit.attribute.Attribute.SPAWN_REINFORCEMENTS)).setBaseValue(0);});
    }
    private CompletionStage<Void> bossAreaState(){
        var fights=new CombatAttempt[2];var targets=new org.bukkit.entity.Zombie[6];var allowed=new HashSet<UUID>();var previews=new HashMap<UUID,List<CombatContent.Pose>>();var events=new PatternDamageProbe();var marks=new int[1];var baseline=new double[6];var count=new long[1];var wa=worlds.get(a).mapping().physicalId();var wb=worlds.get(b).mapping().physicalId();
        return openAll().thenCompose(v->prepareCombatChunks()).thenRun(()->{
            var poseA=new CombatContent.Pose(wa,3,64,3,0,0);var poseB=new CombatContent.Pose(wb,3,64,3,0,0);fights[0]=combatTrials.start(worlds.get(a),List.of(new CombatAttempt.Entrant(a1,s1,poseA),new CombatAttempt.Entrant(a2,s2,poseA)),new CombatActors());fights[1]=combatTrials.start(worlds.get(b),List.of(new CombatAttempt.Entrant(b3,s3,poseB)),new CombatActors());
            for(UUID run:List.of(a,b)){combatTrials.mob(run).setGravity(false);((org.bukkit.entity.Mob)combatTrials.mob(run)).setAI(false);}
            var world=Objects.requireNonNull(Bukkit.getWorld(wa));targets[0]=patternTarget(world,3,64,6);targets[1]=patternTarget(world,6,64,3);targets[2]=patternTarget(world,3,68,6);targets[3]=patternTarget(Objects.requireNonNull(Bukkit.getWorld(wb)),3,64,6);targets[4]=patternTarget(world,3,64,7);targets[5]=patternTarget(world,8,64,8);
            for(int i=0;i<targets.length;i++)if(i!=3)allowed.add(targets[i].getUniqueId());
            combatTrials.access(new PaperCombatTrials.Access(){
                public boolean target(UUID run,org.bukkit.entity.Entity target){return run.equals(a)&&allowed.contains(target.getUniqueId());}
                public boolean hit(UUID run,org.bukkit.entity.Entity source,org.bukkit.entity.Entity target){return target(run,target)&&source==combatTrials.mob(a);}
                public void notice(UUID run,org.bukkit.entity.LivingEntity target,String text){}
                public void preview(UUID run,org.bukkit.entity.LivingEntity target,List<CombatContent.Pose> points){check(target(run,target)&&points.size()<=24&&points.stream().allMatch(p->p.world().equals(wa)),"boss_area_preview_has_only_current_authorized_world_data");previews.put(target.getUniqueId(),points);marks[0]++;}
            });events.source=combatTrials.mob(a);events.target=targets[0];Bukkit.getPluginManager().registerEvents(events,plugin);
        }).thenCompose(v->until(()->combatTrials.patterns(a).orElseThrow().winding())).thenRun(()->{
            check(events.delivered==0&&previews.containsKey(targets[0].getUniqueId())&&!previews.containsKey(targets[3].getUniqueId()),"boss_area_preview_precedes_damage_without_cross_run_recipient");
            check(BossPatternArea.contains(combatContent.patterns().getFirst(),new CombatContent.Pose(wa,3,64,3,0,0),new CombatContent.Pose(wa,3,64,6,0,0)),"boss_area_external_cone_definition_matches_preview");
        }).thenCompose(v->mainResult(targets[4].teleportAsync(new org.bukkit.Location(Objects.requireNonNull(Bukkit.getWorld(wa)),7,64,3)))).thenCompose(ok->{check(Boolean.TRUE.equals(ok),"boss_area_controlled_target_leaves_after_warning");return mainResult(targets[5].teleportAsync(new org.bukkit.Location(Objects.requireNonNull(Bukkit.getWorld(wa)),3.5,64,5.5)));}).thenCompose(ok->{check(Boolean.TRUE.equals(ok),"boss_area_controlled_target_enters_after_warning");return mainResult(combatTrials.mob(a).teleportAsync(new org.bukkit.Location(Objects.requireNonNull(Bukkit.getWorld(wa)),12,64,3,90,0)));})
        .thenCompose(ok->{check(Boolean.TRUE.equals(ok),"boss_area_boss_moves_and_rotates_after_locked_warning");return until(()->combatTrials.patterns(a).orElseThrow().strikes()>0);}).thenRun(()->{
            check(targets[0].getHealth()<20&&targets[5].getHealth()<20&&targets[1].getHealth()==20&&targets[2].getHealth()==20&&targets[3].getHealth()==20&&targets[4].getHealth()==20&&events.delivered>0,"boss_area_locked_cone_uses_original_facing_and_latest_target_positions");
        }).thenCompose(v->mainResult(combatTrials.mob(a).teleportAsync(new org.bukkit.Location(Objects.requireNonNull(Bukkit.getWorld(wa)),3,64,3,0,0)))).thenRun(()->{
            combatTrials.mob(a).setHealth(Objects.requireNonNull(combatTrials.mob(a).getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)).getValue()*0.4);
        }).thenCompose(v->until(()->combatTrials.patterns(a).orElseThrow().phase().equals("pressure")&&combatTrials.patterns(a).orElseThrow().winding())).thenRun(()->{for(int i=0;i<targets.length;i++)baseline[i]=targets[i].getHealth();count[0]=combatTrials.patterns(a).orElseThrow().strikes();})
        .thenCompose(v->mainResult(targets[0].teleportAsync(new org.bukkit.Location(Objects.requireNonNull(Bukkit.getWorld(wa)),11.1,64,6))))
        .thenCompose(v->mainResult(targets[1].teleportAsync(new org.bukkit.Location(Objects.requireNonNull(Bukkit.getWorld(wa)),10,64,6))))
        .thenCompose(v->mainResult(targets[4].teleportAsync(new org.bukkit.Location(Objects.requireNonNull(Bukkit.getWorld(wa)),3,64,6))))
        .thenCompose(v->mainResult(targets[5].teleportAsync(new org.bukkit.Location(Objects.requireNonNull(Bukkit.getWorld(wa)),10.5,64,7))))
        .thenCompose(v->mainResult(combatTrials.mob(a).teleportAsync(new org.bukkit.Location(Objects.requireNonNull(Bukkit.getWorld(wa)),10,64,3,0,0))))
        .thenRun(()->allowed.remove(targets[5].getUniqueId())).thenCompose(v->until(()->combatTrials.patterns(a).orElseThrow().strikes()>count[0])).thenRun(()->{
            check(targets[1].getHealth()<baseline[1]&&targets[0].getHealth()==baseline[0]&&targets[2].getHealth()==baseline[2]&&targets[3].getHealth()==baseline[3]&&targets[4].getHealth()==baseline[4]&&targets[5].getHealth()==baseline[5],"boss_area_live_line_checks_current_origin_width_height_and_revoked_permission");
            check(marks[0]>0&&marks[0]<=12,"boss_area_preview_is_bounded_not_redrawn_each_tick");fights[0].victory();
        }).thenCompose(v->until(()->combatTrials.patterns(a).orElseThrow().state()==BossPatterns.State.CLOSED))
        .thenCompose(v->verify(a1,32,0)).thenCompose(v->verify(a2,5,0)).thenCompose(v->verify(b3,7,0))
        .whenCompleteAsync((v,error)->{org.bukkit.event.HandlerList.unregisterAll(events);combatTrials.access(combatPlayers);for(var target:targets)if(target!=null)target.remove();for(UUID run:List.of(a,b)){combatTrials.stop(run);var world=Bukkit.getWorld(worlds.get(run).mapping().physicalId());if(world!=null)world.removePluginChunkTicket(0,0,plugin);}},owner);
    }
    private CompletionStage<Void> bossPatternState(){
        var fights=new CombatAttempt[2];var targets=new org.bukkit.entity.Zombie[4];var events=new PatternDamageProbe();var permitted=new java.util.concurrent.atomic.AtomicBoolean(false);var notices=new ArrayList<String>();var saved=new long[5];var health=new double[2];
        return openAll().thenCompose(v->prepareCombatChunks()).thenRun(()->{
            check(combatContent.patterns().size()==2,"boss_patterns_external_content_loaded_without_balance_defaults");
            var poseA=new CombatContent.Pose(worlds.get(a).mapping().physicalId(),3,64,3,0,0);var poseB=new CombatContent.Pose(worlds.get(b).mapping().physicalId(),3,64,3,0,0);
            fights[0]=combatTrials.start(worlds.get(a),List.of(new CombatAttempt.Entrant(a1,s1,poseA),new CombatAttempt.Entrant(a2,s2,poseA)),new CombatActors());
            fights[1]=combatTrials.start(worlds.get(b),List.of(new CombatAttempt.Entrant(b3,s3,poseB)),new CombatActors());combatTrials.mob(a).setGravity(false);combatTrials.mob(b).setGravity(false);
            for(int i=0;i<targets.length;i++){var world=Objects.requireNonNull(Bukkit.getWorld(worlds.get(i==3?b:a).mapping().physicalId()));int x=i==2?12:5;int z=i==1?5:3;targets[i]=patternTarget(world,x,64,z);}
            combatTrials.access(new PaperCombatTrials.Access(){
                public boolean target(UUID run,org.bukkit.entity.Entity target){return permitted.get()&&run.equals(a)&&(target==targets[0]||target==targets[2]);}
                public boolean hit(UUID run,org.bukkit.entity.Entity source,org.bukkit.entity.Entity target){return target(run,target)&&source==combatTrials.mob(a);}
                public void notice(UUID run,org.bukkit.entity.LivingEntity target,String text){notices.add(run+":"+target.getUniqueId()+":"+text);}
            });events.source=combatTrials.mob(a);events.target=targets[0];Bukkit.getPluginManager().registerEvents(events,plugin);
        }).thenCompose(v->until(()->combatTrials.patterns(a).orElseThrow().winding())).thenRun(()->{
            check(events.delivered==0&&targets[0].getHealth()==20,"boss_pattern_windup_precedes_actual_damage");
        }).thenCompose(v->until(()->combatTrials.patterns(a).orElseThrow().strikes()>0)).thenRun(()->{
            check(events.delivered==0&&targets[0].getHealth()==20,"boss_pattern_current_permission_denial_blocks_damage");permitted.set(true);
        }).thenCompose(v->until(()->events.delivered>0)).thenRun(()->{
            check(targets[0].getHealth()<20&&targets[1].getHealth()==20&&targets[2].getHealth()==20&&targets[3].getHealth()==20&&notices.stream().anyMatch(n->n.contains("공격 예고")),"boss_pattern_real_damage_event_respects_radius_permission_and_other_world");
            health[0]=targets[0].getHealth();events.cancel=true;saved[0]=combatTrials.patterns(a).orElseThrow().strikes();
        }).thenCompose(v->until(()->events.cancelled>0)).thenRun(()->{
            check(targets[0].getHealth()==health[0],"boss_pattern_cancelled_real_event_does_not_bypass_protection");events.cancel=false;
        }).thenCompose(v->until(()->combatTrials.patterns(a).orElseThrow().winding())).thenRun(()->{
            var pose=new CombatContent.Pose(worlds.get(a).mapping().physicalId(),3,64,3,0,0);fights[0].died(a1,s1,pose);fights[0].disconnect(a2,s2,pose);saved[0]=fights[0].snapshot().combatMillis();saved[1]=combatTrials.patterns(a).orElseThrow().strikes();saved[2]=System.nanoTime()+700_000_000L;saved[3]=fights[1].snapshot().combatMillis();health[0]=targets[0].getHealth();
        }).thenCompose(v->until(()->System.nanoTime()>=saved[2])).thenRun(()->{
            check(fights[0].snapshot().phase()==CombatAttempt.Phase.PAUSED&&fights[0].snapshot().combatMillis()==saved[0]&&combatTrials.patterns(a).orElseThrow().strikes()==saved[1]&&targets[0].getHealth()==health[0]&&fights[1].snapshot().combatMillis()>saved[3],"boss_pattern_pause_freezes_only_own_windup_and_combat_clock");
            var fresh=new Object();fights[0].reconnect(a2,fresh);fights[0].returned(a2,fresh);
        }).thenCompose(v->until(()->fights[0].snapshot().phase()==CombatAttempt.Phase.ACTIVE)).thenRun(()->check(targets[0].getHealth()==health[0]&&combatTrials.patterns(a).orElseThrow().strikes()==saved[1],"boss_pattern_return_protection_elapsed_without_attacking"))
        .thenCompose(v->until(()->combatTrials.patterns(a).orElseThrow().strikes()>saved[1])).thenRun(()->{
            check(targets[0].getHealth()<health[0],"boss_pattern_resumes_remaining_windup_after_protection");var boss=combatTrials.mob(a);boss.setHealth(Objects.requireNonNull(boss.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)).getValue()*0.4);
        }).thenCompose(v->until(()->combatTrials.patterns(a).orElseThrow().phase().equals("pressure"))).thenCompose(v->until(()->combatTrials.patterns(a).orElseThrow().winding())).thenRun(()->{
            health[1]=targets[0].getHealth();saved[4]=events.delivered;fights[0].victory();saved[2]=System.nanoTime()+700_000_000L;
        }).thenCompose(v->until(()->System.nanoTime()>=saved[2])).thenRun(()->{
            check(combatTrials.patterns(a).orElseThrow().state()==BossPatterns.State.CLOSED&&events.delivered==saved[4]&&targets[0].getHealth()==health[1],"boss_pattern_terminal_result_cancels_old_attack");
            var old=combatTrials.mob(a).getUniqueId();combatTrials.releaseEnded(a,fights[0].snapshot().attempt());check(Bukkit.getEntity(old)==null&&combatTrials.patterns(a).isEmpty(),"boss_pattern_release_removes_old_mob_and_pattern_reference");permitted.set(false);
            var pose=new CombatContent.Pose(worlds.get(a).mapping().physicalId(),3,64,3,0,0);fights[0]=combatTrials.start(worlds.get(a),List.of(new CombatAttempt.Entrant(a1,s1,pose)),new CombatActors());check(combatTrials.patterns(a).orElseThrow().strikes()==0&&combatTrials.mob(a).getHealth()==20,"boss_pattern_new_attempt_has_independent_timer_phase_and_difficulty");
        }).thenCompose(v->verify(a1,32,0)).thenCompose(v->verify(a2,5,0)).thenCompose(v->verify(b3,7,0))
        .whenCompleteAsync((v,error)->{org.bukkit.event.HandlerList.unregisterAll(events);combatTrials.access(combatPlayers);for(var target:targets)if(target!=null)target.remove();for(UUID run:List.of(a,b)){combatTrials.stop(run);var world=Bukkit.getWorld(worlds.get(run).mapping().physicalId());if(world!=null)world.removePluginChunkTicket(0,0,plugin);}},owner);
    }
    private CompletionStage<Void> departureApply(CombatDepartures ledger,CombatBody port){
        var claim=ledger.claim(port.context(),port.session());long generation=worlds.get(port.context().storyRunId()).generation();
        var job=new CombatReturn(ledger,claim,port,()->coordinator.saveRunBoundary(port.context().storyRunId()).thenComposeAsync(v->quests.barrier(port.context().storyRunId()),owner),()->{port.cleanup();ledger.complete(claim);},owner,SessionVerification::main,()->coordinator.playable(port.session)&&worlds.get(port.context().storyRunId()).generation()==generation);
        var work=job.begin();check(work==job.begin()&&!job.barrier().toCompletableFuture().isDone(),"departure_duplicate_reuses_save_move_barrier");
        return work.thenRun(()->check(job.phase()==CombatReturn.Phase.DONE&&!ledger.pending(port.context())&&port.health==combatContent.exit().healthFraction()&&!port.spectator&&CombatPlayers.samePosition(port.position,combatContent.exit().pose(worlds.get(port.context().storyRunId()).mapping().physicalId())),"departure_confirmed_save_move_health_cleanup_before_ticket_consumption"));
    }
    private CompletionStage<Void> combatDepartureState(){
        var fights=new CombatAttempt[2];var bridges=new CombatPlayers[2];var actors=new CombatBody[3];var ledgers=new CombatDepartures[2];var snapshots=new CombatAttempt.Snapshot[2];UUID physical=worlds.get(a).mapping().physicalId();long[] generation={0};
        return openAll().thenCompose(v->prepareCombatChunks()).thenRun(()->{
            for(int i=0;i<2;i++){UUID run=i==0?a:b;var world=worlds.get(run);bridges[i]=new CombatPlayers(run,world.mapping().physicalId(),world.generation(),world::generation,owner,SessionVerification::main);}
            var pose=new CombatContent.Pose(physical,3,64,3,31,12);actors[0]=new CombatBody(s1,pose);actors[1]=new CombatBody(s2,pose);actors[2]=new CombatBody(s3,new CombatContent.Pose(worlds.get(b).mapping().physicalId(),3,64,3,0,0));
            fights[0]=combatTrials.start(worlds.get(a),List.of(new CombatAttempt.Entrant(a1,s1,pose),new CombatAttempt.Entrant(a2,s2,pose)),bridges[0]);bridges[0].attach(fights[0],List.of(actors[0],actors[1]));
            fights[1]=combatTrials.start(worlds.get(b),List.of(new CombatAttempt.Entrant(b3,s3,actors[2].position)),bridges[1]);bridges[1].attach(fights[1],List.of(actors[2]));
            bridges[0].died(actors[1]);bridges[0].leave(actors[1]);
        }).thenCompose(v->mainResult(coordinator.disconnect(s2))).thenRun(()->{
            fights[0].victory();snapshots[0]=fights[0].snapshot();ledgers[0]=new CombatDepartures(snapshots[0],physical,combatContent.exit(),Map.of(a1.playerId(),"SURVIVAL",a2.playerId(),"SURVIVAL"));bridges[0].close();combatTrials.releaseEnded(a,snapshots[0].attempt());
            check(ledgers[0].pending(a2)&&fights[1].snapshot().phase()==CombatAttempt.Phase.ACTIVE,"departure_offline_dead_member_retained_after_actual_mob_release_B_active");
        }).thenCompose(v->{two=new Controlled();s2=connect(a2,two);return mainResult(s2.ready());}).thenCompose(v->verify(a2,5,0)).thenCompose(v->mainResult(store.readInventory(a2))).thenCompose(before->{
            actors[1]=new CombatBody(s2,new CombatContent.Pose(physical,3,64,3,0,0));var probeLedger=new CombatDepartures(snapshots[0],physical,combatContent.exit(),Map.of(a1.playerId(),"SURVIVAL",a2.playerId(),"SURVIVAL"));var claim=probeLedger.claim(a2,s2);var failed=new InventorySaveSession(store,a2,before.revision()+1,before::payload,owner,SessionVerification::main,()->true,code->{});failed.markDirty();
            var probe=new CombatReturn(probeLedger,claim,actors[1],failed::flush,()->{throw new AssertionError("Failed departure cleaned ticket");},owner,SessionVerification::main,()->true);
            return rejectQuest(probe.begin()).thenCompose(x->mainResult(store.readInventory(a2))).thenAccept(after->{probeLedger.hold(claim);check(after.equals(before)&&failed.dirty()&&probe.phase()==CombatReturn.Phase.HOLD&&actors[1].applications==0&&probeLedger.pending(a2),"departure_real_CAS_failure_keeps_original_dirty_pending_and_blocks_move");probe.close();});
        }).thenCompose(v->{two.count(4);coordinator.refresh(s2);return departureApply(ledgers[0],actors[1]);}).thenCompose(v->verify(a2,4,1)).thenCompose(v->{
            var world=worlds.get(a);var nextBridge=new CombatPlayers(a,physical,world.generation(),world::generation,owner,SessionVerification::main);var next=combatTrials.start(world,List.of(new CombatAttempt.Entrant(a2,s2,actors[1].position)),nextBridge);nextBridge.attach(next,List.of(actors[1]));next.victory();var newer=new CombatDepartures(next.snapshot(),physical,combatContent.exit(),Map.of(a2.playerId(),"SURVIVAL"));nextBridge.close();combatTrials.releaseEnded(a,next.snapshot().attempt());
            return departureApply(newer,actors[1]).thenRun(()->check(newer.empty()&&ledgers[0].pending(a1)&&!ledgers[0].pending(a2)&&!next.snapshot().attempt().equals(snapshots[0].attempt()),"departure_new_attempt_for_ready_player_preserves_older_offline_player_receipt"));
        }).thenRun(()->{
            check(ledgers[0].pending(a1)&&snapshots[0].equals(fights[0].snapshot())&&fights[1].snapshot().phase()==CombatAttempt.Phase.ACTIVE,"departure_return_does_not_reopen_battle_change_counters_or_other_run");
            bridges[1].leave(actors[2]);check(fights[1].snapshot().outcome()==CombatAttempt.Outcome.ALL_OFFLINE,"departure_all_offline_ends_original_battle");snapshots[1]=fights[1].snapshot();ledgers[1]=new CombatDepartures(snapshots[1],worlds.get(b).mapping().physicalId(),combatContent.exit(),Map.of(b3.playerId(),"ADVENTURE"));bridges[1].close();combatTrials.releaseEnded(b,snapshots[1].attempt());
        }).thenCompose(v->mainResult(coordinator.disconnect(s3))).thenCompose(v->{three=new Controlled();s3=connect(b3,three);return mainResult(s3.ready());}).thenCompose(v->{actors[2]=new CombatBody(s3,new CombatContent.Pose(worlds.get(b).mapping().physicalId(),3,64,3,0,0));return departureApply(ledgers[1],actors[2]);}).thenRun(()->{
            check(ledgers[1].empty()&&fights[1].snapshot().equals(snapshots[1]),"departure_all_offline_reconnect_exits_instead_of_rejoining_closed_battle");generation[0]=worlds.get(a).generation();Objects.requireNonNull(Bukkit.getWorld(physical)).removePluginChunkTicket(0,0,plugin);
        }).thenCompose(v->mainResult(coordinator.disconnect(s1))).thenCompose(v->mainResult(coordinator.disconnect(s2))).thenCompose(v->until(()->worlds.get(a).state()==WorldLifecycle.State.OFFLINE)).thenRun(()->check(ledgers[0].pending(a1)&&Bukkit.getWorld(physical)==null&&worlds.get(b).state()==WorldLifecycle.State.ACTIVE,"departure_pending_receipt_survives_world_unload_without_live_world_reference"))
        .thenCompose(v->{one=new Controlled();s1=connect(a1,one);return mainResult(s1.ready());}).thenCompose(v->{check(worlds.get(a).mapping().physicalId().equals(physical)&&worlds.get(a).generation()>generation[0],"departure_existing_world_reloaded_with_new_generation");actors[0]=new CombatBody(s1,new CombatContent.Pose(physical,3,64,3,0,0));return departureApply(ledgers[0],actors[0]);})
        .thenRun(()->{check(ledgers[0].empty()&&snapshots[0].equals(fights[0].snapshot()),"departure_all_tickets_consumed_only_after_confirmed_returns");Objects.requireNonNull(Bukkit.getWorld(worlds.get(b).mapping().physicalId())).removePluginChunkTicket(0,0,plugin);})
        .thenCompose(v->verify(a1,32,0)).thenCompose(v->verify(a2,4,1)).thenCompose(v->verify(b3,7,0));
    }
    private CompletionStage<Void> combatExitCheck(CombatAttempt[] attempts,CombatPlayers[] bridges,CombatBody[] actors){
        var previous=attempts[0];previous.victory();var oldMob=combatTrials.mob(a).getUniqueId();var applications=actors[0].applications;
        return mainResult(store.readInventory(a1)).thenCompose(before->{
            var failed=new InventorySaveSession(store,a1,before.revision()+1,before::payload,owner,SessionVerification::main,()->true,code->{});failed.markDirty();
            var probe=new CombatReturn(previous,combatContent.exit(),actors[0],failed::flush,()->{throw new AssertionError("Failed exit cleaned world");},owner,SessionVerification::main,()->true);
            return rejectQuest(probe.begin()).thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(after->{check(after.equals(before)&&probe.phase()==CombatReturn.Phase.HOLD&&failed.dirty()&&actors[0].applications==applications&&combatTrials.mob(a).getUniqueId().equals(oldMob),"combat_exit_real_CAS_failure_blocks_move_cleanup_preserves_original");probe.close();});
        }).thenCompose(v->{
            one.count(28);coordinator.refresh(s1);var job=new CombatReturn(previous,combatContent.exit(),actors[0],()->coordinator.saveRunBoundary(a).thenComposeAsync(x->quests.barrier(a),owner),()->{bridges[0].leave(actors[0]);bridges[0].close();combatTrials.releaseEnded(a,previous.snapshot().attempt());},owner,SessionVerification::main,()->coordinator.playable(s1)&&worlds.get(a).generation()==previous.snapshot().generation());
            var work=job.begin();check(work==job.begin()&&!job.barrier().toCompletableFuture().isDone(),"combat_exit_duplicate_and_save_barrier");
            return work.thenRun(()->{
                check(job.phase()==CombatReturn.Phase.DONE&&previous.snapshot().outcome()==CombatAttempt.Outcome.VICTORY&&Bukkit.getEntity(oldMob)==null&&!actors[0].spectator&&CombatPlayers.samePosition(actors[0].position,combatContent.exit().pose(worlds.get(a).mapping().physicalId())),"combat_exit_controlled_return_camera_cleanup_actual_mob_removed");
                bridges[0]=new CombatPlayers(a,worlds.get(a).mapping().physicalId(),worlds.get(a).generation(),worlds.get(a)::generation,owner,SessionVerification::main);
                attempts[0]=combatTrials.start(worlds.get(a),List.of(new CombatAttempt.Entrant(a1,s1,actors[0].position)),bridges[0]);bridges[0].attach(attempts[0],List.of(actors[0]));
                check(!attempts[0].snapshot().attempt().equals(previous.snapshot().attempt())&&attempts[0].snapshot().difficultyPlayers()==1&&attempts[1].snapshot().phase()==CombatAttempt.Phase.HOLD,"combat_exit_next_explicit_start_and_other_run_preserved");
            });
        }).thenCompose(v->verify(a1,28,4));
    }
    private CompletionStage<Void> combatGatheringCheck(CombatAttempt[] attempts,CombatPlayers[] bridges,CombatBody[] actors){
        var world=worlds.get(a);attempts[0].victory();bridges[0].close();combatTrials.releaseEnded(a,attempts[0].snapshot().attempt());
        var pathBridge=new CombatPlayers(a,world.mapping().physicalId(),world.generation(),world::generation,owner,SessionVerification::main);
        actors[0].position=new CombatContent.Pose(world.mapping().physicalId(),3.5,64,3.5,0,0);actors[0].health=0.75;actors[1].position=new CombatContent.Pose(world.mapping().physicalId(),15,64,15,0,0);
        var path=combatTrials.startPath(world,List.of(new CombatAttempt.Entrant(a1,s1,actors[0].position),new CombatAttempt.Entrant(a2,s2,actors[1].position)),pathBridge);pathBridge.attach(path,List.of(actors[0],actors[1]));
        check(combatTrials.mob(a).getHealth()==20&&combatContent.path().combat().revival().limit()==1&&combatContent.revival().limit()==2,"gathering_path_and_boss_content_and_revive_limits_independent");
        path.died(a2,s2,actors[1].position);
        return path.revive(a2,s2,UUID.randomUUID(),"public_path_rescue").thenRun(()->{path.died(a2,s2,actors[1].position);pathBridge.project();check(path.snapshot().sharedUsed()==1&&actors[1].spectator,"gathering_source_path_budget_consumed_before_auto_return");})
        .thenCompose(v->mainResult(store.readInventory(a1))).thenCompose(before->{
            var failed=new InventorySaveSession(store,a1,before.revision()+1,before::payload,owner,SessionVerification::main,()->true,code->{});failed.markDirty();var count=actors[0].applications;
            var probe=new CombatGathering(path,combatContent.path(),List.of(actors[0],actors[1]),failed::flush,roster->{throw new AssertionError("Save failed but boss created");},owner,SessionVerification::main,()->true);
            return rejectQuest(probe.begin(UUID.randomUUID())).thenCompose(x->mainResult(store.readInventory(a1))).thenAccept(after->{check(after.equals(before)&&failed.dirty()&&probe.phase()==CombatGathering.Phase.HOLD&&actors[0].applications==count&&path.snapshot().sharedUsed()==1,"gathering_real_CAS_failure_preserves_items_path_budget_and_blocks_boss");probe.close();});
        }).thenCompose(v->{
            var nextBridge=new CombatPlayers(a,world.mapping().physicalId(),world.generation(),world::generation,owner,SessionVerification::main);UUID request=UUID.randomUUID();var oldMob=combatTrials.mob(a).getUniqueId();
            var job=new CombatGathering(path,combatContent.path(),List.of(actors[0],actors[1]),()->{combatTrials.freezePath(a,path.snapshot().attempt());check(!combatTrials.mob(a).hasAI(),"gathering_actual_path_AI_paused_during_save");return coordinator.saveRunBoundary(a).thenComposeAsync(x->quests.barrier(a),owner);},roster->{var next=combatTrials.enterBoss(world,path.snapshot().attempt(),request,roster,nextBridge);pathBridge.close();nextBridge.attach(next,List.of(actors[0],actors[1]));return next;},owner,SessionVerification::main,()->world.generation()==path.snapshot().generation()&&coordinator.playable(s1)&&coordinator.playable(s2));
            var work=job.begin(request);check(work==job.begin(request)&&!job.barrier().toCompletableFuture().isDone(),"gathering_inflight_duplicate_and_save_barrier");
            return work.thenAccept(next->{attempts[0]=next;bridges[0]=nextBridge;
                check(next.snapshot().difficultyPlayers()==2&&next.snapshot().sharedUsed()==0&&next.snapshot().members().stream().allMatch(m->!m.dead()&&m.used()==0)&&path.snapshot().sharedUsed()==1,"gathering_online_dead_auto_return_no_path_charge_new_boss_budget_zero");
                check(Bukkit.getEntity(oldMob)==null&&combatTrials.mob(a).getHealth()==40&&actors[0].health==0.75&&actors[1].health==0.5&&!actors[1].spectator&&job.phase()==CombatGathering.Phase.STARTED,"gathering_actual_boss_spawned_after_controlled_entry_health_and_mode_checks");
                var roster=List.of(new CombatAttempt.Entrant(a1,s1,actors[0].position),new CombatAttempt.Entrant(a2,s2,actors[1].position));check(combatTrials.enterBoss(world,path.snapshot().attempt(),request,roster,nextBridge)==next&&attempts[1].snapshot().phase()==CombatAttempt.Phase.HOLD,"gathering_backend_duplicate_no_second_boss_and_other_run_unchanged");job.close();
            });
        }).thenCompose(v->verify(a1,28,4)).thenCompose(v->verify(a2,5,0));
    }
    private CompletionStage<Void> fieldResetCheck(){
        CompletionStage<Void> ready=CompletableFuture.completedFuture(null);
        for(UUID run:List.of(a,b))for(int x:List.of(1,2)){int chunkX=x;ready=ready.thenCompose(v->mainResult(Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId())).getChunkAtAsync(chunkX,1)).thenAccept(chunk->chunk.addPluginChunkTicket(plugin)));}
        return ready.thenRun(()->{
            var from=new FieldContent.Point(24,64,29);var first=new FieldContent.Point(29,64,29);var next=new FieldContent.Point(37,64,29);var alpha=fieldEncounters.binding(a);var beta=fieldEncounters.binding(b);
            fieldEncounters.enter(alpha,a1.playerId(),from,first,List.of(new FieldEncounters.Presence(alpha,a1.playerId(),first)));
            fieldEncounters.enter(alpha,a1.playerId(),from,next,List.of(new FieldEncounters.Presence(alpha,a1.playerId(),next)));
            fieldEncounters.enter(beta,b3.playerId(),from,first,List.of(new FieldEncounters.Presence(beta,b3.playerId(),first)));
        }).thenCompose(v->{var before=fieldEncounters.entities(a).stream().map(Entity::getUniqueId).collect(java.util.stream.Collectors.toSet());return mainResult(store.readInventory(a1)).thenCompose(row->{
            var failed=new InventorySaveSession(store,a1,row.revision()+1,row::payload,owner,SessionVerification::main,()->true,code->{});failed.markDirty();
            return rejectQuest(fieldEncounters.reset(a,UUID.randomUUID(),fieldEncounters.resetPolicy(),failed::flush,()->true,owner)).thenRun(()->check(before.equals(fieldEncounters.entities(a).stream().map(Entity::getUniqueId).collect(java.util.stream.Collectors.toSet()))&&failed.dirty(),"field_reset_DB_failure_does_not_remove_managed_entities"));
        });}).thenCompose(v->{var request=UUID.randomUUID();var binding=fieldEncounters.binding(a);var before=fieldEncounters.engine().state(a,"public_field_one");var other=fieldEncounters.entities(b).stream().map(Entity::getUniqueId).toList();
            return fieldEncounters.reset(a,request,fieldEncounters.resetPolicy(),()->coordinator.saveRunBoundary(a),()->coordinator.playable(s1),owner).thenCompose(x->{
                check(fieldEncounters.engine().state(a,"public_field_one").equals(before)&&fieldEncounters.engine().state(a,"public_field_two").isEmpty()&&fieldEncounters.count(a)==1,"field_checkpoint_reset_preserves_prior_zone_and_removes_only_later_mobs");
                var next=new FieldContent.Point(37,64,29);fieldEncounters.enter(binding,a1.playerId(),new FieldContent.Point(24,64,29),next,List.of(new FieldEncounters.Presence(binding,a1.playerId(),next)));var fresh=fieldEncounters.entities(a).stream().map(Entity::getUniqueId).toList();
                return fieldEncounters.reset(a,request,fieldEncounters.resetPolicy(),()->coordinator.saveRunBoundary(a),()->true,owner).thenRun(()->check(fieldEncounters.entities(a).stream().map(Entity::getUniqueId).toList().equals(fresh)&&fieldEncounters.entities(b).stream().map(Entity::getUniqueId).toList().equals(other),"field_duplicate_reset_preserves_new_encounter_and_other_run"));
            });
        }).thenRun(()->{for(UUID run:List.of(a,b))for(int x:List.of(1,2))Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId())).removePluginChunkTicket(x,1,plugin);});
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
        check(fieldEncounters.count(a)==1&&fieldEncounters.count(b)==1,"field_A_active_tail_and_B_before_normal_unload");
        try{new PaperFieldEncounters(plugin,adapter,worlds,fieldDefinition(new java.io.File(plugin.getDataFolder(),"field-demo.yml")));throw new AssertionError("Existing field scope recreated");}catch(IllegalStateException expected){check(expected.getMessage().equals("FIELD_RESTART_REVIEW_REQUIRED"),"field_existing_scope_marker_prevents_automatic_recreation");}
        for(UUID run:List.of(a,b))for(int x:List.of(1,2))Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId())).removePluginChunkTicket(x,1,plugin);
    }).thenCompose(v->mainResult(coordinator.disconnect(s1))).thenCompose(v->mainResult(coordinator.disconnect(s2)))
        .thenCompose(v->until(()->worlds.get(a).state()==WorldLifecycle.State.OFFLINE))
        .thenRun(()->check(fieldEncounters.count(a)==0&&fieldEncounters.count(b)==1,"field_world_unload_removes_only_A_references"))
        .thenCompose(v->{one=new Controlled();s1=connect(a1,one);return mainResult(s1.ready());})
        .thenCompose(v->mainResult(Bukkit.getWorld(worlds.get(a).mapping().physicalId()).getChunkAtAsync(2,1))).thenCompose(chunk->{
            chunk.addPluginChunkTicket(plugin);
            var binding=fieldEncounters.binding(a);var first=fieldEncounters.engine().state(a,"public_field_one");var tail=fieldEncounters.engine().state(a,"public_field_two");var other=fieldEncounters.engine().state(b,"public_field_one");
            check(fieldEncounters.count(a)==0&&first.orElseThrow().phase()==FieldEncounters.Phase.CLEARED&&tail.orElseThrow().phase()==FieldEncounters.Phase.RETIRED,"field_normal_reload_preserves_clear_and_does_not_respawn_active_tail");
            var plan=new FieldReset(FieldReset.Mode.AFTER_CHECKPOINT,List.of("public_field_one","public_field_two"),"public_field_one");var request=UUID.randomUUID();
            return rejectQuest(fieldEncounters.reset(a,request,plan,()->store.readInventory(ctx(a,9999)).thenApply(row->null),()->coordinator.playable(s1),owner)).thenRun(()->check(first.equals(fieldEncounters.engine().state(a,"public_field_one"))&&tail.equals(fieldEncounters.engine().state(a,"public_field_two"))&&other.equals(fieldEncounters.engine().state(b,"public_field_one")),"field_PG_prerequisite_failure_preserves_all_original_encounters"))
                .thenCompose(v->fieldEncounters.reset(a,request,plan,()->coordinator.saveRunBoundary(a),()->coordinator.playable(s1),owner)).thenRun(()->{var from=new FieldContent.Point(24,64,29);var next=new FieldContent.Point(37,64,29);fieldEncounters.enter(binding,a1.playerId(),from,next,List.of(new FieldEncounters.Presence(binding,a1.playerId(),next)));check(fieldEncounters.count(a)==1&&first.equals(fieldEncounters.engine().state(a,"public_field_one"))&&other.equals(fieldEncounters.engine().state(b,"public_field_one")),"field_explicit_saved_reset_reopens_only_retired_tail_after_world_reload");fieldEncounters.retire(a);try{fieldEncounters.binding(a);throw new AssertionError("Retired field auto reset");}catch(IllegalStateException expected){check(expected.getMessage().equals("FIELD_RETIRED"),"field_terminal_retire_never_auto_respawns");}fieldEncounters.retire(b);chunk.removePluginChunkTicket(plugin);check(fieldEncounters.count(a)==0&&fieldEncounters.count(b)==0,"field_all_owned_entities_removed");});})
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
        .thenCompose(row->{check(row.phase()==QuestStore.Phase.FAILED&&row.objectiveIndex()==expected,"completion_failure_policy_"+id);return journal.load(a1,s1,()->true).thenCompose(view->{
            if(plugin.getConfig().getBoolean("phone-dialog-enabled",false)){var choice=io.github.jesterportfolio.hearth.phone.PhoneCompletion.choose(catalog,view,io.github.jesterportfolio.hearth.phone.PhoneCompletion.Operation.RETRY,id);io.github.jesterportfolio.hearth.phone.PhoneCompletion.require(catalog,view,choice);check(true,"phone_DB_failed_quest_retry_confirmation");}
            return expectQuestCode(quests.fail(current[0],c,"OBJECTIVE"),"QUEST_FAILURE_NOT_ALLOWED");});})
        .thenCompose(v->quests.retry(current[0],c)).thenCompose(v->{CompletionStage<?> work=CompletableFuture.completedFuture(null);for(int index=expected;index<3;index++){int x=5+index*4;work=work.thenCompose(done->quests.reach(current[0],c,x,64,31));}return work;})
        .thenCompose(v->quests.advance(current[0],QuestStore.Phase.COMPLETED)).thenAccept(row->check(row.phase()==QuestStore.Phase.COMPLETED,"completion_retry_flow_"+id));}
    private CompletionStage<Void> completionNavigation(){
        var c=catalog.quest("trial_checkpoint");var q=new PersonalQuest.Session[1];var flow=new io.github.jesterportfolio.hearth.phone.CompletionFlow();
        var resumeBefore=new CourseResume.Board[1];var retry=new io.github.jesterportfolio.hearth.phone.PhoneCompletion.Choice[1];var claim=new io.github.jesterportfolio.hearth.phone.PhoneCompletion.Choice[1];
        var requests=new io.github.jesterportfolio.hearth.phone.CompletionFlow.Request[3];var original=new Object[2];
        return openAll().thenCompose(v->quests.open(a1,c.id(),s1,()->coordinator.playable(s1))).thenCompose(value->{q[0]=value;return quests.accept(value,c);})
            .thenCompose(v->quests.reach(q[0],c,5,64,31)).thenCompose(v->quests.reach(q[0],c,9,64,31)).thenCompose(v->quests.fail(q[0],c,"OBJECTIVE"))
            .thenCompose(v->mainResult(store.readQuest(a1,c.id()))).thenAccept(value->original[0]=value)
            .thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(value->original[1]=value)
            .thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenAccept(view->{
                resumeBefore[0]=completionResume(view,c.id(),CourseResume.Kind.FAILED,null);retry[0]=QuestFollowups.choices(catalog,view,c.id()).getFirst();check(retry[0].operation()==io.github.jesterportfolio.hearth.phone.PhoneCompletion.Operation.RETRY,"followup_actual_DB_failed_record_has_retry_confirmation");
                io.github.jesterportfolio.hearth.phone.PhoneCompletion.require(catalog,view,retry[0]);followupNativeScreens(view,c.id());
            })
            .thenCompose(v->mainResult(store.readQuest(a1,c.id()))).thenAccept(value->check(value.equals(original[0])&&flow.current(s1)==null,"followup_confirmation_cancel_has_no_quest_write_or_request"))
            .thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(value->check(value.equals(original[1]),"followup_confirmation_preview_preserves_source_inventory"))
            .thenRun(()->{requests[0]=flow.begin(s1,retry[0]);try{flow.begin(s1,retry[0]);throw new AssertionError("duplicate followup accepted");}catch(IllegalStateException busy){check(busy.getMessage().equals("COMPLETION_ACTION_BUSY")&&requests[0].phase()==io.github.jesterportfolio.hearth.phone.CompletionFlow.Phase.PENDING,"followup_shared_book_phone_pending_blocks_second_request");}})
            .thenCompose(v->quests.retry(q[0],c)).thenRun(()->{check(q[0].saved().objectiveIndex()==1&&flow.complete(s1,requests[0],true),"followup_existing_checkpoint_retry_settles_own_request");})
            .thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenAccept(view->{resumeBefore[0]=completionResume(view,c.id(),CourseResume.Kind.ACTIVE,resumeBefore[0]);try{io.github.jesterportfolio.hearth.phone.PhoneCompletion.require(catalog,view,retry[0]);throw new AssertionError("old retry accepted");}catch(RuntimeException stale){check(QuestFollowups.choices(catalog,view,c.id()).isEmpty(),"followup_current_active_record_rejects_old_retry_confirmation");}})
            .thenCompose(v->quests.reach(q[0],c,9,64,31)).thenCompose(v->quests.reach(q[0],c,13,64,31)).thenCompose(v->quests.advance(q[0],QuestStore.Phase.COMPLETED))
            .thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenAccept(view->{
                resumeBefore[0]=completionResume(view,c.id(),CourseResume.Kind.REWARD,resumeBefore[0]);claim[0]=QuestFollowups.choices(catalog,view,c.id()).getFirst();check(claim[0].operation()==io.github.jesterportfolio.hearth.phone.PhoneCompletion.Operation.CLAIM,"followup_actual_DB_pending_reward_has_claim_confirmation");followupNativeScreens(view,c.id());
                requests[1]=flow.begin(s1,claim[0]);check(!flow.complete(s1,requests[0],false)&&requests[1].phase()==io.github.jesterportfolio.hearth.phone.CompletionFlow.Phase.PENDING,"followup_old_retry_callback_cannot_overwrite_new_reward_request");
                one.count(20);coordinator.refresh(s1);
            })
            .thenCompose(v->questRewards.claim(coordinator,s1,c.id())).thenRun(()->check(flow.complete(s1,requests[1],true)&&craftingCount(one.capture(),Material.STONE)==23,"followup_existing_reward_source_save_settles_success_once"))
            .thenCompose(v->mainResult(store.readInventory(a1))).thenCompose(before->questRewards.claim(coordinator,s1,c.id()).thenCompose(done->mainResult(store.readInventory(a1))).thenAccept(after->check(before.equals(after)&&codec.equivalent(after.payload(),one.capture()),"followup_repeat_grant_preserves_payload_revision_and_receipt")))
            .thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenAccept(view->{resumeBefore[0]=completionResume(view,c.id(),null,resumeBefore[0]);check(QuestFollowups.choices(catalog,view,c.id()).isEmpty(),"followup_granted_reward_disappears_from_action_list");try{io.github.jesterportfolio.hearth.phone.PhoneCompletion.require(catalog,view,claim[0]);throw new AssertionError("paid reward accepted");}catch(RuntimeException stale){check(true,"followup_paid_reward_rejects_old_confirmation");}})
            .thenCompose(v->journal.load(a2,s2,()->coordinator.playable(s2))).thenAccept(view->check(QuestFollowups.choices(catalog,view,c.id()).isEmpty(),"followup_other_person_does_not_borrow_failed_or_reward_record"))
            .thenCompose(v->finishCourseQuest(b3,s3,c.id())).thenCompose(v->journal.load(b3,s3,()->coordinator.playable(s3))).thenAccept(view->{requests[2]=flow.begin(s3,QuestFollowups.choices(catalog,view,c.id()).getFirst());})
            .thenCompose(v->mainResult(store.readInventory(b3))).thenCompose(before->{
                var probe=new InventorySaveSession(store,b3,before.revision(),before::payload,owner,SessionVerification::main,()->true,code->log("EXPECTED_FOLLOWUP_HOLD "+code));
                return expect(probe.transaction(saved->store.grantReward(b3,c.id(),"wrong-fingerprint",saved.revision(),saved.payload()),saved->{throw new AssertionError("rejected reward applied");}),"REWARD_POLICY_MISMATCH")
                    .thenCompose(done->expect(probe.flush(),"REWARD_POLICY_MISMATCH")).thenCompose(done->mainResult(store.readInventory(b3))).thenAccept(after->check(after.equals(before)&&probe.holdCode()!=null&&flow.complete(s3,requests[2],false)&&requests[2].phase()==io.github.jesterportfolio.hearth.phone.CompletionFlow.Phase.FAILED,"followup_real_DB_failure_keeps_source_and_failed_request_result"));
            })
            .thenCompose(v->mainResult(store.rewardStatus(b3,c.id()))).thenAccept(status->check(status.equals("PENDING")&&requests[1].phase()==io.github.jesterportfolio.hearth.phone.CompletionFlow.Phase.SUCCEEDED,"followup_B_rejection_preserves_pending_receipt_and_A_success"))
            .thenRun(()->{flow.forget(s3);check(!flow.complete(s3,requests[2],true),"followup_forgotten_session_discards_late_result");flow.clear();});
    }
    private void followupNativeScreens(QuestJournal.View view,String id){
        var choices=QuestFollowups.choices(catalog,view,id);var choice=choices.getFirst();var book=QuestBookView.accepted(catalog,view.states(),view.exploration().blockedQuests(),id).orElseThrow();
        var actual=PaperQuestUI.book(book,List.of(Component.text(QuestFollowups.label(choice)),Component.text("취소 · 원래 의뢰로"),Component.text("현재 의뢰로")));
        var flow=new io.github.jesterportfolio.hearth.phone.CompletionFlow();var request=flow.begin(s1,choice);
        var dialog=io.github.jesterportfolio.hearth.phone.PaperPhone.compose(request.title(),request.lines().stream().map(line->io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(Component.text(line))).toList(),List.of(),List.of(io.papermc.paper.registry.data.dialog.ActionButton.builder(Component.text("원래 의뢰·안내로")).build()));
        check(actual.pages().size()>book.pages().size()&&dialog!=null&&request.phase()==io.github.jesterportfolio.hearth.phone.CompletionFlow.Phase.PENDING,"followup_native_book_Dialog_confirmation_and_pending_result_"+choice.operation());
    }
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
        .thenCompose(v->journal.load(b3,s3,()->true)).thenAccept(view->{if(plugin.getConfig().getBoolean("phone-dialog-enabled",false)){var choice=io.github.jesterportfolio.hearth.phone.PhoneCompletion.choose(catalog,view,io.github.jesterportfolio.hearth.phone.PhoneCompletion.Operation.CLAIM,"trial_checkpoint");io.github.jesterportfolio.hearth.phone.PhoneCompletion.require(catalog,view,choice);check(true,"phone_DB_pending_reward_confirmation");}})
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
    private CompletionStage<Void> selectedHelpIntegration(QuestJournal.View view){
        var data=journal.projectInformation(a1,view,()->coordinator.playable(s1));var source=io.github.jesterportfolio.hearth.phone.PhoneReaderLinks.resolve(catalog,view,"PERSON","P:facility_clue");var selection=io.github.jesterportfolio.hearth.phone.PhoneHelp.record(catalog,view,data,source);var result=io.github.jesterportfolio.hearth.phone.PhoneHelp.result(selection);
        check(result.sources().size()==1&&!result.context().contains("facility_clue")&&!result.context().contains("공개 거점 방문"),"help_one_selected_public_record_without_other_fact_or_ID");
        var prompt=new String[1];io.github.jesterportfolio.hearth.phone.AssistantService service=text->{prompt[0]=text;return io.github.jesterportfolio.hearth.phone.LocalAssistant.mock().ask(text);};var chat=new io.github.jesterportfolio.hearth.phone.PhoneChat(service,owner);var verified=new boolean[1];
        var work=chat.submit(io.github.jesterportfolio.hearth.phone.PhoneHelp.question(),result.context(),selection.section(),()->coordinator.playable(s1),()->journal.load(a1,s1,()->coordinator.playable(s1)).thenApplyAsync(latest->{io.github.jesterportfolio.hearth.phone.PhoneHelp.refresh(catalog,latest,journal.projectInformation(a1,latest,()->coordinator.playable(s1)),selection);verified[0]=true;return true;},owner));
        check(chat.busy()&&chat.history().isEmpty(),"help_response_waits_for_async_DB_authorization");
        return work.thenRun(()->{check(verified[0]&&!chat.busy()&&chat.history().size()==1&&chat.history().getFirst().answer().contains("Mock AI"),"help_Mock_answer_commits_after_fresh_DB_validation");check(prompt[0]!=null&&!prompt[0].contains(a.toString())&&!prompt[0].contains(a1.playerId().toString())&&!prompt[0].contains("facility_clue"),"help_prompt_no_scope_ID_or_internal_source_key");chat.close();});
    }
    private CompletionStage<Void> readerIntegration(){return exploreEntry(2,2).thenCompose(v->mainResult(store.readExploration(a1))).thenCompose(before->mainResult(store.readJournal(a1)).thenCompose(journalBefore->journal.load(a1,s1,()->coordinator.playable(s1)).thenCompose(view->{
        var inventory=one.capture();var associations=journal.placeFacts(a1,view);check("P:village_record".equals(associations.get("public_village")),"reader_discovered_place_private_fact_association_from_DB");
        var place=io.github.jesterportfolio.hearth.phone.PhoneReaderLinks.place(view,"public_village");var links=io.github.jesterportfolio.hearth.phone.PhoneReaderLinks.links(catalog,view,associations,place);
        check(links.stream().anyMatch(l->"EXPLORATION".equals(l.section())&&l.key().equals("P:village_record"))&&links.stream().anyMatch(l->"regions".equals(l.section())),"reader_map_to_diary_and_public_region");
        var fact=io.github.jesterportfolio.hearth.phone.PhoneReaderLinks.resolve(catalog,view,"PERSON","P:facility_clue");var contact=io.github.jesterportfolio.hearth.phone.PhoneReaderLinks.links(catalog,view,associations,fact).stream().filter(l->l.kind()==io.github.jesterportfolio.hearth.phone.PhoneReaderLinks.Kind.NPC).findFirst().orElseThrow();io.github.jesterportfolio.hearth.phone.PhoneReaderLinks.require(catalog,view,associations,fact,contact);
        check(NpcContacts.resolve(catalog,view,contact.key()).isPresent(),"reader_existing_validated_NPC_contact_boundary");
        var diary=io.github.jesterportfolio.hearth.phone.PhoneReaderLinks.resolve(catalog,view,"EXPLORATION","P:village_record");var history=io.github.jesterportfolio.hearth.phone.PhoneReaderLinks.links(catalog,view,associations,diary).stream().filter(l->"CHRONICLE".equals(l.section())).findFirst().orElseThrow();var event=io.github.jesterportfolio.hearth.phone.PhoneReaderLinks.resolve(catalog,view,history.section(),history.key());
        check(io.github.jesterportfolio.hearth.phone.PhoneReaderLinks.links(catalog,view,associations,event).stream().anyMatch(l->l.key().equals(diary.key())&&l.section().equals("EXPLORATION")),"reader_first_history_to_current_fact_without_rewrite");
        var dialog=io.github.jesterportfolio.hearth.phone.PaperPhone.compose("관련 정보",List.of(io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(Component.text(place.entry().title()))),List.of(),links.stream().map(l->io.papermc.paper.registry.data.dialog.ActionButton.builder(Component.text(l.label())).build()).toList());check(dialog!=null&&codec.equivalent(inventory,one.capture()),"reader_actual_Dialog_factory_inventory_unchanged");
        return selectedHelpIntegration(view).thenCompose(v->mainResult(store.readExploration(a1))).thenAccept(after->check(after.equals(before),"reader_exploration_places_facts_events_revision_unchanged")).thenCompose(v->mainResult(store.readJournal(a1))).thenAccept(after->check(after.equals(journalBefore),"reader_personal_notifications_tracking_preferences_unchanged"));
    }))).thenCompose(v->journal.load(a2,s2,()->coordinator.playable(s2))).thenAccept(view->{check(!journal.placeFacts(a2,view).containsKey("public_village"),"reader_other_player_no_place_fact_association");try{io.github.jesterportfolio.hearth.phone.PhoneReaderLinks.resolve(catalog,view,"EXPLORATION","P:village_record");throw new IllegalStateException("Private source leaked");}catch(IllegalArgumentException expected){check(true,"reader_other_player_private_source_rejected");}})
        .thenCompose(v->journal.load(b3,s3,()->coordinator.playable(s3))).thenAccept(view->{check(view.exploration().facts().isEmpty()&&journal.placeFacts(b3,view).isEmpty(),"reader_other_run_private_information_isolated");});}
    private CompletionStage<Void> npcTopicsRestored(){return openAll()
        .thenCompose(v->journal.load(a1,s1,()->true)).thenAccept(view->{check(view.exploration().facts().stream().anyMatch(f->f.id().equals("facility_clue"))&&view.exploration().events().stream().filter(e->e.id().equals("facility_clue")).count()==1,"topics_new_JVM_personal_fact_and_chronicle_restored");
            check(QuestScreens.screen(catalog,view,"fact","P:facility_clue",0).stream().anyMatch(b->b.label().contains("안내원")),"topics_restored_diary_details_public_to_owner");})
        .thenCompose(v->npcTopicsIsolation());}
    private CompletionStage<Void> npcTopicsIsolation(){return CompletableFuture.<Void>completedFuture(null)
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
    private CompletionStage<Void> npcPages(){return openAll().thenCompose(v->npcPagesPrepared());}
    private CompletionStage<Void> npcPagesPrepared(){return mainResult(store.readJournal(a1)).thenCompose(before->{
        var d=new QuestDialogue(catalog);World w=Bukkit.getWorld(worlds.get(a).mapping().physicalId());questAdapter.ensure(w);var entity=w.getEntitiesByClass(org.bukkit.entity.Villager.class).iterator().next();
        var options=d.options(a1.playerId(),s1,a,w.getUID(),entity.getUniqueId(),"survey_guide");var page=d.beginPages(a1.playerId(),s1,a,w.getUID(),entity.getUniqueId(),"survey_guide").orElseThrow();
        check(catalog.pages("survey_guide").size()==3,"pages_content_loaded");
        for(int i=0;i<3;i++){check(!PaperQuestAdapter.pageMessages(catalog,d,page).isEmpty(),"pages_component_"+i);if(i<2)page=d.turn(a1.playerId(),s1,a,w.getUID(),entity.getUniqueId(),"survey_guide",page.nonce(),"next").orElseThrow();}
        d.turn(a1.playerId(),s1,a,w.getUID(),entity.getUniqueId(),"survey_guide",page.nonce(),"close");check(!d.current(options.getFirst()),"pages_close_clears_quest_tokens");
        return journal.load(a1,s1,()->true).thenCompose(view->{
            var rows=view.states();var menu=QuestScreens.npc(catalog,"survey_guide",rows,id->true,0);var topics=QuestScreens.npcTopics(catalog,"survey_guide",rows,id->true,0);
            var screen=new PaperQuestUI.Screen(a1.playerId(),s1,menu,"npc","survey_guide",0);var topicScreen=new PaperQuestUI.Screen(a1.playerId(),s1,topics,"npc-topics","survey_guide",0);
            check(screen.getInventory().getSize()==54&&topicScreen.getInventory().getSize()==54&&menu.stream().anyMatch(bu->bu.action().equals("npc-read"))&&topics.stream().filter(bu->bu.action().equals("npc-topic")).count()==2,"dialogue_inventory_and_question_menu");
            var tickets=d.options(a1.playerId(),s1,a,w.getUID(),entity.getUniqueId(),"survey_guide");var scope=d.conversation(a1.playerId());
            check(d.authorizeMenu(a1.playerId(),s1,a,w.getUID(),entity.getUniqueId(),"survey_guide",scope.nonce())==scope,"dialogue_menu_bound_to_context");
            var selected=tickets.stream().filter(t->t.quest().equals("path_marker")).findFirst().orElseThrow();d.authorize(a1.playerId(),s1,a,w.getUID(),entity.getUniqueId(),QuestDialogue.Request.parse(d.command(selected,"select")));var accept=d.action(selected,"accept");
            var acceptButton=net.kyori.adventure.text.Component.text("수락").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(d.command(accept,"accept")));var declineButton=net.kyori.adventure.text.Component.text("거절").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(d.declineCommand(accept)));
            var book=PaperQuestUI.book(new QuestBookView("공개 대화",List.of("공개 시험 안내")),List.of(acceptButton,declineButton));
            check(book.pages().size()==2&&book.pages().getLast().children().stream().filter(c->c.clickEvent()!=null).count()==2,"dialogue_book_accept_decline_components");
            d.decline(a1.playerId(),s1,a,w.getUID(),entity.getUniqueId(),QuestDialogue.Request.parse(d.declineCommand(accept)));check(!d.current(accept)&&!d.current(scope),"dialogue_decline_invalidates_accept_and_menu");
            return quests.listAfterPending(a1,s1,()->true).thenAccept(after->check(rows.equals(after),"dialogue_read_and_decline_quest_rows_revision_unchanged"));
        }).thenCompose(v->mainResult(store.readJournal(a1))).thenAccept(after->check(after.equals(before),"pages_read_only_journal"));
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
    private CourseRoute.Page previousPublicRoute;private long publicGatheringGeneration;
    private void publicRoster(){storyTestRoster=new HashMap<>();if(s1!=null&&coordinator.playable(s1))storyTestRoster.put(a1.playerId(),s1);if(s2!=null&&coordinator.playable(s2))storyTestRoster.put(a2.playerId(),s2);sharedStory.rosterChanged(a1);}
    private CompletionStage<Void> verifyPublicDungeonSource(InventoryPayload expected,long revision){return mainResult(store.readInventory(a1)).thenAccept(row->check(row.revision()==revision&&codec.equivalent(row.payload(),expected)&&codec.equivalent(one.capture(),expected),"public_course_dungeon_preserves_existing_tools_and_source_payload"));}
    private CompletionStage<Void> publicRouteCheck(String step,String card){
        var before=one.capture();long revision=s1.save().revision();return journal.load(a1,s1,()->coordinator.playable(s1)).thenCompose(view->{
            var page=courseRouteView(s1,view,before,false,List.of("현재 채집 상태는 현장에서 확인하세요."),dungeonPlay.play.lines(new DungeonActor(s1)));
            var current=page.board().rows().stream().filter(PlaythroughOverview.Row::current).findFirst().orElse(null);
            check(step==null?page.board().guide().complete():current!=null&&current.key().equals(step),"public_route_current_"+Objects.toString(step,"complete"));check(Objects.equals(page.preparation(),card),"public_route_facility_"+Objects.toString(card,"none"));
            var book=PaperQuestUI.book(page.book(),List.of(Component.text("현재 시설 준비 안내"),Component.text("현재 코스 동선으로")));var dialog=io.github.jesterportfolio.hearth.phone.PaperPhone.compose("현재 코스 동선",page.lines().stream().map(line->io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(Component.text(line))).toList(),List.of(),List.of(io.papermc.paper.registry.data.dialog.ActionButton.builder(Component.text("현재 의뢰 상세")).build()));
            check(book.pages().size()>page.book().pages().size()&&dialog!=null,"public_route_native_book_Dialog_"+Objects.toString(step,"complete"));
            if(previousPublicRoute!=null&&!previousPublicRoute.board().equals(page.board()))try{CourseRoute.require(page,previousPublicRoute);throw new AssertionError("old route accepted");}catch(IllegalStateException stale){check(true,"public_route_saved_transition_rejects_previous_selection");}
            previousPublicRoute=page;check(codec.equivalent(before,one.capture())&&s1.save().revision()==revision,"public_route_reads_preserve_inventory_"+Objects.toString(step,"complete"));
            var resume=CourseResume.build(playthrough,catalog,view,sharedStory.cached(a),sharedStory.phase(a));resumeNative(resume);check(resume.available()&&resume.course().guide().equals(page.board().guide()),"resume_current_guide_matches_saved_course_"+Objects.toString(step,"complete"));
            return groundingProgress(step,card,view);
        });
    }
    private void resumeNative(CourseResume.Board board){
        var book=PaperQuestUI.book(board.book(1),List.of(Component.text("이어 하기로"),Component.text("다음 행동·담당 NPC")));var dialog=io.github.jesterportfolio.hearth.phone.PaperPhone.compose("이어 하기",List.of(io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(Component.text(board.book(1).pages().getFirst()))),List.of(),List.of(io.papermc.paper.registry.data.dialog.ActionButton.builder(Component.text("이어 하기 새로고침")).build(),io.papermc.paper.registry.data.dialog.ActionButton.builder(Component.text("현재 코스 동선")).build()));check(book.pages().size()>board.book(1).pages().size()&&dialog!=null,"resume_native_book_Dialog_navigation_objects");
    }
    private CompletionStage<Void> resumeReconnect(){
        var savedInventory=one.capture();long inventoryRevision=s1.save().revision(),generation=worlds.get(a).generation();var old=s1;var reminder=new ResumeReminder();var expected=new CourseResume.Board[1];
        return journal.load(a1,s1,()->coordinator.playable(s1)).thenAccept(view->{expected[0]=CourseResume.build(playthrough,catalog,view,sharedStory.cached(a),sharedStory.phase(a));var row=expected[0].rows().stream().filter(r->r.quest().equals("gathering_workshop_route")).findFirst().orElseThrow();check(row.kind()==CourseResume.Kind.ACTIVE&&row.revision()==2&&row.objective()==2&&reminder.claim(a1.playerId(),old,true,expected[0]).isPresent()&&reminder.claim(a1.playerId(),old,true,expected[0]).isEmpty(),"resume_active_goal_and_single_hint_before_disconnect");})
            .thenCompose(v->mainResult(coordinator.disconnect(s1))).thenRun(()->check(coordinator.playable(s2)&&worlds.get(a).generation()==generation,"resume_other_member_keeps_run_loaded_during_disconnect"))
            .thenCompose(v->{one=new Controlled();s1=connect(a1,one);return mainResult(s1.ready());}).thenRun(this::publicRoster)
            .thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenAccept(view->{var board=CourseResume.build(playthrough,catalog,view,sharedStory.cached(a),sharedStory.phase(a));var row=board.rows().stream().filter(r->r.quest().equals("gathering_workshop_route")).findFirst().orElseThrow();
                check(row.kind()==CourseResume.Kind.ACTIVE&&row.revision()==2&&row.objective()==2&&row.current()&&row.tracked(),"resume_reconnected_current_personal_goal_tracking_revision_restored");
                check(!coordinator.playable(old)&&reminder.claim(a1.playerId(),s1,true,board).isPresent()&&reminder.claim(a1.playerId(),old,coordinator.playable(old),expected[0]).isEmpty()&&!reminder.current(a1.playerId(),old),"resume_new_session_hint_and_old_session_callback_rejected");reminder.forget(a1.playerId(),old);check(reminder.current(a1.playerId(),s1)&&reminder.claim(a1.playerId(),s1,true,board).isEmpty(),"resume_old_quit_does_not_clear_new_session_hint");
                check(codec.equivalent(savedInventory,one.capture())&&s1.save().revision()==inventoryRevision&&worlds.get(a).generation()==generation,"resume_reconnect_preserves_full_inventory_revision_and_world_generation");resumeNative(board);
            }).thenCompose(v->journal.load(a2,s2,()->coordinator.playable(s2))).thenAccept(view->check(CourseResume.build(playthrough,catalog,view,sharedStory.cached(a),sharedStory.phase(a)).rows().stream().noneMatch(r->r.quest().equals("gathering_workshop_route")),"resume_P1_saved_goal_does_not_appear_for_P2"));
    }
    private Playthrough completionResumePlan;
    private CourseResume.Board completionResume(QuestJournal.View view,String id,CourseResume.Kind expected,CourseResume.Board previous){
        if(completionResumePlan==null){var yaml=YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(Objects.requireNonNull(plugin.getResource("playthrough-demo.yml")),java.nio.charset.StandardCharsets.UTF_8));completionResumePlan=Playthrough.parse(new HashMap<>(yaml.getValues(false)),catalog,sharedStory.content(),explorationContent);}
        var board=CourseResume.build(completionResumePlan,catalog,view,sharedStory.cached(a),sharedStory.phase(a));var row=board.rows().stream().filter(r->r.quest().equals(id)).findFirst();check(board.available()&&(expected==null?row.isEmpty():row.isPresent()&&row.get().kind()==expected),"resume_actual_DB_followup_kind_"+Objects.toString(expected,"none"));resumeNative(board);
        if(previous!=null)try{CourseResume.require(board,previous,1,null);throw new AssertionError("old resume accepted");}catch(IllegalStateException stale){check(true,"resume_actual_DB_transition_rejects_previous_selection");}return board;
    }
    private io.github.jesterportfolio.hearth.phone.PhoneChat groundingPending;
    private CompletableFuture<String> groundingAnswer;private CompletionStage<Void> groundingWork;private boolean groundingValidated;
    private CompletionStage<Void> groundingProgress(String step,String card,QuestJournal.View view){
        if(!"tools".equals(step))return CompletableFuture.completedFuture(null);var saved=view.states().get("gathering_workshop_route");
        if(saved.objectiveIndex()==0&&"material_site".equals(card)&&groundingPending==null){
            var data=journal.projectInformation(a1,view,()->coordinator.playable(s1));var plan=io.github.jesterportfolio.hearth.phone.PhoneGrounding.search(catalog,view,data,"quests",catalog.quest("gathering_workshop_route").title());
            check(plan.materials().stream().anyMatch(m->m.item().key().equals("gathering_workshop_route")),"grounding_inflight_source_is_actual_active_personal_goal");
            for(var m:plan.materials())if(!m.item().key().equals("gathering_workshop_route"))plan=plan.toggle(m.item().key());var expected=plan.sent();groundingAnswer=new CompletableFuture<>();groundingPending=new io.github.jesterportfolio.hearth.phone.PhoneChat(text->groundingAnswer,owner);groundingValidated=false;
            groundingWork=groundingPending.submit(expected.question(),expected.result().context(),expected.mode(),()->coordinator.playable(s1),()->journal.load(a1,s1,()->coordinator.playable(s1)).thenApplyAsync(latest->{groundingValidated=true;io.github.jesterportfolio.hearth.phone.PhoneGrounding.refresh(catalog,latest,journal.projectInformation(a1,latest,()->coordinator.playable(s1)),expected);return true;},owner),expected.scope());
            check(groundingPending.busy()&&groundingPending.history().isEmpty(),"grounding_waiting_answer_before_real_PG_goal_change");
        }else if(saved.objectiveIndex()==1&&"workbench".equals(card)&&groundingPending!=null){
            groundingAnswer.complete("이전 목표 자료로 만든 늦은 응답");return rejectQuest(groundingWork).thenRun(()->{check(groundingValidated&&groundingPending.history().isEmpty()&&!groundingPending.draft().isBlank(),"grounding_real_PG_goal_change_rejects_late_answer_keeps_question");groundingPending.close();groundingPending=null;});
        }
        return CompletableFuture.completedFuture(null);
    }
    private CompletionStage<Void> groundingIntegration(){
        var inventory=one.capture();long revision=s1.save().revision();return mainResult(store.readExploration(a1)).thenCompose(before->mainResult(store.readJournal(a1)).thenCompose(journalBefore->{
            CompletionStage<Void> work=CompletableFuture.completedFuture(null);
            for(String choice:List.of("quests","map","diary","story","single","no_match")){String mode=choice.equals("single")||choice.equals("no_match")?"diary":choice;work=work.thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenCompose(view->{
                var data=journal.projectInformation(a1,view,()->coordinator.playable(s1));var plan=choice.equals("single")?io.github.jesterportfolio.hearth.phone.PhoneGrounding.single(catalog,view,data,"개인 기록 도움",io.github.jesterportfolio.hearth.phone.PhoneHelp.select(catalog,view,data,"diary",0)):io.github.jesterportfolio.hearth.phone.PhoneGrounding.search(catalog,view,data,mode,choice.equals("no_match")?"일치없는검색단어":"공개 공유 조사 시설");
                if(plan.materials().size()>1)plan=plan.toggle(plan.materials().getLast().item().key());var expected=plan.sent();check(expected.result().sources().size()==expected.materials().size()&&(choice.equals("no_match")?expected.materials().isEmpty():!expected.materials().isEmpty()),"grounding_selected_public_materials_"+choice);
                var refs=new io.github.jesterportfolio.hearth.phone.PhoneReferences();var request=refs.begin(s1,expected);var prompt=new String[1];var chat=new io.github.jesterportfolio.hearth.phone.PhoneChat(text->{prompt[0]=text;return io.github.jesterportfolio.hearth.phone.LocalAssistant.mock().ask(text);},owner);var checked=new boolean[1];
                var response=chat.submit(expected.question(),expected.result().context(),expected.mode(),()->coordinator.playable(s1),()->journal.load(a1,s1,()->coordinator.playable(s1)).thenApplyAsync(latest->{var current=io.github.jesterportfolio.hearth.phone.PhoneGrounding.refresh(catalog,latest,journal.projectInformation(a1,latest,()->coordinator.playable(s1)),expected);check(refs.update(request,current),"grounding_same_request_reference_update_"+choice);checked[0]=true;return true;},owner),expected.scope());
                check(chat.busy()&&chat.history().isEmpty(),"grounding_answer_waits_fresh_DB_validation_"+choice);
                return response.thenRun(()->{check(checked[0]&&chat.history().size()==1&&chat.history().getFirst().answer().contains("Mock AI"),"grounding_Mock_answer_after_DB_validation_"+choice);check(prompt[0]!=null&&!prompt[0].contains(a.toString())&&!prompt[0].contains(a1.playerId().toString())&&expected.materials().stream().noneMatch(m->prompt[0].contains(m.item().key())),"grounding_prompt_has_no_owner_or_internal_material_keys_"+choice);
                    var body=io.github.jesterportfolio.hearth.phone.PhonePages.text("전송 자료",List.of(expected.result().context()));var dialog=io.github.jesterportfolio.hearth.phone.PaperPhone.compose("공개 자료 확인",List.of(io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(Component.text(body.pages().getFirst()))),List.of(),List.of(io.papermc.paper.registry.data.dialog.ActionButton.builder(Component.text("선택 자료로 질문")).build(),io.papermc.paper.registry.data.dialog.ActionButton.builder(Component.text("전송 취소")).build()));check(dialog!=null&&expected.materials().stream().allMatch(m->!io.github.jesterportfolio.hearth.phone.PhoneGrounding.detail(m).pages().isEmpty()),"grounding_native_preview_reference_details_and_cancel_"+choice);chat.close();
                });
            });}
            return work.thenCompose(v->{var chat=new io.github.jesterportfolio.hearth.phone.PhoneChat(io.github.jesterportfolio.hearth.phone.LocalAssistant.mock(),owner);var request=chat.submit("조회 실패 보존","공개 자료","diary",()->coordinator.playable(s1),()->mainResult(store.readInventory(ctx(b,999))).thenApply(row->true));return rejectQuest(request).thenRun(()->{check(chat.history().isEmpty()&&chat.canRetry()&&!chat.draft().isBlank(),"grounding_actual_PG_missing_row_read_failure_rejects_answer_keeps_question");chat.close();});})
                .thenCompose(v->mainResult(store.readExploration(a1))).thenAccept(after->check(after.equals(before),"grounding_reads_preserve_places_facts_events_and_story"))
                .thenCompose(v->mainResult(store.readJournal(a1))).thenAccept(after->check(after.equals(journalBefore)&&codec.equivalent(inventory,one.capture())&&s1.save().revision()==revision,"grounding_reads_preserve_notifications_tracking_preferences_inventory_revision"));
        }));
    }
    private NpcGuidance.Page previousPublicContacts;
    private CompletionStage<Void> publicContacts(boolean open){
        var inventory=one.capture();long revision=s1.save().revision();var world=Objects.requireNonNull(Bukkit.getWorld(worlds.get(a).mapping().physicalId()));
        return mainResult(world.getChunkAtAsync(1,0)).thenAccept(chunk->{chunk.addPluginChunkTicket(plugin);questAdapter.ensure(world);}).thenCompose(v->later(()->{})).thenCompose(v->mainResult(store.readJournal(a1))).thenCompose(before->journal.load(a1,s1,()->coordinator.playable(s1)).thenCompose(view->{
            var journey=QuestJourneyView.build(playthrough,catalog,view,sharedStory.cached(a),sharedStory.phase(a));var page=NpcGuidance.build(catalog,view,journey,sharedStory.content().guideNpcs());
            var contacts=NpcContacts.forFact(catalog,view,"P:facility_clue");check(contacts.stream().anyMatch(c->c.npc().equals("course_researcher"))==open&&page.contacts().stream().anyMatch(c->c.npc().equals("course_researcher"))==open&&view.blockedNpcs().contains("course_researcher")!=open&&journal.npcAllowed(a1,"course_researcher")==open,"npc_guidance_same_location_permission_contacts_and_directory_"+open);
            int fact=io.github.jesterportfolio.hearth.phone.PhoneLibrary.facts(view,"PERSON").indexOf(view.exploration().facts().stream().filter(f->!f.shared()&&f.id().equals("facility_clue")).findFirst().orElseThrow());var source=io.github.jesterportfolio.hearth.phone.PhoneReaderLinks.library(catalog,view,"PERSON",fact);var links=io.github.jesterportfolio.hearth.phone.PhoneReaderLinks.links(catalog,view,journal.placeFacts(a1,view),source);
            check(QuestScreens.screen(catalog,view,"fact","P:facility_clue",0).stream().anyMatch(button->button.action().equals("npc-contact")&&"facility_clue:course_researcher".equals(button.quest()))==open,"npc_guidance_book_fact_contact_filtered_"+open);
            check(links.stream().anyMatch(link->link.kind()==io.github.jesterportfolio.hearth.phone.PhoneReaderLinks.Kind.NPC&&link.key().equals("facility_clue:course_researcher"))==open,"npc_guidance_phone_reader_filtered_link_"+open);
            var entity=Bukkit.getWorld(worlds.get(a).mapping().physicalId()).getEntitiesByClass(org.bukkit.entity.Villager.class).stream().filter(e->"course_researcher".equals(e.getPersistentDataContainer().get(new NamespacedKey(plugin,"quest_demo_npc"),org.bukkit.persistence.PersistentDataType.STRING))).findFirst().orElseThrow();check(!entity.isVisibleByDefault()&&!entity.isPersistent()&&questAdapter.count(world,"course_researcher")==1,"npc_guidance_real_actor_hidden_by_default_and_unique_"+open);
            var book=PaperQuestUI.book(page.book(1),List.of(Component.text("이전 안내로")));var dialog=io.github.jesterportfolio.hearth.phone.PaperPhone.compose("인물 안내",List.of(io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(Component.text(page.book(1).pages().getFirst()))),List.of(),List.of(io.papermc.paper.registry.data.dialog.ActionButton.builder(Component.text("인물 목록으로")).build()));check(book.pages().size()>page.book(1).pages().size()&&dialog!=null,"npc_guidance_native_book_Dialog_and_return_"+open);
            for(var c:page.contacts())check(!String.join(" ",c.lines()).contains("21.5")&&!String.join(" ",c.lines()).contains(catalog.npc(c.npc()).npcId())&&catalog.topics(c.npc()).stream().noneMatch(t->String.join(" ",c.lines()).contains(t.text())),"npc_guidance_only_name_reasons_question_labels_"+c.npc());
            if(previousPublicContacts!=null){try{NpcGuidance.require(page,previousPublicContacts,null);throw new AssertionError("old NPC guide accepted");}catch(IllegalStateException stale){check(true,"npc_guidance_shared_open_rejects_previous_selection");}}previousPublicContacts=page;
            return mainResult(store.readJournal(a1)).thenAccept(after->check(before.equals(after)&&codec.equivalent(inventory,one.capture())&&s1.save().revision()==revision,"npc_guidance_reads_preserve_journal_inventory_revision_"+open));
        })).thenCompose(v->journal.load(a2,s2,()->coordinator.playable(s2))).thenAccept(view->{var page=NpcGuidance.build(catalog,view,null,Set.of());check(page.contacts().stream().noneMatch(c->c.npc().equals("course_researcher"))&&NpcContacts.forFact(catalog,view,"P:facility_clue").isEmpty(),"npc_guidance_P1_record_never_makes_P2_contact_"+open);}).whenCompleteAsync((v,error)->world.removePluginChunkTicket(1,0,plugin),owner);
    }
    private final class EscortActor implements EscortPlay.Actor{
        final PlayerSessions.Session session;final UUID physical;final long generation;EscortContent.Point point;
        EscortActor(PlayerSessions.Session session,EscortContent.Point point){this.session=session;this.point=point;var world=worlds.get(session.context().storyRunId());physical=world.mapping().physicalId();generation=world.generation();}
        public ServerProgressAccess.Context context(){return session.context();}public Object token(){return session;}public UUID world(){return physical;}public long generation(){return generation;}public boolean current(){return coordinator.playable(session)&&worlds.get(context().storyRunId()).generation()==generation;}public boolean allowed(String id){return current()&&journal.questAllowed(context(),id);}public EscortContent.Point position(){return point;}
    }
    private CompletionStage<Void> escortRead(ServerProgressAccess.Context context,QuestStore.Phase phase,long revision,int index){return mainResult(store.readQuest(context,"public_courier")).thenAccept(saved->check(saved.phase()==phase&&saved.revision()==revision&&saved.objectiveIndex()==index,"escort_PG_"+context.storyRunId()+"_"+context.playerId()+"_"+phase+"_"+revision+"_"+index));}
    private CourseOutcome.Board escortPendingOutcome;
    private CompletionStage<Void> escortPlay(){
        if(escort==null)throw new IllegalStateException("ESCORT_REQUIRED");var definition=escort.content.quests().get("public_courier");var q=catalog.quest(definition.quest());var actors=new EscortActor[3];var attempts=new EscortPlay.Attempt[3];var oldSession=new PlayerSessions.Session[1];var oldBody=new UUID[1];
        CompletionStage<Void> floor=CompletableFuture.completedFuture(null);for(UUID run:List.of(a,b))floor=floor.thenCompose(v->mainResult(Bukkit.getWorld(worlds.get(run).mapping().physicalId()).getChunkAtAsync(0,0)).thenRun(()->{var world=Bukkit.getWorld(worlds.get(run).mapping().physicalId());world.getChunkAt(0,0).addPluginChunkTicket(plugin);for(int x=0;x<=15;x++)for(int z=7;z<=10;z++){var block=world.getBlockAt(x,63,z);if(block.getType().isAir())block.setType(Material.STONE,false);else if(!block.getType().isSolid())throw new IllegalStateException("ESCORT_FLOOR_CONFLICT");}}));
        return floor.thenRun(()->{actors[0]=new EscortActor(s1,definition.legs().getFirst().start());actors[1]=new EscortActor(s2,definition.legs().getFirst().start());actors[2]=new EscortActor(s3,definition.legs().getFirst().start());})
            .thenCompose(v->rejectQuest(escort.start(actors[0],q.id(),null)))
            .thenCompose(v->{CompletionStage<Void> work=CompletableFuture.completedFuture(null);for(var actor:actors)work=work.thenCompose(x->quests.open(actor.context(),q.id(),actor.token(),actor::current)).thenCompose(open->quests.accept(open,q)).thenApply(x->null);return work;})
            .thenCompose(v->escort.start(actors[0],q.id(),null)).thenAccept(attempt->attempts[0]=attempt)
            .thenCompose(v->escort.start(actors[0],q.id(),null)).thenAccept(attempt->check(attempt==attempts[0],"escort_duplicate_start_keeps_single_owned_actor"))
            .thenCompose(v->escort.start(actors[1],q.id(),null)).thenAccept(attempt->attempts[1]=attempt)
            .thenCompose(v->escort.start(actors[2],q.id(),null)).thenAccept(attempt->attempts[2]=attempt)
            .thenRun(()->{for(int i=0;i<3;i++){var mob=(org.bukkit.entity.Villager)Bukkit.getEntity(attempts[i].body().id());check(mob!=null&&!mob.isVisibleByDefault()&&!mob.isPersistent()&&!mob.getCollidableExemptions().contains(a1.playerId()),"escort_real_private_nonpersistent_Villager_"+i);}actors[0].point=definition.legs().getFirst().end();})
            .thenCompose(v->until(()->attempts[0].phase()==EscortPlay.Phase.DONE)).thenCompose(v->escortRead(a1,QuestStore.Phase.ACTIVE,1,1))
            .thenCompose(v->escortRead(a2,QuestStore.Phase.ACTIVE,0,0)).thenCompose(v->escortRead(b3,QuestStore.Phase.ACTIVE,0,0))
            .thenRun(()->{check(attempts[0].body().position().distance(definition.legs().getFirst().end())<=1.1,"escort_actual_Pathfinder_reaches_first_goal_before_PG_credit");actors[0].point=definition.legs().getLast().start();})
            .thenCompose(v->escort.start(actors[0],q.id(),null)).thenAccept(attempt->attempts[0]=attempt)
            .thenRun(()->{var id=attempts[0].body().id();((org.bukkit.entity.LivingEntity)Objects.requireNonNull(Bukkit.getEntity(id))).setHealth(0);escort.play.died(id);})
            .thenCompose(v->until(()->attempts[0].phase()==EscortPlay.Phase.FAILED)).thenCompose(v->escortRead(a1,QuestStore.Phase.FAILED,2,1))
            .thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenAccept(view->{check(CourseResume.build(playthrough,catalog,view,sharedStory.cached(a),sharedStory.phase(a)).rows().stream().anyMatch(row->row.quest().equals(q.id())&&row.kind()==CourseResume.Kind.FAILED),"escort_death_failure_reuses_resume_retry_record");check(QuestFollowups.choices(catalog,view,q.id()).getFirst().operation()==io.github.jesterportfolio.hearth.phone.PhoneCompletion.Operation.RETRY,"escort_death_existing_retry_confirmation");})
            .thenCompose(v->quests.open(a1,q.id(),s1,()->coordinator.playable(s1))).thenCompose(open->quests.retry(open,q)).thenCompose(v->escortRead(a1,QuestStore.Phase.ACTIVE,3,1))
            .thenCompose(v->escort.start(actors[0],q.id(),null)).thenAccept(attempt->{oldBody[0]=attempt.body().id();oldSession[0]=s1;escort.play.left(s1);check(attempt.phase()==EscortPlay.Phase.CLOSED,"escort_disconnect_cleanup_preserves_active_quest");})
            .thenCompose(v->mainResult(coordinator.disconnect(s1))).thenCompose(v->{one=new Controlled();s1=connect(a1,one);return mainResult(s1.ready());}).thenRun(()->{publicRoster();actors[0]=new EscortActor(s1,definition.legs().getLast().start());escort.play.died(oldBody[0]);check(!coordinator.playable(oldSession[0]),"escort_old_session_and_body_no_longer_authorized");})
            .thenCompose(v->escortRead(a1,QuestStore.Phase.ACTIVE,3,1)).thenCompose(v->rejectQuest(escort.start(actors[0],q.id(),new QuestStore.Saved(QuestStore.Phase.ACTIVE,0,0))))
            .thenCompose(v->escort.start(actors[0],q.id(),null)).thenAccept(attempt->{attempts[0]=attempt;actors[0].point=definition.legs().getLast().end();})
            .thenCompose(v->until(()->attempts[0].phase()==EscortPlay.Phase.DONE)).thenCompose(v->escortRead(a1,QuestStore.Phase.REPORT_READY,4,1))
            .thenCompose(v->quests.open(a1,q.id(),s1,()->coordinator.playable(s1))).thenCompose(open->quests.advance(open,QuestStore.Phase.COMPLETED))
            .thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenAccept(view->{escortPendingOutcome=CourseOutcome.build(playthrough,catalog,view,sharedStory.cached(a),sharedStory.phase(a));check(escortPendingOutcome.counts().pendingRewards()==1,"escort_report_pending_reward_visible_in_course_outcome");})
            .thenCompose(v->questRewards.claim(coordinator,s1,q.id())).thenCompose(v->mainResult(store.readInventory(a1))).thenCompose(before->questRewards.claim(coordinator,s1,q.id()).thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(after->check(before.equals(after)&&craftingCount(one.capture(),Material.BREAD)==1,"escort_reward_granted_once_existing_source_save_preserved")))
            .thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenAccept(view->{var outcome=CourseOutcome.build(playthrough,catalog,view,sharedStory.cached(a),sharedStory.phase(a));check(outcome.counts().pendingRewards()==0&&outcome.counts().grantedRewards()==1,"escort_paid_reward_count_replaces_pending");try{CourseOutcome.require(outcome,escortPendingOutcome,1,null);throw new AssertionError("old outcome accepted");}catch(IllegalStateException stale){check(true,"escort_reward_transition_rejects_old_outcome");}escort.play.left(s2);escort.play.left(s3);})
            .thenCompose(v->escortRead(a2,QuestStore.Phase.ACTIVE,0,0)).thenCompose(v->escortRead(b3,QuestStore.Phase.ACTIVE,0,0)).thenCompose(v->escortCreditFailure(actors[2])).thenRun(()->{for(UUID run:List.of(a,b)){var chunk=Bukkit.getWorld(worlds.get(run).mapping().physicalId()).getChunkAt(0,0);check(chunk.getPluginChunkTickets().contains(plugin),"escort_cleanup_preserves_existing_same_plugin_chunk_ticket_"+run);chunk.removePluginChunkTicket(plugin);}});
    }
    private CompletionStage<Void> escortCreditFailure(EscortActor actor){
        // 경합은 독립 개인 행에서만 주입합니다. 이후 정상 던전이 읽는 B3 캐시를 오염시키지 않습니다.
        var isolated=ctx(b,88);Object token=new Object();var subject=new EscortPlay.Actor(){public ServerProgressAccess.Context context(){return isolated;}public Object token(){return token;}public UUID world(){return actor.world();}public long generation(){return actor.generation();}public boolean current(){return actor.current();}public boolean allowed(String q){return actor.allowed(q);}public EscortContent.Point position(){return actor.position();}};
        var progress=new PersonalQuest(store,Set.of("public_courier"),owner,SessionVerification::main);progress.rules(questRules);var probe=escort.createPlay(progress);var def=escort.content.quests().get("public_courier");actor.point=def.legs().getFirst().start();var attempt=new EscortPlay.Attempt[1];var ticker=new org.bukkit.scheduler.BukkitTask[1];
        return mainResult(store.createPlayer(isolated,"escort-cas-isolated")).thenCompose(v->mainResult(store.createInventory(isolated,fixture.apply(7))))
            .thenCompose(v->progress.open(isolated,"public_courier",token,subject::current)).thenCompose(open->progress.accept(open,catalog.quest("public_courier")))
            .thenCompose(v->mainResult(store.readInventory(isolated))).thenCompose(before->probe.start(subject,"public_courier").thenCompose(value->{attempt[0]=value;return mainResult(store.advanceQuest(isolated,"public_courier",new QuestStore.Saved(QuestStore.Phase.ACTIVE,0,0),QuestStore.Phase.ACTIVE,1,0));}).thenCompose(updated->{actor.point=def.legs().getFirst().end();ticker[0]=Bukkit.getScheduler().runTaskTimer(plugin,probe::tick,1,5);return until(()->attempt[0].phase()==EscortPlay.Phase.HOLD);}).thenCompose(v->escortRead(isolated,QuestStore.Phase.ACTIVE,1,1)).thenCompose(v->rejectQuest(probe.start(subject,"public_courier"))).thenCompose(v->mainResult(store.readInventory(isolated))).thenAccept(after->check(before.equals(after)&&attempt[0].error()!=null,"escort_real_PG_CAS_failure_keeps_source_and_HOLD_without_restart")))
            .thenCompose(v->escortRead(b3,QuestStore.Phase.ACTIVE,0,0)).whenCompleteAsync((v,error)->{if(ticker[0]!=null)ticker[0].cancel();probe.close();},owner);
    }
    private FeaturesVerification featureVerification(){return new FeaturesVerification(store,features,codec,owner,this::check,n->ctx(a,n),()->Bukkit.getWorld(worlds.get(a).mapping().physicalId()));}
    private void offlineEnvironment(boolean restored){var w=Objects.requireNonNull(Bukkit.getWorld(worlds.get(a).mapping().physicalId()));var file=worlds.get(a).mapping().directory().resolve("hearth-offline-environment.properties");try{if(!restored)w.setDifficulty(Difficulty.HARD);var original=ManualEnvironmentState.load(file,a,w.getUID(),w.getDifficulty().name());check(original.equals("HARD"),restored?"offline_new_JVM_environment_original_difficulty_preserved":"offline_environment_first_difficulty_saved");if(!restored){w.setDifficulty(Difficulty.PEACEFUL);check(ManualEnvironmentState.load(file,a,w.getUID(),"PEACEFUL").equals("HARD"),"offline_environment_UI_never_overwrites_original");}else{w.setDifficulty(Difficulty.valueOf(original));check(w.getDifficulty()==Difficulty.HARD,"offline_new_JVM_original_difficulty_restored");}}catch(java.io.IOException error){throw new CompletionException(error);}}
    private CompletionStage<Void> featuresPost(){
        return journal.load(a1,s1,()->coordinator.playable(s1)).thenAccept(view->{var w=Bukkit.getWorld(worlds.get(a).mapping().physicalId());int chunks=w.getLoadedChunks().length;var pixels=features.maps.raster(w,0,0,true,view.exploration());check(pixels.length==16384&&w.getLoadedChunks().length==chunks,"s4_native_personal_terrain_renderer_does_not_load_chunks");check(PaperCartography.zones(explorationContent,view.exploration()).stream().noneMatch(z->explorationContent.places().get(z.id()).location().kind().equals("NONE")),"s4_native_map_excludes_unpublished_coordinates");})
            .thenCompose(v->{var w=Bukkit.getWorld(worlds.get(a).mapping().physicalId());for(var station:paperTravel.content.stations().values()){var p=station.facility();var block=w.getBlockAt((int)p.x(),(int)p.y(),(int)p.z());if(!block.isEmpty()&&block.getType()!=Material.CHISELED_STONE_BRICKS)throw new IllegalStateException("TRAVEL_FIXTURE_NOT_EMPTY");block.setType(Material.CHISELED_STONE_BRICKS,false);}return journal.enterPlaces(a1,s1,()->coordinator.playable(s1),0,64,0,2,64,1.5);})
            .thenCompose(v->journal.enterPlaces(a1,s1,()->coordinator.playable(s1),2,64,1.5,11.5,64,10.5)).thenCompose(v->mainResult(store.readInventory(a1))).thenCompose(before->{var p=new TravelPort(s1,one);var t=paperTravel.travel;UUID request=UUID.randomUUID();var first=t.start(p,"village_station","outpost_station",request);check(first==t.start(p,"village_station","outpost_station",request),"s4_travel_duplicate_joins_same_request");return mainResult(first).thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(after->check(p.moves==1&&!p.frozen&&codec.equivalent(before.payload(),after.payload())&&craftingCount(after.payload(),Material.WOODEN_PICKAXE)==1,"s4_travel_approved_free_instant_controlled_port_preserves_tools"));});
    }
    private CompletionStage<Void> publicCourseStart(){
        if(!publicCourse||courseRoute==null)throw new IllegalStateException("PUBLIC_COURSE_REQUIRED");
        check(playthrough.steps().size()==(escort==null?10:11)&&courseRoute.bindings().size()==7,"public_course_packaged_plan_and_facility_links_validated");
        var defaults=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(Objects.requireNonNull(plugin.getResource("config.yml")),java.nio.charset.StandardCharsets.UTF_8));
        check("playthrough-demo.yml".equals(defaults.getString("playthrough-content-file"))&&defaults.getKeys(false).stream().filter(key->key.endsWith("-enabled")).allMatch(key->!defaults.getBoolean(key)),"public_course_default_activation_gates_remain_disabled");
        return openAll().thenCompose(v->features==null?CompletableFuture.completedFuture(null):quests.open(a1,"survey_marker",s1,()->coordinator.playable(s1)).thenCompose(q->expectQuestCode(quests.accept(q,catalog.quest("survey_marker")),"CUTSCENE_REQUIRED")).thenCompose(x->featureVerification().prepare(a1,s1,a2,s2))).thenRun(()->{publicRoster();facilityBlocks(a,true);facilityBlocks(b,true);})
            .thenCompose(v->publicRouteCheck("survey",null)).thenCompose(v->finishCourseQuest(a1,s1,"survey_marker"))
            .thenCompose(v->expectQuestCode(sharedStory.advance(a1,s1,()->true),"CONDITIONS_UNMET"))
            .thenCompose(v->finishCourseQuest(a2,s2,"survey_marker")).thenCompose(v->sharedStory.advance(a1,s1,()->true)).thenCompose(v->sharedStory.advance(a1,s1,()->true))
            .thenCompose(v->publicRouteCheck("materials","material_site"))
            .thenCompose(v->journal.topic(a1,s1,()->coordinator.playable(s1),"survey_guide","facility_clue")).thenCompose(v->publicContacts(false))
            .thenCompose(v->expectQuestCode(journal.topic(a1,s1,()->coordinator.playable(s1),"course_researcher","course_records"),"NPC_UNAVAILABLE"));
    }
    private CompletionStage<Void> publicCourseVote(){
        publicRoster();return publicRouteCheck("path",null).thenCompose(v->finishCourseQuest(a1,s1,"path_marker"))
            .thenCompose(v->publicRouteCheck("shared_open",null)).thenCompose(v->finishCourseQuest(a2,s2,"path_marker"))
            .thenCompose(v->sharedStory.openVote(a1,s1,()->true)).thenCompose(vote->{storyVote=vote;return sharedStory.cast(a1,s1,()->true,vote.id(),"route_one");})
            .thenRun(()->check(!Boolean.TRUE.equals(sharedStory.cached(a).story().explorationOpen()),"public_course_one_vote_does_not_open_exploration"))
            .thenCompose(v->sharedStory.cast(a2,s2,()->true,storyVote.id(),"route_one"))
            .thenRun(()->check(Boolean.TRUE.equals(sharedStory.cached(a).story().explorationOpen())&&!Boolean.TRUE.equals(sharedStory.cached(b).story().explorationOpen()),"public_course_shared_branch_opens_only_own_run"))
            .thenCompose(v->publicRouteCheck("dungeon",null)).thenCompose(v->publicContacts(true)).thenCompose(v->groundingIntegration());
    }
    private CompletionStage<Void> publicCourseFinish(){
        publicRoster();var place=explorationContent.places().get("survey_place");var area=place.bounds();double x=(area.minX()+area.maxX())/2,y=(area.minY()+area.maxY())/2,z=(area.minZ()+area.maxZ())/2;
        return gathering.bind(a1,false,gatheringPeople(a)).thenAccept(binding->check(binding.dungeon().snapshot().generation()==publicGatheringGeneration&&Bukkit.getWorld(worlds.get(a).mapping().physicalId()).getBlockAt(6,64,6).getType()==Material.CRAFTING_TABLE&&GatheringStatus.lines(binding.dungeon().snapshot(),0,false,gatheringClock.get()).stream().anyMatch(line->line.contains("다음 입장 전에 초기화")),"public_course_gathering_generation_reset_status_and_workbench_survive_final_reload")).thenCompose(v->journal.enterPlaces(a1,s1,()->coordinator.playable(s1),area.minX()-1,y,z,x,y,z))
            .thenCompose(v->sharedStory.advance(a1,s1,()->true)).thenCompose(v->publicRouteCheck(null,null))
            .thenCompose(v->courseGuide(a2,new Object())).thenAccept(guide->check(!guide.complete(),"public_course_P1_completion_does_not_complete_P2_itinerary"))
            .thenCompose(v->questRead(b3,"survey_marker",QuestStore.Phase.NOT_ACCEPTED,-1))
            .thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(row->check(codec.equivalent(row.payload(),one.capture())&&craftingCount(one.capture(),Material.WOODEN_PICKAXE)==1&&craftingCount(one.capture(),Material.STONE_PICKAXE)==1,"public_course_crafted_tools_survive_dungeon_and_world_reconnect"))
            .thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenAccept(view->check(view.exploration().place("public_material_site").filter(p->p.discovered()).isPresent()&&view.exploration().place("public_workshop").filter(p->p.discovered()).isPresent()&&view.exploration().facts().stream().anyMatch(f->f.id().equals("gathering_workshop_report")),"public_course_personal_visits_and_report_diary_preserved")).thenCompose(v->escort==null?CompletableFuture.completedFuture(null):courseOutcomeCheck()).thenCompose(v->features==null?CompletableFuture.completedFuture(null):featuresPost().thenRun(()->offlineEnvironment(false)));
    }
    private CompletionStage<Void> courseOutcomeCheck(){
        var inventory=one.capture();long revision=s1.save().revision();
        return mainResult(store.readJournal(a1)).thenCompose(before->journal.load(a1,s1,()->coordinator.playable(s1)).thenCompose(view->{
            var board=CourseOutcome.build(playthrough,catalog,view,sharedStory.cached(a),sharedStory.phase(a));var counts=board.counts();
            check(board.available()&&board.course().guide().complete()&&counts.completed()==5&&counts.skipped()==0&&counts.active()==0&&counts.reporting()==0&&counts.failed()==0,"outcome_final_personal_completion_distinct_from_skip_and_progress");
            check(counts.discovered()==3&&counts.visited()==3&&counts.sharedReached()==2&&counts.sharedCompleted()==2,"outcome_final_own_visits_and_unique_shared_stages");
            check(counts.grantedRewards()>=1&&counts.pendingRewards()==0,"outcome_final_reward_record_after_explicit_claim");
            for(int page=1;page<=board.pages();page++){
                var book=PaperQuestUI.book(board.book(page),List.of(Component.text("코스 결과로")));var dialog=io.github.jesterportfolio.hearth.phone.PaperPhone.compose("코스 결과",board.book(page).pages().stream().map(line->io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(Component.text(line))).toList(),List.of(),List.of(io.papermc.paper.registry.data.dialog.ActionButton.builder(Component.text("결과로")).build()));
                check(!book.pages().isEmpty()&&dialog!=null,"outcome_native_book_Dialog_page_"+page);
                for(var item:board.page(page))if(item.readable()){
                    CourseOutcome.require(board,board,page,item);var row=item.source();
                    if(row.quest()!=null)check(QuestBookView.accepted(catalog,view.states(),view.exploration().blockedQuests(),row.quest()).isPresent(),"outcome_existing_personal_detail_"+row.key());
                    else{var screen=CourseRecordView.open(playthrough,catalog,view,journal.placeFacts(a1,view),board.course(),board.course(),row,board.course().rows().indexOf(row)/PlaythroughOverview.PAGE_SIZE+1);var fresh=CourseRecordView.navigate(playthrough,catalog,view,journal.placeFacts(a1,view),board.course(),screen,CourseRecordView.Action.REFRESH,null);check(fresh.row().equals(row)&&!fresh.record().text().isEmpty(),"outcome_existing_record_refresh_and_return_"+row.key());}
                }
            }
            return mainResult(store.readJournal(a1)).thenAccept(after->check(before.equals(after)&&codec.equivalent(inventory,one.capture())&&s1.save().revision()==revision,"outcome_read_only_preserves_journal_inventory_revision"));
        })).thenCompose(v->journal.load(a2,new Object(),()->true)).thenAccept(view->{var board=CourseOutcome.build(playthrough,catalog,view,sharedStory.cached(a),sharedStory.phase(a));check(!board.course().guide().complete()&&board.counts().completed()<5&&board.counts().grantedRewards()==0,"outcome_P1_completion_and_reward_do_not_complete_P2");});
    }
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
    private void courseRecordScreens(QuestJournal.View view){
        var shared=sharedStory.cached(a);var definitions=new ArrayList<Playthrough>();var placeFacts=journal.placeFacts(a1,view);
        if(!view.exploration().places().isEmpty()){var place=view.exploration().places().stream().filter(p->placeFacts.containsKey(p.key())).findFirst().orElse(view.exploration().places().getFirst());definitions.add(new Playthrough(List.of(new Playthrough.Step("public_place_record",Playthrough.Kind.PLACE,place.key(),"공개 장소","현재 공개 정보"))));}
        if(shared.story().stage()!=null)definitions.add(new Playthrough(List.of(new Playthrough.Step("current_shared_record",Playthrough.Kind.STAGE,shared.story().stage(),"공유 단계","현재 공개 정보"))));
        var before=one.capture();for(var plan:definitions){
            var board=PlaythroughOverview.build(plan,catalog,view,shared,sharedStory.phase(a));var row=board.rows().getFirst();var screen=CourseRecordView.open(plan,catalog,view,placeFacts,board,board,row,1);
            if(row.kind()==PlaythroughOverview.Kind.EXPLORATION&&placeFacts.containsKey(screen.record().target().key())){
                var factLink=screen.page().links().stream().filter(l->Set.of("STORY","PERSON","EXPLORATION").contains(l.target().section())).findFirst().orElseThrow();
                var fact=CourseRecordView.navigate(plan,catalog,view,placeFacts,board,screen,CourseRecordView.Action.RELATED,factLink);
                check(fact.record().target().key().startsWith("P:"),"course_record_real_DB_discovery_uses_personal_fact");
                var chronicle=fact.page().links().stream().filter(l->l.target().section().equals("CHRONICLE")).findFirst().orElseThrow();
                var event=CourseRecordView.navigate(plan,catalog,view,placeFacts,board,fact,CourseRecordView.Action.RELATED,chronicle);
                check(event.record().target().key().startsWith("P:")&&CourseRecordView.navigate(plan,catalog,view,placeFacts,board,event,CourseRecordView.Action.BACK,null).record().equals(fact.record()),"course_record_real_DB_first_chronicle_and_return");
            }
            var book=PaperQuestUI.book(screen.page().book(screen.record().title()),List.of(Component.text("이전 정보"),Component.text("코스 진행판으로")));
            check(book.pages().size()==screen.page().book(screen.record().title()).pages().size()+1,"course_record_native_book_"+row.kind());
            var dialog=io.github.jesterportfolio.hearth.phone.PaperPhone.compose(screen.record().title(),screen.page().lines().stream().map(line->io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(Component.text(line))).toList(),List.of(),List.of(io.papermc.paper.registry.data.dialog.ActionButton.builder(Component.text("코스 진행판으로")).build()));
            check(dialog!=null,"course_record_native_Dialog_"+row.kind());
            var refreshed=CourseRecordView.navigate(plan,catalog,view,journal.placeFacts(a1,view),board,screen,CourseRecordView.Action.REFRESH,null);check(refreshed.record().equals(screen.record()),"course_record_refresh_same_public_snapshot_"+row.kind());
        }
        check(codec.equivalent(before,one.capture()),"course_record_views_preserve_41slots");
    }
    private void overviewScreens(Playthrough plan,QuestJournal.View view){
        courseRecordScreens(view);
        var before=one.capture();var id=plan.steps().getFirst().reference();var row=view.states().get(id);
        var board=PlaythroughOverview.build(plan,catalog,view,sharedStory.cached(a),sharedStory.phase(a));var course=board.page(1);var selected=course.rows().getFirst();PlaythroughOverview.require(board,board,selected);
        check(selected.current()==!row.satisfies()&&selected.tracked()==view.journal().tracked().contains(id)&&selected.quest().equals(id)&&selected.revision()==row.revision()&&selected.status().equals(QuestListView.status(row.phase())),"course_overview_own_DB_revision_tracking");
        check(QuestJourneyView.build(plan,catalog,view,sharedStory.cached(a),sharedStory.phase(a)).links().stream().anyMatch(link->link.action()==QuestJourneyView.Action.COURSE),"course_overview_registered_journey_link");
        var courseBook=PaperQuestUI.book(course.book(),List.of(Component.text("의뢰 상세"),Component.text("다음 행동 안내"),Component.text("진행판 새로고침")));
        check(courseBook.pages().size()==course.book().pages().size()+1,"course_overview_native_book_keeps_paginated_records_and_actions");
        var courseDialog=io.github.jesterportfolio.hearth.phone.PaperPhone.compose("공개 코스 진행판",course.lines().stream().map(line->io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(Component.text(line))).toList(),List.of(),List.of(io.papermc.paper.registry.data.dialog.ActionButton.builder(Component.text("의뢰 상세")).build()));
        check(courseDialog!=null,"course_overview_native_Dialog_records_and_details");
        var blockedBoard=PlaythroughOverview.build(plan,catalog,view,sharedStory.cached(a),SharedStory.Phase.HOLD);
        try{PlaythroughOverview.require(blockedBoard,board,selected);throw new AssertionError("HOLD course detail accepted");}catch(IllegalStateException expected){check(expected.getMessage().equals("STALE_COURSE_OVERVIEW"),"course_overview_HOLD_rejects_shown_details");}
        check(codec.equivalent(before,one.capture()),"course_overview_native_reading_keeps_41slots");
    }
    private void journeyScreens(QuestJournal.View view){
        var plan=new Playthrough(List.of(new Playthrough.Step("current",Playthrough.Kind.QUEST,content.id(),"공개 안내","현재 시험 목표 안내")));
        var before=one.capture();var page=QuestJourneyView.build(plan,catalog,view,sharedStory.cached(a),sharedStory.phase(a));
        check(page.links().stream().anyMatch(link->link.action()==QuestJourneyView.Action.DETAIL)&&page.links().stream().anyMatch(link->link.action()==QuestJourneyView.Action.TALK),"journey_current_goal_detail_and_report_link");
        check(page.links().stream().anyMatch(link->link.action()==QuestJourneyView.Action.UNTRACK),"journey_current_tracking_action_from_DB");
        overviewScreens(plan,view);
        var actions=page.links().stream().map(link->(Component)Component.text(link.label()).clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/hearthjourney"))).toList();
        var book=PaperQuestUI.book(new QuestBookView(page.guide().title(),page.guide().lines()),actions);
        check(book.pages().size()==page.guide().lines().size()+(actions.size()+2)/3,"journey_book_action_pages_three_buttons_each");
        check(book.pages().stream().flatMap(c->c.children().stream()).filter(c->c.clickEvent()!=null).count()==actions.size(),"journey_book_all_action_components_preserved");
        try{PaperQuestUI.book(new QuestBookView("과도한 페이지",Collections.nCopies(99,"공개 내용")),Collections.nCopies(4,Component.text("확인")));throw new IllegalStateException("Oversized book accepted");}catch(IllegalArgumentException expected){check(expected.getMessage().equals("QUEST_BOOK_TOO_LONG"),"journey_book_total_page_budget_rejected_without_truncation");}
        var buttons=QuestScreens.npcJourney(QuestScreens.npc(catalog,content.npcId(),view.states(),q->true,0),true);var inventory=new PaperQuestUI.Screen(a1.playerId(),s1,buttons,"npc",content.npcId(),0);
        check(inventory.getInventory().getItem(39)!=null&&buttons.size()==buttons.stream().map(QuestScreens.Button::slot).distinct().count(),"journey_NPC_menu_single_entry_no_overlap");
        var dialog=io.github.jesterportfolio.hearth.phone.PaperPhone.compose(page.guide().title(),page.guide().lines().stream().map(line->io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(Component.text(line))).toList(),List.of(),page.links().stream().map(link->io.papermc.paper.registry.data.dialog.ActionButton.builder(Component.text(link.label())).build()).toList());check(dialog!=null,"journey_actual_Dialog_factory_from_current_actions");
        var held=QuestJourneyView.build(plan,catalog,view,sharedStory.cached(a),SharedStory.Phase.HOLD);try{QuestJourneyView.require(held,page,page.links().getFirst());throw new IllegalStateException("HOLD action accepted");}catch(IllegalStateException expected){check(expected.getMessage().equals("STALE_JOURNEY_ACTION"),"journey_HOLD_stale_action_rejected");}
        check(codec.equivalent(before,one.capture()),"journey_reading_keeps_inventory_snapshot");
    }
    private CompletionStage<Void> navigationPlay(){
        return openAll().thenCompose(v->quests.open(a1,content.id(),s1,()->coordinator.playable(s1))).thenCompose(q->{qa=q;return quests.accept(q,content);})
            .thenCompose(v->{var area=content.goals().getFirst().area();return quests.reach(qa,content,(area.minX()+area.maxX())/2,(area.minY()+area.maxY())/2,(area.minZ()+area.maxZ())/2);})
            .thenCompose(v->journal.track(a1,s1,()->coordinator.playable(s1),content.id(),true)).thenCompose(view->{
                check(view.states().get(content.id()).phase()==QuestStore.Phase.REPORT_READY,"navigation_report_ready");journeyScreens(view);var inventory=one.capture();
                for(String page:List.of("quests","reporting","tracked","completed","notices")){
                    var buttons=QuestScreens.screen(catalog,view,page,null,0);var screen=new PaperQuestUI.Screen(a1.playerId(),s1,buttons,page,null,0);
                    check(screen.getInventory().getSize()==54&&buttons.size()==buttons.stream().map(QuestScreens.Button::slot).distinct().count(),"navigation_screen_"+page);
                }
                check(QuestScreens.screen(catalog,view,"detail",content.id(),0).stream().anyMatch(b->b.action().equals("npc-dialogue")),"navigation_report_conversation_link");
                var bookView=QuestBookView.accepted(catalog,view.states(),view.exploration().blockedQuests(),content.id()).orElseThrow();
                var book=PaperQuestUI.book(bookView,List.of(Component.text("[퀘스트 목록]")));
                check(book.pages().size()==bookView.pages().size()+1&&book.title().equals(Component.text(content.title())),"quest_book_real_Adventure_title_pages");
                var npcButtons=QuestScreens.npc(catalog,content.npcId(),view.states(),q->journal.questAllowed(a1,q.id()),0);
                var npcScreen=new PaperQuestUI.Screen(a1.playerId(),s1,npcButtons,"npc",content.npcId(),0);
                var choice=npcButtons.stream().filter(button->content.id().equals(button.quest())&&button.action().equals("npc-select")).findFirst().orElseThrow();
                var icon=npcScreen.getInventory().getItem(choice.slot());
                if(plugin.getConfig().getBoolean("phone-dialog-enabled",false)){
                    var dialog=io.github.jesterportfolio.hearth.phone.PaperPhone.compose("HEARTH OS",List.of(io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(Component.text("공개 Dialog 시험"))),List.of(io.papermc.paper.registry.data.dialog.input.DialogInput.text("question",Component.text("질문")).maxLength(256).build()),List.of(io.papermc.paper.registry.data.dialog.ActionButton.builder(Component.text("홈")).build()));
                    check(dialog!=null,"phone_actual_Paper_Dialog_input_factory");
                    var data=journal.projectInformation(a1,view,()->coordinator.playable(s1));
                    var page=io.github.jesterportfolio.hearth.phone.PhonePages.list(data,"quests",1);
                    check(!page.entries().isEmpty(),"phone_apps_own_accepted_quest_list");
                    var detail=io.github.jesterportfolio.hearth.phone.PhonePages.detail(data,"quests",page.entries().getFirst().index());
                    check(!detail.pages().isEmpty(),"phone_apps_own_quest_detail");
                    var linked=io.github.jesterportfolio.hearth.phone.PhoneQuestLinks.quests(catalog,view).stream().filter(q->q.id().equals(content.id())).findFirst().orElseThrow();
                    check(linked.reporting()&&linked.tracked()&&linked.trackable(),"phone_quest_track_and_near_NPC_link");
                    check(!io.github.jesterportfolio.hearth.phone.PhoneQuestLinks.notices(catalog,view).isEmpty(),"phone_visible_quest_notifications");
                }
                check(icon.getType()==Material.BOOK&&icon.getItemMeta().displayName().equals(Component.text(choice.label())),"quest_NPC_GUI_item_title_status_book_icon");
                check(npcButtons.stream().anyMatch(button->content.id().equals(button.quest())&&button.action().equals("npc-action")&&button.revision()==1),"quest_NPC_GUI_report_action_current_revision");
                check(QuestBookView.accepted(catalog,view.states(),view.exploration().blockedQuests(),"unknown").isEmpty(),"quest_book_unknown_no_information");
                check(codec.equivalent(one.capture(),inventory),"navigation_inventory_unchanged");
                return questRead(a1,QuestStore.Phase.REPORT_READY,1);
            }).thenCompose(v->quests.advance(qa,QuestStore.Phase.COMPLETED)).thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1)))
            .thenCompose(view->{check(!view.journal().tracked().contains(content.id()),"navigation_completed_tracking_cleared");check(QuestListView.filtered(catalog,view.states(),view.journal().tracked(),view.exploration().blockedQuests(),QuestListView.Filter.REPORTING).isEmpty(),"navigation_completed_removed_from_report_list");
                return questRead(a1,QuestStore.Phase.COMPLETED,2).thenCompose(v->questRead(a2,QuestStore.Phase.NOT_ACCEPTED,-1)).thenCompose(v->questRead(ctx(b,1),QuestStore.Phase.NOT_ACCEPTED,-1)).thenCompose(v->{
                    if(!plugin.getConfig().getBoolean("phone-dialog-enabled",false))return CompletableFuture.completedFuture(null);
                    var before=one.capture();return journal.setting(a1,s1,()->coordinator.playable(s1),"minimap",false)
                        .thenCompose(saved->{check(!saved.journal().preferences().minimapEnabled(),"phone_settings_DB_marker_toggle");return mainResult(store.readJournal(a2));})
                        .thenCompose(other->{check(other.preferences().minimapEnabled(),"phone_settings_other_player_unchanged");return mainResult(store.readJournal(ctx(b,1)));})
                        .thenAccept(other->{check(other.preferences().minimapEnabled(),"phone_settings_other_run_unchanged");check(codec.equivalent(before,one.capture()),"phone_settings_inventory_unchanged");})
                        .thenCompose(x->expect(journal.readNotice(a2,s2,()->coordinator.playable(s2),content.id(),0),"IllegalArgumentException"))
                        .thenCompose(x->journal.readNotice(a1,s1,()->coordinator.playable(s1),content.id(),0))
                        .thenCompose(saved->{var target=io.github.jesterportfolio.hearth.phone.PhoneQuestLinks.notices(catalog,saved).stream().filter(n->n.source().revision()==0&&n.source().quest().equals(content.id())).findFirst().orElseThrow();check(target.source().read()&&target.section().equals("quests"),"phone_notice_read_saved_and_target_resolved");return journal.readAll(a1,s1,()->coordinator.playable(s1));})
                        .thenCompose(saved->{check(io.github.jesterportfolio.hearth.phone.PhoneQuestLinks.notices(catalog,saved).stream().allMatch(n->n.source().read()),"phone_all_notices_read_DB");return mainResult(store.readJournal(a2));})
                        .thenCompose(other->{check(other.notices().isEmpty(),"phone_notice_other_player_unchanged");return mainResult(store.readJournal(ctx(b,1)));})
                        .thenCompose(other->{check(other.notices().isEmpty(),"phone_notice_other_run_unchanged");check(codec.equivalent(before,one.capture()),"phone_notice_inventory_unchanged");return questRead(a1,QuestStore.Phase.COMPLETED,2).thenCompose(v2->phoneLibrary());});
                });
            });
    }
    private CompletionStage<Void> phoneLibrary(){
        var before=one.capture();
        var fact=new io.github.jesterportfolio.hearth.exploration.ExplorationContent.Fact("phone_personal_note","PERSON","공개 시험 인물 기록","합성 요약","개인이 확인한 시험 정보",1,true);
        var updated=new io.github.jesterportfolio.hearth.exploration.ExplorationContent.Fact(fact.id(),fact.category(),fact.title(),fact.summary(),"갱신된 공개 합성 정보",2,true);
        var shared=new io.github.jesterportfolio.hearth.exploration.ExplorationContent.Fact("phone_shared_note","STORY","공개 시험 공동 기록","공유 요약","시험용 공유 기록",1,true);
        return mainResult(store.recordFact(a1,fact,false)).thenCompose(v->mainResult(store.recordFact(a1,updated,false))).thenCompose(v->mainResult(store.recordFact(a1,shared,true)))
            .thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenAccept(view->{
                journal.projectInformation(a1,view,()->coordinator.playable(s1));
                for(String section:List.of("tracked","reporting","completed","STORY","PERSON","EXPLORATION","CHRONICLE","regions","members","shared","conditions","branches")){
                    var rows=io.github.jesterportfolio.hearth.phone.PhoneLibrary.rows(catalog,view,section);
                    for(var row:rows)if(row.targetSection()==null){var text=io.github.jesterportfolio.hearth.phone.PhonePages.text(row.title(),row.lines());check(!text.pages().isEmpty(),"phone_library_detail_"+section);}
                }
                check(io.github.jesterportfolio.hearth.phone.PhoneLibrary.rows(catalog,view,"PERSON").stream().anyMatch(row->row.lines().contains("갱신된 공개 합성 정보")),"phone_library_updated_personal_fact_DB");
                var approved=journal.projectInformation(a1,view,()->coordinator.playable(s1));
                var context=io.github.jesterportfolio.hearth.phone.PhoneContext.from(approved,"diary");
                check(context.contains("갱신된 공개 합성 정보")&&!context.contains(a1.playerId().toString())&&!context.contains(a.toString()),"phone_AI_own_public_context_no_scope_IDs");
                var retrieval=io.github.jesterportfolio.hearth.phone.PhoneRetrieval.find(approved,"diary","갱신된 합성 정보");
                check(retrieval.sources().stream().anyMatch(source->source.text().contains("갱신된 공개 합성 정보")),"phone_AI_retrieval_own_record_and_source");
                check(!retrieval.context().contains(a1.playerId().toString()),"phone_AI_retrieval_no_player_ID");
                check(io.github.jesterportfolio.hearth.phone.PhoneContext.from(approved,"none").isEmpty(),"phone_AI_general_question_no_game_context");
                var mock=io.github.jesterportfolio.hearth.phone.LocalAssistant.mock().ask(new io.github.jesterportfolio.hearth.phone.AssistantRequest("도움",List.of(),context)).toCompletableFuture();
                check(mock.isDone()&&mock.getNow("").contains("Mock AI"),"phone_AI_Mock_structured_request");
                check(io.github.jesterportfolio.hearth.phone.PhoneLibrary.rows(catalog,view,"CHRONICLE").size()==3,"phone_library_old_and_new_events_DB");
                check(codec.equivalent(before,one.capture()),"phone_library_inventory_preserved");
            }).thenCompose(v->journal.load(a2,s2,()->coordinator.playable(s2))).thenAccept(view->{
                check(io.github.jesterportfolio.hearth.phone.PhoneLibrary.rows(catalog,view,"PERSON").isEmpty(),"phone_library_other_player_no_private_fact");
                check(!io.github.jesterportfolio.hearth.phone.PhoneContext.from(journal.projectInformation(a2,view,()->coordinator.playable(s2)),"diary").contains("갱신된 공개 합성 정보"),"phone_AI_other_player_no_private_context");
                check(io.github.jesterportfolio.hearth.phone.PhoneRetrieval.find(journal.projectInformation(a2,view,()->coordinator.playable(s2)),"diary","갱신된 정보").sources().isEmpty(),"phone_AI_retrieval_other_player_no_private_source");
                check(io.github.jesterportfolio.hearth.phone.PhoneLibrary.rows(catalog,view,"STORY").size()==1,"phone_library_same_run_shared_fact");
            }).thenCompose(v->journal.load(ctx(b,1),new Object(),()->true)).thenAccept(view->{check(view.exploration().facts().isEmpty()&&view.exploration().events().isEmpty(),"phone_library_other_run_no_records");check(!io.github.jesterportfolio.hearth.phone.PhoneContext.from(journal.projectInformation(ctx(b,1),view,()->true),"diary").contains("공개 시험 공동 기록"),"phone_AI_other_run_no_context");}).thenCompose(v->phoneRequestLifecycle()).thenCompose(v->phoneCachedGrounding());
    }
    private io.github.jesterportfolio.hearth.phone.PhoneGrounding.Plan phoneCachePlan(QuestJournal.View view){
        var data=journal.projectInformation(a1,view,()->coordinator.playable(s1));int index=java.util.stream.IntStream.range(0,view.exploration().facts().size()).filter(i->view.exploration().facts().get(i).id().equals("phone_personal_note")).findFirst().orElseThrow();
        return io.github.jesterportfolio.hearth.phone.PhoneGrounding.single(catalog,view,data,"공개 기록을 설명해.",io.github.jesterportfolio.hearth.phone.PhoneHelp.select(catalog,view,data,"diary",index));
    }
    private CompletionStage<Boolean> phoneCacheValidate(io.github.jesterportfolio.hearth.phone.PhoneGrounding.Plan expected,int[] reads){
        reads[0]++;return journal.load(a1,s1,()->coordinator.playable(s1)).thenApplyAsync(view->{io.github.jesterportfolio.hearth.phone.PhoneGrounding.refresh(catalog,view,journal.projectInformation(a1,view,()->coordinator.playable(s1)),expected);return true;},owner);
    }
    private CompletionStage<Void> phoneCachedGrounding(){
        var inventory=one.capture();long revision=s1.save().revision();int[] generated={0},identities={0},reads={0};var bad=new java.util.concurrent.atomic.AtomicBoolean();var identity=new java.util.concurrent.atomic.AtomicReference<CompletableFuture<String>>();
        var service=new io.github.jesterportfolio.hearth.phone.AssistantService(){
            public boolean allowsCache(){return true;}
            public CompletionStage<String> ask(String text){throw new AssertionError("Structured request required");}
            public CompletionStage<String> ask(io.github.jesterportfolio.hearth.phone.AssistantRequest request){generated[0]++;check(request.sources()==1&&!request.prompt().contains(a1.playerId().toString())&&!request.prompt().contains("phone_personal_note"),"grounded_cache_only_public_prompt_and_source_count");return CompletableFuture.completedFuture(bad.get()?"잘못된 자료 [자료 2]":"공개 기록의 내용입니다. [자료 1]");}
            public CompletionStage<String> confirmCached(String answer){identities[0]++;return identity.get()==null?CompletableFuture.completedFuture(answer):identity.get();}
        };
        var chat=new io.github.jesterportfolio.hearth.phone.PhoneChat(service,owner);var beforeHistory=new java.util.concurrent.atomic.AtomicReference<List<io.github.jesterportfolio.hearth.phone.PhoneChat.Turn>>();
        return mainResult(store.readJournal(a1)).thenCompose(journalBefore->mainResult(store.readExploration(a2)).thenCompose(otherBefore->journal.load(a1,s1,()->coordinator.playable(s1)).thenCompose(view->{
            var plan=phoneCachePlan(view);CompletionStage<Void> sequence=CompletableFuture.completedFuture(null);
            for(int i=0;i<5;i++)sequence=sequence.thenCompose(v->chat.submit(plan.question(),plan.result().context(),plan.mode(),()->coordinator.playable(s1),()->phoneCacheValidate(plan,reads),plan.scope()));
            return sequence.thenRun(()->{check(generated[0]==4&&identities[0]==1&&reads[0]==5&&chat.cacheStats().hits()==1,"grounded_cache_hit_still_checks_identity_and_real_PG");check(io.github.jesterportfolio.hearth.phone.PhoneReferences.label(plan,plan.selected().getFirst().item().key()).startsWith("[자료 1]"),"grounded_cache_public_reference_number_matches_material");beforeHistory.set(chat.history());})
                .thenCompose(v->rejectQuest(chat.submit(plan.question(),plan.result().context(),plan.mode(),()->coordinator.playable(s1),()->mainResult(store.readInventory(ctx(b,9999))).thenApply(row->true),plan.scope())))
                .thenRun(()->check(chat.cacheStats().entries()==0&&chat.history().equals(beforeHistory.get())&&chat.draft().equals(plan.question()),"grounded_cache_real_PG_failure_evicts_without_history_or_draft_loss"))
                .thenCompose(v->chat.submit(plan.question(),plan.result().context(),plan.mode(),()->coordinator.playable(s1),()->phoneCacheValidate(plan,reads),plan.scope()))
                .thenCompose(v->{
                    identity.set(new CompletableFuture<>());beforeHistory.set(chat.history());int prior=generated[0];var pending=chat.submit(plan.question(),plan.result().context(),plan.mode(),()->coordinator.playable(s1),()->phoneCacheValidate(plan,reads),plan.scope());
                    check(chat.busy()&&generated[0]==prior,"grounded_cache_waits_identity_without_generation");
                    var updated=new io.github.jesterportfolio.hearth.exploration.ExplorationContent.Fact("phone_personal_note","PERSON","공개 시험 인물 기록","합성 요약","갱신된 공개 합성 정보",3,true);
                    return mainResult(store.recordFact(a1,updated,false)).thenRun(()->identity.get().complete("공개 기록의 내용입니다. [자료 1]")).thenCompose(x->rejectQuest(pending)).thenRun(()->{identity.set(null);check(generated[0]==prior&&chat.cacheStats().entries()==0&&chat.history().equals(beforeHistory.get()),"grounded_cache_real_PG_revision_change_rejects_same_excerpt");});
                }).thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenCompose(latest->{
                    var fresh=phoneCachePlan(latest);check(fresh.result().context().equals(plan.result().context())&&!fresh.scope().equals(plan.scope()),"grounded_cache_same_text_has_distinct_actual_PG_provenance");
                    return chat.submit(fresh.question(),fresh.result().context(),fresh.mode(),()->coordinator.playable(s1),()->phoneCacheValidate(fresh,reads),fresh.scope()).thenRun(()->check(chat.cacheStats().entries()==1,"grounded_cache_new_revision_requires_fresh_answer"))
                        .thenCompose(x->{bad.set(true);beforeHistory.set(chat.history());int beforeReads=reads[0];return rejectQuest(chat.submit("다른 질문",fresh.result().context(),fresh.mode(),()->coordinator.playable(s1),()->phoneCacheValidate(fresh,reads),fresh.scope())).thenRun(()->check(reads[0]==beforeReads&&chat.history().equals(beforeHistory.get())&&chat.cacheStats().entries()==0,"grounded_cache_bad_reference_never_cached_or_recorded"));});
                });
        }).thenCompose(v->mainResult(store.readExploration(a2))).thenAccept(otherAfter->check(otherAfter.equals(otherBefore),"grounded_cache_preserves_other_player_PG"))
            .thenCompose(v->mainResult(store.readJournal(a1))).thenAccept(after->check(after.equals(journalBefore)&&codec.equivalent(inventory,one.capture())&&s1.save().revision()==revision,"grounded_cache_reads_preserve_journal_inventory_and_revision"))))
            .whenCompleteAsync((v,error)->{chat.close();check(chat.cacheStats().entries()==0,"grounded_cache_close_discards_session_answers");},owner);
    }
    private CompletionStage<Void> phoneRequestLifecycle(){
        var inventory=one.capture();long revision=s1.save().revision();
        return mainResult(store.readJournal(a1)).thenCompose(before->{
            CompletionStage<Void> sequence=CompletableFuture.completedFuture(null);
            for(String action:List.of("cancel","close","stale"))sequence=sequence.thenCompose(v->{
                var gate=new CompletableFuture<Boolean>();var started=new CompletableFuture<Void>();var live=new java.util.concurrent.atomic.AtomicBoolean(true);
                var shared=new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Boolean>>();
                var chat=new io.github.jesterportfolio.hearth.phone.PhoneChat(io.github.jesterportfolio.hearth.phone.LocalAssistant.mock(),owner);
                var result=chat.submit("보존할 공개 질문","본인 공개 자료","diary",()->coordinator.playable(s1)&&live.get(),()->{
                    var validation=mainResult(store.readJournal(a1)).thenApply(row->row.equals(before)).thenCombine(gate,(same,allowed)->same&&allowed).toCompletableFuture();
                    shared.set(validation);started.complete(null);return validation;
                }).toCompletableFuture();
                return started.thenRunAsync(()->{
                    if(action.equals("cancel"))chat.cancel();else if(action.equals("close"))chat.close();else live.set(false);
                    check(!shared.get().isDone(),"ai_lifecycle_shared_PG_validation_untouched_"+action);
                    if(!action.equals("stale"))check(result.isDone()&&!chat.busy(),"ai_lifecycle_result_immediately_settled_"+action);
                    gate.complete(true);
                },owner).thenCompose(x->mainResult(shared.get())).thenAccept(accepted->check(Boolean.TRUE.equals(accepted),"ai_lifecycle_original_PG_read_completes_"+action))
                    .thenCompose(x->rejectQuest(result)).thenRun(()->check(chat.history().isEmpty()&&!chat.busy(),"ai_lifecycle_no_late_history_"+action)).whenCompleteAsync((x,error)->chat.close(),owner);
            });
            return sequence.thenCompose(v->{
                var chat=new io.github.jesterportfolio.hearth.phone.PhoneChat(io.github.jesterportfolio.hearth.phone.LocalAssistant.mock(),owner);
                var result=chat.submit("조회 실패 질문","본인 공개 자료","diary",()->coordinator.playable(s1),()->mainResult(store.readInventory(ctx(b,9999))).thenApply(row->true));
                return rejectQuest(result).thenRun(()->{check(chat.history().isEmpty()&&chat.canRetry()&&chat.draft().equals("조회 실패 질문"),"ai_lifecycle_PG_failure_keeps_question_without_answer");chat.close();});
            }).thenCompose(v->{
                var service=io.github.jesterportfolio.hearth.phone.LocalAssistant.configured("ollama","http://127.0.0.1:11435/api/generate");
                return service.ask("가".repeat(7000)).handleAsync((answer,error)->{service.close();check(error!=null&&io.github.jesterportfolio.hearth.phone.AssistantFailure.reason(error)==io.github.jesterportfolio.hearth.phone.AssistantFailure.Reason.CONTEXT_LIMIT,"ollama_Paper_Gson_load_and_input_limit_never_mock_success");return (Void)null;},owner);
            }).thenCompose(v->mainResult(store.readJournal(a1))).thenAccept(after->check(after.equals(before)&&codec.equivalent(inventory,one.capture())&&s1.save().revision()==revision,"ai_lifecycle_original_journal_and_inventory_unchanged"));
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
        var background=quests.list(a1,s1,()->coordinator.playable(s1));
        var foreground=quests.listAfterPending(a1,s1,()->coordinator.playable(s1));
        check(!foreground.toCompletableFuture().isDone(),"npc_foreground_waits_existing_query");
        return background.thenCompose(rows->foreground).thenAccept(rows->{
            check(rows.keySet().equals(catalog.questIds()),"npc_foreground_complete_personal_state");
            quests.requireListDisplay(a1,rows);World w=Bukkit.getWorld(worlds.get(a).mapping().physicalId());
            var before=one.capture();
            for(var c:catalog.npcs()){
                var view=NpcQuestView.build(catalog,c.npcId(),rows,q->journal.questAllowed(a1,q.id()));
                check(view.options().stream().noneMatch(q->!journal.questAllowed(a1,q.quest())),"npc_view_restricted_options_hidden");
            }
            questAdapter.ensure(w);var npc=w.getEntitiesByClass(org.bukkit.entity.Villager.class).stream().filter(e->"survey_guide".equals(e.getPersistentDataContainer().get(new NamespacedKey(plugin,"quest_demo_npc"),org.bukkit.persistence.PersistentDataType.STRING))).findFirst().orElseThrow();var npcLocation=npc.getLocation();
            for(var marker:List.of(NpcQuestView.Marker.AVAILABLE,NpcQuestView.Marker.REPORT_READY)){
                var e=PaperQuestAdapter.markerEntity(w,PaperQuestAdapter.markerLocation(npc),marker);
                check(e.getLocation().getY()-npc.getBoundingBox().getMaxY()>=.99&&e.getLocation().getX()==npcLocation.getX()&&e.getLocation().getZ()==npcLocation.getZ()&&npc.getLocation().equals(npcLocation),"npc_marker_above_nameplate_without_moving_NPC_"+marker);
                check(!e.isVisibleByDefault()&&!e.isPersistent(),"npc_marker_private_nonpersistent_"+marker);e.remove();check(!e.isValid(),"npc_marker_removed_"+marker);
            }
            check(codec.equivalent(one.capture(),before),"npc_view_inventory_unchanged");
            check(w.getEntitiesByClass(org.bukkit.entity.Villager.class).stream().filter(e->e.getPersistentDataContainer().has(new NamespacedKey(plugin,"quest_demo_npc"))).allMatch(e->!e.isVisibleByDefault()),"npc_default_hidden_until_authorized");
            questAdapter.ensure(w);for(var c:catalog.npcs())check(questAdapter.count(w,c.npcId())==1,"npc_no_duplicate_after_projection");
        });
    }
    /** 실제 순정 드롭 API + 시험용 인벤토리 투영입니다. Player 채굴/줍기 이벤트를 흉내 내지 않습니다. */
    private CompletionStage<Void> gatherFixtureItem(PaperGathering.Binding binding,GatheringActor actor,int x,int y,int z,ItemStack tool,Material expected){
        var run=actor.session.context().storyRunId();var world=Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId()));var chunk=world.getChunkAt(x>>4,z>>4);long generation=binding.dungeon().snapshot().generation();boolean owned=chunk.addPluginChunkTicket(plugin);
        return until(()->chunk.getLoadLevel()==Chunk.LoadLevel.ENTITY_TICKING).thenComposeAsync(v->{if(!binding.dungeon().canGather(actor,generation))throw new IllegalStateException("SUPPLY_STALE_HARVEST");return gatherLoadedFixtureItem(binding,actor,x,y,z,tool,expected);},owner).whenCompleteAsync((v,error)->{if(owned)chunk.removePluginChunkTicket(plugin);},owner);
    }
    private CompletionStage<Void> gatherLoadedFixtureItem(PaperGathering.Binding binding,GatheringActor actor,int x,int y,int z,ItemStack tool,Material expected){
        var state=binding.dungeon().snapshot();var run=actor.session.context().storyRunId();check(run.equals(state.run())&&binding.dungeon().canGather(actor,state.generation()),"supply_harvest_current_run_member_and_generation");
        var w=Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId()));var block=w.getBlockAt(x,y,z);var chunk=block.getChunk();chunk.getEntities();var spawned=new ArrayList<org.bukkit.entity.Item>();
        var observer=new org.bukkit.event.Listener(){@org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR) public void spawn(org.bukkit.event.entity.ItemSpawnEvent e){var item=e.getEntity();if(item.getWorld().equals(w)&&item.getLocation().distanceSquared(new Location(w,x+.5,y+.5,z+.5))<9){log("SUPPLY_DROP_EVENT material="+item.getItemStack().getType()+" cancelled="+e.isCancelled());if(!e.isCancelled())spawned.add(item);}}};
        Bukkit.getPluginManager().registerEvents(observer,plugin);log("SUPPLY_DROP_CHUNK loaded="+chunk.isEntitiesLoaded()+" level="+chunk.getLoadLevel());
        try{check(block.getDrops(tool).stream().mapToInt(i->i.getType()==expected?i.getAmount():0).sum()==1,"supply_native_drop_rule_"+expected);check(block.breakNaturally(tool,false)&&block.getType().isAir(),"supply_native_break_"+expected);}catch(RuntimeException error){org.bukkit.event.HandlerList.unregisterAll(observer);throw error;}
        return later(()->{check(binding.dungeon().canGather(actor,state.generation()),"supply_pickup_projection_same_generation");chunk.getEntities();var items=spawned.stream().filter(i->i.isValid()&&i.getItemStack().getType()==expected).distinct().toList();check(items.stream().mapToInt(i->i.getItemStack().getAmount()).sum()==1,"supply_real_item_entity_"+expected);
            var slots=codec.decode(one.capture());for(var item:items)slots=codec.withCursor(slots,item.getItemStack());one.apply(codec.encode(slots));coordinator.refresh(s1);check(coordinator.playable(s1),"supply_existing_dirty_capture_after_controlled_pickup");items.forEach(org.bukkit.entity.Item::remove);
        }).whenCompleteAsync((v,error)->org.bukkit.event.HandlerList.unregisterAll(observer),owner);
    }
    private void supplyMaterials(boolean woodReady,boolean stoneReady){
        var before=one.capture();var needs=crafting.planner.materials(crafting.content.recipes().get("stone_pickaxe"),before);var cobble=needs.stream().filter(n->n.material().equals("COBBLESTONE")).findFirst().orElseThrow();check(cobble.required()==3&&cobble.missing()==(stoneReady?0:3),"supply_stone_pickaxe_shortage_"+stoneReady);
        var panel=PaperCrafting.previewMaterials(needs);check(panel.getItem(9)!=null&&panel.getItem(9).getItemMeta().hasLore()&&codec.equivalent(before,one.capture()),"supply_material_panel_read_only_actual_inventory_object");
        if(woodReady)check(craftingCount(before,Material.WOODEN_PICKAXE)==1,"supply_crafted_wooden_pickaxe_preserved");
    }
    private CompletionStage<Void> supplyQuestRead(QuestStore.Phase phase,long revision,int index){return mainResult(store.readQuest(a1,"gathering_workshop_route")).thenAccept(row->check(row.phase()==phase&&row.revision()==revision&&row.objectiveIndex()==index,"supply_quest_"+phase+"_r"+revision+"_i"+index));}
    private CompletionStage<Void> supplyQuestView(){return journal.load(a1,s1,()->coordinator.playable(s1)).thenAccept(view->{
        journal.requireView(a1,view);var id="gathering_workshop_route";check(QuestListView.detail(catalog,view.states(),id).isPresent()&&QuestBookView.accepted(catalog,view.states(),journal.blocked(a1),id).isPresent(),"supply_existing_quest_detail_and_book_data");
        check(view.exploration().places().stream().anyMatch(p->p.key().equals("public_material_site")&&p.discovered()&&!p.hubUnlocked()),"supply_discovered_map_place_not_hub_unlock");
        if(view.states().get(id).phase()==QuestStore.Phase.COMPLETED){check(view.exploration().facts().stream().filter(f->f.id().equals("gathering_workshop_report")).count()==1&&view.journal().notices().stream().filter(n->n.quest().equals(id)).count()==6,"supply_private_report_diary_and_six_progress_notices");check(!view.journal().tracked().contains(id),"supply_completed_tracking_removed");}
    });}
    /** 이동은 제어 경계이며 실제 Player/teleportAsync 시험을 대체하지 않습니다. */
    private final class FacilityActor implements GatheringVisit.Port{
        final PlayerSessions.Session s;long extra;boolean held,frozen;int moves;CompletableFuture<Void> gate=CompletableFuture.completedFuture(null);Supplier<CompletionStage<Void>> saving;
        FacilityActor(PlayerSessions.Session s){this.s=s;saving=()->coordinator.saveBoundary(s);}
        public UUID player(){return s.context().playerId();}public Object session(){return s;}public UUID run(){return s.context().storyRunId();}public long worldGeneration(){return worlds.get(run()).generation()+extra;}public boolean current(){return !held&&extra==0&&coordinator.playable(s);}public boolean allowed(){return current();}public void freeze(boolean b){frozen=b;}public CompletionStage<Void> save(){return gate.thenComposeAsync(v->saving.get(),owner);}public CompletionStage<Boolean> exit(BooleanSupplier valid){moves++;return CompletableFuture.completedFuture(valid.getAsBoolean());}public void hold(){held=true;}
    }
    private void facilityBlocks(UUID run,boolean create){var w=Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId()));for(var site:gatheringFacilities.sites()){var block=w.getBlockAt(site.x(),site.y(),site.z());if(create){if(!block.getType().isAir())throw new IllegalStateException("GATHERING_FACILITY_FIXTURE_CONFLICT");var button=(org.bukkit.block.data.type.Switch)Material.STONE_BUTTON.createBlockData();button.setAttachedFace(org.bukkit.block.data.FaceAttachable.AttachedFace.FLOOR);block.setBlockData(button,false);}check(block.getType()==Material.STONE_BUTTON,"facility_actual_button_"+run+"_"+site.id());var manager=com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer().get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(w));var region=manager.getRegion("hearth_gathering_facility_"+site.id());check(region!=null&&region.getFlag(com.sk89q.worldguard.protection.flags.Flags.INTERACT)==com.sk89q.worldguard.protection.flags.StateFlag.State.ALLOW,"facility_WG_interaction_region_"+run+"_"+site.id());}}
    private final Map<Boolean,FacilityObjectives.Board> previousFacilityObjectives=new HashMap<>();
    private CompletionStage<Void> facilityObjectivesCheck(boolean dungeon,FacilityObjectives.Kind expected,List<String> status){
        var before=one.capture();long revision=s1.save().revision();
        return journal.load(a1,s1,()->coordinator.playable(s1)).thenAccept(view->{
            var board=dungeon?facilityDungeonObjectives(s1,view,status):facilityGatheringObjectives(s1,view,status);
            check(expected==null?board.rows().isEmpty():board.rows().size()==1&&board.rows().getFirst().kind()==expected,"facility_objectives_"+(dungeon?"dungeon_":"gathering_")+Objects.toString(expected,"no_active_order"));
            var page=board.page(1);var book=PaperQuestUI.book(page.book(board.title()),List.of(Component.text("현장/이전 안내로"),Component.text("목표·상태 새로고침")));
            check(book.pages().size()==page.book(board.title()).pages().size()+1,"facility_objectives_native_book_and_return_"+dungeon);
            var previous=previousFacilityObjectives.put(dungeon,board);
            if(previous!=null&&!previous.rows().equals(board.rows()))try{FacilityObjectives.require(board,previous);throw new AssertionError("old facility goal accepted");}catch(IllegalStateException stale){check(stale.getMessage().equals("STALE_FACILITY_OBJECTIVES"),"facility_objectives_saved_transition_rejects_old_selection_"+dungeon);}
            check(codec.equivalent(before,one.capture())&&s1.save().revision()==revision,"facility_objectives_read_preserves_inventory_"+dungeon);
        });
    }
    private CompletionStage<Void> facilityObjectivesIsolation(boolean dungeon,PlayerSessions.Session other,boolean empty){
        return journal.load(other.context(),other,()->coordinator.playable(other)).thenAccept(view->{
            var board=dungeon?facilityDungeonObjectives(other,view,List.of()):facilityGatheringObjectives(other,view,List.of());
            check(board.rows().isEmpty()==empty,"facility_objectives_own_personal_scope_"+dungeon+"_"+other.context().playerId()+"_"+other.context().storyRunId());
        });
    }
    private CompletionStage<Void> gatheringFacilityPlay(){
        var bindings=new PaperGathering.Binding[2];var actors=new FacilityActor[3];var visits=new GatheringVisit(owner,SessionVerification::main);var pending=new CompletionStage<?>[1];var before=new InventoryStore.Stored[1];var mapping=worlds.get(a).mapping();var generation=new long[1];
        return openAll().thenRun(()->{facilityBlocks(a,true);facilityBlocks(b,true);actors[0]=new FacilityActor(s1);actors[1]=new FacilityActor(s2);actors[2]=new FacilityActor(s3);})
        .thenCompose(v->gathering.bind(a1,true,gatheringPeople(a))).thenAccept(bd->bindings[0]=bd)
        .thenCompose(v->gathering.bind(b3,true,gatheringPeople(b))).thenAccept(bd->bindings[1]=bd)
        .thenCompose(v->facilityObjectivesCheck(false,null,List.of("현장 상태")))
        .thenCompose(v->quests.open(a1,"gathering_workshop_route",s1,()->coordinator.playable(s1))).thenCompose(q->quests.accept(q,catalog.quest("gathering_workshop_route")))
        .thenCompose(v->facilityObjectivesCheck(false,FacilityObjectives.Kind.VISIT,List.of("현장 상태")))
        .thenCompose(v->facilityObjectivesIsolation(false,s2,true)).thenCompose(v->facilityObjectivesIsolation(false,s3,true))
        .thenCompose(v->visits.request(actors[0],bindings[0].dungeon(),GatheringFacilities.Action.ENTER))
        .thenCompose(v->quests.open(a1,"gathering_workshop_route",s1,()->coordinator.playable(s1))).thenCompose(q->quests.reach(q,catalog.quest("gathering_workshop_route"),32.5,64,.5))
        .thenCompose(v->facilityObjectivesCheck(false,FacilityObjectives.Kind.NEXT,List.of("현재 채집 참가 중")))
        .thenCompose(v->visits.request(actors[1],bindings[0].dungeon(),GatheringFacilities.Action.ENTER))
        .thenCompose(v->visits.request(actors[2],bindings[1].dungeon(),GatheringFacilities.Action.ENTER))
        .thenRun(()->{var bd=bindings[0].dungeon();check(bd.occupants()==2&&bindings[1].dungeon().occupants()==1,"facility_two_same_run_members_and_independent_B");generation[0]=bd.snapshot().generation();var site=gatheringFacilities.sites().stream().filter(x->x.action()==GatheringFacilities.Action.LEAVE).findFirst().orElseThrow();var state=bd.snapshot();var stamp=new GatheringFacilities.Stamp(a,s1,mapping.physicalId(),worlds.get(a).generation(),state.generation(),state.revision(),state.phase(),true,false);var choice=gatheringFacilities.choose(site,stamp,true);gatheringFacilities.require(choice,site,stamp,true);var beforeSlots=one.capture();for(boolean confirm:List.of(false,true)){var gui=PaperGatheringFacilities.preview(site,GatheringStatus.lines(state,bd.occupants(),true,gatheringClock.get()),true,confirm);check(gui.getSize()==54&&gui.getItem(22).getType()==Material.BOOK&&gui.getItem(31).getType()==Material.LIME_DYE&&gui.getItem(49).getType()==Material.CLOCK,"facility_native_inventory_"+(confirm?"confirmation":"status"));}check(codec.equivalent(beforeSlots,one.capture()),"facility_menu_preview_preserves_41slots");one.count(31);coordinator.refresh(s1);actors[0].gate=new CompletableFuture<>();pending[0]=visits.request(actors[0],bd,GatheringFacilities.Action.LEAVE);})
        .thenCompose(v->rejectQuest(visits.request(actors[0],bindings[0].dungeon(),GatheringFacilities.Action.LEAVE)))
        .thenRun(()->{check(actors[0].moves==0&&actors[0].frozen&&!pending[0].toCompletableFuture().isDone()&&!visits.barrier(a).toCompletableFuture().isDone(),"facility_save_wait_and_duplicate_exit_have_no_early_movement");check(bindings[1].dungeon().canGather(actors[2],bindings[1].dungeon().snapshot().generation())&&visits.settled(b),"facility_A_exit_wait_does_not_block_B");actors[0].gate.complete(null);})
        .thenCompose(v->mainResult(pending[0])).thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(saved->{before[0]=saved;check(saved.revision()==1&&codec.decode(saved.payload())[0].getAmount()==31&&actors[0].moves==1&&!actors[0].frozen,"facility_latest_dirty_saved_before_one_controlled_exit");check(bindings[0].dungeon().occupants()==1&&!bindings[0].dungeon().snapshot().emptyReset(),"facility_partial_exit_keeps_same_resource_generation");})
        .thenCompose(v->mainResult(store.readInventory(a2))).thenAccept(saved->check(saved.revision()==0&&codec.decode(saved.payload())[0].getAmount()==5,"facility_P2_inventory_unchanged"))
        .thenCompose(v->mainResult(store.readInventory(b3))).thenAccept(saved->check(saved.revision()==0&&codec.decode(saved.payload())[0].getAmount()==7,"facility_B_inventory_unchanged"))
        .thenCompose(v->{var probe=new GatheringVisit(owner,SessionVerification::main);var actor=new FacilityActor(s2);actor.saving=()->mainResult(store.readInventory(a2)).thenCompose(row->mainResult(store.saveInventory(a2,row.revision()+10,row.payload()))).thenApply(row->null);var original=bindings[0].dungeon().snapshot();return rejectQuest(probe.request(actor,bindings[0].dungeon(),GatheringFacilities.Action.LEAVE)).thenCompose(x->rejectQuest(probe.barrier(a))).thenRun(()->{check(actor.held&&actor.moves==0&&!actor.frozen&&original.equals(bindings[0].dungeon().snapshot()),"facility_real_DB_CAS_failure_preserves_lease_and_HOLD");check(bindings[1].dungeon().canGather(actors[2],bindings[1].dungeon().snapshot().generation()),"facility_failed_A_exit_preserves_B_access");probe.close();});})
        .thenCompose(v->visits.request(actors[1],bindings[0].dungeon(),GatheringFacilities.Action.LEAVE))
        .thenCompose(v->visits.request(actors[2],bindings[1].dungeon(),GatheringFacilities.Action.LEAVE))
        .thenRun(()->check(bindings[0].dungeon().snapshot().emptyReset()&&bindings[1].dungeon().snapshot().emptyReset()&&visits.settled(a)&&visits.settled(b),"facility_last_exit_marks_next_entry_reset_and_drains_all_operations"))
        .thenCompose(v->mainResult(coordinator.disconnect(s1))).thenCompose(v->mainResult(coordinator.disconnect(s2)))
        .thenCompose(v->until(()->worlds.get(a).state()==WorldLifecycle.State.OFFLINE)).thenCompose(v->{one=new Controlled();s1=connect(a1,one);return mainResult(s1.ready());})
        .thenCompose(v->gathering.bind(a1,false,gatheringPeople(a))).thenAccept(bd->{check(worlds.get(a).mapping().equals(mapping)&&bd.dungeon().snapshot().generation()==generation[0],"facility_existing_world_mapping_and_gathering_generation_restored");facilityBlocks(a,false);})
        .thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(saved->check(before[0].equals(saved)&&codec.equivalent(saved.payload(),one.capture()),"facility_reentry_preserves_saved_inventory_and_revision"))
        .thenCompose(v->facilityObjectivesCheck(false,FacilityObjectives.Kind.NEXT,List.of("재입장 후 현장 상태"))).whenCompleteAsync((v,error)->visits.close(),owner);
    }
    /** 신규 준비 안내 fixture의 발견 기록만 먼저 저장합니다. Player 이동을 흉내 내지 않습니다. */
    private CompletionStage<Void> preparationRecordFixture(){
        var entry=preparation.entries().stream().filter(e->e.kind()==PreparationGuide.Kind.GATHERING).findFirst().orElseThrow();var place=explorationContent.places().get(entry.place());if(place.fact()==null)throw new IllegalStateException("COURSE_RECORD_FIXTURE_FACT_REQUIRED");var area=place.bounds();double x=(area.minX()+area.maxX())/2,y=(area.minY()+area.maxY())/2,z=(area.minZ()+area.maxZ())/2;
        return journal.enterPlaces(a1,s1,()->coordinator.playable(s1),area.minX()-1,y,z,x,y,z).thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenAccept(view->check(journal.placeFacts(a1,view).containsKey(place.id()),"course_record_fixture_personal_discovery_durable_before_read_baseline"));
    }
    /** 실제 Paper 객체·격리 DB를 이용한 읽기 전용 경계 시험. Player 접속/클릭 시험은 아닙니다. */
    private CompletionStage<Void> preparationPlay(){
        var first=new PreparationGuide.Page[1];var before=new Object[5];var old=new PlayerSessions.Session[1];
        return openAll().thenCompose(v->preparationRecordFixture()).thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(value->before[0]=value)
        .thenCompose(v->mainResult(store.readPersonal(a1))).thenAccept(value->before[1]=value)
        .thenCompose(v->mainResult(store.readShared(a1))).thenAccept(value->before[2]=value)
        .thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenAccept(view->{
            before[3]=Map.copyOf(view.states());before[4]=dungeonPlay.play.lines(new DungeonActor(s1));var page=preparationView(s1,view,one.capture(),false,List.of("채집 구역 미준비 · 현장 입장 시 확인"),dungeonPlay.play.lines(new DungeonActor(s1)));first[0]=page;
            check(page.cards().size()==3,"preparation_public_three_sections_and_dependencies_loaded");
            check(page.card("dungeon_entry").quest()==null&&!page.card("dungeon_entry").lines().toString().contains(catalog.quest(dungeonDefinition.quest()).title()),"preparation_unaccepted_title_and_details_not_disclosed");
            check(page.card("workbench").lines().stream().anyMatch(line->line.contains("부족")),"preparation_native_material_requirements_from_41slot_snapshot");
            check(page.card("workbench").lines().contains("함께 준비하기")&&page.card("workbench").lines().contains("참나무 원목 × 2")&&page.card("workbench").lines().contains("조약돌 × 3"),"preparation_combined_native_tool_plan_reuses_intermediate_surplus");
            check(!page.card("workbench").lines().toString().contains("public_material_site"),"preparation_source_uses_filtered_place_label_without_internal_id");
            check(page.cards().stream().allMatch(card->card.lines().getLast().contains("읽기 전용")),"preparation_all_sections_explicitly_read_only");
            var slots=codec.decode(one.capture());slots[10]=new ItemStack(Material.OAK_PLANKS,3);slots[11]=new ItemStack(Material.STICK,2);var preview=codec.encode(slots);
            var changed=preparationView(s1,view,preview,false,List.of("채집 구역 미준비 · 현장 입장 시 확인"),dungeonPlay.play.lines(new DungeonActor(s1)));
            check(changed.card("workbench").lines().stream().anyMatch(line->line.contains("보유 3"))&&!codec.equivalent(preview,one.capture()),"preparation_controlled_material_snapshot_changes_without_inventory_application");
            try{PreparationGuide.require(changed,page,"workbench");throw new AssertionError("Old preparation accepted");}catch(IllegalStateException expected){check(true,"preparation_changed_materials_reject_stale_selection");}
            check(Bukkit.getPluginManager().getPlugin("Hearth").getDescription().getCommands().containsKey("hearthprepare"),"preparation_development_command_registered");
            check(plugin.getConfig().getBoolean("phone-dialog-enabled"),"preparation_phone_adapter_initialized_without_client");
        }).thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(value->check(before[0].equals(value),"preparation_reads_preserve_inventory_payload_and_revision"))
        .thenCompose(v->mainResult(store.readPersonal(a1))).thenAccept(value->check(before[1].equals(value),"preparation_reads_preserve_personal_revision"))
        .thenCompose(v->mainResult(store.readShared(a1))).thenAccept(value->check(before[2].equals(value),"preparation_reads_preserve_shared_revision"))
        .thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenAccept(view->check(before[3].equals(view.states()),"preparation_reads_do_not_accept_or_advance_any_quest"))
        .thenCompose(v->quests.open(a1,dungeonDefinition.quest(),s1,()->coordinator.playable(s1))).thenCompose(q->quests.accept(q,catalog.quest(dungeonDefinition.quest())))
        .thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenAccept(view->{
            var outside=preparationView(s1,view,one.capture(),false,List.of("채집 구역 미준비"),dungeonPlay.play.lines(new DungeonActor(s1)));var inside=preparationView(s1,view,one.capture(),true,List.of("채집 구역 미준비"),dungeonPlay.play.lines(new DungeonActor(s1)));
            check(outside.card("dungeon_entry").quest().equals(dungeonDefinition.quest())&&outside.card("dungeon_entry").revision()==0,"preparation_accepted_personal_quest_revision0_display");
            check(inside.card("dungeon_entry").lines().toString().contains("입구 범위 안")&&outside.card("dungeon_entry").lines().toString().contains("입구 범위 밖"),"preparation_current_entry_location_without_movement");
            check(inside.card("dungeon_entry").lines().toString().contains("새 필수 입장 조건이 아닙니다"),"preparation_crafting_is_not_new_dungeon_requirement");
            overviewScreens(new Playthrough(List.of(new Playthrough.Step("accepted_dungeon",Playthrough.Kind.QUEST,dungeonDefinition.quest(),"공개 던전 의뢰","현재 개인 의뢰"))),view);
            check(before[4].equals(dungeonPlay.play.lines(new DungeonActor(s1))),"preparation_dungeon_information_has_no_entry_action");
            try{PreparationGuide.require(outside,first[0],"dungeon_entry");throw new AssertionError("Old quest accepted");}catch(IllegalStateException expected){check(true,"preparation_saved_quest_change_rejects_old_page");}
        }).thenCompose(v->journal.load(a2,s2,()->coordinator.playable(s2))).thenAccept(view->check(preparationView(s2,view,two.capture(),false,List.of("채집 구역 미준비"),dungeonPlay.play.lines(new DungeonActor(s2))).card("dungeon_entry").quest()==null,"preparation_P2_personal_quest_remains_unaccepted"))
        .thenCompose(v->journal.load(b3,s3,()->coordinator.playable(s3))).thenAccept(view->check(preparationView(s3,view,three.capture(),false,List.of("채집 구역 미준비"),dungeonPlay.play.lines(new DungeonActor(s3))).card("dungeon_entry").quest()==null,"preparation_other_run_personal_quest_remains_unaccepted"))
        .thenCompose(v->rejectQuest(journal.load(a1,s1,()->false))).thenRun(()->check(true,"preparation_invalidated_async_read_is_failure_not_empty_page"))
        .thenCompose(v->mainResult(store.readQuest(a1,dungeonDefinition.quest()))).thenAccept(saved->check(saved.phase()==QuestStore.Phase.ACTIVE&&saved.revision()==0,"preparation_repeated_views_do_not_advance_saved_dungeon_quest"))
        .thenCompose(v->{old[0]=s1;return mainResult(coordinator.disconnect(s1));})
        .thenCompose(v->{one=new Controlled();s1=connect(a1,one);return mainResult(s1.ready());})
        .thenCompose(v->journal.load(a1,s1,()->coordinator.playable(s1))).thenAccept(view->{
            try{preparationView(old[0],view,one.capture(),false,List.of(),List.of());throw new AssertionError("Old session accepted");}catch(IllegalStateException expected){check(true,"preparation_old_session_rejected_after_controlled_reconnect");}
            var restored=preparationView(s1,view,one.capture(),false,List.of("채집 구역 미준비"),dungeonPlay.play.lines(new DungeonActor(s1)));check(restored.card("dungeon_entry").revision()==0,"preparation_current_session_restores_accepted_revision");
        });
    }
    private CompletionStage<Void> gatheringCraftingPlay(){return gatheringCraftingPlay(false);}
    private CompletionStage<Void> gatheringCraftingPlay(boolean joined){
        var bd=new PaperGathering.Binding[2];var actor=new GatheringActor[3];var payload=new InventoryPayload[1];var revision=new long[1];var gen=new long[1];var physical=worlds.get(a).mapping().physicalId();var course=catalog.quest("gathering_workshop_route");
        return (joined?CompletableFuture.<Void>completedFuture(null):openAll()).thenRun(()->{
            for(UUID run:List.of(a,b)){var w=Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId()));if(!w.getBlockAt(6,64,6).getType().isAir())throw new IllegalStateException("SUPPLY_WORKBENCH_CONFLICT");w.getBlockAt(6,64,6).setType(Material.CRAFTING_TABLE,false);crafting.ensureRegions(run);}
            craftingBatchNativeChecks();supplyMaterials(false,false);check(craftingCount(one.capture(),Material.OAK_LOG)==0&&craftingCount(one.capture(),Material.COBBLESTONE)==0,"supply_no_free_raw_material_fixture");
        }).thenCompose(v->gathering.bind(a1,true,gatheringPeople(a))).thenAccept(value->bd[0]=value)
        .thenCompose(v->gathering.bind(b3,true,gatheringPeople(b))).thenAccept(value->bd[1]=value)
        .thenCompose(v->{actor[0]=new GatheringActor(s1);actor[1]=new GatheringActor(s2);actor[2]=new GatheringActor(s3);return bd[0].dungeon().enter(actor[0]);})
        .thenCompose(v->bd[1].dungeon().enter(actor[2]))
        .thenRun(()->check(!bd[0].dungeon().canGather(actor[1],bd[0].dungeon().snapshot().generation())&&!bd[0].dungeon().canGather(actor[2],bd[0].dungeon().snapshot().generation()),"supply_unregistered_and_other_run_members_denied"))
        .thenCompose(v->quests.open(a1,course.id(),s1,()->coordinator.playable(s1))).thenCompose(q->quests.accept(q,course))
        .thenCompose(v->journal.track(a1,s1,()->coordinator.playable(s1),course.id(),true))
        .thenCompose(v->journal.enterPlaces(a1,s1,()->coordinator.playable(s1),0,64,0,32.5,64,.5))
        .thenCompose(v->publicCourse?publicRouteCheck("tools","material_site"):CompletableFuture.completedFuture(null))
        .thenCompose(v->quests.open(a1,course.id(),s1,()->coordinator.playable(s1))).thenCompose(q->quests.reach(q,course,32.5,64,.5))
        .thenCompose(v->supplyQuestRead(QuestStore.Phase.ACTIVE,1,1)).thenCompose(v->publicCourse?publicRouteCheck("tools","workbench"):CompletableFuture.completedFuture(null)).thenCompose(v->supplyQuestView())
        .thenCompose(v->gatherFixtureItem(bd[0],actor[0],33,64,1,new ItemStack(Material.AIR),Material.OAK_LOG))
        .thenCompose(v->gatherFixtureItem(bd[0],actor[0],34,64,1,new ItemStack(Material.AIR),Material.OAK_LOG))
        .thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(row->check(row.revision()==0&&s1.save().dirty()&&craftingCount(one.capture(),Material.OAK_LOG)==2,"supply_two_pickups_dirty_without_per_item_DB_write"))
        .thenCompose(v->coordinator.saveBoundary(s1)).thenCompose(v->bd[0].dungeon().leave(actor[0]))
        .thenCompose(v->publicCourse?journal.enterPlaces(a1,s1,()->coordinator.playable(s1),0,64,0,6,64,6).thenAccept(view->{}):CompletableFuture.completedFuture(null))
        .thenCompose(v->craftingQuestTool()).thenCompose(v->supplyQuestRead(QuestStore.Phase.ACTIVE,2,2)).thenCompose(v->publicCourse?publicRouteCheck("tools","material_site"):CompletableFuture.completedFuture(null))
        .thenCompose(v->publicCourse?resumeReconnect().thenRun(()->actor[0]=new GatheringActor(s1)):CompletableFuture.completedFuture(null))
        .thenRun(()->{supplyMaterials(true,false);check(craftingQuestSaves==1,"supply_wood_tool_goal_after_confirmed_source_save");gen[0]=bd[0].dungeon().snapshot().generation();})
        .thenCompose(v->bd[0].dungeon().enter(actor[0]))
        .thenRun(()->{check(bd[0].dungeon().snapshot().generation()==gen[0]+1&&!bd[0].dungeon().canGather(actor[0],gen[0]),"supply_reentry_reset_old_generation_rejected");supplyMaterials(true,false);check(Bukkit.getWorld(worlds.get(b).mapping().physicalId()).getBlockAt(33,64,1).getType()==Material.OAK_LOG&&bd[1].dungeon().snapshot().generation()==1,"supply_other_run_resources_generation_unchanged");})
        .thenCompose(v->quests.open(a1,course.id(),s1,()->coordinator.playable(s1))).thenCompose(q->quests.reach(q,course,32.5,64,.5))
        .thenCompose(v->supplyQuestRead(QuestStore.Phase.ACTIVE,3,3)).thenCompose(v->publicCourse?publicRouteCheck("tools","workbench"):CompletableFuture.completedFuture(null))
        .thenRun(()->{var block=Bukkit.getWorld(physical).getBlockAt(33,64,2);check(block.getDrops(new ItemStack(Material.AIR)).isEmpty(),"supply_stone_requires_native_appropriate_tool");})
        .thenCompose(v->gatherFixtureItem(bd[0],actor[0],33,64,2,new ItemStack(Material.WOODEN_PICKAXE),Material.COBBLESTONE))
        .thenCompose(v->gatherFixtureItem(bd[0],actor[0],34,64,2,new ItemStack(Material.WOODEN_PICKAXE),Material.COBBLESTONE))
        .thenCompose(v->gatherFixtureItem(bd[0],actor[0],35,64,2,new ItemStack(Material.WOODEN_PICKAXE),Material.COBBLESTONE))
        .thenRun(()->{supplyMaterials(true,true);var protectedSlots=codec.decode(one.capture());protectedSlots[36]=new ItemStack(Material.COBBLESTONE,3);protectedSlots[40]=new ItemStack(Material.STICK,2);protectedSlots[9]=new ItemStack(Material.COBBLESTONE,3);protectedSlots[9].editMeta(m->m.displayName(Component.text("제작 제외 시험 재료")));for(int i=0;i<36;i++)if(protectedSlots[i]!=null&&!protectedSlots[i].hasItemMeta()&&protectedSlots[i].getType()==Material.COBBLESTONE)protectedSlots[i]=null;var counts=crafting.planner.materials(crafting.content.recipes().get("stone_pickaxe"),codec.encode(protectedSlots));check(counts.stream().filter(n->n.material().equals("COBBLESTONE")).allMatch(n->n.available()==0),"supply_material_counts_ignore_named_and_equipment_slots");})
        .thenCompose(v->coordinator.saveBoundary(s1)).thenCompose(v->bd[0].dungeon().leave(actor[0]))
        .thenCompose(v->craftingQuestRecipe("stone_pickaxe")).thenCompose(v->supplyQuestRead(QuestStore.Phase.REPORT_READY,4,3)).thenCompose(v->publicCourse?publicRouteCheck("tools",null):CompletableFuture.completedFuture(null))
        .thenRun(()->check(craftingQuestSaves==2&&craftingCount(one.capture(),Material.STONE_PICKAXE)==1&&craftingCount(one.capture(),Material.COBBLESTONE)==0,"supply_real_recipe_consumes_collected_cobble_and_saves_before_goal"))
        .thenCompose(v->quests.open(a1,course.id(),s1,()->coordinator.playable(s1))).thenCompose(q->quests.advance(q,QuestStore.Phase.COMPLETED))
        .thenCompose(v->supplyQuestRead(QuestStore.Phase.COMPLETED,5,3)).thenCompose(v->supplyQuestView())
        .thenCompose(v->questRead(a2,course.id(),QuestStore.Phase.NOT_ACCEPTED,-1)).thenCompose(v->questRead(ctx(b,1),course.id(),QuestStore.Phase.NOT_ACCEPTED,-1))
        .thenCompose(v->bd[0].dungeon().enter(actor[0])).thenRun(()->check(craftingCount(one.capture(),Material.STONE_PICKAXE)==1&&craftingCount(one.capture(),Material.WOODEN_PICKAXE)==1&&Bukkit.getWorld(physical).getBlockAt(33,64,2).getType()==Material.STONE,"supply_next_reset_keeps_obtained_items_and_restores_resources"))
        .thenCompose(v->bd[0].dungeon().leave(actor[0]))
        .thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(row->{payload[0]=row.payload();revision[0]=row.revision();gen[0]=bd[0].dungeon().snapshot().generation();check(codec.equivalent(row.payload(),one.capture()),"supply_latest_inventory_durable");})
        // 연결 코스에서는 필드 조우의 언로드 후 재소환 금지를 유지하고, 재로드 검사를 던전 종료 뒤 함께 수행합니다.
        .thenCompose(ignored->{if(joined){publicGatheringGeneration=gen[0];return CompletableFuture.<Void>completedFuture(null);}return coordinator.disconnect(s1).thenCompose(x->coordinator.disconnect(s2)).thenCompose(x->until(()->worlds.get(a).state()==WorldLifecycle.State.OFFLINE))
        .thenRun(()->check(Bukkit.getWorld(physical)==null&&coordinator.playable(s3),"supply_world_unload_other_run_active"))
        .thenCompose(v->{one=new Controlled();two=new Controlled();s1=connect(a1,one);s2=connect(a2,two);return CompletableFuture.allOf(s1.ready().toCompletableFuture(),s2.ready().toCompletableFuture());})
        .thenCompose(v->gathering.bind(a1,false,gatheringPeople(a)))
        .thenAccept(value->{bd[0]=value;check(value.dungeon().snapshot().generation()==gen[0]&&codec.equivalent(payload[0],one.capture())&&s1.save().revision()==revision[0],"supply_existing_gather_generation_inventory_revision_restored_without_create");check(Bukkit.getWorld(physical).getBlockAt(6,64,6).getType()==Material.CRAFTING_TABLE,"supply_existing_workbench_preserved");check(GatheringStatus.lines(value.dungeon().snapshot(),0,false,gatheringClock.get()).stream().anyMatch(line->line.contains("다음 입장 전에 초기화")),"supply_empty_reset_status_after_reload");});})
        .thenRun(()->{if(publicCourse)publicRoster();}).thenCompose(v->supplyQuestRead(QuestStore.Phase.COMPLETED,5,3)).thenCompose(v->supplyQuestView()).thenCompose(v->verify(a2,5,0)).thenCompose(v->verify(ctx(b,1),7,0));
    }
    private CompletionStage<CraftingOrders.Board> craftingOrders(PlayerSessions.Session session,String facility,BooleanSupplier current){
        main();if(!coordinator.playable(session)||!current.getAsBoolean())return CompletableFuture.failedFuture(new IllegalStateException("CRAFT_ORDERS_SESSION"));
        return journal.load(session.context(),session,current).thenApplyAsync(view->{
            if(!coordinator.playable(session)||!current.getAsBoolean())throw new IllegalStateException("CRAFT_ORDERS_SESSION");journal.requireView(session.context(),view);
            return CraftingOrders.build(catalog,crafting.content,facility,view);
        },owner);
    }
    private CraftingOrders.Board previousCraftingOrders;
    private CompletionStage<Void> craftingOrdersCheck(QuestStore.Phase phase,String recipe){
        var before=one.capture();long inventoryRevision=s1.save().revision();
        return craftingOrders(s1,"public_workbench",()->coordinator.playable(s1)).thenAccept(board->{
            var orders=board.orders().stream().filter(row->row.quest().equals("workshop_route")).toList();
            boolean visible=phase==QuestStore.Phase.ACTIVE||phase==QuestStore.Phase.REPORT_READY;
            check(visible?orders.size()==1&&orders.getFirst().phase()==phase&&Objects.equals(orders.getFirst().recipe(),recipe):orders.isEmpty(),"orders_current_saved_goal_"+phase+"_"+Objects.toString(recipe,"guidance"));
            var world=Objects.requireNonNull(Bukkit.getWorld(worlds.get(a).mapping().physicalId()));
            var panel=PaperCrafting.previewOrders(crafting.content,crafting.content.facilities().get("public_workbench"),crafting.planner,world,board.page(1),before);
            check(panel.inventory().getSize()==54&&panel.inventory().getItem(6)!=null&&panel.inventory().getItem(45).getType()==Material.ARROW&&panel.inventory().getItem(49).getType()==Material.CLOCK,"orders_native_current_goal_menu_and_return_"+phase);
            if(recipe!=null)check(panel.recipes().get(0).equals(new CraftingBatch.Selection(recipe,1))&&panel.inventory().getItem(41)!=null&&panel.inventory().getItem(42)!=null,"orders_current_recipe_and_joint_material_preparation");
            else if(phase==QuestStore.Phase.REPORT_READY)check(panel.recipes().isEmpty()&&panel.inventory().getItem(0).getType()==Material.BOOK,"orders_reporting_guidance_does_not_repeat_craft");
            if(previousCraftingOrders!=null&&!previousCraftingOrders.equals(board))try{CraftingOrders.require(board,previousCraftingOrders);throw new AssertionError("stale work order accepted");}catch(IllegalStateException expected){check(expected.getMessage().equals("STALE_CRAFT_ORDERS"),"orders_saved_goal_change_rejects_old_selection_"+phase);}
            previousCraftingOrders=board;
            check(codec.equivalent(before,one.capture())&&inventoryRevision==s1.save().revision(),"orders_read_and_native_preparation_preserve_inventory_"+phase);
        });
    }
    private CompletionStage<Void> craftingOrdersIsolation(){
        var live=new java.util.concurrent.atomic.AtomicBoolean(true);
        return craftingOrders(s2,"public_workbench",()->coordinator.playable(s2)).thenAccept(board->check(board.orders().isEmpty(),"orders_other_player_has_no_borrowed_work_orders"))
            .thenCompose(v->craftingOrders(s3,"public_workbench",()->coordinator.playable(s3))).thenAccept(board->check(board.orders().isEmpty(),"orders_other_run_has_no_borrowed_work_orders"))
            .thenCompose(v->{var read=craftingOrders(s1,"public_workbench",live::get);live.set(false);return rejectQuest(read);})
            .thenRun(()->check(coordinator.playable(s1),"orders_invalidated_async_read_is_rejected_without_empty_success_or_session_hold"));
    }
    private int craftingQuestSaves;
    private CompletionStage<List<String>> craftingQuestRecipe(String recipe){return craftingQuestRecipe(recipe,1);}
    private CompletionStage<List<String>> craftingQuestRecipe(String recipe,int times){
        var session=s1;var port=one;var prepared=new CraftingQuest.Prepared[1];var valid=(BooleanSupplier)()->s1==session&&coordinator.playable(session);
        var ticket=crafting.actions.preview(craftingPort(port,session,recipe,times,()->valid.getAsBoolean()&&(prepared[0]==null||craftingQuests.valid(prepared[0]))));
        return craftingQuests.prepare(session.context(),"public_workbench",recipe,ticket,valid,valid,id->journal.questAllowed(session.context(),id))
            .thenComposeAsync(p->{prepared[0]=p;return crafting.actions.confirm(ticket);},owner)
            .thenComposeAsync(v->{var task=craftingQuests.complete(prepared[0],()->{craftingQuestSaves++;return coordinator.saveBoundary(session);});check(task==craftingQuests.complete(prepared[0],()->{throw new AssertionError("duplicate source save");}),"workshop_duplicate_receipt_shared_"+recipe);return task;},owner);
    }
    private CompletionStage<Void> craftingQuestTool(){return craftingQuestRecipe("oak_planks").thenCompose(v->craftingQuestRecipe("stick")).thenCompose(v->craftingQuestRecipe("oak_planks")).thenCompose(v->craftingQuestRecipe("wooden_pickaxe")).thenAccept(v->{});}
    private CompletionStage<Void> craftingQuestRead(QuestStore.Phase phase,long revision,int index){return mainResult(store.readQuest(a1,"workshop_route")).thenAccept(row->check(row.phase()==phase&&row.revision()==revision&&row.objectiveIndex()==index,"workshop_phase_"+phase+"_revision_"+revision+"_index_"+index));}
    private CompletionStage<Void> craftingQuestView(){return journal.load(a1,s1,()->coordinator.playable(s1)).thenAccept(view->{journal.requireView(a1,view);
        var row=view.states().get("workshop_route");var detail=QuestListView.detail(catalog,view.states(),"workshop_route");check(detail.isPresent()&&detail.get().quest().phase()==row.phase()&&QuestBookView.accepted(catalog,view.states(),journal.blocked(a1),"workshop_route").isPresent(),"workshop_list_detail_book_use_saved_current_goal");
        if(row.phase()==QuestStore.Phase.ACTIVE&&row.objectiveIndex()==1)check(detail.get().guidance().contains("나무 곡괭이")&&detail.get().guidance().contains("6"),"workshop_crafting_guidance_and_public_facility_location");
        var place=view.exploration().places().stream().filter(p->p.key().equals("public_workshop")).findFirst().orElseThrow();check(place.discovered()&&!place.hubUnlocked(),"workshop_discovery_is_not_fast_travel_unlock");
        check(view.exploration().facts().stream().filter(f->f.id().equals("workshop_visit")).count()==1,"workshop_discovery_diary_once");
        if(row.phase()==QuestStore.Phase.COMPLETED){check(!view.journal().tracked().contains("workshop_route")&&view.exploration().facts().stream().filter(f->f.id().equals("workshop_report")).count()==1,"workshop_report_diary_and_tracking_cleanup");check(view.journal().notices().stream().filter(n->n.quest().equals("public_workshop")).count()==1&&view.journal().notices().stream().filter(n->n.quest().equals("workshop_route")).count()==5,"workshop_discovery_and_quest_notifications_no_duplicate");}
        var guide=QuestJourneyView.build(playthrough,catalog,view,sharedStory.cached(a),sharedStory.phase(a));check(guide!=null&&guide.links().stream().anyMatch(l->l.action()==QuestJourneyView.Action.MAP),"workshop_registered_journey_map_diary_links");
    });}
    private CompletionStage<Void> craftingQuestPlay(){
        var snapshot=new InventoryPayload[1];var savedRevision=new long[1];var originalWorld=worlds.get(a).mapping().physicalId();
        var course=catalog.quest("workshop_route");var followup=catalog.quest("workshop_followup");
        return openAll().thenRun(()->{
            for(UUID run:List.of(a,b)){var w=Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId()));var block=w.getBlockAt(6,64,6);if(!block.getType().isAir())throw new IllegalStateException("WORKSHOP_FIXTURE_CONFLICT");block.setType(Material.CRAFTING_TABLE,false);crafting.ensureRegions(run);}
            craftingBatchNativeChecks();one.inventory.setItem(9,new ItemStack(Material.OAK_LOG,10));coordinator.refresh(s1);
        }).thenCompose(v->journal.enterPlaces(a1,s1,()->coordinator.playable(s1),0,64,0,4,64,6))
        .thenCompose(v->quests.enter(a1,course,s1,()->coordinator.playable(s1),0,64,0,4,64,6)).thenCompose(v->craftingQuestTool())
        .thenCompose(v->craftingQuestRead(QuestStore.Phase.NOT_ACCEPTED,-1,0)).thenCompose(v->craftingOrdersCheck(QuestStore.Phase.NOT_ACCEPTED,null)).thenRun(()->check(craftingQuestSaves==0,"workshop_unaccepted_craft_no_quest_write_or_per_craft_flush"))
        .thenCompose(v->coordinator.saveBoundary(s1))
        .thenCompose(v->quests.enter(a1,followup,s1,()->coordinator.playable(s1),0,64,0,4,64,6)).thenCompose(v->questRead(a1,"workshop_followup",QuestStore.Phase.NOT_ACCEPTED,-1))
        .thenCompose(v->quests.open(a1,course.id(),s1,()->coordinator.playable(s1))).thenCompose(q->quests.accept(q,course))
        .thenCompose(v->journal.track(a1,s1,()->coordinator.playable(s1),course.id(),true))
        .thenCompose(v->quests.open(a1,course.id(),s1,()->coordinator.playable(s1))).thenCompose(q->quests.reach(q,course,4,64,6))
        .thenCompose(v->craftingQuestRead(QuestStore.Phase.ACTIVE,1,1)).thenCompose(v->craftingOrdersCheck(QuestStore.Phase.ACTIVE,"wooden_pickaxe")).thenCompose(v->craftingQuestView())
        .thenCompose(v->quests.open(a1,course.id(),s1,()->coordinator.playable(s1))).thenCompose(q->rejectQuest(quests.reach(q,course,6,64,6)))
        .thenCompose(v->craftingQuestRecipe("oak_planks",8)).thenCompose(v->craftingQuestRecipe("stick",4)).thenCompose(v->craftingQuestRecipe("wooden_pickaxe",4)).thenCompose(v->craftingQuestRead(QuestStore.Phase.ACTIVE,2,2)).thenCompose(v->craftingOrdersCheck(QuestStore.Phase.ACTIVE,null))
        .thenRun(()->check(craftingQuestSaves==1&&craftingCount(one.capture(),Material.WOODEN_PICKAXE)==5,"workshop_batch_four_tools_single_goal_and_source_save"))
        .thenCompose(v->quests.open(a1,course.id(),s1,()->coordinator.playable(s1))).thenCompose(q->quests.reach(q,course,13,64,13))
        .thenCompose(v->craftingQuestRead(QuestStore.Phase.REPORT_READY,3,2)).thenCompose(v->craftingOrdersCheck(QuestStore.Phase.REPORT_READY,null))
        .thenCompose(v->quests.open(a1,course.id(),s1,()->coordinator.playable(s1))).thenCompose(q->quests.advance(q,QuestStore.Phase.COMPLETED))
        .thenCompose(v->craftingQuestRead(QuestStore.Phase.COMPLETED,4,2))
        .thenCompose(v->quests.enter(a1,followup,s1,()->coordinator.playable(s1),0,64,0,4,64,6)).thenCompose(v->questRead(a1,followup.id(),QuestStore.Phase.ACTIVE,0))
        .thenCompose(v->craftingQuestRecipe("stick")).thenCompose(v->questRead(a1,followup.id(),QuestStore.Phase.COMPLETED,1))
        .thenCompose(v->journal.enterPlaces(a1,s1,()->coordinator.playable(s1),0,64,0,4,64,6)).thenCompose(v->craftingQuestView()).thenCompose(v->craftingOrdersCheck(QuestStore.Phase.COMPLETED,null)).thenCompose(v->craftingOrdersIsolation())
        .thenCompose(v->questRead(a2,course.id(),QuestStore.Phase.NOT_ACCEPTED,-1)).thenCompose(v->questRead(ctx(b,1),course.id(),QuestStore.Phase.NOT_ACCEPTED,-1))
        .thenCompose(v->journal.load(a2,s2,()->coordinator.playable(s2))).thenAccept(view->check(view.exploration().places().stream().filter(p->p.key().equals("public_workshop")).noneMatch(ExplorationView.Place::discovered),"workshop_other_player_discovery_unchanged"))
        .thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(row->{snapshot[0]=row.payload();savedRevision[0]=row.revision();check(craftingQuestSaves==2&&codec.equivalent(one.capture(),row.payload()),"workshop_inventory_durable_before_both_quest_completions");})
        .thenCompose(v->coordinator.disconnect(s1)).thenCompose(v->coordinator.disconnect(s2)).thenCompose(v->until(()->worlds.get(a).state()==WorldLifecycle.State.OFFLINE))
        .thenRun(()->check(Bukkit.getWorld(originalWorld)==null&&coordinator.playable(s3),"workshop_idle_unload_and_other_run_active"))
        .thenCompose(v->{one=new Controlled();two=new Controlled();s1=connect(a1,one);s2=connect(a2,two);return CompletableFuture.allOf(s1.ready().toCompletableFuture(),s2.ready().toCompletableFuture());})
        .thenRun(()->{var w=Objects.requireNonNull(Bukkit.getWorld(originalWorld));var manager=Objects.requireNonNull(com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer().get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(w)));check(w.getUID().equals(originalWorld)&&w.getBlockAt(6,64,6).getType()==Material.CRAFTING_TABLE&&manager.getRegion("hearth_crafting_public_workbench")!=null,"workshop_existing_world_workbench_and_region_restored");check(codec.equivalent(one.capture(),snapshot[0])&&s1.save().revision()==savedRevision[0],"workshop_existing_inventory_reconnect_revision_preserved");})
        .thenCompose(v->craftingQuestRead(QuestStore.Phase.COMPLETED,4,2)).thenCompose(v->craftingQuestView()).thenCompose(v->craftingOrdersCheck(QuestStore.Phase.COMPLETED,null))
        .thenCompose(v->craftingQuestFailure(false)).thenCompose(v->craftingQuestFailure(true))
        .thenCompose(v->craftingQuestRead(QuestStore.Phase.COMPLETED,4,2)).thenCompose(v->verify(a2,5,0)).thenCompose(v->verify(ctx(b,1),7,0));
    }
    private CompletionStage<Void> craftingQuestFailure(boolean questConflict){
        var context=ctx(a,questConflict?26:25);var isolatedQuests=new PersonalQuest(store,catalog.questIds(),owner,SessionVerification::main);var isolated=new CraftingQuest(isolatedQuests,catalog,crafting.content,owner,SessionVerification::main);var inv=new Controlled();var live=new java.util.concurrent.atomic.AtomicBoolean(true);var course=catalog.quest("workshop_route");
        var initial=new ItemStack[41];initial[0]=new ItemStack(Material.OAK_PLANKS,3);initial[1]=new ItemStack(Material.STICK,2);var before=codec.encode(initial);inv.apply(before);
        return mainResult(store.createPlayer(context,"workshop-failure-probe")).thenCompose(v->mainResult(store.createInventory(context,before)))
        .thenCompose(v->mainResult(store.advanceQuest(context,course.id(),new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1),QuestStore.Phase.ACTIVE,0)))
        .thenCompose(v->mainResult(store.advanceQuest(context,course.id(),v,QuestStore.Phase.ACTIVE,1)))
        .thenCompose(expected->{var port=new CraftingAction.Port(){public UUID run(){return context.storyRunId();}public UUID player(){return context.playerId();}public String source(){return "craft:public_workbench:wooden_pickaxe";}public boolean valid(){return live.get();}public InventoryPayload capture(){return inv.capture();}public InventoryPayload plan(InventoryPayload value){return crafting.planner.plan(Objects.requireNonNull(Bukkit.getWorld(originalPhysical(context.storyRunId()))),crafting.content.recipes().get("wooden_pickaxe"),value);}public void apply(InventoryPayload value){inv.apply(value);}public void changed(){}public void hold(){live.set(false);}};
            var ticket=crafting.actions.preview(port);return isolated.prepare(context,"public_workbench","wooden_pickaxe",ticket,live::get,live::get,id->true)
                .thenCompose(prepared->crafting.actions.confirm(ticket).thenCompose(v->rejectQuest(isolated.complete(prepared,()->mainResult(store.saveInventory(context,questConflict?0:99,inv.capture())).thenCompose(saved->questConflict?mainResult(store.advanceQuest(context,course.id(),expected,QuestStore.Phase.ACTIVE,2)).thenAccept(row->{}):CompletableFuture.completedFuture(null))))))
                .thenCompose(v->mainResult(store.readQuest(context,course.id()))).thenAccept(row->check(isolated.held(context)&&row.phase()==QuestStore.Phase.ACTIVE&&row.revision()==(questConflict?2:1),questConflict?"workshop_real_quest_CAS_failure_preserves_competing_row":"workshop_real_source_CAS_failure_does_not_advance_quest"))
                .thenCompose(v->mainResult(store.readInventory(context))).thenAccept(row->check(codec.equivalent(row.payload(),questConflict?inv.capture():before),"workshop_failure_inventory_original_or_confirmed_source_preserved_"+questConflict));
        }).whenCompleteAsync((v,error)->{live.set(false);isolated.close();},owner);
    }
    private UUID originalPhysical(UUID run){return worlds.get(run).mapping().physicalId();}
    private int craftingCount(InventoryPayload value,Material material){return Arrays.stream(codec.decode(value)).filter(Objects::nonNull).filter(i->i.getType()==material).mapToInt(ItemStack::getAmount).sum();}
    private CraftingAction.Port craftingPort(Controlled port,PlayerSessions.Session session,String recipe,BooleanSupplier valid){return craftingPort(port,session,recipe,1,valid);}
    private CraftingAction.Port craftingPort(Controlled port,PlayerSessions.Session session,String recipe,int times,BooleanSupplier valid){CraftingBatch.require(times);long generation=worlds.get(session.context().storyRunId()).generation();return new CraftingAction.Port(){
        public UUID run(){return session.context().storyRunId();}public UUID player(){return session.context().playerId();}public String source(){return "craft:public_workbench:"+recipe;}public boolean valid(){return valid.getAsBoolean()&&coordinator.playable(session)&&worlds.get(run()).generation()==generation;}
        public InventoryPayload capture(){return port.capture();}public InventoryPayload plan(InventoryPayload before){return crafting.planner.plan(Objects.requireNonNull(Bukkit.getWorld(worlds.get(run()).mapping().physicalId())),crafting.content.recipes().get(recipe),before,times);}
        public void apply(InventoryPayload after){port.apply(after);}public void changed(){coordinator.refresh(session);if(!coordinator.playable(session))throw new IllegalStateException("CRAFT_HOLD");}public void hold(){coordinator.inventoryHold(session,"CRAFT_APPLY_HOLD");}
    };}
    private CompletionStage<Void> craftOne(String recipe){return crafting.actions.confirm(crafting.actions.preview(craftingPort(one,s1,recipe,()->true)));}
    /** 실제 Paper 레시피/인벤토리 객체로 일괄 제작과 현장 제작법 연결을 확인합니다. Player 이벤트 시험은 아닙니다. */
    private void craftingBatchNativeChecks(){
        var world=Objects.requireNonNull(Bukkit.getWorld(worlds.get(a).mapping().physicalId()));var recipe=crafting.content.recipes().get("oak_planks");
        var slots=new ItemStack[41];slots[0]=new ItemStack(Material.OAK_LOG,8);slots[40]=new ItemStack(Material.OAK_LOG,8);slots[9]=new ItemStack(Material.OAK_LOG,8);slots[9].editMeta(m->m.displayName(Component.text("보존할 원목")));
        var before=codec.encode(slots);var after=crafting.planner.plan(world,recipe,before,8);
        var facility=crafting.content.facilities().get("public_workbench");var plan=crafting.planner.preparation(world,crafting.content,facility,List.of(new CraftingBatch.Selection("wooden_pickaxe",8)),before);
        check(plan.status()==CraftingPreparation.Status.READY&&plan.ownedUsed().equals(Map.of("OAK_LOG",8))&&plan.additional().isEmpty(),"craft_preparation_native_graph_uses_plain_owned_logs_once");
        var preparationMenu=PaperCrafting.previewPreparation(plan);check(preparationMenu.getSize()==54&&preparationMenu.getItem(31)==null&&preparationMenu.getItem(40).getType()==Material.CHEST&&preparationMenu.getItem(41).getType()==Material.HOPPER&&preparationMenu.getItem(49).getType()==Material.CLOCK,"craft_preparation_actual_menu_has_refresh_and_no_confirmation");
        check(craftingCount(after,Material.OAK_PLANKS)==32&&codec.decode(after)[9].equals(slots[9])&&codec.decode(after)[40].equals(slots[40])&&codec.equivalent(before,codec.encode(slots)),"craft_batch_eight_native_results_original_named_offhand_preserved");
        var needs=crafting.planner.materials(recipe,before,8);check(needs.equals(List.of(new CraftingMaterials.Need("OAK_LOG",8,8))),"craft_batch_total_materials_plain_storage_only");
        var detail=PaperCrafting.previewDetail(crafting.planner,world,recipe,needs,8);
        check(detail.getItem(27).getType()==Material.GRAY_DYE&&detail.getItem(28).getType()==Material.GRAY_DYE&&detail.getItem(29).getType()==Material.LIME_DYE&&detail.getItem(22).getItemMeta().lore().contains(Component.text("총 결과 수량: 32")),"craft_batch_actual_detail_controls_and_total_result");
        slots[0]=new ItemStack(Material.OAK_LOG,3);var shortValue=codec.encode(slots);
        var shortage=crafting.planner.preparation(world,crafting.content,facility,List.of(new CraftingBatch.Selection("wooden_pickaxe",4)),shortValue);
        check(shortage.additional().equals(Map.of("OAK_LOG",1))&&shortage.ownedUsed().equals(Map.of("OAK_LOG",3))&&codec.equivalent(shortValue,codec.encode(slots)),"craft_preparation_named_offhand_excluded_snapshot_unchanged");
        try{crafting.planner.plan(world,recipe,shortValue,4);throw new AssertionError("partial batch accepted");}catch(IllegalStateException expected){check(expected.getMessage().equals("CRAFT_MISSING_INGREDIENT")&&codec.equivalent(shortValue,codec.encode(slots)),"craft_batch_late_material_failure_preserves_original");}
        var full=new ItemStack[41];Arrays.fill(full,0,36,new ItemStack(Material.STONE,64));full[0]=new ItemStack(Material.OAK_LOG,8);full[1]=new ItemStack(Material.OAK_PLANKS,60);var fullValue=codec.encode(full);
        try{crafting.planner.plan(world,recipe,fullValue,4);throw new AssertionError("partial overflow accepted");}catch(IllegalStateException expected){check(expected.getMessage().equals("CURSOR_STORAGE_FULL")&&codec.equivalent(fullValue,codec.encode(full)),"craft_batch_late_space_failure_preserves_original");}
        var cake=new CraftContent.Recipe("cake","잔여물 시험","minecraft:cake",List.of("MILK_BUCKET","MILK_BUCKET","MILK_BUCKET","SUGAR","EGG","SUGAR","WHEAT","WHEAT","WHEAT"));var cakeSlots=new ItemStack[41];for(int i=0;i<12;i++)cakeSlots[i]=new ItemStack(Material.MILK_BUCKET);cakeSlots[12]=new ItemStack(Material.SUGAR,8);cakeSlots[13]=new ItemStack(Material.EGG,4);cakeSlots[14]=new ItemStack(Material.WHEAT,12);
        var baked=crafting.planner.plan(world,cake,codec.encode(cakeSlots),4);check(craftingCount(baked,Material.CAKE)==4&&craftingCount(baked,Material.BUCKET)==12&&craftingCount(baked,Material.MILK_BUCKET)==0,"craft_batch_native_result_and_all_remainders");
        var tool=crafting.content.recipes().get("wooden_pickaxe");var missing=CraftingMaterials.view(tool,Map.of());var links=crafting.planner.materialRecipes(world,crafting.content,facility,tool.id(),missing);
        check(links.equals(Map.of("OAK_PLANKS","oak_planks","STICK","stick")),"craft_batch_missing_material_links_use_registered_native_results");
        var restricted=new CraftContent.Facility("restricted",6,64,6,List.of(tool.id()));check(crafting.planner.materialRecipes(world,crafting.content,restricted,tool.id(),missing).isEmpty()&&crafting.planner.materialRecipes(world,crafting.content,facility,tool.id(),CraftingMaterials.view(tool,Map.of("OAK_PLANKS",3,"STICK",2))).isEmpty(),"craft_batch_no_links_for_unregistered_or_ready_materials");
    }
    private void craftingNativeChecks(){
        var world=Objects.requireNonNull(Bukkit.getWorld(worlds.get(a).mapping().physicalId()));var recipe=crafting.content.recipes().get("oak_planks");
        var menu=PaperCrafting.previewRecipes(crafting.content,crafting.content.facilities().get("public_workbench"),crafting.planner,world);check(menu.getSize()==54&&menu.getItem(0)!=null&&menu.getItem(0).getItemMeta().hasDisplayName()&&menu.getItem(0).getItemMeta().hasLore(),"craft_menu_actual_inventory_object_and_recipe_labels");
        var full=new ItemStack[41];Arrays.fill(full,0,36,new ItemStack(Material.STONE,64));full[0]=new ItemStack(Material.OAK_LOG,64);var before=codec.encode(full);
        try{crafting.planner.plan(world,recipe,before);throw new AssertionError("full inventory accepted");}catch(IllegalStateException expected){check(expected.getMessage().equals("CURSOR_STORAGE_FULL")&&codec.equivalent(before,codec.encode(full)),"craft_full_storage_rejected_original_preserved");}
        var wrong=new CraftContent.Recipe("wrong","잘못된 참조","minecraft:stick",recipe.matrix());try{crafting.planner.plan(world,wrong,before);throw new AssertionError("wrong recipe accepted");}catch(IllegalStateException expected){check(expected.getMessage().equals("CRAFT_RECIPE_MISMATCH"),"craft_native_recipe_key_mismatch_rejected");}
        var cake=new CraftContent.Recipe("cake","잔여물 시험","minecraft:cake",List.of("MILK_BUCKET","MILK_BUCKET","MILK_BUCKET","SUGAR","EGG","SUGAR","WHEAT","WHEAT","WHEAT"));var ingredients=PaperCraftingPlan.matrix(cake);var slots=new ItemStack[41];System.arraycopy(ingredients,0,slots,0,9);var baked=crafting.planner.plan(world,cake,codec.encode(slots));check(craftingCount(baked,Material.CAKE)==1&&craftingCount(baked,Material.BUCKET)==3&&craftingCount(baked,Material.MILK_BUCKET)==0,"craft_native_cake_result_and_three_bucket_remainders");
        var named=new ItemStack(Material.OAK_LOG,4);named.editMeta(m->m.displayName(Component.text("보존할 시험 원목")));slots=new ItemStack[41];slots[0]=named;slots[40]=new ItemStack(Material.OAK_LOG,1);var protectedItems=codec.encode(slots);
        try{crafting.planner.plan(world,recipe,protectedItems);throw new AssertionError("protected materials consumed");}catch(IllegalStateException expected){check(expected.getMessage().equals("CRAFT_MISSING_INGREDIENT")&&codec.equivalent(protectedItems,codec.encode(slots)),"craft_named_items_and_offhand_not_consumed");}
        for(UUID run:List.of(a,b)){var w=Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId()));var f=crafting.content.facilities().get("public_workbench");var block=w.getBlockAt(f.x(),f.y(),f.z());if(!block.getType().isAir())throw new IllegalStateException("CRAFT_FIXTURE_BLOCK_CONFLICT");block.setType(Material.CRAFTING_TABLE,false);crafting.ensureRegions(run);var manager=Objects.requireNonNull(com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer().get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(w)));var region=manager.getRegion("hearth_crafting_public_workbench");check(region!=null&&region.getFlag(com.sk89q.worldguard.protection.flags.Flags.INTERACT)==com.sk89q.worldguard.protection.flags.StateFlag.State.ALLOW,"craft_facility_region_"+run);}
        var namedLive=new ItemStack(Material.OAK_LOG,7);namedLive.editMeta(m->m.displayName(Component.text("보존할 원목")));one.inventory.setItem(9,new ItemStack(Material.OAK_LOG,2));one.inventory.setItem(10,namedLive);coordinator.refresh(s1);
    }
    private CompletionStage<Void> craftingPlay(){
        var initial=new InventoryPayload[1];var finalValue=new InventoryPayload[1];var revision=new long[1];var delayed=new CompletionStage<?>[1];
        return openAll().thenRun(()->{craftingBatchNativeChecks();craftingNativeChecks();initial[0]=one.capture();var p=craftingPort(one,s1,"oak_planks",()->true);crafting.actions.preview(p);check(codec.equivalent(initial[0],one.capture()),"craft_preview_does_not_consume");craftingDelay=true;craftingCaptured=false;craftingGate=new CompletableFuture<>();delayed[0]=s1.save().flush();})
        .thenCompose(v->until(()->craftingCaptured)).thenCompose(v->{var ticket=crafting.actions.preview(craftingPort(one,s1,"oak_planks",()->true));var first=crafting.actions.confirm(ticket);check(first==crafting.actions.confirm(ticket),"craft_duplicate_confirmation_shared");return first;})
        .thenRun(()->{check(s1.save().saving()&&s1.save().dirty()&&craftingCount(one.capture(),Material.OAK_PLANKS)==4,"craft_applied_during_old_save_completion_wait");craftingGate.complete(null);})
        .thenCompose(v->mainResult(delayed[0])).thenRun(()->check(s1.save().dirty()&&s1.save().revision()==1,"craft_old_save_completion_preserves_new_dirty"))
        .thenCompose(v->craftOne("stick")).thenCompose(v->craftOne("oak_planks")).thenCompose(v->craftOne("wooden_pickaxe"))
        .thenRun(()->{finalValue[0]=one.capture();check(craftingCount(finalValue[0],Material.OAK_PLANKS)==3&&craftingCount(finalValue[0],Material.STICK)==2&&craftingCount(finalValue[0],Material.WOODEN_PICKAXE)==1&&one.inventory.getItem(10).getAmount()==7,"craft_log_planks_sticks_tool_flow_and_named_stack_preserved");check(Objects.equals(codec.decode(initial[0])[36],codec.decode(finalValue[0])[36])&&Objects.equals(codec.decode(initial[0])[40],codec.decode(finalValue[0])[40]),"craft_equipment_and_offhand_preserved");})
        .thenCompose(v->{var valid=new java.util.concurrent.atomic.AtomicBoolean(true);var ticket=crafting.actions.preview(craftingPort(one,s1,"stick",valid::get));var result=crafting.actions.confirm(ticket);var drain=crafting.actions.barrier(a);valid.set(false);return rejectQuest(result).thenCompose(x->drain);})
        .thenRun(()->check(codec.equivalent(one.capture(),finalValue[0])&&crafting.actions.settled(a),"craft_stale_scope_rejected_pending_drained"))
        .thenCompose(v->coordinator.saveBoundary(s1)).thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(row->{revision[0]=row.revision();check(row.revision()==2&&codec.equivalent(row.payload(),finalValue[0]),"craft_latest_inventory_saved_revision_2");})
        .thenCompose(v->verify(a2,5,0)).thenCompose(v->verify(b3,7,0)).thenCompose(v->verify(ctx(b,1),7,0))
        .thenCompose(v->coordinator.disconnect(s1)).thenCompose(v->{one=new Controlled();s1=connect(a1,one);return s1.ready();})
        .thenRun(()->check(s1.save().revision()==revision[0]&&codec.equivalent(one.capture(),finalValue[0]),"craft_reconnect_existing_payload_restored"))
        .thenCompose(v->{var probeCtx=ctx(a,24);return mainResult(store.createPlayer(probeCtx,"craft-failure-probe")).thenCompose(x->mainResult(store.createInventory(probeCtx,fixture.apply(3)))).thenCompose(original->{var failure=new InventorySaveSession(store,probeCtx,99,()->finalValue[0],owner,SessionVerification::main,()->true,c->{});failure.markDirty();return rejectQuest(failure.flush()).thenCompose(x->mainResult(store.readInventory(probeCtx))).thenAccept(after->check(failure.dirty()&&failure.holdCode()!=null&&after.equals(original),"craft_real_DB_CAS_failure_dirty_HOLD_original_preserved"));});})
        .thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(row->check(row.revision()==revision[0]&&codec.equivalent(row.payload(),finalValue[0]),"craft_failure_other_inventory_unchanged"));
    }
    private org.bukkit.block.Container workspaceBarrel(UUID run){return (org.bukkit.block.Container)Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId())).getBlockAt(10,64,6).getState(false);}
    private Inventory workspaceChest(UUID run){return ((org.bukkit.block.Chest)Bukkit.getWorld(worlds.get(run).mapping().physicalId()).getBlockAt(12,64,6).getState(false)).getInventory();}
    private void workspaceBlocks(UUID run){
        var world=Bukkit.getWorld(worlds.get(run).mapping().physicalId());for(int x:List.of(10,12,13)){var block=world.getBlockAt(x,64,6);if(!block.getType().isAir())throw new IllegalStateException("WORKSPACE_EXISTING_BLOCK");block.setType(x==10?Material.BARREL:Material.CHEST,false);}
        var left=world.getBlockAt(12,64,6);var right=world.getBlockAt(13,64,6);var a=(org.bukkit.block.data.type.Chest)left.getBlockData();a.setFacing(org.bukkit.block.BlockFace.NORTH);a.setType(org.bukkit.block.data.type.Chest.Type.LEFT);left.setBlockData(a,false);var b=(org.bukkit.block.data.type.Chest)right.getBlockData();b.setFacing(org.bukkit.block.BlockFace.NORTH);b.setType(org.bukkit.block.data.type.Chest.Type.RIGHT);right.setBlockData(b,false);
        var manager=WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));var region=new com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion("hearth_workspace_storage",com.sk89q.worldedit.math.BlockVector3.at(9,63,5),com.sk89q.worldedit.math.BlockVector3.at(14,66,7));region.setPriority(40);region.setFlag(com.sk89q.worldguard.protection.flags.Flags.CHEST_ACCESS,com.sk89q.worldguard.protection.flags.StateFlag.State.ALLOW);region.setFlag(com.sk89q.worldguard.protection.flags.Flags.INTERACT,com.sk89q.worldguard.protection.flags.StateFlag.State.ALLOW);region.setFlag(com.sk89q.worldguard.protection.flags.Flags.PASSTHROUGH,com.sk89q.worldguard.protection.flags.StateFlag.State.ALLOW);manager.addRegion(region);
    }
    private void workspaceAccessChecks(){
        var world=Bukkit.getWorld(worlds.get(a).mapping().physicalId());var regions=WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));java.util.function.Predicate<Location> access=location->regions.getApplicableRegions(com.sk89q.worldedit.math.BlockVector3.at(location.getBlockX(),location.getBlockY(),location.getBlockZ())).testState((com.sk89q.worldguard.protection.association.RegionAssociable)null,com.sk89q.worldguard.protection.flags.Flags.CHEST_ACCESS,com.sk89q.worldguard.protection.flags.Flags.INTERACT);
        var chest=workspaceChest(a);check(chest.getSize()==54&&WorldInventoryAccess.blocks(chest).size()==2&&WorldInventoryAccess.allowed(world.getUID(),chest,access),"workspace_real_double_chest_both_blocks_and_WorldGuard_allow");
        check(!WorldInventoryAccess.allowed(Bukkit.getWorld(worlds.get(b).mapping().physicalId()).getUID(),chest,access)&&!WorldInventoryAccess.allowed(world.getUID(),Bukkit.createInventory(null,27),access),"workspace_other_run_and_GUI_inventory_denied");
        var manager=WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));var denied=new com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion("hearth_workspace_denied_half",com.sk89q.worldedit.math.BlockVector3.at(13,64,6),com.sk89q.worldedit.math.BlockVector3.at(13,64,6));denied.setPriority(50);denied.setFlag(com.sk89q.worldguard.protection.flags.Flags.CHEST_ACCESS,com.sk89q.worldguard.protection.flags.StateFlag.State.DENY);manager.addRegion(denied);
        check(!WorldInventoryAccess.allowed(world.getUID(),chest,access),"workspace_denied_second_half_rejects_entire_double_chest");manager.removeRegion(denied.getId());
        check(!WorldInventoryAccess.allowed(world.getUID(),chest,location->location.getBlockX()!=13),"workspace_progress_access_checks_both_chest_halves");
        check(WorldInventoryAccess.allowed(world.getUID(),workspaceBarrel(a).getInventory(),access),"workspace_real_barrel_uses_same_ownership_and_WorldGuard_boundary");
        check(!WorldInventoryAccess.allowed(world.getUID(),((org.bukkit.block.Container)world.getBlockAt(10,64,6).getState(true)).getSnapshotInventory(),access),"workspace_detached_snapshot_cannot_be_opened_as_live_world_storage");
    }
    private void workspaceProjectionChecks(){
        var world=Bukkit.getWorld(worlds.get(a).mapping().physicalId());var slots=new ItemStack[41];slots[0]=new ItemStack(Material.STONE,5);var inputs=new ItemStack[4];inputs[0]=new ItemStack(Material.OAK_LOG,2);
        var before=codec.encode(slots);var projected=PaperInventoryWindow.preview(codec,slots,inputs,null,new PaperInventoryWindow.Click(InventoryWindowRules.Slot.STORAGE,0,org.bukkit.event.inventory.InventoryAction.PICKUP_HALF,-1,slots[0],null));
        check(codec.decode(projected)[0].getAmount()==5&&codec.encode(slots).equals(before)&&inputs[0].getAmount()==2,"workspace_pickup_half_preflight_keeps_original_storage_and_inputs");
        var result=PaperInventoryWindow.recipe(inputs,world);check(result.getResult().getType()==Material.OAK_PLANKS&&result.getResult().getAmount()==4&&inputs[0].getAmount()==2,"workspace_actual_2x2_recipe_result_does_not_mutate_input");
        projected=PaperInventoryWindow.preview(codec,slots,inputs,null,new PaperInventoryWindow.Click(InventoryWindowRules.Slot.RESULT,0,org.bukkit.event.inventory.InventoryAction.PICKUP_ALL,-1,result.getResult(),result));var restored=codec.decode(projected);check(Arrays.stream(restored).filter(Objects::nonNull).filter(i->i.getType()==Material.OAK_PLANKS).mapToInt(ItemStack::getAmount).sum()==4&&Arrays.stream(restored).filter(Objects::nonNull).filter(i->i.getType()==Material.OAK_LOG).mapToInt(ItemStack::getAmount).sum()==1,"workspace_result_preflight_counts_output_and_remaining_matrix_once");
        var cake=new ItemStack[]{new ItemStack(Material.MILK_BUCKET),new ItemStack(Material.MILK_BUCKET),new ItemStack(Material.MILK_BUCKET),new ItemStack(Material.SUGAR),new ItemStack(Material.EGG),new ItemStack(Material.SUGAR),new ItemStack(Material.WHEAT),new ItemStack(Material.WHEAT),new ItemStack(Material.WHEAT)};var crafted=PaperInventoryWindow.recipe(cake,world);var returned=codec.withTemporary(new ItemStack[41],crafted.getResult(),crafted.getResultingMatrix());
        check(crafted.getResult().getType()==Material.CAKE&&Arrays.stream(returned).filter(Objects::nonNull).filter(i->i.getType()==Material.BUCKET).mapToInt(ItemStack::getAmount).sum()==3,"workspace_actual_3x3_recipe_remainders_preserved");
        var honey=new ItemStack[4];Arrays.fill(honey,new ItemStack(Material.HONEY_BOTTLE,2));var h=PaperInventoryWindow.recipe(honey,world);var hPreview=codec.decode(PaperInventoryWindow.preview(codec,new ItemStack[41],honey,null,new PaperInventoryWindow.Click(InventoryWindowRules.Slot.RESULT,0,org.bukkit.event.inventory.InventoryAction.PICKUP_ALL,-1,h.getResult(),h)));
        check(Arrays.stream(hPreview).filter(Objects::nonNull).filter(i->i.getType()==Material.GLASS_BOTTLE).mapToInt(ItemStack::getAmount).sum()==4&&Arrays.stream(hPreview).filter(Objects::nonNull).filter(i->i.getType()==Material.HONEY_BOTTLE).mapToInt(ItemStack::getAmount).sum()==4,"workspace_stackable_recipe_container_overflow_is_preserved");
        var full=new Controlled();for(int i=0;i<36;i++)full.inventory.setItem(i,new ItemStack(Material.STONE,64));full.matrixSize=4;full.inputs.setItem(0,new ItemStack(Material.OAK_LOG,1));full.cursor=new ItemStack(Material.OAK_PLANKS,4);var original=codec.encode(full.slots());int applies=full.applies;
        try{full.settle();throw new AssertionError("full temporary return accepted");}catch(IllegalStateException expected){check(codec.encode(full.slots()).equals(original)&&full.inputs.getItem(0).getAmount()==1&&full.cursor.getAmount()==4&&full.applies==applies,"workspace_full_return_before_mutation_preserves_grid_cursor_and_equipment");}
        try{PaperInventoryWindow.preview(codec,full.slots(),new ItemStack[0],null,new PaperInventoryWindow.Click(InventoryWindowRules.Slot.CONTAINER,0,org.bukkit.event.inventory.InventoryAction.PICKUP_ALL,-1,new ItemStack(Material.OAK_LOG),null));throw new AssertionError("full container pickup accepted");}catch(IllegalStateException expected){check(codec.encode(full.slots()).equals(original),"workspace_container_cursor_capacity_rejects_before_native_move");}
    }
    private CompletionStage<Void> inventoryWorkspacePlay(){
        var preserved=new InventoryStore.Stored[2];var expected=new InventoryPayload[1];
        return mainResult(store.readInventory(a2)).thenCompose(row->{preserved[0]=row;return mainResult(store.readInventory(ctx(b,1)));}).thenAccept(row->preserved[1]=row)
            .thenCompose(v->{CompletionStage<Void> chunks=CompletableFuture.completedFuture(null);for(UUID run:List.of(a,b))chunks=chunks.thenCompose(x->mainResult(Bukkit.getWorld(worlds.get(run).mapping().physicalId()).getChunkAtAsync(0,0))).thenAccept(chunk->chunk.addPluginChunkTicket(plugin));return chunks;})
            .thenRun(()->{workspaceBlocks(a);workspaceBlocks(b);workspaceAccessChecks();workspaceProjectionChecks();workspaceBarrel(a).getInventory().setItem(0,new ItemStack(Material.OAK_LOG,8));workspaceBarrel(b).getInventory().setItem(0,new ItemStack(Material.OAK_LOG,19));var marked=new ItemStack(Material.PAPER,3);marked.editMeta(m->{m.displayName(Component.text("공개 공동 창고 표본"));m.lore(List.of(Component.text("월드 파일 보존")));});workspaceChest(a).setItem(2,marked);workspaceChest(b).setItem(2,new ItemStack(Material.PAPER,7));})
            .thenCompose(v->mainResult(store.readInventory(a1))).thenCompose(before->{
                var barrel=workspaceBarrel(a).getInventory();barrel.setItem(0,new ItemStack(Material.OAK_LOG,4));one.inventory.setItem(14,new ItemStack(Material.OAK_LOG,4));coordinator.refresh(s1);
                return mainResult(store.readInventory(a1)).thenAccept(after->check(before.equals(after)&&s1.save().dirty()&&barrel.getItem(0).getAmount()==4,"workspace_container_move_uses_dirty_without_per_click_DB_save"));
            }).thenRun(()->{one.matrixSize=4;one.inputs.setItem(0,new ItemStack(Material.OAK_LOG,2));one.inventory.setItem(14,new ItemStack(Material.OAK_LOG,2));var result=PaperInventoryWindow.recipe(one.matrix(),Bukkit.getWorld(worlds.get(a).mapping().physicalId()));one.inputs.clear();var matrix=result.getResultingMatrix();for(int i=0;i<matrix.length;i++)one.inputs.setItem(i,matrix[i]);one.cursor=result.getResult();expected[0]=one.capture();check(one.inputs.getItem(0).getAmount()==1&&one.cursor.getAmount()==4,"workspace_live_grid_and_cursor_remain_until_explicit_boundary");})
            .thenCompose(v->coordinator.saveBoundary(s1)).thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(saved->{check(codec.equivalent(saved.payload(),expected[0])&&saved.revision()==2&&one.cursor==null&&Arrays.stream(one.matrix()).allMatch(i->i==null||i.isEmpty()),"workspace_boundary_returns_inputs_cursor_before_PG_revision2");var stable=one.capture();one.settle();check(codec.equivalent(stable,one.capture()),"workspace_repeated_close_return_is_idempotent");})
            .thenCompose(v->{var barrel=workspaceBarrel(a).getInventory();one.inventory.removeItem(new ItemStack(Material.OAK_LOG,1));barrel.addItem(new ItemStack(Material.OAK_LOG,1));coordinator.refresh(s1);var first=s1.save().flush();check(s1.save().saving(),"workspace_first_snapshot_in_flight_before_next_move");one.inventory.addItem(new ItemStack(Material.OAK_LOG,1));barrel.removeItem(new ItemStack(Material.OAK_LOG,1));coordinator.refresh(s1);return first.thenCompose(x->coordinator.saveBoundary(s1));})
            .thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(saved->check(saved.revision()==4&&codec.equivalent(saved.payload(),one.capture())&&craftingCount(one.capture(),Material.OAK_LOG)==3&&craftingCount(one.capture(),Material.OAK_PLANKS)==4,"workspace_latest_dirty_not_overwritten_by_prior_snapshot"))
            .thenCompose(v->mainResult(store.readInventory(a1))).thenCompose(before->{var failure=new InventorySaveSession(store,a1,before.revision()+1,one::capture,owner,SessionVerification::main,()->true,code->{});failure.markDirty();return rejectQuest(failure.flush()).thenCompose(x->mainResult(store.readInventory(a1))).thenAccept(after->check(before.equals(after)&&failure.dirty()&&failure.holdCode()!=null&&workspaceBarrel(a).getInventory().getItem(0).getAmount()==4,"workspace_real_PG_CAS_failure_preserves_source_dirty_and_world_storage"));})
            .thenCompose(v->mainResult(store.readInventory(a2))).thenCompose(after->{check(after.equals(preserved[0]),"workspace_other_person_inventory_preserved");return mainResult(store.readInventory(ctx(b,1)));}).thenAccept(after->check(after.equals(preserved[1])&&workspaceBarrel(b).getInventory().getItem(0).getAmount()==19,"workspace_other_run_DB_and_world_storage_preserved"))
            .thenRun(()->{for(UUID run:List.of(a,b)){Bukkit.getWorld(worlds.get(run).mapping().physicalId()).getChunkAt(0,0).removePluginChunkTicket(plugin);}});
    }
    private CompletionStage<Void> inventoryWorkspaceRestored(){
        return openAll().thenCompose(v->{CompletionStage<Void> chunks=CompletableFuture.completedFuture(null);for(UUID run:List.of(a,b))chunks=chunks.thenCompose(x->mainResult(Bukkit.getWorld(worlds.get(run).mapping().physicalId()).getChunkAtAsync(0,0))).thenAccept(chunk->chunk.addPluginChunkTicket(plugin));return chunks;})
            .thenRun(()->{check(craftingCount(one.capture(),Material.OAK_LOG)==3&&craftingCount(one.capture(),Material.OAK_PLANKS)==4&&s1.save().revision()==4&&one.matrixSize==0&&one.cursor==null,"workspace_second_JVM_canonical_inventory_restored_without_transient_replay");check(workspaceBarrel(a).getInventory().getItem(0).getAmount()==4&&workspaceBarrel(b).getInventory().getItem(0).getAmount()==19,"workspace_second_JVM_world_barrels_and_progress_isolation");var item=workspaceChest(a).getItem(2);check(item.getAmount()==3&&item.getItemMeta().hasDisplayName()&&item.getItemMeta().hasLore()&&workspaceChest(b).getItem(2).getAmount()==7,"workspace_second_JVM_double_chest_metadata_and_other_run_preserved");workspaceAccessChecks();for(UUID run:List.of(a,b))Bukkit.getWorld(worlds.get(run).mapping().physicalId()).getChunkAt(0,0).removePluginChunkTicket(plugin);})
            .thenCompose(v->verify(a2,5,0));
    }
    private CompletionStage<Void> interactionPlay(){
        var beforeB=new InventoryStore.Stored[1];
        return openAll().thenCompose(v->mainResult(store.readInventory(ctx(b,1)))).thenCompose(v->{beforeB[0]=v;cursorProjectionChecks();
            check(PlayerInteractionRules.inventory(new PlayerInteractionRules.InventoryRequest(true,true,true,true,9,"NUMBER_KEY","HOTBAR_SWAP",0)),"interaction_hotbar_policy_allowed");
            check(!PlayerInteractionRules.inventory(new PlayerInteractionRules.InventoryRequest(false,true,true,true,9,"NUMBER_KEY","HOTBAR_SWAP",0)),"interaction_unprepared_denied");
            check(!PlayerInteractionRules.inventory(new PlayerInteractionRules.InventoryRequest(true,false,true,true,9,"NUMBER_KEY","HOTBAR_SWAP",0)),"interaction_GUI_and_containers_denied");
            check(PlayerInteractionRules.facility("OAK_DOOR")&&!PlayerInteractionRules.facility("CHEST"),"interaction_facility_policy");
            var initial=one.inventory.getItem(0);one.inventory.setItem(0,one.inventory.getItem(9));one.inventory.setItem(9,initial);
            var offhand=one.inventory.getItem(40);one.inventory.setItem(40,one.inventory.getItem(9));one.inventory.setItem(9,offhand);
            var carried=one.inventory.getItem(9);one.cursor=carried.clone();one.inventory.setItem(9,null);
            var expected=one.capture();check(one.cursor.equals(carried),"cursor_projection_does_not_modify_live_items");
            coordinator.refresh(s1);check(s1.save().dirty(),"interaction_existing_dirty_path");
            return s1.save().flush().thenCompose(x->mainResult(store.readInventory(a1))).thenCompose(saved->{
                check(codec.equivalent(saved.payload(),expected)&&saved.revision()==1,"interaction_hotbar_offhand_slots_and_metadata_saved");
                return verify(a2,5,0).thenCompose(x->mainResult(store.readInventory(ctx(b,1)))).thenCompose(afterB->{check(afterB.equals(beforeB[0]),"interaction_other_run_inventory_unchanged");return coordinator.disconnect(s1);}).thenCompose(x->{
                    check(one.cursor==null,"cursor_logout_settled_before_final_capture");one=new Controlled();s1=connect(a1,one);return s1.ready();
                }).thenRun(()->check(codec.equivalent(one.capture(),expected)&&s1.save().revision()==1,"interaction_reconnect_restores_slot_changes"));
            });
        });
    }
    private void cursorProjectionChecks(){
        var cursor=new ItemStack(Material.STONE,12);var slots=new ItemStack[41];slots[0]=new ItemStack(Material.STONE,60);
        var projected=codec.withCursor(slots,cursor);check(slots[0].getAmount()==60&&cursor.getAmount()==12&&projected[0].getAmount()==64&&projected[1].getAmount()==8,"cursor_merge_split_and_input_preservation");
        var named=cursor.clone();var meta=named.getItemMeta();meta.displayName(Component.text("공개 시험 표본"));meta.lore(List.of(Component.text("개인 보관 시험")));named.setItemMeta(meta);
        projected=codec.withCursor(slots,named);check(projected[0].getAmount()==60&&projected[1].equals(named)&&codec.decode(codec.encode(projected))[1].equals(named),"cursor_metadata_no_wrong_merge_roundtrip");
        for(int i=0;i<36;i++)slots[i]=new ItemStack(Material.STONE,64);
        var original=codec.encode(slots);boolean rejected=false;try{codec.withCursor(slots,named);}catch(IllegalStateException failure){rejected=failure.getMessage().equals("CURSOR_STORAGE_FULL");}
        check(rejected&&codec.encode(slots).equals(original)&&named.getAmount()==12,"cursor_full_storage_rejected_without_mutation_or_armor_spill");
        check(PlayerInteractionRules.inventory(new PlayerInteractionRules.InventoryRequest(true,true,true,false,9,"RIGHT","PLACE_ONE",0))&&PlayerInteractionRules.drag(true,true,Set.of(9,10)),"cursor_click_drag_policy_connected");
    }
    private CompletionStage<Void> interactionWriteFailure(){
        return mainResult(store.readInventory(a1)).thenCompose(before->{
            var slots=codec.decode(before.payload());slots[10]=new ItemStack(Material.STONE,2);var snapshot=codec.encode(slots);
            var probe=new InventorySaveSession(store,a1,before.revision(),()->snapshot,owner,SessionVerification::main,()->true,code->{});probe.markDirty();
            return expect(probe.flush(),"23514").thenCompose(v->mainResult(store.readInventory(a1))).thenAccept(after->{
                check(after.equals(before)&&probe.dirty()&&probe.revision()==before.revision()&&"23514".equals(probe.holdCode()),"cursor_DB_write_failure_original_dirty_revision_HOLD_preserved");
            });
        });
    }
    private StoryGuide.Action guideAction(UUID run){return StoryGuide.panel(run,sharedStory.content(),sharedStory.cached(run),sharedStory.phase(run),new CommunityBoundary.Status(false,List.of("진행 조건 확인 필요"))).action().orElseThrow();}
    private CompletionStage<Void> gameplayGuide(){
        var before=sharedStory.cached(a);var lines=StoryGuide.lines(sharedStory.content(),before,sharedStory.phase(a));
        check(sharedStory.content().guideNpcs().contains("survey_guide"),"guide_content_npc_link");
        check(lines.stream().anyMatch(line->"/hearthphone".equals(line.command())),"guide_phone_chat_link");
        check(lines.stream().noneMatch(line->line.text().contains("survey_marker")||line.text().contains("path_marker")),"guide_private_condition_ids_hidden");
        return journal.load(a1,s1,()->coordinator.playable(s1)).thenCompose(view->{
            var buttons=QuestScreens.screen(catalog,view,"story",null,0);
            check(buttons.stream().anyMatch(button->button.action().equals("story-guide")),"guide_GUI_shared_book_entry");
            var panel=StoryGuide.panel(a,sharedStory.content(),sharedStory.cached(a),sharedStory.phase(a),view.exploration().conditions());
            var actions=panel.action().stream().map(action->Component.text(action.label())).map(c->(Component)c).toList();var book=PaperQuestUI.book(new QuestBookView("공동 조사 안내",panel.lines()),actions);
            check(!book.pages().isEmpty()&&panel.lines().stream().noneMatch(line->line.contains("survey_marker")||line.contains("path_marker")),"guide_Paper_book_public_summary_no_private_conditions");
            var npcRows=QuestScreens.npcGuide(QuestScreens.npc(catalog,"survey_guide",view.states(),q->true,0),sharedStory.content().guideNpcs().contains("survey_guide"));
            check(npcRows.stream().filter(button->button.action().equals("npc-story")).count()==1,"guide_NPC_inventory_single_shared_entry");
            if(plugin.getConfig().getBoolean("phone-dialog-enabled",false)){
                var dialogActions=panel.action().stream().map(action->io.papermc.paper.registry.data.dialog.ActionButton.builder(Component.text(action.label())).width(110).build()).toList();
                var dialog=io.github.jesterportfolio.hearth.phone.PaperPhone.compose("공동 조사 안내",panel.lines().stream().map(line->io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(Component.text(line))).toList(),List.of(),dialogActions);
                check(dialog!=null&&panel.action().stream().noneMatch(action->action.command().startsWith("/hearthstory vote ")),"guide_Dialog_request_only_no_ballot_choices");
            }
            return mainResult(store.readStory(a1));
        }).thenAccept(saved->{main();check(saved.equals(before),"guide_read_only_revision_unchanged");});
    }
    private CompletionStage<Void> storyPrepare(){var legacy=ctx(new UUID(42,99),99);return openAll().thenRun(()->{storyTestRoster=new HashMap<>(Map.of(a1.playerId(),s1,a2.playerId(),s2));sharedStory.rosterChanged(a1);sharedStory.rosterChanged(b3);})
        .thenCompose(v->mainResult(store.readExploration(legacy))).thenAccept(data->check(data.story().revision()==3 && data.story().stage().equals("public_test_stage") && data.places().get("public_village").hub(),"story_V005_rows_preserved_after_V006"))
        .thenCompose(v->seedList(a1,"survey_marker",QuestStore.Phase.COMPLETED))
        .thenCompose(v->{oldGuideAction=guideAction(a);return expectQuestCode(sharedStory.act(a1,s1,()->true,oldGuideAction),"CONDITIONS_UNMET");})
        .thenRun(()->check(sharedStory.cached(a).story().stage()==null && !paperStory.access().entry(a,21,64,21) && paperStory.access().entry(a,2,64,2) && !paperStory.access().content(a,21,64,3),"story_P2_blocks_first_stage_ENTRY_CONTENT_OPEN_separate"))
        .thenCompose(v->seedList(a2,"survey_marker",QuestStore.Phase.COMPLETED))
        .thenCompose(v->{var first=sharedStory.act(a1,s1,()->true,oldGuideAction);var duplicate=expectQuestCode(sharedStory.act(a2,s2,()->true,oldGuideAction),"STALE_STORY_ACTION");return first.thenCombine(duplicate,(s,x)->s);})
        .thenAccept(value->{var s=(StoryStore.Snapshot)value;check(s.story().stage().equals("trial_arrival")&&s.story().revision()==0,"story_single_initial_CAS_shared_state");})
        .thenCompose(v->expectQuestCode(sharedStory.act(a1,s1,()->true,oldGuideAction),"STALE_STORY_ACTION"))
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
            .thenCompose(v->after.resolve(confirmed)).thenCompose(saved->{check(saved.branches().get("trial_vote").choice().equals("route_one"),"story_same_request_receipt_resolved_branch");return after.barrier(confirmed.storyRunId());}).thenRun(()->check(applied[0]==1&&after.effectStatus(confirmed.storyRunId()).equals("NOT_IMPLEMENTED"),"story_same_request_receipt_positive_resolve_once_not_real_world_effect"))
            .thenCompose(v->expectQuestCode(after.resolve(confirmed),"NO_UNKNOWN_REQUEST"));}
    private CompletionStage<Void> storyFailure(){var ctx=ctx(new UUID(42,14),14);Object token=new Object();storyFailureProbe=storyProbe(store,ctx,token);var before=new StoryStore.Snapshot[2];return mainResult(store.readStory(a1)).thenAccept(value->before[0]=value).thenCompose(v->mainResult(store.readStory(b3))).thenAccept(value->before[1]=value).thenCompose(v->mainResult(store.createRun(ctx.storyRunId(),"story-failure-only")))
        .thenCompose(v->mainResult(store.createPlayer(ctx,"story-failure-only"))).thenCompose(v->seedList(ctx,"survey_marker",QuestStore.Phase.COMPLETED)).thenCompose(v->storyFailureProbe.load(ctx))
        .thenCompose(v->{var action=StoryGuide.panel(ctx.storyRunId(),storyFailureProbe.content(),storyFailureProbe.cached(ctx.storyRunId()),storyFailureProbe.phase(ctx.storyRunId()),new CommunityBoundary.Status(false,List.of("진행 조건 확인 필요"))).action().orElseThrow();return expect(storyFailureProbe.act(ctx,token,()->true,action),"23514");})
        .thenRun(()->check(storyFailureProbe.phase(ctx.storyRunId())==SharedStory.Phase.HOLD&&storyFailureProbe.barrier(ctx.storyRunId()).toCompletableFuture().isCompletedExceptionally(),"story_SQL_failure_probe_HOLD_preserved"))
        .thenCompose(v->mainResult(store.readStory(ctx))).thenAccept(s->check(s.story().stage()==null&&s.branches().isEmpty()&&s.completed().isEmpty(),"story_SQL_failure_no_false_stage_branch_or_completion"))
        .thenCompose(v->mainResult(store.readExploration(ctx))).thenAccept(d->check(d.facts().isEmpty()&&d.events().isEmpty(),"story_SQL_failure_no_partial_shared_diary"))
        .thenCompose(v->mainResult(store.readStory(a1))).thenAccept(s->check(s.equals(before[0]),"story_failed_run_other_run_A_unchanged"))
        .thenCompose(v->mainResult(store.readStory(b3))).thenAccept(s->check(s.equals(before[1]),"story_failed_run_other_run_B_unchanged"));}
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
        check(rows.stream().anyMatch(b->b.action().equals("book")) && rows.stream().anyMatch(b->b.action().equals("track"))&&QuestBookView.accepted(catalog,view.states(),view.exploration().blockedQuests(),"route_marker").orElseThrow().pages().toString().replace("\n","").contains("[2/3]"),"batch_GUI_book_current_step_and_track_button");
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
    public CompletionStage<Void> barrier(UUID run){return (features==null?CompletableFuture.<Void>completedFuture(null):features.barrier(run)).thenComposeAsync(v->crafting==null?CompletableFuture.<Void>completedFuture(null):crafting.barrier(run),owner).thenComposeAsync(v->escort==null?CompletableFuture.completedFuture(null):escort.barrier(run),owner).thenComposeAsync(v->dungeonPlay==null?CompletableFuture.completedFuture(null):dungeonPlay.barrier(run),owner).thenComposeAsync(v->gathering==null?CompletableFuture.completedFuture(null):gathering.drain(run),owner).thenComposeAsync(v->paperTravel==null?CompletableFuture.completedFuture(null):paperTravel.barrier(run),owner).thenComposeAsync(v->coordinator.barrier(run),owner).thenComposeAsync(v->combatPlayers==null?CompletableFuture.completedFuture(null):combatPlayers.barrier(run),owner).thenComposeAsync(v->quests==null ? CompletableFuture.completedFuture(null):quests.barrier(run).thenCompose(v2->journal.barrier(run)).thenCompose(v2->sharedStory==null?CompletableFuture.completedFuture(null):sharedStory.barrier(run)),owner);}
    public boolean canUnload(UUID run){return (crafting==null||crafting.settled(run))&&(gathering==null||gathering.settled(run))&&(paperTravel==null||paperTravel.settled(run))&&coordinator.canUnload(run) && (quests==null || quests.settled(run) && journal.settled(run) && (sharedStory==null||sharedStory.settled(run)));}
    public CompletionStage<Void> restoreStoryWorld(UUID run){if(manualS4!=null){prepareCourse(run);prepareManualS4(run);}if(gathering!=null)gathering.ensureFacilities(run);if(paperTravel!=null)paperTravel.ensureRegions(run);if(blockEffect==null)return CompletableFuture.completedFuture(null);blockEffect.prepared(run);var context=ctx(run,1);return sharedStory.load(context).thenComposeAsync(s->sharedStory.reconcile(context),owner).thenAccept(ok->{});}
    public void releaseQuests(UUID run){if(dungeonPlay!=null)dungeonPlay.unloaded(run);if(gathering!=null)gathering.unloaded(run);if(quests!=null)quests.release(run);if(blockEffect!=null){blockEffect.unloaded(run);sharedStory.worldUnloaded(run);}}
    public CompletionStage<Void> shutdown(){
        if(manual && !Bukkit.getOnlinePlayers().isEmpty())return CompletableFuture.failedFuture(new LifecycleFailure("MANUAL_PLAYER_STILL_ONLINE"));
        if(recoveryConsole!=null)recoveryConsole.close();if(escort!=null)escort.close();if(dungeonPlay!=null)dungeonPlay.close();if(gathering!=null)gathering.close();if(paperTravel!=null)paperTravel.close();if(combatPlayers!=null)combatPlayers.close();if(combatTrials!=null)combatTrials.close();if(fieldEncounters!=null)fieldEncounters.close();if(paperStory!=null)paperStory.close();if(questAdapter!=null)questAdapter.stopUi();autosave.close();adapter.close();
        CompletionStage<Void> other=s3==null ? CompletableFuture.completedFuture(null) : coordinator.disconnect(s3);
        return (recoveryConsole==null?CompletableFuture.<Void>completedFuture(null):recoveryConsole.barrier()).thenComposeAsync(v->manualStatus,owner).thenComposeAsync(v->crafting==null?CompletableFuture.<Void>completedFuture(null):CompletableFuture.allOf(worlds.keySet().stream().map(crafting::barrier).map(CompletionStage::toCompletableFuture).toArray(CompletableFuture[]::new)),owner).thenCompose(v->gathering==null?CompletableFuture.<Void>completedFuture(null):CompletableFuture.allOf(worlds.keySet().stream().map(gathering::drain).map(CompletionStage::toCompletableFuture).toArray(CompletableFuture[]::new))).thenCompose(v->economy==null?CompletableFuture.<Void>completedFuture(null):economy.shutdown()).thenCompose(v->mainResult(other)).thenCompose(ignored -> coordinator.shutdown().handleAsync((v,error)->{
            if(error!=null && !(expectedHold && "23514".equals(s1.error()) && "MOVE_FAILED".equals(s2.error())
                    && generationProbes.size()==2 && generationProbes.values().stream().allMatch(s->s.state()==PlayerSessions.State.HOLD && "STALE_WORLD".equals(s.error()))
                    && Set.of("23514","MOVE_FAILED","STALE_WORLD").contains(LifecycleFailure.code(error))))throw new CompletionException(error);
            log("DRAIN "+(error==null ? "PASS" : "EXPECTED_HOLD_PRESERVED"));return null;
        },owner)).thenCompose(v->paperTravel==null?CompletableFuture.completedFuture(null):CompletableFuture.allOf(worlds.keySet().stream().map(paperTravel::barrier).map(CompletionStage::toCompletableFuture).toArray(CompletableFuture[]::new))).thenCompose(v->journal==null?CompletableFuture.completedFuture(null):journal.shutdown()).thenCompose(v->quests==null?CompletableFuture.completedFuture(null):quests.shutdown()).thenCompose(v->sharedStory==null?CompletableFuture.completedFuture(null):sharedStory.shutdown());
    }
    private CompletionStage<Void> linkExisting(ServerProgressAccess.Context context) {
        return mainResult(store.readPersonal(context)).thenCompose(v -> mainResult(store.readInventory(context))).thenCompose(saved->(sharedStory==null?CompletableFuture.completedFuture(null):sharedStory.load(context)).thenApply(v->saved)).thenAccept(saved -> {
            codec.decode(saved.payload());
            adapter.fixture(new PaperSessionAdapter.Link(context,worlds.get(context.storyRunId()),()->destination(context.storyRunId())));
        });
    }
    public void economyAdministration(String[] args){if(economy==null)throw new IllegalStateException("Economy not ready");economy.economy.administration(args);}
    public void economyFixture(String name,boolean existing){if(economy==null)throw new IllegalStateException("Economy not ready");economy.economy.fixture(name,existing);}
    public void fixturePlayer(String name,boolean existing){
        main();if(!manual)throw new IllegalStateException("실제 프로필은 별도 수동 시험 환경에서만 준비합니다");
        var player=Bukkit.getWhitelistedPlayers().stream().filter(p->name.equals(p.getName())).findFirst().orElseThrow(()->new IllegalArgumentException("Whitelist profile required"));
        var context=access.forVerifiedServerLink(a,player.getUniqueId());
        CompletionStage<?> work=existing ? CompletableFuture.completedFuture(null) : mainResult(store.createPlayer(context,"fixture"))
            .thenCompose(v -> mainResult(store.createInventory(context,fixture.apply(32))));
        work.thenCompose(v -> manualS4==null?CompletableFuture.completedFuture(null):features.anchoredHud.ready()).thenCompose(v -> linkExisting(context)).thenRun(()->log("FIXTURE READY"))
            .exceptionally(error->{log("FIXTURE HOLD "+LifecycleFailure.code(error));return null;});
    }
    public void playerStatus(String name){
        main();if(!manual)throw new IllegalStateException("수동 시험 전용입니다");
        var profile=Bukkit.getWhitelistedPlayers().stream().filter(p->name.equals(p.getName())).findFirst().orElseThrow();
        var context=access.forVerifiedServerLink(a,profile.getUniqueId());
        log("MANUAL "+adapter.status(profile.getUniqueId())+" world_state="+worlds.get(a).state()+" world_epoch="+worlds.get(a).generation());
        if(!manualStatus.toCompletableFuture().isDone()){log("MANUAL_QUERY PENDING");return;}
        CompletionStage<Void> work=CompletableFuture.completedFuture(null);
        if(quests!=null)for(QuestContent c:catalog.all())work=work.thenCompose(v->mainResult(store.readQuest(context,c.id())).thenAccept(row->{var key=profile.getUniqueId()+"/"+c.id();var value=row.phase()+"/"+row.revision();if(!value.equals(manualQuestStatus.put(key,value)))log("MANUAL_QUEST id="+c.id()+" phase="+row.phase()+" revision="+row.revision());}));
        manualStatus=work.thenCompose(v->mainResult(store.readInventory(context))).thenAccept(saved->{var slots=codec.decode(saved.payload());log("MANUAL_DB count="+(slots[0]==null?0:slots[0].getAmount())+" revision="+saved.revision());})
            .exceptionally(error->{log("MANUAL_QUERY_ERROR "+LifecycleFailure.code(error));return null;});
    }
    @Override public void close(){if(crafting!=null)crafting.close();if(paperTravel!=null)paperTravel.close();
        io.github.jesterportfolio.hearth.util.ShutdownCleanup.close(manualS4,recoveryConsole,features,escort,dungeonPlay,gathering,economy,combatPlayers,combatTrials,fieldEncounters,questAdapter,autosave,adapter);
    }
}
