package io.github.jesterportfolio.hearth.verification;

import io.github.jesterportfolio.hearth.economy.*;
import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.session.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** 명시된 새 격리 경로에서만 실행하는 정상 경제 모드의 제어 포트 검사. Player/인증을 만들지 않습니다. */
public final class EconomyRuntimeVerification {
    private static final UUID SAVED=new UUID(0x45434f,101),RACE=new UUID(0x45434f,102),FAILED=new UUID(0x45434f,103),NEW=new UUID(0x45434f,104);
    private EconomyRuntimeVerification(){}
    private static Path root(JavaPlugin plugin)throws Exception{
        if(!Boolean.getBoolean("hearth.verification.economyRuntime")||!Bukkit.getOnlinePlayers().isEmpty())throw new IllegalStateException("ECON_RUNTIME_PROBE_GATE");
        var root=IsolatedPaths.require(Path.of("").toRealPath().getParent(),"hearth-s4-11.");
        if(!Files.readString(root.resolve(".hearth-lifecycle-isolated")).equals("S4-11 isolated test only\n")||!plugin.getDataFolder().toPath().toRealPath().equals(root.resolve("server/plugins/Hearth")))throw new IllegalStateException("ECON_RUNTIME_PROBE_GATE");
        return root;
    }
    private static void check(JavaPlugin plugin,boolean value,String label){if(!value)throw new IllegalStateException("ECON_RUNTIME_CHECK_FAILED_"+label);plugin.getLogger().info("HEARTH_SESSION_CHECK PASS economy_runtime_"+label);}
    private static String account(EconomyStore.Account a){return a.balance()+"/"+a.inventory().revision()+"/"+Base64.getEncoder().encodeToString(a.inventory().payload().bytes());}
    static String progress(Connection connection)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256");var tables=new ArrayList<String>();try(var st=connection.createStatement();var rows=st.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema='hearth_progress' AND table_type='BASE TABLE' ORDER BY table_name")){while(rows.next())tables.add(rows.getString(1));}
        for(String table:tables){if(!table.matches("[a-z_]+"))throw new IllegalStateException("Invalid fixture table");digest.update(table.getBytes(java.nio.charset.StandardCharsets.UTF_8));try(var st=connection.createStatement();var rows=st.executeQuery("SELECT row_to_json(t)::text FROM hearth_progress."+table+" t ORDER BY row_to_json(t)::text COLLATE \"C\"")){while(rows.next()){digest.update(rows.getString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update((byte)'\n');}}}return HexFormat.of().formatHex(digest.digest());
    }
    private static <T> CompletionStage<T> on(CompletionStage<T> value,Executor owner){return value.thenApplyAsync(v->v,owner);}
    private static CompletionStage<Void> rejected(CompletionStage<?> work,JavaPlugin plugin,String label,Executor owner){return work.handleAsync((v,error)->{check(plugin,error!=null,label);return (Void)null;},owner);}
    public static CompletionStage<Void> prepare(JavaPlugin plugin,PostgresProgressStore db,PaperEconomy economy,PaperInventoryCodec codec,Executor owner){
        final Path root;try{root=root(plugin);}catch(Exception error){return CompletableFuture.failedFuture(error);}
        var expected=new Properties();var empty=codec.encode(new ItemStack[41]);var context=economy.store.context(economy.realm,SAVED);var race=economy.store.context(economy.realm,RACE);var failed=economy.store.context(economy.realm,FAILED);
        CompletionStage<Void> chain=on(db.module("runtime_story_before",EconomyRuntimeVerification::progress),owner).thenAccept(hash->expected.setProperty("story",hash));
        for(int i=1;i<=8;i++){int n=i;chain=chain.thenCompose(v->on(economy.store.account(economy.store.context(economy.realm,new UUID(0x45434f,n))),owner).thenAccept(a->expected.setProperty("account."+n,account(a))));}
        return chain.thenCompose(v->on(economy.store.ensureRealm(economy.realm,System.currentTimeMillis()),owner)).thenAccept(created->check(plugin,!created,"existing_realm_not_reinitialized"))
            .thenCompose(v->{var realm=new UUID(0x45434f,201);var one=economy.store.ensureRealm(realm,System.currentTimeMillis());var two=economy.store.ensureRealm(realm,System.currentTimeMillis());return on(one.thenCombine(two,(a,b)->a!=b),owner);}).thenAccept(ok->check(plugin,ok,"concurrent_realm_bootstrap_initializes_once"))
            .thenCompose(v->{var slots=new ItemStack[41];slots[0]=new ItemStack(Material.COBBLESTONE);return rejected(economy.store.ensureAccount(failed,codec.encode(slots)),plugin,"new_account_rejects_nonempty_initial_assets",owner);})
            .thenCompose(v->on(economy.store.ensureAccount(context,empty),owner)).thenAccept(a->check(plugin,a.balance()==0&&a.inventory().revision()==0&&codec.equivalent(empty,a.inventory().payload()),"first_empty_account_balance_zero"))
            .thenCompose(v->{var slots=new ItemStack[41];slots[0]=new ItemStack(Material.COBBLESTONE,3);return on(economy.store.saveInventory(context,0,codec.encode(slots)),owner);})
            .thenCompose(v->on(economy.store.ensureAccount(context,empty),owner)).thenAccept(a->{check(plugin,a.balance()==0&&a.inventory().revision()==1&&codec.decode(a.inventory().payload())[0].getAmount()==3,"reopening_never_resets_existing_account");expected.setProperty("saved",account(a));})
            .thenCompose(v->{var one=economy.store.ensureAccount(race,empty);var two=economy.store.ensureAccount(race,empty);return on(one.thenCombine(two,(a,b)->a.equals(b)&&a.balance()==0&&a.inventory().revision()==0),owner);}).thenAccept(ok->check(plugin,ok,"concurrent_account_creation_single_row"))
            .thenCompose(v->on(db.module("runtime_fail_on",sql->{try(var st=sql.createStatement()){st.execute("CREATE FUNCTION hearth_economy.reject_runtime_account() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.player_uuid='"+FAILED+"'::uuid THEN RAISE EXCEPTION 'isolated account failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");st.execute("CREATE TRIGGER reject_runtime_account BEFORE INSERT ON hearth_economy.account FOR EACH ROW EXECUTE FUNCTION hearth_economy.reject_runtime_account()");}return null;}),owner))
            .thenCompose(v->rejected(economy.store.ensureAccount(failed,empty),plugin,"failed_creation_not_accepted",owner))
            .thenCompose(v->on(db.module("runtime_fail_off",sql->{try(var st=sql.createStatement()){st.execute("DROP TRIGGER reject_runtime_account ON hearth_economy.account");st.execute("DROP FUNCTION hearth_economy.reject_runtime_account()");}try(var st=sql.prepareStatement("SELECT count(*) FROM hearth_economy.account WHERE realm_id=? AND player_uuid=?")){st.setObject(1,economy.realm);st.setObject(2,FAILED);try(var rows=st.executeQuery()){rows.next();return rows.getInt(1)==0;}}}),owner)).thenAccept(ok->check(plugin,ok,"creation_rollback_preserves_missing_row"))
            .thenCompose(v->rejected(economy.store.ensureAccount(new ServerProgressAccess().forVerifiedServerLink(economy.realm,SAVED),empty),plugin,"foreign_context_cannot_provision",owner))
            .thenCompose(v->on(db.module("runtime_story_after",EconomyRuntimeVerification::progress),owner)).thenAccept(hash->check(plugin,hash.equals(expected.getProperty("story")),"bootstrap_keeps_story_database"))
            .thenRunAsync(()->{try(var out=Files.newOutputStream(root.resolve("economy-runtime-expected.properties"),StandardOpenOption.CREATE_NEW)){expected.store(out,"Synthetic isolated account and story hashes");}catch(Exception error){throw new CompletionException(error);}},owner);
    }
    /** 수정 재검사에서는 통과한 전체 거래 시나리오를 반복하지 않고 복원 입력만 새로 만듭니다. */
    public static CompletionStage<Void> seed(JavaPlugin plugin,PostgresProgressStore db,PaperEconomy economy,PaperInventoryCodec codec,Executor owner){
        final Path root;try{root=root(plugin);}catch(Exception error){return CompletableFuture.failedFuture(error);}var expected=new Properties();var empty=codec.encode(new ItemStack[41]);
        CompletionStage<Void> chain=on(economy.store.migrate(),owner).thenCompose(v->on(economy.store.createRealm(economy.realm,System.currentTimeMillis()),owner));
        for(int i=1;i<=8;i++){int n=i;chain=chain.thenCompose(v->{var slots=new ItemStack[41];slots[0]=new ItemStack(Material.IRON_INGOT,n);return on(economy.store.createAccount(economy.store.context(economy.realm,new UUID(0x45434f,n)),100000+n,codec.encode(slots)),owner);});}
        var context=economy.store.context(economy.realm,SAVED);
        chain=chain.thenCompose(v->on(economy.store.ensureAccount(context,empty),owner)).thenCompose(v->{var slots=new ItemStack[41];slots[0]=new ItemStack(Material.COBBLESTONE,3);return on(economy.store.saveInventory(context,0,codec.encode(slots)),owner);}).thenApply(v->null);
        for(int i=1;i<=8;i++){int n=i;chain=chain.thenCompose(v->on(economy.store.account(economy.store.context(economy.realm,new UUID(0x45434f,n))),owner).thenAccept(a->expected.setProperty("account."+n,account(a))));}
        return chain.thenCompose(v->on(economy.store.account(context),owner)).thenAccept(a->expected.setProperty("saved",account(a)))
            .thenCompose(v->on(db.module("runtime_story_seed",EconomyRuntimeVerification::progress),owner)).thenAccept(hash->expected.setProperty("story",hash))
            .thenCompose(v->{var actor=new PlayerSessions.Port(){InventoryPayload value;public boolean connected(){return true;}public InventoryPayload capture(){return value;}public void apply(InventoryPayload p){value=p;}public CompletionStage<Boolean> move(){return CompletableFuture.completedFuture(true);}};var session=economy.sessions.connect(context,economy.world,actor);return on(session.ready(),owner).thenCompose(x->on(economy.sessions.disconnect(session),owner));})
            .thenRunAsync(()->{try(var output=Files.newOutputStream(root.resolve("economy-runtime-expected.properties"),StandardOpenOption.CREATE_NEW)){expected.store(output,"Fresh restore inputs only; successful transaction tests are not repeated");}catch(Exception error){throw new CompletionException(error);}},owner);
    }
    private static CompletionStage<Void> storyAfterRestart(Path root,JavaPlugin plugin,Executor owner,String expected)throws Exception{
        var endpoint=new Properties();try(var input=Files.newInputStream(root.resolve("db-endpoint.properties"))){endpoint.load(input);}var source=new org.postgresql.ds.PGSimpleDataSource();source.setServerNames(new String[]{"127.0.0.1"});source.setPortNumbers(new int[]{Integer.parseInt(endpoint.getProperty("port"))});source.setDatabaseName("hearth_s4_11");source.setUser("postgres");source.setConnectTimeout(2);source.setSocketTimeout(5);
        var read=PostgresProgressStore.forModules(source,f->{});var done=new CompletableFuture<Void>();
        read.module("runtime_story_after_restart",EconomyRuntimeVerification::progress).whenComplete((hash,error)->{
            read.closeAsync().whenComplete((v,closeError)->owner.execute(()->{
                if(error!=null)done.completeExceptionally(error);
                else if(closeError!=null)done.completeExceptionally(closeError);
                else try{check(plugin,expected.equals(hash),"normal_module_boot_keeps_original_story_PG");done.complete(null);}
                catch(RuntimeException failure){done.completeExceptionally(failure);}
            }));
        });return done;
    }
    public static CompletionStage<Void> run(JavaPlugin plugin,EconomyRuntime runtime){
        if(Boolean.getBoolean("hearth.verification.peerTrade"))return PeerTradeVerification.restored(plugin,runtime);
        final Path root;final Properties expected=new Properties();try{root=root(plugin);try(var input=Files.newInputStream(root.resolve("economy-runtime-expected.properties"))){expected.load(input);}var endpoint=new Properties();try(var input=Files.newInputStream(root.resolve("db-endpoint.properties"))){endpoint.load(input);}var pid=Files.readAllLines(root.resolve("data/postmaster.pid"));if(!runtime.settings().dbHost().equals("127.0.0.1")||runtime.settings().dbPort()!=Integer.parseInt(endpoint.getProperty("port"))||!runtime.settings().dbName().equals("hearth_s4_11")||!Path.of(pid.get(1)).toRealPath().equals(root.resolve("data")))throw new IllegalStateException("ECON_RUNTIME_PROBE_DB_GATE");}catch(Exception error){return CompletableFuture.failedFuture(error);}
        var economy=runtime.economy();economy.pinFixtureChunks();var owner=runtime.owner();var codec=new PaperInventoryCodec();var empty=codec.encode(new ItemStack[41]);var saved=economy.store.context(economy.realm,SAVED);var fresh=economy.store.context(economy.realm,NEW);var baseline=new java.util.concurrent.atomic.AtomicReference<EconomyEngine.State>();
        check(plugin,runtime.phase()==EconomyRuntime.Phase.READY&&!plugin.getConfig().getBoolean("verification-enabled")&&!plugin.getConfig().getBoolean("lifecycle-verification-enabled")&&!plugin.getConfig().getBoolean("economy-verification-enabled"),"normal_mode_without_story_or_verification_runtime");
        var chain=on(economy.stopTicker(),owner).thenCompose(v->on(economy.store.snapshot(economy.realm),owner)).thenAccept(baseline::set).thenCompose(v->on(economy.store.resumeClock(economy.realm,System.currentTimeMillis()+1000000),owner)).thenAccept(count->check(plugin,count==0,"offline_pause_no_catchup"))
            .thenCompose(v->on(runtime.admissions().open(saved,economy.realm.toString(),false,()->true),owner)).thenAccept(a->check(plugin,account(a).equals(expected.getProperty("saved")),"new_JVM_existing_account_preserved"));
        for(int i=1;i<=8;i++){int n=i;chain=chain.thenCompose(v->on(economy.store.account(economy.store.context(economy.realm,new UUID(0x45434f,n))),owner).thenAccept(a->check(plugin,account(a).equals(expected.getProperty("account."+n)),"legacy_account_preserved_"+n)));}
        return chain.thenCompose(v->rejected(runtime.admissions().open(fresh,null,false,()->true),plugin,"unmanaged_nonempty_inventory_preserved",owner))
            .thenCompose(v->rejected(runtime.admissions().open(fresh,UUID.randomUUID().toString(),true,()->true),plugin,"foreign_inventory_owner_denied",owner))
            .thenCompose(v->on(runtime.admissions().open(fresh,null,true,()->true),owner)).thenAccept(a->check(plugin,a.balance()==0&&codec.equivalent(a.inventory().payload(),empty),"normal_admission_new_zero_account"))
            .thenCompose(v->on(economy.store.ensureRealm(economy.realm,System.currentTimeMillis()),owner)).thenAccept(created->check(plugin,!created,"normal_bootstrap_does_not_reset_realm"))
            .thenCompose(v->on(economy.store.snapshot(economy.realm),owner)).thenAccept(after->check(plugin,after.tick()==baseline.get().tick()&&after.markets().equals(baseline.get().markets())&&after.shipments().equals(baseline.get().shipments()),"offline_resume_and_admission_preserve_market_transport_tick"))
            .thenCompose(v->{try{return storyAfterRestart(root,plugin,owner,expected.getProperty("story"));}catch(Exception error){return CompletableFuture.failedFuture(error);}})
            .thenCompose(v->{var actor=new PlayerSessions.Port(){InventoryPayload value;public boolean connected(){return true;}public InventoryPayload capture(){return value;}public void apply(InventoryPayload p){value=p;check(plugin,codec.equivalent(p,empty),"normal_controlled_session_applies_only_own_PG_inventory");}public CompletionStage<Boolean> move(){return CompletableFuture.completedFuture(true);}};var session=economy.sessions.connect(fresh,economy.world,actor);return on(session.ready(),owner).thenAccept(x->check(plugin,economy.world.state()==io.github.jesterportfolio.hearth.lifecycle.WorldLifecycle.State.ACTIVE&&economy.sessions.playable(session)&&!economy.merchantEntities().isEmpty(),"normal_session_world_existing_WorldGuard_and_merchants_ready"));});
    }
}
