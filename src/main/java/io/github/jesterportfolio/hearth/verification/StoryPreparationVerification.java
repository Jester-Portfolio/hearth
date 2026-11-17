package io.github.jesterportfolio.hearth.verification;

import io.github.jesterportfolio.hearth.story.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.session.*;
import io.github.jesterportfolio.hearth.world.TemplateStore;
import io.github.jesterportfolio.hearth.lifecycle.LifecycleFailure;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** 새 소유 격리 경로의 준비/다음 JVM 복원 검사. Player·인증/클릭을 만들지 않습니다. */
public final class StoryPreparationVerification {
    private static final UUID A=new UUID(42,1001),B=new UUID(42,1002),FAILED=new UUID(42,1003),SENTINEL=new UUID(42,1999),RACE=new UUID(42,1998);
    private static final UUID PA=new UUID(49,301),PB=new UUID(49,302),PS=new UUID(49,399);
    private static boolean prepared,restored;
    private StoryPreparationVerification(){}
    private static Path root(JavaPlugin plugin)throws Exception{
        if(!Boolean.getBoolean("hearth.verification.storyPreparation")||!Bukkit.getOnlinePlayers().isEmpty())throw new IllegalStateException("STORY_PREPARATION_PROBE_GATE");
        var root=IsolatedPaths.require(Path.of("").toRealPath().getParent(),"hearth-s4-11.");if(!Files.readString(root.resolve(".hearth-lifecycle-isolated")).equals("S4-11 isolated test only\n")||!plugin.getDataFolder().toPath().toRealPath().equals(root.resolve("server/plugins/Hearth")))throw new IllegalStateException("STORY_PREPARATION_PROBE_GATE");return root;
    }
    private static void check(JavaPlugin plugin,boolean yes,String name){if(!yes)throw new IllegalStateException("STORY_PREPARATION_CHECK_FAILED_"+name);plugin.getLogger().info("HEARTH_SESSION_CHECK PASS story_preparation_"+name);}
    private static <T> CompletionStage<T> on(CompletionStage<T> work,java.util.concurrent.Executor owner){return work.thenApplyAsync(v->v,owner);}
    private static CompletionStage<Void> reject(CompletionStage<?> work,JavaPlugin plugin,String name,java.util.concurrent.Executor owner){return work.handleAsync((v,error)->{check(plugin,error!=null,name);return (Void)null;},owner);}
    private static String inventory(InventoryStore.Stored saved){return saved.revision()+"/"+Base64.getEncoder().encodeToString(saved.payload().bytes());}
    private static InventoryPayload items(int count){var slots=new ItemStack[41];slots[0]=new ItemStack(Material.STONE,count);return new PaperInventoryCodec().encode(slots);}
    private static <T> CompletionStage<T> safe(JavaPlugin plugin,Supplier<T> work){var done=new CompletableFuture<T>();Bukkit.getScheduler().runTask(plugin,new Runnable(){public void run(){try{if(Bukkit.isTickingWorlds()){Bukkit.getScheduler().runTaskLater(plugin,this,1);return;}done.complete(work.get());}catch(RuntimeException error){done.completeExceptionally(error);}}});return done.orTimeout(10,TimeUnit.SECONDS);}
    private static CompletionStage<Void> member(StoryPreparationRuntime runtime,StoryRunPreparation.Prepared prepared,UUID player,int amount,String label){
        var c=runtime.access().forVerifiedServerLink(prepared.run().binding().run(),player);var db=runtime.database();var owner=runtime.owner();
        return on(runtime.registry().admit(c,prepared.run().binding(),true,InventoryPayload.pack(new byte[41][])),owner).thenCompose(v->on(db.saveInventory(c,0,items(amount)),owner)).thenCompose(v->on(db.savePersonal(c,0,label),owner)).thenCompose(v->on(db.saveShared(c,0,label+" shared"),owner)).thenApply(v->null);
    }
    public static CompletionStage<Void> prepare(JavaPlugin plugin,StoryPreparationRuntime runtime){
        final Path root;try{root=root(plugin);if(prepared)throw new IllegalStateException("PROBE_ONCE");prepared=true;}catch(Exception error){return CompletableFuture.failedFuture(error);}
        var db=runtime.database();var owner=runtime.owner();var sentinel=runtime.access().forVerifiedServerLink(SENTINEL,PS);var a=new StoryRunPreparation.Prepared[1];var b=new StoryRunPreparation.Prepared[1];var expected=new Properties();
        check(plugin,runtime.phase()==StoryPreparationRuntime.Phase.READY&&!plugin.getConfig().getBoolean("verification-enabled")&&"story-prepare".equals(plugin.getConfig().getString("runtime-mode")),"normal_preparation_mode_without_gameplay_or_fixture_bootstrap");
        return on(db.createRun(SENTINEL,"original shared"),owner).thenCompose(v->on(db.createPlayer(sentinel,"original personal"),owner)).thenCompose(v->on(db.createInventory(sentinel,items(7)),owner)).thenAccept(row->expected.setProperty("sentinel",inventory(row)))
            .thenCompose(v->{var one=runtime.preparation().prepare("a");var two=runtime.preparation().prepare("a");check(plugin,one==two,"identical_job_shares_one_operation");return on(one,owner);}).thenAccept(value->a[0]=value)
            .thenCompose(v->on(runtime.preparation().prepare("b"),owner)).thenAccept(value->{b[0]=value;check(plugin,!a[0].run().binding().worldId().equals(value.run().binding().worldId()),"copied_runs_have_independent_physical_UUIDs");})
            .thenCompose(v->member(runtime,a[0],PA,3,"A personal")).thenCompose(v->member(runtime,b[0],PB,5,"B personal"))
            .thenCompose(v->mutateA(plugin,runtime,a[0]))
            .thenCompose(v->Boolean.getBoolean("hearth.verification.preparationRecheck")?CompletableFuture.completedFuture(null):strictRace(plugin,runtime))
            .thenCompose(v->Boolean.getBoolean("hearth.verification.preparationRecheck")?CompletableFuture.completedFuture(null):failureCases(plugin,runtime,expected))
            .thenCompose(v->on(db.readInventory(sentinel),owner)).thenAccept(row->check(plugin,inventory(row).equals(expected.getProperty("sentinel")),"original_sentinel_inventory_preserved"))
            .thenCompose(v->on(db.readShared(sentinel),owner)).thenAccept(row->check(plugin,row.revision()==0&&row.value().equals("original shared"),"original_shared_progress_preserved"))
            .thenCompose(v->on(db.module("preparation_fixture_artifacts",c->{var source=plugin.getDataFolder().toPath().resolve("templates").resolve(runtime.settings().jobs().get("a").template());return new TemplateStore().digest(source).equals(runtime.settings().jobs().get("a").spatialHash())&&StoryTemplateSource.hash(source.resolve("worldguard-regions.yml")).equals(runtime.settings().jobs().get("a").regionHash());}),owner)).thenAccept(ok->check(plugin,ok,"source_spatial_and_protection_hashes_unchanged"))
            .thenRunAsync(()->{try{writeNormal(plugin,runtime,List.of(a[0],b[0]),root,expected);}catch(Exception error){throw new CompletionException(error);}},owner);
    }
    private static CompletionStage<Void> mutateA(JavaPlugin plugin,StoryPreparationRuntime runtime,StoryRunPreparation.Prepared a){
        var backend=new StoryWorldBackend(a.run());var owner=runtime.owner();
        return safe(plugin,()->{backend.registerExisting(backend.placement());var world=backend.load(backend.placement());var manager=Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world)));manager.getRegion("protected").setPriority(9);world.getBlockAt(0,64,0).setType(Material.GOLD_BLOCK,false);return manager;})
            .thenComposeAsync(manager->runtime.database().module("preparation_fixture_regions_save",c->{manager.save();return null;}),owner)
            .thenComposeAsync(v->safe(plugin,()->{backend.saveAndUnload(backend.placement());check(plugin,true,"prepared_run_progress_saved_without_replacing_template");return (Void)null;}),owner);
    }
    private static CompletionStage<Void> strictRace(JavaPlugin plugin,StoryPreparationRuntime runtime){
        var source=runtime.settings().jobs().get("a");var one=new StoryBinding(RACE,"hearth_story:registry_race_a",UUID.randomUUID(),source.version(),source.spatialHash(),runtime.settings().contentHash());var two=new StoryBinding(RACE,"hearth_story:registry_race_b",UUID.randomUUID(),source.version(),source.spatialHash(),runtime.settings().contentHash());
        return on(runtime.registry().createPrepared(one).handle((v,e)->e==null?"OK":LifecycleFailure.code(e)).thenCombine(runtime.registry().createPrepared(two).handle((v,e)->e==null?"OK":LifecycleFailure.code(e)),(a,b)->a.equals("OK")&&b.equals("23505")||a.equals("23505")&&b.equals("OK")),runtime.owner()).thenAccept(ok->check(plugin,ok,"concurrent_strict_new_registration_has_one_winner"))
            .thenCompose(v->on(runtime.registry().read(RACE),runtime.owner())).thenAccept(binding->check(plugin,binding.equals(one)||binding.equals(two),"strict_registration_race_preserves_winning_binding"));
    }
    private static CompletionStage<Void> failureCases(JavaPlugin plugin,StoryPreparationRuntime runtime,Properties expected){
        final StoryRunPreparation child;final ExecutorService io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"hearth-preparation-native-io");t.setDaemon(true);return t;});
        var s=runtime.settings();var source=s.jobs().get("a");
        var refused=new StoryPreparationSettings.Job("existing",SENTINEL,"hearth_story:refused_existing",source.template(),source.version(),source.spatialHash(),source.regionHash(),source.x(),source.y(),source.z(),source.yaw(),source.pitch(),source.regions());
        var failed=new StoryPreparationSettings.Job("failed",FAILED,"hearth_story:failed_preparation",source.template(),source.version(),source.spatialHash(),source.regionHash(),source.x(),source.y(),source.z(),source.yaw(),source.pitch(),source.regions());
        try{child=new StoryRunPreparation(plugin,new StoryPreparationSettings(Map.of("existing",refused,"failed",failed),s.contentFile(),s.contentHash(),s.maxFiles(),s.maxBytes(),s.reserveBytes(),s.dbHost(),s.dbPort(),s.dbName(),s.dbUser(),s.passwordEnvironment()),runtime.registry(),runtime.owner(),io);}catch(Exception error){io.shutdownNow();return CompletableFuture.failedFuture(error);}
        var owner=runtime.owner();var db=runtime.database();
        return reject(child.prepare("existing"),plugin,"existing_progress_refused_without_adoption",owner)
            .thenRunAsync(()->check(plugin,child.status().get("existing").state()==StoryRunPreparation.State.REFUSED&&!Files.exists(Bukkit.getServer().getLevelDirectory().resolve("dimensions/hearth_story/refused_existing")),"existing_run_refusal_allocates_no_world"),owner)
            .thenCompose(v->on(db.module("preparation_fixture_reject",c->{try(var st=c.createStatement()){st.execute("CREATE FUNCTION hearth_runtime.reject_prepared_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.story_run_id='"+FAILED+"'::uuid THEN RAISE EXCEPTION 'isolated preparation fixture' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");st.execute("CREATE TRIGGER reject_prepared_fixture BEFORE INSERT ON hearth_runtime.story_binding FOR EACH ROW EXECUTE FUNCTION hearth_runtime.reject_prepared_fixture()");}return null;}),owner))
            .thenCompose(v->child.prepare("failed").handleAsync((prepared,error)->{check(plugin,error!=null&&LifecycleFailure.code(error).equals("23514"),"real_final_registration_SQL_failure_preserves_prepared_assets");return (Void)null;},owner))
            .thenRunAsync(()->{check(plugin,child.held()&&child.status().get("failed").state()==StoryRunPreparation.State.HOLD,"failed_registration_is_HOLD_not_retry_or_cleanup");try{
                var dir=Bukkit.getServer().getLevelDirectory().resolve("dimensions/hearth_story/failed_preparation").toAbsolutePath();var marker=dir.resolve(".hearth-story-world");var id=UUID.fromString(Files.readString(marker).split("\n")[0].split("/")[2]);boolean rejected=false;try{new StoryWorldBackend(failed.run(id,s.contentHash())).verifyPrepared();}catch(IllegalStateException hold){rejected="STORY_PREPARATION_REVIEW_REQUIRED".equals(hold.getMessage());}check(plugin,rejected&&Files.isRegularFile(dir.resolve(".hearth-story-provisioning")),"normal_runtime_rejects_pending_preparation_marker");expected.setProperty("failed.marker",StoryTemplateSource.hash(dir.resolve(".hearth-story-provisioning")));expected.setProperty("failed.uuid",id.toString());
            }catch(Exception error){throw new CompletionException(error);}},owner)
            .thenCompose(v->on(db.module("preparation_fixture_rollback",c->{try(var st=c.createStatement();var rows=st.executeQuery("SELECT NOT EXISTS(SELECT 1 FROM hearth_progress.story_run WHERE story_run_id='"+FAILED+"') AND NOT EXISTS(SELECT 1 FROM hearth_runtime.story_binding WHERE story_run_id='"+FAILED+"')")){rows.next();return rows.getBoolean(1);}}),owner)).thenAccept(ok->check(plugin,ok,"failed_registration_rolls_back_run_and_binding_together"))
            .whenCompleteAsync((v,error)->{try{child.close();}finally{io.shutdownNow();}},owner);
    }
    private static void writeNormal(JavaPlugin plugin,StoryPreparationRuntime runtime,List<StoryRunPreparation.Prepared> runs,Path root,Properties expected)throws Exception{
        var folder=plugin.getDataFolder().toPath();var source=folder.resolve("templates").resolve(runtime.settings().jobs().get("a").template());var sourceMetadata=StoryTemplateSource.hash(source.resolve("data/paper/metadata.dat"));var sourceId=UUID.fromString(Files.readString(source.resolve("source-uuid.txt")).trim());var config=new YamlConfiguration();config.set("runtime-mode","story");config.set("phone-dialog-enabled",true);config.set("phone-ai-mode","ollama");config.set("phone-ai-endpoint","http://127.0.0.1:11435/api/generate");var settings=config.createSection("story-runtime");var rows=new ArrayList<Object>();
        for(var prepared:runs){var b=prepared.run().binding();var key=Objects.requireNonNull(NamespacedKey.fromString(b.worldKey()));var dir=Bukkit.getServer().getLevelDirectory().resolve("dimensions").resolve(key.getNamespace()).resolve(key.getKey());check(plugin,!b.worldId().equals(sourceId)&&!StoryTemplateSource.hash(dir.resolve("data/paper/metadata.dat")).equals(sourceMetadata)&&!Files.exists(dir.resolve("playerdata/source-only.dat")),"template_UUID_and_personal_files_not_imported_"+(b.run().equals(A)?"a":"b"));rows.addAll(YamlConfiguration.loadConfiguration(prepared.report().toFile()).getList("runs"));expected.setProperty(b.run().equals(A)?"a.uuid":"b.uuid",b.worldId().toString());}
        settings.set("runs",rows);settings.set("members",List.of(Map.of("player",PA.toString(),"run",A.toString()),Map.of("player",PB.toString(),"run",B.toString())));var s=runtime.settings();settings.set("content-file",s.contentFile());settings.set("content-sha256",s.contentHash());settings.set("idle-seconds",1);settings.set("capacity",4);settings.set("db-host",s.dbHost());settings.set("db-port",s.dbPort());settings.set("db-name",s.dbName());settings.set("db-user",s.dbUser());settings.set("password-environment",s.passwordEnvironment());StoryRuntimeSettings.parse(settings.getValues(false));var outputConfig=folder.resolve("prepared-normal-story.yml");if(Files.exists(outputConfig,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("Preparation evidence already exists");config.save(outputConfig.toFile());try(var output=Files.newOutputStream(root.resolve("story-preparation-expected.properties"),StandardOpenOption.CREATE_NEW)){expected.store(output,"Synthetic prepared-run evidence only");}
    }
    public static CompletionStage<Void> story(JavaPlugin plugin,StoryRuntime runtime){
        final Properties expected=new Properties();try(var in=Files.newInputStream(root(plugin).resolve("story-preparation-expected.properties"))){if(restored)throw new IllegalStateException("PROBE_ONCE");restored=true;expected.load(in);}catch(Exception error){return CompletableFuture.failedFuture(error);}
        var owner=runtime.owner();var a=runtime.access().forVerifiedServerLink(A,PA);var b=runtime.access().forVerifiedServerLink(B,PB);var original=runtime.access().forVerifiedServerLink(SENTINEL,PS);
        check(plugin,runtime.phase()==StoryRuntime.Phase.READY&&!plugin.getConfig().getBoolean("verification-enabled"),"new_JVM_normal_gameplay_uses_prepared_runs");
        return on(runtime.admissions().open(a,A.toString(),false,()->true),owner).thenAccept(saved->check(plugin,saved.revision()==1&&saved.payload().equals(items(3)),"new_JVM_personal_A_inventory_preserved"))
            .thenCompose(v->on(runtime.admissions().open(b,B.toString(),false,()->true),owner)).thenAccept(saved->check(plugin,saved.revision()==1&&saved.payload().equals(items(5)),"new_JVM_personal_B_inventory_preserved"))
            .thenCompose(v->connect(runtime,a,items(3))).thenCompose(v->connect(runtime,b,items(5)))
            .thenRunAsync(()->{var wa=Objects.requireNonNull(Bukkit.getWorld(UUID.fromString(expected.getProperty("a.uuid"))));var wb=Objects.requireNonNull(Bukkit.getWorld(UUID.fromString(expected.getProperty("b.uuid"))));check(plugin,wa.getBlockAt(0,64,0).getType()==Material.GOLD_BLOCK&&wb.getBlockAt(0,64,0).getType()==Material.STONE,"new_JVM_saved_world_A_change_and_original_B_are_independent");var container=WorldGuard.getInstance().getPlatform().getRegionContainer();check(plugin,container.get(BukkitAdapter.adapt(wa)).getRegion("protected").getPriority()==9&&container.get(BukkitAdapter.adapt(wb)).getRegion("protected").getPriority()==0,"new_JVM_WorldGuard_progress_is_independent");if(!Boolean.getBoolean("hearth.verification.preparationRecheck")){try{var pending=Bukkit.getServer().getLevelDirectory().resolve("dimensions/hearth_story/failed_preparation/.hearth-story-provisioning");check(plugin,StoryTemplateSource.hash(pending).equals(expected.getProperty("failed.marker")),"failed_preparation_original_HOLD_marker_survives_restart");}catch(Exception error){throw new CompletionException(error);}}},owner)
            .thenCompose(v->on(runtime.database().readInventory(original),owner)).thenAccept(saved->check(plugin,inventory(saved).equals(expected.getProperty("sentinel")),"new_JVM_original_nonmember_inventory_preserved"))
            .thenCompose(v->on(runtime.database().readPersonal(a),owner)).thenAccept(saved->check(plugin,saved.revision()==1&&saved.value().equals("A personal"),"new_JVM_personal_progress_preserved"))
            .thenCompose(v->on(runtime.database().readShared(b),owner)).thenAccept(saved->check(plugin,saved.revision()==1&&saved.value().equals("B personal shared"),"new_JVM_shared_progress_preserved"));
    }
    private static CompletionStage<Void> connect(StoryRuntime runtime,ServerProgressAccess.Context context,InventoryPayload expected){
        var port=new PlayerSessions.Port(){InventoryPayload value;public boolean connected(){return true;}public InventoryPayload capture(){return value;}public void apply(InventoryPayload payload){if(!payload.equals(expected))throw new IllegalStateException("PREPARED_MEMBER_INVENTORY_MISMATCH");value=payload;}public CompletionStage<Boolean> move(){return CompletableFuture.completedFuture(true);}};
        return on(runtime.sessions().connect(context,runtime.worlds().get(context.storyRunId()),port).ready(),runtime.owner());
    }
}
