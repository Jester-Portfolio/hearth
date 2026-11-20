package io.github.jesterportfolio.hearth.verification;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import io.github.jesterportfolio.hearth.survival.*;
import io.github.jesterportfolio.hearth.survival.skills.WorldAccessPolicy.Role;

/** 소유 격리 콘솔에서만 수행하는 API/위치 객체 검증. Player/행동 이벤트를 만들지 않는다. */
public final class SkillsAdmissionVerification {
    private static void check(JavaPlugin p,boolean ok,String name){if(!ok)throw new IllegalStateException(name);p.getLogger().info("HEARTH_ACCESS_CHECK PASS "+name);}
    public static CompletionStage<Void> run(JavaPlugin plugin,SurvivalRuntime runtime){
        try{
            var root=IsolatedPaths.require(Path.of(System.getProperty("hearth.aura.verification.root","")),"hearth-native-economy-run.");
            if(!Files.readString(root.resolve(".hearth-aura-isolated")).equals("Hearth AuraSkills isolated v1\n")||!Path.of("").toRealPath().equals(root.resolve("server"))||!Bukkit.getOnlineMode()||!Bukkit.hasWhitelist()||!Bukkit.getIp().equals("127.0.0.1")||!Bukkit.getOnlinePlayers().isEmpty())throw new IllegalStateException("ACCESS_FIXTURE_GATE");
            var file=root.resolve("access-"+(runtime.admission().enabled()?"on":"off")+".started");Files.writeString(file,"one console batch\n",StandardOpenOption.CREATE_NEW);
            var gate=runtime.admission();var id=UUID.randomUUID();var home=runtime.worlds().entry(SurvivalWorld.RESIDENCE);var resource=runtime.worlds().entry(SurvivalWorld.WILDERNESS);
            check(plugin,runtime.phase()==SurvivalRuntime.Phase.READY&&gate.role(home.getWorld())==Role.PUBLIC_HOME&&gate.role(resource.getWorld())==Role.RESOURCE,"registered_world_roles_and_public_fallback");
            check(plugin,gate.decision(id,home).allowed(),"public_home_never_requires_skill_data");
            var end=Bukkit.getWorlds().stream().filter(w->w.getEnvironment()==org.bukkit.World.Environment.THE_END).findFirst().orElseThrow();
            check(plugin,!gate.decision(id,end.getSpawnLocation()).allowed(),"unregistered_end_is_not_adopted_as_managed_world");
            var items=PaperWorldMenu.items(null,w->false,w->"숙련도 조건 안내");check(plugin,items[SurvivalWorld.WILDERNESS.slot()].getItemMeta().lore().contains(Component.text("숙련도 조건 안내")),"world_menu_contains_denial_reason_without_player");
            if(!gate.enabled()){boolean refused=false;try{io.github.jesterportfolio.hearth.survival.skills.SkillsBridge.enabled(plugin,gate.policy(),()->{});}catch(IllegalStateException expected){refused=true;}check(plugin,refused,"enabled_feature_refuses_missing_Aura_without_linkage_error");check(plugin,gate.decision(id,resource).allowed(),"disabled_feature_works_without_AuraSkills_API_classes");return CompletableFuture.completedFuture(null);}
            return Enabled.run(plugin,runtime,id,resource);
        }catch(Exception error){return CompletableFuture.failedFuture(error);}
    }
    private static final class Enabled {
        static CompletionStage<Void> run(JavaPlugin plugin,SurvivalRuntime runtime,UUID id,org.bukkit.Location resource){
            var gate=runtime.admission();var api=dev.aurelium.auraskills.api.AuraSkillsApi.get();var mining=dev.aurelium.auraskills.api.skill.Skills.MINING;
            var craft=api.getGlobalRegistry().getSkill(dev.aurelium.auraskills.api.registry.NamespacedId.fromString("hearthprobe/metallurgy"));
            check(plugin,!gate.decision(id,resource).allowed(),"unloaded_offline_record_cannot_enter_resource_world");
            return api.getUserManager().loadUser(id).thenComposeAsync(user->{
                user.setSkillLevel(mining,19,false);user.setSkillLevel(craft,3,false);
                check(plugin,!gate.decision(id,resource).allowed(),"below_threshold_is_denied_by_production_bridge");
                user.setSkillLevel(mining,20,false);
                check(plugin,gate.decision(id,resource).allowed(),"loaded_public_API_levels_meet_multiple_custom_conditions");
                var permit=gate.prepare(id,resource);user.setSkillLevel(mining,19,false);
                check(plugin,!gate.commit(permit,id,resource),"level_reduction_between_prepare_and_commit_is_denied");
                user.setSkillLevel(mining,20,false);check(plugin,gate.commit(permit,id,resource)&&!gate.commit(permit,UUID.randomUUID(),resource)&&!gate.commit(permit,id,runtime.worlds().entry(SurvivalWorld.NETHER)),"permit_is_bound_to_player_and_target_world");
                return runtime.housing().refresh().thenComposeAsync(v->{
                    check(plugin,!gate.commit(permit,id,resource),"PG_housing_generation_change_invalidates_prepared_move");
                    var h=runtime.housing().snapshot().houses().getFirst();return runtime.housing().target(h.owner(),h.id()).thenAcceptAsync(target->{check(plugin,gate.role(target.getWorld())==Role.PRIVATE_HOME&&gate.decision(h.owner(),target).allowed()&&!gate.decision(id,target).allowed(),"real_PG_private_world_ACL_does_not_use_default_skill_levels");check(plugin,!gate.decision(h.owner(),target.clone().add(100000,0,0)).allowed(),"private_world_boundary_is_checked_on_target_location");},r->Bukkit.getScheduler().runTask(plugin,r));
                },r->Bukkit.getScheduler().runTask(plugin,r));
            },r->Bukkit.getScheduler().runTask(plugin,r));
        }
    }
}
