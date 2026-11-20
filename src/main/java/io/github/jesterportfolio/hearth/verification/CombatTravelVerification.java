package io.github.jesterportfolio.hearth.verification;
import java.util.*;import java.util.concurrent.*;import java.nio.file.*;
import org.bukkit.*;import org.bukkit.event.EventPriority;import org.bukkit.event.entity.EntityDamageByEntityEvent;import org.bukkit.plugin.java.JavaPlugin;
import io.github.jesterportfolio.hearth.survival.*;

/** 실제 Player 없이 등록/PG 저장·복원/단일 이동 허가 상태만 확인하는 소유 격리 검사. */
public final class CombatTravelVerification {
    static void check(JavaPlugin p,boolean ok,String name){if(!ok)throw new IllegalStateException(name);p.getLogger().info("HEARTH_COMBAT_CHECK PASS "+name);}
    public static CompletionStage<Void> run(JavaPlugin plugin,SurvivalRuntime runtime,String phase){
        try{
            var root=IsolatedPaths.require(Path.of(System.getProperty("hearth.aura.verification.root","")),"hearth-native-economy-run.");if(!Files.readString(root.resolve(".hearth-aura-isolated")).equals("Hearth AuraSkills isolated v1\n")||!Path.of("").toRealPath().equals(root.resolve("server"))||!Bukkit.getOnlineMode()||!Bukkit.hasWhitelist()||!Bukkit.getIp().equals("127.0.0.1")||!Bukkit.getOnlinePlayers().isEmpty()||!Set.of("prepare","resume").contains(phase))throw new IllegalStateException("COMBAT_FIXTURE_GATE");
            Files.writeString(root.resolve("combat-"+phase+".started"),"one console batch\n",StandardOpenOption.CREATE_NEW);
            var combat=runtime.combatTravel();check(plugin,runtime.phase()==SurvivalRuntime.Phase.READY,"combat_runtime_reaches_ready_with_native_PG");
            check(plugin,Arrays.stream(EntityDamageByEntityEvent.getHandlerList().getRegisteredListeners()).anyMatch(l->l.getListener()==combat&&l.getPriority()==EventPriority.MONITOR),"actual_Paper_MONITOR_damage_listener_is_registered_without_firing_a_player_event");
            for(String command:List.of("tpa","tpaccept","tpdeny","tpcancel"))check(plugin,plugin.getCommand(command).getExecutor()!=null,"command_registered_"+command);
            var expected=root.resolve("combat-expected.properties");
            if(phase.equals("resume")){
                var values=new Properties();try(var reader=Files.newBufferedReader(expected)){values.load(reader);}UUID id=UUID.fromString(values.getProperty("player"));long until=Long.parseLong(values.getProperty("until"));
                check(plugin,combat.tags.deadline(id)==until&&!runtime.travelAllowed(id),"new_Paper_JVM_restores_PG_deadline_and_blocks_ordinary_travel");
                check(plugin,runtime.admission().decision(id,runtime.worlds().entry(SurvivalWorld.RESIDENCE)).allowed(),"safe_public_access_remains_valid_without_clearing_combat_tag");
                return combat.store.load(0).thenAcceptAsync(saved->check(plugin,saved.get(id)==until,"new_pool_reads_exact_persisted_deadline"),runtime.economy().owner);
            }
            UUID a=UUID.randomUUID(),b=UUID.randomUUID();combat.tags.hit(a,b);
            check(plugin,!runtime.travelAllowed(a)&&!runtime.travelAllowed(b),"both_combat_participants_are_denied_by_shared_runtime_gate");
            check(plugin,combat.tags.pending().size()==2,"hits_are_coalesced_in_memory_before_PG_flush");
            return combat.flush().thenComposeAsync(v->{check(plugin,combat.tags.pending().isEmpty(),"PG_completion_acknowledges_only_saved_deadlines");return combat.store.load(0);},runtime.economy().owner).thenComposeAsync(saved->{check(plugin,saved.get(a)>System.currentTimeMillis()&&saved.get(b)>System.currentTimeMillis(),"actual_30_second_deadlines_are_durable");long until=System.currentTimeMillis()+120_000;var values=new Properties();values.setProperty("player",a.toString());values.setProperty("until",""+until);try(var writer=Files.newBufferedWriter(expected,StandardOpenOption.CREATE_NEW)){values.store(writer,"fixture-only future deadline for bounded two JVM restoration");}catch(Exception error){throw new CompletionException(error);}return combat.store.save(Map.of(a,until));},runtime.economy().owner);
        }catch(Exception error){return CompletableFuture.failedFuture(error);}
    }
}
