package io.github.jesterportfolio.hearth.verification;

import io.github.jesterportfolio.hearth.story.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.exploration.CommunityBoundary;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** 기존 명단의 실제 PG/정상 실행 복원 검사. 실제 연결/투표·플레이어 객체를 만들지 않습니다. */
public final class StoryCommunityVerification {
    private static final UUID ACTOR=new UUID(49,1),PEER=new UUID(49,82),OTHER=new UUID(49,83),NEW=new UUID(49,71),GHOST=new UUID(49,84);
    private StoryCommunityVerification(){}
    private static Path root(JavaPlugin plugin)throws Exception{if(!Boolean.getBoolean("hearth.verification.storyCommunity")||!Bukkit.getOnlinePlayers().isEmpty())throw new IllegalStateException("COMMUNITY_PROBE_GATE");var root=IsolatedPaths.require(Path.of("").toRealPath().getParent(),"hearth-s4-11.");if(!Files.readString(root.resolve(".hearth-lifecycle-isolated")).equals("S4-11 isolated test only\n")||!plugin.getDataFolder().toPath().toRealPath().equals(root.resolve("server/plugins/Hearth")))throw new IllegalStateException("COMMUNITY_PROBE_GATE");return root;}
    private static void check(JavaPlugin plugin,boolean ok,String label){if(!ok)throw new IllegalStateException("COMMUNITY_CHECK_FAILED_"+label);plugin.getLogger().info("HEARTH_SESSION_CHECK PASS story_community_"+label);}
    private static <T> CompletionStage<T> on(CompletionStage<T> work,Executor owner){return work.thenApplyAsync(v->v,owner);}
    private static CompletionStage<Void> rejected(CompletionStage<?> work,String code,JavaPlugin plugin,String label,Executor owner){return work.handleAsync((v,error)->{check(plugin,error!=null&&(code==null||InventorySaveSession.code(error).equals(code)),label);return (Void)null;},owner);}
    private static String inventory(InventoryStore.Stored row){return row.revision()+"/"+Base64.getEncoder().encodeToString(row.payload().bytes());}
    public static CompletionStage<Void> seed(JavaPlugin plugin,PostgresProgressStore db,StoryRegistry registry,ServerProgressAccess access,StoryBinding a,StoryBinding b,Executor owner){
        final Path root;try{root=root(plugin);var configPath=plugin.getDataFolder().toPath().resolve("normal-story.yml");var config=YamlConfiguration.loadConfiguration(configPath.toFile());var list=new ArrayList<Object>(config.getMapList("story-runtime.members"));list.add(Map.of("player",GHOST.toString(),"run",a.run().toString()));config.set("story-runtime.members",list);config.save(configPath.toFile());}catch(Exception e){return CompletableFuture.failedFuture(e);}var peer=access.forVerifiedServerLink(a.run(),PEER);var other=access.forVerifiedServerLink(b.run(),OTHER);var empty=InventoryPayload.pack(new byte[41][]);var slots=new ItemStack[41];slots[0]=new ItemStack(Material.IRON_INGOT,3);var payload=new PaperInventoryCodec().encode(slots);var expected=new Properties();
        return on(registry.admit(peer,a,true,empty),owner).thenCompose(v->on(db.saveInventory(peer,0,payload),owner)).thenAccept(row->expected.setProperty("peer",inventory(row)))
            .thenCompose(v->on(db.savePersonal(peer,0,"private peer progress"),owner)).thenCompose(v->on(registry.admit(other,b,true,empty),owner))
            .thenCompose(v->on(registry.members(List.of(a,b)),owner)).thenAccept(rows->check(plugin,new HashSet<>(rows).equals(Set.of(new StoryRegistry.Member(a.run(),ACTOR),new StoryRegistry.Member(a.run(),PEER),new StoryRegistry.Member(b.run(),OTHER))),"existing_registered_scope_includes_offline_and_other_run_members_without_new_configured_accounts"))
            .thenRunAsync(()->{try(var output=Files.newOutputStream(root.resolve("story-community-expected.properties"),StandardOpenOption.CREATE_NEW)){expected.store(output,"Synthetic coarse membership and private original preservation only");}catch(Exception e){throw new CompletionException(e);}},owner);
    }
    public static CompletionStage<Void> run(JavaPlugin plugin,StoryRuntime runtime){
        final Properties expected=new Properties(),original=new Properties();try{var root=root(plugin);try(var input=Files.newInputStream(root.resolve("story-community-expected.properties"))){expected.load(input);}try(var input=Files.newInputStream(root.resolve("story-runtime-expected.properties"))){original.load(input);}}catch(Exception e){return CompletableFuture.failedFuture(e);}var owner=runtime.owner();var run=runtime.settings().members().get(ACTOR);var actor=runtime.access().forVerifiedServerLink(run,ACTOR);var peer=runtime.access().forVerifiedServerLink(run,PEER);var fresh=runtime.access().forVerifiedServerLink(run,NEW);var ghost=runtime.access().forVerifiedServerLink(run,GHOST);var bindings=runtime.settings().runs().values().stream().map(StoryRuntimeSettings.Run::binding).toList();var before=runtime.community(actor);
        check(plugin,runtime.phase()==StoryRuntime.Phase.READY&&!plugin.getConfig().getBoolean("verification-enabled"),"normal_story_boot_restores_directory_before_admissions_open");
        check(plugin,new HashSet<>(before.stream().map(CommunityBoundary.Ref::player).toList()).equals(Set.of(ACTOR,PEER)),"offline_peer_survives_restart_even_without_current_login_configuration");
        check(plugin,before.stream().noneMatch(CommunityBoundary.Ref::online),"stored_members_are_not_fabricated_online_players_or_vote_targets");
        check(plugin,before.stream().noneMatch(r->r.player().equals(OTHER)||r.player().equals(NEW)||r.player().equals(GHOST)),"another_run_and_never_admitted_accounts_are_not_public_members");
        var boundary=new CommunityBoundary(runtime::community,(ctx,targets)->Optional.empty());check(plugin,boundary.members(actor).stream().filter(CommunityBoundary.Member::self).count()==1,"public_member_view_preserves_self_marker_and_unknown_name_fallback");
        var lines=new ArrayList<String>();runtime.console(true,new String[]{"members",run.toString()},lines::add);check(plugin,lines.stream().anyMatch(s->s.contains("total=2"))&&lines.stream().anyMatch(s->s.contains(PEER.toString())),"bounded_console_directory_reads_cached_members_without_private_progress");
        return on(runtime.database().readInventory(actor),owner).thenAccept(row->check(plugin,inventory(row).equals(original.getProperty("inventory")),"hydration_does_not_reinitialize_original_actor_inventory"))
            .thenCompose(v->on(runtime.database().readInventory(peer),owner)).thenAccept(row->check(plugin,inventory(row).equals(expected.getProperty("peer")),"hydration_keeps_offline_peer_saved_items_and_revision"))
            .thenCompose(v->on(runtime.database().module("community_fixture_failure",c->{try(var st=c.createStatement()){st.execute("CREATE FUNCTION hearth_runtime.reject_community_probe() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.player_uuid='"+GHOST+"'::uuid THEN RAISE EXCEPTION 'isolated membership failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");st.execute("CREATE TRIGGER reject_community_probe BEFORE INSERT ON hearth_runtime.story_member FOR EACH ROW EXECUTE FUNCTION hearth_runtime.reject_community_probe()");}return null;}),owner))
            .thenCompose(v->rejected(runtime.admissions().open(ghost,null,true,()->true),"23514",plugin,"real_member_INSERT_failure_is_not_published_as_success",owner))
            .thenCompose(v->on(runtime.database().module("community_fixture_restore",c->{try(var st=c.createStatement()){st.execute("DROP TRIGGER reject_community_probe ON hearth_runtime.story_member");st.execute("DROP FUNCTION hearth_runtime.reject_community_probe()");}try(var st=c.createStatement();var rows=st.executeQuery("SELECT NOT EXISTS(SELECT 1 FROM hearth_progress.player_progress WHERE player_uuid='"+GHOST+"') AND NOT EXISTS(SELECT 1 FROM hearth_progress.story_inventory WHERE player_uuid='"+GHOST+"') AND NOT EXISTS(SELECT 1 FROM hearth_runtime.story_member WHERE player_uuid='"+GHOST+"')")){rows.next();return rows.getBoolean(1);}}),owner)).thenAccept(ok->check(plugin,ok&&runtime.community(actor).equals(before),"failed_admission_rolls_back_all_rows_and_preserves_previous_roster"))
            .thenCompose(v->on(runtime.admissions().open(fresh,null,true,()->true),owner)).thenAccept(row->{check(plugin,row.revision()==0&&Arrays.stream(row.payload().slots()).allMatch(Objects::isNull),"normal_confirmed_new_member_keeps_existing_empty_inventory_policy");var refs=runtime.community(actor);check(plugin,refs.size()==3&&refs.stream().anyMatch(r->r.player().equals(NEW)&&!r.online()),"only_database_acknowledged_admission_adds_one_offline_member");})
            .thenCompose(v->{var a=bindings.stream().filter(b->b.run().equals(run)).findFirst().orElseThrow();var changed=new StoryBinding(a.run(),a.worldKey(),a.worldId(),a.templateVersion(),a.templateHash(),"c".repeat(64));return rejected(runtime.registry().members(List.of(changed)),null,plugin,"mismatched_binding_does_not_return_an_empty_successful_directory",owner);})
            .thenCompose(v->on(runtime.database().module("community_fixture_hide",c->{try(var st=c.createStatement()){st.execute("ALTER TABLE hearth_runtime.story_member RENAME TO community_probe_hidden");}return null;}),owner))
            .thenCompose(v->rejected(runtime.registry().members(bindings),"42P01",plugin,"real_directory_SELECT_failure_is_not_an_empty_roster",owner))
            .thenCompose(v->on(runtime.database().module("community_fixture_unhide",c->{try(var st=c.createStatement()){st.execute("ALTER TABLE hearth_runtime.community_probe_hidden RENAME TO story_member");}return null;}),owner))
            .thenCompose(v->on(runtime.registry().members(bindings),owner)).thenAccept(rows->check(plugin,rows.size()==4&&runtime.community(actor).size()==3,"read_failure_and_restore_keep_confirmed_members_and_current_cache"))
            .thenCompose(v->on(runtime.database().readInventory(peer),owner)).thenAccept(row->check(plugin,inventory(row).equals(expected.getProperty("peer")),"new_member_and_directory_failures_preserve_offline_inventory_original"))
            .thenCompose(v->on(runtime.database().readPersonal(peer),owner)).thenAccept(row->check(plugin,row.revision()==1&&row.value().equals("private peer progress"),"coarse_roster_does_not_modify_or_publish_peer_private_quest_progress"))
            .thenCompose(v->on(runtime.database().readShared(actor),owner)).thenAccept(row->check(plugin,original.getProperty("shared").equals(row.revision()+"/"+row.value()),"community_hydration_and_admission_do_not_reconfirm_shared_story"));
    }
}
