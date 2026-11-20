package io.github.jesterportfolio.hearth.verification;

import java.util.*;import java.util.concurrent.*;import java.nio.file.*;
import org.bukkit.Bukkit;
import io.github.jesterportfolio.hearth.economy.*;
import io.github.jesterportfolio.hearth.survival.platform.*;

/** 실제 격리 PG/Paper의 설정 중단 경계. 인증된 Player 행동을 대체하지 않는다. */
public final class ConfigurationVerification {
    private static void check(PlatformRuntime p,boolean ok,String name){if(!ok)throw new IllegalStateException(name);p.plugin.getLogger().info("HEARTH_CONFIG_CHECK PASS "+name);}
    private static CompletionStage<Void> rejected(PlatformRuntime p,CompletionStage<?> work,String name){return work.handleAsync((v,e)->{check(p,e!=null,name);return (Void)null;},p.owner);}
    public static CompletionStage<Void> run(PlatformRuntime p,String phase){try{
        var root=IsolatedPaths.require(Path.of(System.getProperty("hearth.aura.verification.root","")),"hearth-native-economy-run.");if(!Files.readString(root.resolve(".hearth-aura-isolated")).equals("Hearth AuraSkills isolated v1\n")||!Path.of("").toRealPath().equals(root.resolve("server"))||!Bukkit.getOnlineMode()||!Bukkit.hasWhitelist()||!Bukkit.getIp().equals("127.0.0.1")||!Bukkit.getOnlinePlayers().isEmpty()||!Set.of("prepare","resume").contains(phase))throw new IllegalStateException("CONFIG_FIXTURE_GATE");Files.writeString(root.resolve("config-"+phase+".started"),"owned localhost zero Players\n",StandardOpenOption.CREATE_NEW);
        Path expected=root.resolve("config-expected.properties");var values=new Properties();Path data=p.plugin.getDataFolder().toPath(),live=data.resolve("platform.properties");
        if(phase.equals("resume")){
            try(var in=Files.newBufferedReader(expected)){values.load(in);}UUID id=UUID.fromString(values.getProperty("pending"));
            check(p,p.maintenance()&&!p.ready()&&!p.jobsAllowed(),"new_JVM_blocks_runtime_and_dispatch_for_unconfirmed_rollback");
            check(p,p.changes.state(p.changes.plan(id)).equals("ROLLBACK_APPLIED")&&p.configReview().entries().stream().anyMatch(e->e.id().equals(id)&&e.blocked()),"both_file_and_PG_evidence_restore_unfinished_direction");
            return rejected(p,p.recoverConfig(),"generic_recovery_cannot_bypass_pending_change").thenComposeAsync(v->p.apply(id,true),p.owner).thenComposeAsync(v->p.store.configState(id),p.owner).thenAcceptAsync(state->{check(p,state.equals("ROLLED_BACK")&&p.ready()&&p.jobsAllowed()&&p.configReview().clear(),"explicit_same_rollback_finalizes_once_and_releases_only_config_maintenance");try{check(p,ConfigurationChanges.hash(Files.readAllBytes(live)).equals(values.getProperty("original")),"retry_keeps_original_file_instead_of_overwriting_new_values");}catch(Exception e){throw new CompletionException(e);}},p.owner).thenComposeAsync(v->preserved(p,values),p.owner);
        }
        check(p,p.ready()&&p.configReview().clear()&&!p.settings().jobs().enabled(),"existing_platform_and_terminal_journals_start_clean");
        try(var in=Files.newBufferedReader(root.resolve("jobs-expected.properties"))){values.load(in);}byte[] original=Files.readAllBytes(live);values.setProperty("original",ConfigurationChanges.hash(original));var props=new Properties();props.load(new java.io.ByteArrayInputStream(original));props.setProperty("stats.cap","123");Path candidate=data.resolve("config_live.candidate");try(var out=Files.newBufferedWriter(candidate,StandardOpenOption.CREATE_NEW)){props.store(out,"fixture-only value");}var plan=p.changes.stage("platform.properties",candidate.getFileName().toString(),p::validateCandidate);
        check(p,p.changes.inspect(plan.id()).position().equals("BEFORE")&&!p.changes.inspect(plan.id()).preview().isEmpty(),"workbench_inspects_original_hash_and_bounded_change_preview");
        return p.apply(plan.id(),false).thenRunAsync(()->check(p,p.ready()&&p.settings().statCap()==123,"live_apply_publishes_settings_and_audit_without_stuck_maintenance"),p.owner)
            .thenComposeAsync(v->p.apply(plan.id(),true),p.owner).thenRunAsync(()->{try{check(p,p.ready()&&ConfigurationChanges.hash(Files.readAllBytes(live)).equals(values.getProperty("original")),"live_rollback_preserves_archives_and_returns_to_ready");Files.writeString(live,"external-invalid-config");}catch(Exception e){throw new CompletionException(e);}},p.owner)
            .thenComposeAsync(v->rejected(p,p.apply(plan.id(),false),"external_edit_is_rejected_without_overwrite"),p.owner)
            .thenRunAsync(()->{try{check(p,Files.readString(live).equals("external-invalid-config"),"external_original_is_preserved_for_operator_review");Files.write(live,original);}catch(Exception e){throw new CompletionException(e);}},p.owner)
            .thenComposeAsync(v->p.recoverConfig(),p.owner).thenRunAsync(()->check(p,p.ready(),"explicit_revalidation_recovers_after_operator_restores_external_file"),p.owner)
            .thenComposeAsync(v->restartRequired(p),p.owner)
            .thenComposeAsync(v->p.apply(plan.id(),false),p.owner)
            .thenComposeAsync(v->{try{p.changes.begin(plan,true,p::validateCandidate);return p.store.prepareConfig(plan.id(),plan.name(),plan.before(),plan.after());}catch(Exception e){return CompletableFuture.failedFuture(e);}},p.owner)
            .thenRunAsync(()->{try{p.changes.publish(plan,true,p::validateCandidate);values.setProperty("pending",plan.id().toString());check(p,p.changes.pending().stream().anyMatch(c->c.id().equals(plan.id())),"rollback_after_atomic_move_remains_pending_before_PG_commit");try(var out=Files.newBufferedWriter(expected,StandardOpenOption.CREATE_NEW)){values.store(out,"configuration restart evidence");}}catch(Exception e){throw new CompletionException(e);}},p.owner)
            .thenComposeAsync(v->rejected(p,p.recoverConfig(),"unfinished_rollback_cannot_be_cleared_in_same_JVM"),p.owner)
            .thenComposeAsync(v->preserved(p,values),p.owner);
    }catch(Exception e){return CompletableFuture.failedFuture(e);}}
    private static CompletionStage<Void> restartRequired(PlatformRuntime p){try{Path data=p.plugin.getDataFolder().toPath();Files.writeString(data.resolve("config_restart.candidate"),Files.readString(data.resolve("rpg-policy.properties"))+"\n# owned restart boundary fixture\n",StandardOpenOption.CREATE_NEW);var plan=p.changes.stage("rpg-policy.properties","config_restart.candidate",p::validateCandidate);return p.apply(plan.id(),false).thenRunAsync(()->check(p,p.maintenance()&&p.configReview().clear(),"restart_required_change_remains_maintenance_even_after_audit_commit"),p.owner).thenComposeAsync(v->rejected(p,p.recoverConfig(),"live_recovery_cannot_bypass_required_restart"),p.owner);}catch(Exception e){return CompletableFuture.failedFuture(e);}}
    private static CompletionStage<Void> preserved(PlatformRuntime p,Properties values){var economy=p.rpg.runtime.economy();return economy.store.account(economy.store.context(economy.settings.realm(),UUID.fromString(values.getProperty("actor")))).thenAcceptAsync(account->check(p,EnchantStore.hash(account.inventory().payload()).equals(values.getProperty("upgrade-hash")),"configuration_operations_preserve_upgraded_inventory"),p.owner).thenCompose(v->economy.store.account(economy.store.context(economy.settings.realm(),UUID.fromString(values.getProperty("sale"))))).thenAcceptAsync(account->check(p,account.balance()==Long.parseLong(values.getProperty("balance"))&&EnchantStore.hash(account.inventory().payload()).equals(values.getProperty("sale-hash")),"configuration_operations_preserve_sale_wallet_and_items"),p.owner);}
}
