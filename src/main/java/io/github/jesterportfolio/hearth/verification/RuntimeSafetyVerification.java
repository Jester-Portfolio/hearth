package io.github.jesterportfolio.hearth.verification;

import io.github.jesterportfolio.hearth.story.*;
import io.github.jesterportfolio.hearth.economy.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.inventory.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** 실제 PG 누락/실패와 두 JVM 보존 검사. 인증 플레이·운영 DB를 사용하지 않습니다. */
public final class RuntimeSafetyVerification {
    private static final UUID MISSING=new UUID(51,91),FRESH=new UUID(51,92),PLAYER=new UUID(49,1);
    private RuntimeSafetyVerification(){}
    private static void gate(){if(!Boolean.getBoolean("hearth.verification.runtimeSafety")||!Bukkit.getOnlinePlayers().isEmpty())throw new IllegalStateException("RUNTIME_SAFETY_GATE");try{IsolatedPaths.require(Path.of("").toRealPath().getParent(),"hearth-s4-11.");}catch(Exception e){throw new IllegalStateException("RUNTIME_SAFETY_GATE");}}
    private static void check(JavaPlugin p,boolean ok,String name){if(!ok)throw new IllegalStateException("RUNTIME_SAFETY_FAILED_"+name);p.getLogger().info("HEARTH_SESSION_CHECK PASS runtime_safety_"+name);}
    private static <T> CompletionStage<T> on(CompletionStage<T> work,Executor owner){return work.thenApplyAsync(v->v,owner);}
    private static CompletionStage<Void> rejected(CompletionStage<?> work,String code,JavaPlugin p,String name,Executor owner){return work.handleAsync((v,error)->{check(p,error!=null&&io.github.jesterportfolio.hearth.administration.RecoveryConsole.errorCode(error).equals(code),name);return (Void)null;},owner);}
    private static EconomyStore economy(JavaPlugin p,PostgresProgressStore db)throws Exception{var props=new Properties();try(var input=Objects.requireNonNull(p.getResource("economy-demo.properties"))){props.load(input);}return new EconomyStore(db,EconomyContent.parse(props));}
    public static CompletionStage<Void> seed(JavaPlugin p,PostgresProgressStore db,StoryRegistry registry,StoryBinding binding,Executor owner){
        gate();final EconomyStore economy;try{economy=economy(p,db);}catch(Exception e){return CompletableFuture.failedFuture(e);}var absent=new StoryBinding(MISSING,"hearth:missing",MISSING,binding.templateVersion(),binding.templateHash(),binding.contentHash());
        return rejected(registry.verify(absent),"INVALID_STATE",p,"missing_story_binding_is_not_adopted",owner)
            .thenCompose(v->on(registry.verify(binding),owner)).thenRun(()->check(p,true,"existing_story_binding_verified_without_recreation"))
            .thenCompose(v->rejected(registry.verify(new StoryBinding(binding.run(),binding.worldKey(),binding.worldId(),binding.templateVersion(),binding.templateHash(),"d".repeat(64))),"STORY_BINDING_CONFLICT",p,"changed_content_is_not_rebound",owner))
            .thenCompose(v->on(economy.migrate(),owner))
            .thenCompose(v->rejected(economy.openRealm(MISSING,100,false),"ECON_REALM_MISSING",p,"known_world_missing_realm_refuses_new_money_and_stock",owner))
            .thenCompose(v->on(db.module("safety_absent_rows",c->{try(var st=c.createStatement();var r=st.executeQuery("SELECT NOT EXISTS(SELECT 1 FROM hearth_runtime.story_binding WHERE story_run_id='"+MISSING+"') AND NOT EXISTS(SELECT 1 FROM hearth_economy.realm WHERE realm_id='"+MISSING+"') AND NOT EXISTS(SELECT 1 FROM hearth_economy.market WHERE realm_id='"+MISSING+"')")){r.next();return r.getBoolean(1);}}),owner)).thenAccept(ok->check(p,ok,"refusal_leaves_all_missing_owner_rows_absent"))
            .thenCompose(v->on(economy.openRealm(FRESH,123,true).thenCombine(economy.openRealm(FRESH,123,true),(a,b)->a!=b),owner)).thenAccept(ok->check(p,ok,"explicit_fresh_realm_race_has_one_initializer"))
            .thenCompose(v->on(db.module("safety_preserved_clock",c->{try(var st=c.createStatement()){st.executeUpdate("UPDATE hearth_economy.realm SET tick=19,revision=7 WHERE realm_id='"+FRESH+"'");}return null;}),owner))
            .thenCompose(v->on(economy.openRealm(FRESH,999,false),owner)).thenAccept(created->check(p,!created,"known_realm_verification_does_not_report_creation"))
            .thenCompose(v->on(db.module("safety_hide_market",c->{try(var st=c.createStatement()){st.execute("ALTER TABLE hearth_economy.market RENAME TO safety_hidden_market");}return null;}),owner))
            .thenCompose(v->rejected(economy.openRealm(FRESH,999,false),"42P01",p,"actual_market_read_failure_is_not_success",owner))
            .thenCompose(v->on(db.module("safety_restore_market",c->{try(var st=c.createStatement()){st.execute("ALTER TABLE hearth_economy.safety_hidden_market RENAME TO market");}return null;}),owner));
    }
    public static CompletionStage<Void> restored(JavaPlugin p,StoryRuntime runtime){
        gate();var owner=runtime.owner();var ctx=runtime.access().forVerifiedServerLink(runtime.settings().members().get(PLAYER),PLAYER);var expected=new Properties();try(var input=Files.newInputStream(Path.of("").toRealPath().getParent().resolve("story-runtime-expected.properties"))){expected.load(input);}catch(Exception e){return CompletableFuture.failedFuture(e);}final EconomyStore economy;try{economy=economy(p,runtime.database());}catch(Exception e){return CompletableFuture.failedFuture(e);}
        check(p,runtime.phase()==StoryRuntime.Phase.READY&&!p.getConfig().getBoolean("verification-enabled"),"normal_new_JVM_opens_only_pre_registered_story_bindings");
        return on(economy.migrate(),owner).thenCompose(v->on(economy.openRealm(FRESH,999,false),owner)).thenAccept(created->check(p,!created,"economic_existing_realm_contract_survives_new_JVM"))
            .thenCompose(v->on(runtime.database().module("safety_restored_clock",c->{try(var st=c.createStatement();var r=st.executeQuery("SELECT tick=19 AND revision=7 AND wall_millis=123 FROM hearth_economy.realm WHERE realm_id='"+FRESH+"'")){return r.next()&&r.getBoolean(1);}}),owner)).thenAccept(ok->check(p,ok,"clock_revision_and_offline_pause_origin_preserved"))
            .thenCompose(v->rejected(economy.openRealm(MISSING,999,false),"ECON_REALM_MISSING",p,"missing_economic_owner_stays_missing_after_restart",owner))
            .thenCompose(v->on(runtime.database().readInventory(ctx),owner)).thenAccept(row->check(p,expected.getProperty("inventory").equals(row.revision()+"/"+Base64.getEncoder().encodeToString(row.payload().bytes())),"original_story_inventory_unchanged"))
            .thenCompose(v->on(runtime.database().readShared(ctx),owner)).thenAccept(row->check(p,expected.getProperty("shared").equals(row.revision()+"/"+row.value()),"shared_progress_not_reconfirmed"));
    }
}
