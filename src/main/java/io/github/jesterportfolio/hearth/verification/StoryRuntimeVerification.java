package io.github.jesterportfolio.hearth.verification;

import io.github.jesterportfolio.hearth.story.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.session.PlayerSessions;
import io.github.jesterportfolio.hearth.lifecycle.WorldLifecycle;
import io.github.jesterportfolio.hearth.world.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.*;
import org.bukkit.event.world.WorldLoadEvent;

/** 새 사용자 소유 격리 환경 전용. 인증 Player·클릭·AI 추론을 만들거나 대체하지 않습니다. */
public final class StoryRuntimeVerification {
    private static final UUID PLAYER=new UUID(49,1),NEW=new UUID(49,71),RACE=new UUID(49,72),FAILED=new UUID(49,73),ORPHAN=new UUID(49,74);
    private StoryRuntimeVerification(){}
    private static Path root(JavaPlugin plugin)throws Exception{if(!Boolean.getBoolean("hearth.verification.storyRuntime")||!Bukkit.getOnlinePlayers().isEmpty())throw new IllegalStateException("STORY_RUNTIME_PROBE_GATE");var root=IsolatedPaths.require(Path.of("").toRealPath().getParent(),"hearth-s4-11.");if(!Files.readString(root.resolve(".hearth-lifecycle-isolated")).equals("S4-11 isolated test only\n")||!plugin.getDataFolder().toPath().toRealPath().equals(root.resolve("server/plugins/Hearth")))throw new IllegalStateException("STORY_RUNTIME_PROBE_GATE");return root;}
    private static void check(JavaPlugin plugin,boolean ok,String name){if(!ok)throw new IllegalStateException("STORY_RUNTIME_CHECK_FAILED_"+name);plugin.getLogger().info("HEARTH_SESSION_CHECK PASS story_runtime_"+name);}
    private static <T> CompletionStage<T> on(CompletionStage<T> stage,Executor owner){return stage.thenApplyAsync(v->v,owner);}
    private static CompletionStage<Void> reject(CompletionStage<?> stage,JavaPlugin plugin,String name,Executor owner){return stage.handleAsync((v,error)->{check(plugin,error!=null,name);return (Void)null;},owner);}
    private static String inventory(InventoryStore.Stored saved){return saved.revision()+"/"+Base64.getEncoder().encodeToString(saved.payload().bytes());}
    public static CompletionStage<Void> seed(JavaPlugin plugin,PostgresProgressStore database,ServerProgressAccess access,Map<UUID,WorldLifecycle> worlds,PaperInventoryCodec codec,Executor owner){
        final Path root;final Map<UUID,StoryBinding> bindings=new LinkedHashMap<>();final String hash;
        try{
            root=root(plugin);var folder=plugin.getDataFolder().toPath();var bundle=new YamlConfiguration();
            for(var section:Map.of("quests","quest-demo.yml","exploration","exploration-demo.yml","shared","story-demo.yml","rules","quest-rules-demo.yml","scenes","cutscene-demo.yml").entrySet()){
                var y=new YamlConfiguration();try(var input=Objects.requireNonNull(plugin.getResource(section.getValue()))){y.loadFromString(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}bundle.set(section.getKey(),StoryRuntime.map(y));
            }
            if(Boolean.getBoolean("hearth.verification.storyModules"))PublicStoryModuleFixture.enrich(plugin,bundle);
            var parsed=StoryContentBundle.parse(StoryRuntime.map(bundle));var contentFile=folder.resolve("story-runtime-content.yml");if(Files.exists(contentFile))throw new IllegalStateException("Fixture content already present");bundle.save(contentFile.toFile());hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(contentFile)));
            var runs=new ArrayList<Map<String,Object>>();var backend=new PaperWorldBackend();
            for(var world:worlds.values().stream().filter(w->w.mapping().storyRunId().equals(new UUID(42,10))||w.mapping().storyRunId().equals(new UUID(42,11))).toList()){
                var m=world.mapping();var binding=new StoryBinding(m.storyRunId(),m.worldKey(),m.physicalId(),"native-prepared-v1","a".repeat(64),hash);bindings.put(binding.run(),binding);
                var p=new WorldBackend.Placement(m.storyRunId(),Objects.requireNonNull(NamespacedKey.fromString(m.worldKey())),m.physicalId(),m.directory());backend.registerExisting(p);var loaded=backend.load(p);
                for(int x=1;x<8;x++)for(int z=1;z<8;z++)if(loaded.getBlockAt(x,63,z).getType().isAir())loaded.getBlockAt(x,63,z).setType(Material.STONE,false);
                if(Boolean.getBoolean("hearth.verification.storyModules"))PublicStoryModuleFixture.prepare(loaded,parsed);
                backend.saveAndUnload(p);Files.writeString(m.directory().resolve(".hearth-story-world"),binding.run()+"/"+binding.worldKey()+"/"+binding.worldId()+"\n"+binding.templateVersion()+"/"+binding.templateHash()+"\n",StandardOpenOption.CREATE_NEW);
                var row=new LinkedHashMap<String,Object>();row.put("run",binding.run().toString());row.put("world-key",binding.worldKey());row.put("world-uuid",binding.worldId().toString());row.put("template-version",binding.templateVersion());row.put("template-sha256",binding.templateHash());row.put("entry-x",2.5);row.put("entry-y",64);row.put("entry-z",Boolean.getBoolean("hearth.verification.storyModules")?1.5:2.5);row.put("entry-yaw",0);row.put("entry-pitch",0);row.put("required-regions",List.of("protected"));runs.add(row);
            }
            var run=new UUID(42,10);var endpoint=new Properties();try(var input=Files.newInputStream(root.resolve("db-endpoint.properties"))){endpoint.load(input);}
            var config=new YamlConfiguration();config.set("runtime-mode","story");config.set("phone-dialog-enabled",true);config.set("phone-ai-mode","ollama");config.set("phone-ai-endpoint","http://127.0.0.1:11435/api/generate");
            var s=config.createSection("story-runtime");s.set("runs",runs);s.set("members",List.of(Map.of("player",PLAYER.toString(),"run",run.toString()),Map.of("player",NEW.toString(),"run",run.toString())));s.set("content-file",contentFile.getFileName().toString());s.set("content-sha256",hash);s.set("idle-seconds",1);s.set("capacity",4);s.set("db-host","127.0.0.1");s.set("db-port",Integer.parseInt(endpoint.getProperty("port")));s.set("db-name","hearth_s4_11");s.set("db-user","postgres");s.set("password-environment","HEARTH_ISOLATED_STORY_PASSWORD");StoryRuntimeSettings.parse(s.getValues(false));config.save(folder.resolve("normal-story.yml").toFile());
        }catch(Exception error){return CompletableFuture.failedFuture(error);}
        var registry=new StoryRegistry(database,access);var a=bindings.get(new UUID(42,10));var b=bindings.get(new UUID(42,11));var context=access.forVerifiedServerLink(a.run(),PLAYER);var empty=codec.encode(new ItemStack[41]);var expected=new Properties();
        var chain=on(registry.migrate(),owner).thenCompose(v->on(database.readInventory(context),owner)).thenAccept(row->expected.setProperty("inventory",inventory(row))).thenCompose(v->on(database.readShared(context),owner)).thenAccept(row->expected.setProperty("shared",row.revision()+"/"+row.value()));
        for(var binding:bindings.values())chain=chain.thenCompose(v->on(registry.ensure(binding),owner));
        if(Boolean.getBoolean("hearth.verification.storyRuntimeRecheck"))return chain.thenCompose(v->on(registry.admit(context,a,true,empty),owner)).thenAccept(row->{try(var output=Files.newOutputStream(root.resolve("story-runtime-expected.properties"),StandardOpenOption.CREATE_NEW)){expected.store(output,"Synthetic restore input only");}catch(Exception error){throw new CompletionException(error);}});
        return chain.thenCompose(v->on(registry.ensure(a).thenCombine(registry.ensure(a),(x,y)->true),owner)).thenAccept(ok->check(plugin,ok,"concurrent_identical_world_binding_preserved"))
            .thenCompose(v->reject(registry.ensure(new StoryBinding(a.run(),a.worldKey(),a.worldId(),a.templateVersion(),a.templateHash(),"c".repeat(64))),plugin,"content_change_rejected_without_rebinding",owner))
            .thenCompose(v->reject(registry.ensure(new StoryBinding(b.run(),a.worldKey(),a.worldId(),b.templateVersion(),b.templateHash(),hash)),plugin,"physical_world_cannot_belong_to_two_runs",owner))
            .thenCompose(v->on(registry.admit(context,a,true,empty),owner)).thenAccept(row->check(plugin,expected.getProperty("inventory").equals(inventory(row)),"legacy_saved_inventory_not_replaced_with_empty"))
            .thenCompose(v->{var one=registry.admit(access.forVerifiedServerLink(a.run(),RACE),a,true,empty).handle((r,e)->e==null);var two=registry.admit(access.forVerifiedServerLink(b.run(),RACE),b,true,empty).handle((r,e)->e==null);return on(one.thenCombine(two,(x,y)->x!=y),owner);}).thenAccept(ok->check(plugin,ok,"account_cross_run_race_has_one_owner"))
            .thenCompose(v->on(database.createPlayer(access.forVerifiedServerLink(a.run(),ORPHAN),"preserved"),owner))
            .thenCompose(v->reject(registry.admit(access.forVerifiedServerLink(a.run(),ORPHAN),a,true,empty),plugin,"missing_legacy_inventory_is_HOLD_not_empty_recreation",owner))
            .thenCompose(v->on(database.module("story_fixture_failure",c->{try(var st=c.createStatement()){st.execute("CREATE FUNCTION hearth_runtime.reject_fixture_inventory() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.player_uuid='"+FAILED+"'::uuid THEN RAISE EXCEPTION 'isolated fixture' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");st.execute("CREATE TRIGGER reject_fixture_inventory BEFORE INSERT ON hearth_progress.story_inventory FOR EACH ROW EXECUTE FUNCTION hearth_runtime.reject_fixture_inventory()");}return null;}),owner))
            .thenCompose(v->reject(registry.admit(access.forVerifiedServerLink(a.run(),FAILED),a,true,empty),plugin,"real_SQL_inventory_failure_refused",owner))
            .thenCompose(v->on(database.module("story_fixture_failure_check",c->{try(var st=c.createStatement();var r=st.executeQuery("SELECT NOT EXISTS(SELECT 1 FROM hearth_progress.player_progress WHERE player_uuid='"+FAILED+"') AND NOT EXISTS(SELECT 1 FROM hearth_runtime.story_member WHERE player_uuid='"+FAILED+"')")){r.next();return r.getBoolean(1);}}),owner)).thenAccept(ok->check(plugin,ok,"failed_admission_transaction_keeps_no_partial_member_or_progress"))
            .thenCompose(v->on(registry.read(a.run()),owner)).thenAccept(row->check(plugin,row.equals(a),"binding_original_preserved_after_conflicts"))
            .thenCompose(v->on(database.readShared(context),owner)).thenAccept(row->{check(plugin,expected.getProperty("shared").equals(row.revision()+"/"+row.value()),"existing_shared_progress_preserved");try(var output=Files.newOutputStream(root.resolve("story-runtime-expected.properties"),StandardOpenOption.CREATE_NEW)){expected.store(output,"Synthetic proof only");}catch(Exception error){throw new CompletionException(error);}});
    }
    private static CompletionStage<Void> modules(JavaPlugin plugin,StoryRuntime runtime,ServerProgressAccess.Context context,PlayerSessions.Session session,Runnable releasePins){
        var m=runtime.modules();check(plugin,m.crafting!=null&&m.gathering!=null&&m.travel!=null&&m.escort!=null&&m.trials!=null&&m.fields!=null&&m.combat!=null&&m.dungeon!=null,"normal_all_gameplay_modules_connected_without_verification_flags");
        var owner=runtime.owner();var database=runtime.database();var preserved=new java.util.concurrent.atomic.AtomicReference<InventoryStore.Stored>();
        var other=runtime.access().forVerifiedServerLink(new UUID(42,11),PLAYER);
        return on(database.readInventory(context),owner).thenAccept(preserved::set)
            .thenCompose(v->reject(m.prepare(other),plugin,"unloaded_other_run_rejected_before_gathering_creation",owner))
            .thenCompose(v->on(database.module("module_fixture_reject",c->{try(var st=c.createStatement()){st.execute("CREATE FUNCTION hearth_runtime.reject_module_gathering() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated module fixture' USING ERRCODE='23514'; END $$");st.execute("CREATE TRIGGER reject_module_gathering BEFORE INSERT ON hearth_progress.gathering_instance FOR EACH ROW EXECUTE FUNCTION hearth_runtime.reject_module_gathering()");}return null;}),owner))
            .thenCompose(v->{var one=m.prepare(context);var two=m.prepare(context);check(plugin,one==two,"concurrent_gathering_preparation_shares_one_job");return reject(one,plugin,"real_gathering_INSERT_failure_is_preserved_not_reinitialized",owner);})
            .thenCompose(v->on(database.module("module_fixture_original",c->{try(var st=c.createStatement();var rows=st.executeQuery("SELECT count(*) FROM hearth_progress.gathering_instance")){rows.next();return rows.getLong(1)==0;}}),owner)).thenAccept(ok->check(plugin,ok,"failed_or_unloaded_preparation_keeps_all_run_rows_absent"))
            .thenCompose(v->on(database.readInventory(context),owner)).thenAccept(row->check(plugin,row.equals(preserved.get()),"gathering_preparation_failure_preserves_original_personal_slots_revision"))
            .thenCompose(v->on(database.module("module_fixture_allow",c->{try(var st=c.createStatement()){st.execute("DROP TRIGGER reject_module_gathering ON hearth_progress.gathering_instance");}return null;}),owner))
            .thenCompose(v->m.prepare(context)).thenRunAsync(()->{var world=Objects.requireNonNull(Bukkit.getWorld(runtime.worlds().get(context.storyRunId()).mapping().physicalId()));var manager=Objects.requireNonNull(com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer().get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world)));check(plugin,m.content.preparation()!=null&&m.content.route()!=null&&m.content.playthrough()!=null,"normal_preparation_course_and_facility_guides_connected");for(var f:m.content.crafting().facilities().values())check(plugin,world.getBlockAt(f.x(),f.y(),f.z()).getType()==Material.CRAFTING_TABLE,"normal_existing_crafting_facility_preserved");for(var station:m.content.travel().stations().values())check(plugin,manager.getRegion("hearth_travel_"+station.id())!=null,"normal_existing_travel_station_prepared");check(plugin,manager.getRegion("hearth_gather_"+m.content.gathering().id())!=null&&m.gathering.settled(context.storyRunId()),"normal_async_gathering_binding_and_protected_area_prepared");},owner).thenComposeAsync(v->m.barrier(context.storyRunId()),owner).thenComposeAsync(v->idleBoundary(plugin,runtime,context,session,releasePins),owner);
    }
    private static CompletionStage<Void> waitFor(JavaPlugin plugin,java.util.function.BooleanSupplier condition,int remaining){
        try{if(condition.getAsBoolean())return CompletableFuture.completedFuture(null);if(remaining<=0)return CompletableFuture.failedFuture(new IllegalStateException("STORY_BOUNDARY_WAIT_EXPIRED"));}catch(RuntimeException error){return CompletableFuture.failedFuture(error);}
        var next=new CompletableFuture<Void>();Bukkit.getScheduler().runTaskLater(plugin,()->waitFor(plugin,condition,remaining-1).whenComplete((v,error)->{if(error==null)next.complete(null);else next.completeExceptionally(error);}),1);return next;
    }
    private static CompletionStage<Void> idleBoundary(JavaPlugin plugin,StoryRuntime runtime,ServerProgressAccess.Context context,PlayerSessions.Session session,Runnable releasePins){
        var run=context.storyRunId();var m=runtime.modules();var owner=runtime.owner();var world=runtime.worlds().get(run);var locked=new CompletableFuture<Void>();var unlock=new java.util.concurrent.CountDownLatch(1);var revision=new long[1];var generation=new long[1];var locker=new java.util.concurrent.atomic.AtomicReference<CompletionStage<Void>>();
        return waitFor(plugin,()->m.gathering.state(run).orElseThrow().generation()>0&&m.gathering.settled(run),100).thenRun(()->check(plugin,true,"normal_gathering_timer_completed_real_initial_reset"))
            .thenComposeAsync(v->{var job=runtime.database().module("module_fixture_save_hold",c->{c.setAutoCommit(false);try(var statement=c.createStatement()){statement.execute("LOCK TABLE hearth_runtime.story_binding IN ACCESS EXCLUSIVE MODE");locked.complete(null);if(!unlock.await(6,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Fixture lock expired");c.commit();return (Void)null;}catch(Exception error){c.rollback();locked.completeExceptionally(error);throw error;}finally{c.setAutoCommit(true);}});locker.set(job);return on(locked,owner);},owner)
            .thenComposeAsync(v->{releasePins.run();return runtime.sessions().disconnect(session);},owner)
            .thenComposeAsync(v->waitFor(plugin,()->world.state()==WorldLifecycle.State.SAVING,60),owner)
            .thenComposeAsync(v->{var saved=m.gathering.state(run).orElseThrow();revision[0]=saved.revision();generation[0]=saved.generation();return waitTicks(plugin,30);},owner)
            .thenRunAsync(()->{var saved=m.gathering.state(run).orElseThrow();check(plugin,world.state()==WorldLifecycle.State.SAVING&&m.gathering.settled(run)&&saved.revision()==revision[0]&&saved.generation()==generation[0],"saving_world_does_not_start_new_periodic_gathering_reset");unlock.countDown();},owner)
            .thenComposeAsync(v->on(locker.get(),owner),owner)
            .thenComposeAsync(v->waitFor(plugin,()->world.state()==WorldLifecycle.State.OFFLINE,100),owner)
            .thenRunAsync(()->check(plugin,m.gathering.state(run).isEmpty()&&runtime.sessions().canUnload(run),"automatic_IDLE_save_unload_releases_gathering_state_and_own_tickets"),owner)
            .whenCompleteAsync((v,error)->unlock.countDown(),owner);
    }
    private static CompletionStage<Void> waitTicks(JavaPlugin plugin,int ticks){var done=new CompletableFuture<Void>();Bukkit.getScheduler().runTaskLater(plugin,()->done.complete(null),ticks);return done;}
    public static CompletionStage<Void> run(JavaPlugin plugin,StoryRuntime runtime){
        final Properties expected=new Properties();try(var input=Files.newInputStream(root(plugin).resolve("story-runtime-expected.properties"))){expected.load(input);}catch(Exception error){return CompletableFuture.failedFuture(error);}
        var owner=runtime.owner();var a=runtime.settings().members().get(PLAYER);var context=runtime.access().forVerifiedServerLink(a,PLAYER);var fresh=runtime.access().forVerifiedServerLink(a,NEW);var empty=InventoryPayload.pack(new byte[41][]);
        check(plugin,runtime.phase()==StoryRuntime.Phase.READY&&!plugin.getConfig().getBoolean("verification-enabled"),"new_JVM_normal_story_bootstrap_without_fixture_runtime");
        try{
            var configured=runtime.settings().runs().get(a);var binding=configured.binding();var backend=new StoryWorldBackend(configured);var marker=backend.placement().directory().resolve(".hearth-story-world");byte[] original=Files.readAllBytes(marker);
            var economySettings=new io.github.jesterportfolio.hearth.economy.EconomyRuntimeSettings(a,binding.worldKey(),binding.worldId(),configured.x(),configured.y(),configured.z(),0,0,1,configured.regions(),"unused.properties","127.0.0.1",5432,"unused","unused","HEARTH_UNUSED");
            var economic=new io.github.jesterportfolio.hearth.economy.EconomyWorldBackend(economySettings);boolean rejected=false;try{economic.registerExisting(economic.placement());}catch(IllegalStateException refused){rejected=true;}check(plugin,rejected,"economy_rejects_story_owned_world");
            var foreign=backend.placement().directory().resolve(".hearth-economy-world");Files.writeString(foreign,"synthetic ownership conflict",StandardOpenOption.CREATE_NEW);
            try{rejected=false;try{backend.verifyPrepared();}catch(IllegalStateException refused){rejected=true;}check(plugin,rejected,"story_rejects_conflicting_economy_marker");}finally{if(!Files.readString(foreign).equals("synthetic ownership conflict"))throw new IllegalStateException("Fixture marker replaced; preserved");Files.delete(foreign);}
            check(plugin,Arrays.equals(original,Files.readAllBytes(marker)),"world_ownership_conflicts_preserve_original_marker");
        }catch(Exception error){return CompletableFuture.failedFuture(error);}
        var pinned=new ArrayList<Chunk>();
        var positions=YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve(runtime.settings().contentFile()).toFile()).getMapList("quests.npcs");
        var listener=new Listener(){@EventHandler public void load(WorldLoadEvent event){if(!event.getWorld().getUID().equals(runtime.settings().runs().get(a).binding().worldId()))return;for(var point:positions){var chunk=event.getWorld().getChunkAt(((int)Math.floor(((Number)point.get("x")).doubleValue()))>>4,((int)Math.floor(((Number)point.get("z")).doubleValue()))>>4);if(chunk.addPluginChunkTicket(plugin))pinned.add(chunk);}}};
        Bukkit.getPluginManager().registerEvents(listener,plugin);
        CompletionStage<Void> chain=CompletableFuture.completedFuture(null);
        if(!Boolean.getBoolean("hearth.verification.storyRuntimeRecheck"))chain=on(runtime.admissions().open(context,a.toString(),false,()->true),owner).thenAccept(row->check(plugin,inventory(row).equals(expected.getProperty("inventory")),"new_JVM_original_member_slots_and_revision_preserved"))
            .thenCompose(v->reject(runtime.admissions().open(fresh,null,false,()->true),plugin,"normal_unmanaged_items_preserved",owner))
            .thenCompose(v->reject(runtime.admissions().open(fresh,UUID.randomUUID().toString(),true,()->true),plugin,"normal_foreign_inventory_owner_refused",owner))
            .thenCompose(v->on(runtime.admissions().open(fresh,null,true,()->true),owner)).thenAccept(row->check(plugin,row.revision()==0&&row.payload().equals(empty),"normal_new_empty_member_created_once"))
            .thenCompose(v->on(runtime.database().readShared(context),owner)).thenAccept(row->check(plugin,expected.getProperty("shared").equals(row.revision()+"/"+row.value()),"new_JVM_shared_progress_preserved"));
        return chain.thenCompose(v->{var port=new PlayerSessions.Port(){InventoryPayload value;public boolean connected(){return true;}public InventoryPayload capture(){return value;}public void apply(InventoryPayload p){value=p;check(plugin,Base64.getEncoder().encodeToString(p.bytes()).equals(expected.getProperty("inventory").split("/",2)[1]),"normal_session_restores_only_saved_personal_slots");}public CompletionStage<Boolean> move(){return CompletableFuture.completedFuture(true);}};var session=runtime.sessions().connect(context,runtime.worlds().get(a),port);return on(session.ready(),owner).thenAccept(x->{var world=Objects.requireNonNull(Bukkit.getWorld(runtime.settings().runs().get(a).binding().worldId()));check(plugin,session.state()==PlayerSessions.State.ACTIVE&&runtime.worlds().get(a).state()==WorldLifecycle.State.ACTIVE,"normal_controlled_session_world_active");check(plugin,world.getEntities().stream().anyMatch(e->e instanceof org.bukkit.entity.Villager),"normal_WorldGuard_and_NPC_prepared");}).thenComposeAsync(x->Boolean.getBoolean("hearth.verification.storyModules")?modules(plugin,runtime,context,session,()->{for(var chunk:pinned)chunk.removePluginChunkTicket(plugin);pinned.clear();}):CompletableFuture.completedFuture(null),owner);}).whenCompleteAsync((v,error)->{HandlerList.unregisterAll(listener);for(var chunk:pinned)chunk.removePluginChunkTicket(plugin);},owner);
    }
}
