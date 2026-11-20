package io.github.jesterportfolio.hearth.persistence;

import java.util.*;import java.util.concurrent.*;import java.sql.*;import javax.sql.DataSource;
import io.github.jesterportfolio.hearth.economy.*;

public final class ConfigurationDatabaseChecks {
    static <T>T await(CompletionStage<T> s)throws Exception{return s.toCompletableFuture().get(12,TimeUnit.SECONDS);}
    static void check(boolean b,String name){if(!b)throw new IllegalStateException(name);System.out.println("HEARTH_CONFIG_DB_CHECK PASS "+name);System.out.flush();}
    static void reject(CompletionStage<?> s)throws Exception{try{await(s);throw new IllegalStateException("EXPECTED_REJECTION");}catch(ExecutionException expected){}}
    public static void run(DataSource source,Connection c)throws Exception{
        var props=new Properties();try(var in=ConfigurationDatabaseChecks.class.getResourceAsStream("/economy-demo.properties")){props.load(in);}props.setProperty("fixture-balance","0");var db=PostgresProgressStore.forModules(source,f->{});var economy=new EconomyStore(db,EconomyContent.parse(props));await(economy.migrate());UUID realm=UUID.randomUUID(),otherRealm=UUID.randomUUID();await(economy.openNativeRealm(realm,UUID.randomUUID(),"a".repeat(64),1,true));await(economy.openNativeRealm(otherRealm,UUID.randomUUID(),"b".repeat(64),1,true));var p=new PlatformStore(db,economy,realm);var other=new PlatformStore(db,economy,otherRealm);String before="a".repeat(64),after="b".repeat(64);UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        var left=p.prepareConfig(a,"platform.properties",before,after);var right=p.prepareConfig(b,"platform.properties",before,after);int wins=0;for(var work:List.of(left,right))try{await(work);wins++;}catch(ExecutionException expected){}var row=await(p.configs()).getFirst();check(wins==1&&await(p.configs()).size()==1,"concurrent_distinct_candidates_have_one_prepared_winner");
        UUID id=row.request();await(p.prepareConfig(id,row.name(),before,after));check(await(p.configs()).getFirst().equals(row),"same_request_prepare_replays_immutable_identity");reject(p.prepareConfig(id,"rpg-policy.properties",before,after));check(await(p.configs()).getFirst().equals(row),"request_identity_conflict_keeps_original_record");reject(p.requireInactive());check(true,"prepared_change_requires_platform_runtime");
        await(other.prepareConfig(id,"platform.properties",after,before));check(await(other.configs()).getFirst().before().equals(after)&&await(p.configs()).getFirst().before().equals(before),"same_UUID_in_other_realm_is_independent");
        var first=p.config(id,row.name(),before,after,"PREPARED","COMMITTED");var second=p.config(id,row.name(),before,after,"PREPARED","COMMITTED");wins=0;for(var work:List.of(first,second))try{await(work);wins++;}catch(ExecutionException expected){}check(wins==1&&await(p.configState(id)).equals("COMMITTED"),"terminal_CAS_has_one_winner");
        await(p.prepareConfig(id,row.name(),before,after));check(await(p.configState(id)).equals("PREPARED"),"rollback_reopens_terminal_record_before_file_publication");
        try(var s=c.createStatement()){s.execute("CREATE FUNCTION hearth_economy.reject_config_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture failure' USING ERRCODE='23514'; END $$");s.execute("CREATE TRIGGER reject_config_fixture BEFORE UPDATE ON hearth_economy.platform_config_change FOR EACH ROW EXECUTE FUNCTION hearth_economy.reject_config_fixture()");}
        reject(p.config(id,row.name(),before,after,"PREPARED","ROLLED_BACK"));check(await(p.configState(id)).equals("PREPARED"),"SQL_commit_failure_preserves_prepared_recovery_evidence");
        try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_config_fixture ON hearth_economy.platform_config_change");s.execute("DROP FUNCTION hearth_economy.reject_config_fixture()");}
        await(p.config(id,row.name(),before,after,"PREPARED","ROLLED_BACK"));await(p.requireInactive());check(await(p.configState(id)).equals("ROLLED_BACK"),"explicit_retry_finalizes_rollback_and_releases_config_only_guard");
        check(await(other.configState(id)).equals("PREPARED"),"rollback_does_not_release_other_realm_pending_work");await(db.closeAsync());
    }
}
