package io.github.jesterportfolio.hearth.verification;

import io.github.jesterportfolio.hearth.story.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.session.*;
import io.github.jesterportfolio.hearth.lifecycle.*;
import io.github.jesterportfolio.hearth.administration.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.postgresql.ds.PGSimpleDataSource;

/** 일반 실행의 PG/물리 효과/읽기 진단만 검사. 인증 Player와 실제 게임 행동은 만들지 않습니다. */
public final class StoryOperationsVerification {
    public static final UUID B_PLAYER=new UUID(49,81);
    private StoryOperationsVerification(){}
    private static void check(JavaPlugin plugin,boolean ok,String label){if(!ok)throw new IllegalStateException("STORY_OPERATIONS_CHECK_FAILED_"+label);plugin.getLogger().info("HEARTH_SESSION_CHECK PASS story_operations_"+label);}
    private static <T> CompletionStage<T> on(CompletionStage<T> work,Executor owner){return work.thenApplyAsync(v->v,owner);}
    public static void content(YamlConfiguration yaml){
        var shared=new HashMap<>(StoryContentBundle.section(StoryRuntime.map(yaml),"shared"));var rules=new ArrayList<Object>((List<?>)shared.get("regions"));rules.add(Map.of("id","protected","policy","CONTENT","requires-stage","trial_arrival","bounds",Map.of("min-x",0,"max-x",15,"min-y",60,"max-y",70,"min-z",0,"max-z",15)));shared.put("regions",rules);yaml.set("shared",shared);
        var exploration=new HashMap<>(StoryContentBundle.section(StoryRuntime.map(yaml),"exploration"));var regions=new ArrayList<Object>((List<?>)exploration.get("regions"));regions.add(Map.of("id","protected","name","공개 효과 보호 영역","public",false));exploration.put("regions",regions);yaml.set("exploration",exploration);
        yaml.set("world-effects",Map.of("format",1,"changes",List.of(Map.of("id","public_gate","stage","trial_arrival","x",5,"y",63,"z",5,"expected","STONE","target","EMERALD_BLOCK","regions",List.of("protected")))));
    }
    public static CompletionStage<Void> seed(PostgresProgressStore db,StoryRegistry registry,ServerProgressAccess access,StoryBinding a,StoryBinding b,Executor owner){
        return on(registry.admit(access.forVerifiedServerLink(b.run(),B_PLAYER),b,true,InventoryPayload.pack(new byte[41][])),owner)
            .thenCompose(v->db.module("operations_fixture_confirmed_story",c->{try(var st=c.prepareStatement("INSERT INTO hearth_progress.community_story(story_run_id,stage_id,stage_title,exploration_open) VALUES (?,'trial_arrival','공개 확인',false)")){st.setObject(1,a.run());st.executeUpdate();}return null;}));
    }
    private static PGSimpleDataSource source(StoryRuntimeSettings s){var d=new PGSimpleDataSource();d.setServerNames(new String[]{s.dbHost()});d.setPortNumbers(new int[]{s.dbPort()});d.setDatabaseName(s.dbName());d.setUser(s.dbUser());d.setPassword(System.getenv(s.passwordEnvironment()));d.setConnectTimeout(2);d.setSocketTimeout(5);return d;}
    private static CompletionStage<Void> readOnly(JavaPlugin plugin,StoryRuntime runtime){
        var db=PostgresProgressStore.forReadOnlyModules(source(runtime.settings()),new ServerProgressAccess(),f->{});var owner=runtime.owner();
        return on(db.module("operations_readonly",c->{try(var st=c.createStatement();var r=st.executeQuery("SHOW default_transaction_read_only")){r.next();return r.getString(1).equals("on");}}),owner).thenAccept(ok->check(plugin,ok,"diagnostic_PostgreSQL_session_is_read_only"))
            .thenCompose(v->db.module("operations_forbidden_write",c->{try(var st=c.createStatement()){st.execute("INSERT INTO hearth_progress.story_run(story_run_id,state_value) VALUES ('00000000-0000-002a-0000-000000004fff','must not exist')");}return null;}).handleAsync((x,error)->{check(plugin,error!=null&&RecoveryConsole.errorCode(error).equals("25006"),"real_PG_write_refused_without_mutation");return (Void)null;},owner))
            .whenComplete((v,e)->db.closeAsync());
    }
    private static CompletionStage<List<String>> command(StoryRuntime runtime,String... args){var lines=new ArrayList<String>();return runtime.recovery(true,args,lines::add).thenApply(v->List.copyOf(lines));}
    private static UUID report(List<String> lines){var header=lines.stream().filter(s->s.startsWith("HEARTH_RECOVERY_REPORT ")).findFirst().orElseThrow();var value=header.substring(header.indexOf(" report=")+8).split(" ",2)[0];return UUID.fromString(value);}
    private static CompletionStage<PlayerSessions.Session> connect(StoryRuntime runtime,ServerProgressAccess.Context context){
        return runtime.loadStory(context).thenComposeAsync(v->{var port=new PlayerSessions.Port(){InventoryPayload payload;public boolean connected(){return true;}public InventoryPayload capture(){return payload;}public void apply(InventoryPayload value){payload=value;}public CompletionStage<Boolean> move(){return CompletableFuture.completedFuture(true);}};var s=runtime.sessions().connect(context,runtime.worlds().get(context.storyRunId()),port);return on(s.ready(),runtime.owner()).thenApply(x->s);},runtime.owner());
    }
    private static CompletionStage<Void> waitOffline(JavaPlugin plugin,WorldLifecycle world,int count){if(world.state()==WorldLifecycle.State.OFFLINE)return CompletableFuture.completedFuture(null);if(count>100) return CompletableFuture.failedFuture(new IllegalStateException("OPERATIONS_WORLD_UNLOAD_TIMEOUT"));var next=new CompletableFuture<Void>();Bukkit.getScheduler().runTaskLater(plugin,()->waitOffline(plugin,world,count+1).whenComplete((v,e)->{if(e==null)next.complete(null);else next.completeExceptionally(e);}),1);return next;}
    public static CompletionStage<Void> run(JavaPlugin plugin,StoryRuntime runtime){
        if(!Boolean.getBoolean("hearth.verification.storyOperations")||!Bukkit.getOnlinePlayers().isEmpty())return CompletableFuture.failedFuture(new IllegalStateException("STORY_OPERATIONS_GATE"));
        final Path marker;final byte[] originalMarker;try{var root=IsolatedPaths.require(Path.of("").toRealPath().getParent(),"hearth-s4-11.");if(!Files.readString(root.resolve(".hearth-lifecycle-isolated")).equals("S4-11 isolated test only\n"))throw new IllegalStateException("STORY_OPERATIONS_GATE");marker=plugin.getDataFolder().toPath().resolve("story-runtime.active");originalMarker=Files.readAllBytes(marker);}catch(Exception error){return CompletableFuture.failedFuture(error);}
        var owner=runtime.owner();var a=runtime.access().forVerifiedServerLink(new UUID(42,10),new UUID(49,1));var b=runtime.access().forVerifiedServerLink(new UUID(42,11),B_PLAYER);var snapshots=new InspectionStore(runtime.database(),runtime.access());var before=new InspectionStore.Snapshot[1];var story=new StoryStore.Snapshot[1];var current=new PlayerSessions.Session[1];var report=new UUID[1];
        check(plugin,runtime.phase()==StoryRuntime.Phase.READY&&!plugin.getConfig().getBoolean("verification-enabled"),"normal_JVM_without_fixture_runtime");
        return on(snapshots.read(a),owner).thenAccept(value->before[0]=value)
            .thenCompose(v->on(runtime.database().readStory(a),owner)).thenAccept(value->story[0]=value)
            .thenCompose(v->on(connect(runtime,a),owner)).thenAccept(s->{current[0]=s;check(plugin,Bukkit.getWorld(runtime.settings().runs().get(a.storyRunId()).binding().worldId()).getBlockAt(5,63,5).getType()==Material.EMERALD_BLOCK,"new_JVM_confirmed_story_applies_only_expected_block");})
            .thenCompose(v->on(connect(runtime,b),owner)).thenAccept(s->check(plugin,Bukkit.getWorld(runtime.settings().runs().get(b.storyRunId()).binding().worldId()).getBlockAt(5,63,5).getType()==Material.STONE,"unconfirmed_other_run_block_preserved"))
            .thenCompose(v->on(runtime.sessions().disconnect(current[0]),owner)).thenComposeAsync(v->waitOffline(plugin,runtime.worlds().get(a.storyRunId()),0),owner)
            .thenCompose(v->on(connect(runtime,a),owner)).thenAccept(s->{current[0]=s;check(plugin,Bukkit.getWorld(runtime.settings().runs().get(a.storyRunId()).binding().worldId()).getBlockAt(5,63,5).getType()==Material.EMERALD_BLOCK,"saved_unloaded_effect_reloads_idempotently");})
            .thenCompose(v->command(runtime,"inspect",a.storyRunId().toString(),a.playerId().toString())).thenAccept(lines->{report[0]=report(lines);check(plugin,lines.stream().anyMatch(l->l.contains("story.effect=APPLIED")),"normal_console_reports_current_effect_and_saved_payload_hashes");})
            .thenCompose(v->command(runtime,"compare",report[0].toString())).thenAccept(lines->check(plugin,lines.stream().anyMatch(l->l.startsWith("HEARTH_RECOVERY_COMPARE ")&&l.contains("changed=[]")&&l.contains("unconfirmed=[]")),"refreshed_registered_links_keep_report_identity_and_equal_originals"))
            .thenCompose(v->runtime.recovery(true,new String[]{"inspect",a.storyRunId().toString(),new UUID(49,71).toString()},line->{}).handleAsync((x,error)->{check(plugin,error!=null&&RecoveryConsole.errorCode(error).equals("UNKNOWN_VERIFIED_LINK"),"configured_but_never_admitted_account_is_not_created_by_inspection");return (Void)null;},owner))
            .thenCompose(v->runtime.recovery(false,new String[]{"list"},line->{}).handleAsync((x,error)->{check(plugin,error!=null&&RecoveryConsole.errorCode(error).equals("CONSOLE_ONLY"),"nonconsole_inspection_is_rejected");return (Void)null;},owner))
            .thenCompose(v->readOnly(plugin,runtime))
            .thenRunAsync(()->Bukkit.getWorld(runtime.settings().runs().get(a.storyRunId()).binding().worldId()).getBlockAt(5,63,5).setType(Material.DIAMOND_BLOCK,false),owner)
            .thenCompose(v->on(runtime.reconcileStory(a),owner)).thenAccept(applied->check(plugin,!applied&&Bukkit.getWorld(runtime.settings().runs().get(a.storyRunId()).binding().worldId()).getBlockAt(5,63,5).getType()==Material.DIAMOND_BLOCK,"unexpected_physical_conflict_preserved_without_replacement"))
            .thenCompose(v->command(runtime,"inspect",a.storyRunId().toString(),a.playerId().toString())).thenAccept(lines->check(plugin,lines.stream().anyMatch(l->l.contains("WORLD_EFFECT_HOLD")),"effect_conflict_is_visible_in_readonly_recovery"))
            .thenCompose(v->on(runtime.database().readStory(a),owner)).thenAccept(value->check(plugin,value.equals(story[0]),"effect_success_and_conflict_do_not_recommit_story"))
            .thenCompose(v->on(snapshots.read(a),owner)).thenAccept(after->{var c=new RecoveryInspection.Runtime(null,Map.of(),Set.of(),Set.of());var old=RecoveryInspection.build(UUID.randomUUID(),a,before[0],c,c,Map.of());var now=RecoveryInspection.build(UUID.randomUUID(),a,after,c,c,Map.of());check(plugin,RecoveryInspection.compare(old,now).changed().isEmpty(),"all_personal_original_payloads_and_revisions_preserved");})
            .thenCompose(v->held(plugin,runtime,marker,originalMarker,a));
    }
    private static CompletionStage<Void> held(JavaPlugin plugin,StoryRuntime active,Path marker,byte[] original,ServerProgressAccess.Context actor){
        final StoryRuntime held;try{held=new StoryRuntime(plugin);}catch(Exception e){return CompletableFuture.failedFuture(e);}var owner=active.owner();
        check(plugin,held.phase()==StoryRuntime.Phase.HOLD&&held.worlds().isEmpty(),"existing_runtime_marker_keeps_new_bootstrap_HOLD_without_world_or_schema_initialization");
        return held.recovery(true,new String[]{"inspect",actor.storyRunId().toString(),actor.playerId().toString()},line->{if(line.startsWith("HEARTH_RECOVERY_REPORT "))check(plugin,line.contains("STORY_RUNTIME_HOLD"),"HOLD_bootstrap_still_reads_saved_recovery_without_releasing_hold");}).thenRunAsync(()->{try{check(plugin,Arrays.equals(original,Files.readAllBytes(marker)),"HOLD_inspection_preserves_exact_existing_runtime_marker");}catch(Exception e){throw new CompletionException(e);}},owner)
            .whenCompleteAsync((v,error)->{held.close();plugin.getCommand("hearthstoryadmin").setExecutor((sender,cmd,label,args)->{active.console(sender instanceof org.bukkit.command.ConsoleCommandSender,args,sender::sendMessage);return true;});},owner);
    }
}
