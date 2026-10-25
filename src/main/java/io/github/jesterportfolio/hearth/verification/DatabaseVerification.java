package io.github.jesterportfolio.hearth.verification;

import io.github.jesterportfolio.hearth.persistence.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.postgresql.ds.PGSimpleDataSource;

/** S4-5 콘솔 전용 어댑터. 운영 계정·플레이어 세션·인벤토리 관리자가 아닙니다. */
public final class DatabaseVerification implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Logger logger;
    private final PGSimpleDataSource source;
    private final ServerProgressAccess access = new ServerProgressAccess();
    private final ServerProgressAccess.Context a1 = access.forVerifiedServerLink(new UUID(42,10),new UUID(45,1));
    private final ServerProgressAccess.Context a2 = access.forVerifiedServerLink(new UUID(42,10),new UUID(45,2));
    private final ServerProgressAccess.Context b1 = access.forVerifiedServerLink(new UUID(42,11),new UUID(45,1));
    private final ConcurrentLinkedQueue<Runnable> deliveries = new ConcurrentLinkedQueue<>();
    private final AtomicInteger errorCount = new AtomicInteger();
    private final PostgresProgressStore store;
    private PostgresProgressStore probe;
    private final BukkitTask pump;
    private long generation, ticks;
    private boolean busy, closed;

    public DatabaseVerification(JavaPlugin plugin, Path root) throws Exception {
        this.plugin=plugin; this.logger=plugin.getLogger();
        if (!Bukkit.isPrimaryThread() || !root.toRealPath().equals(root) || !root.getParent().equals(Path.of("/tmp"))
                || !root.getFileName().toString().startsWith("hearth-s4-5.")
                || !Files.getOwner(root).getName().equals(System.getProperty("user.name"))) throw new IllegalStateException("DB root rejected");
        Properties endpoint=new Properties();
        try (var in=Files.newInputStream(root.resolve("db-endpoint.properties"))) { endpoint.load(in); }
        Path data=root.resolve("data").toRealPath();
        var pid=Files.readAllLines(data.resolve("postmaster.pid"));
        int port=Integer.parseInt(endpoint.getProperty("port"));
        if (!Path.of(pid.get(1)).toRealPath().equals(data) || port!=Integer.parseInt(pid.get(3))
                || !Files.readString(root.resolve(".hearth-s45-db")).trim().equals(endpoint.getProperty("marker"))
                || !"hearth_s4_5".equals(endpoint.getProperty("database"))) throw new IllegalStateException("DB fixture identity rejected");
        source=new PGSimpleDataSource();
        source.setServerNames(new String[]{"127.0.0.1"}); source.setPortNumbers(new int[]{port});
        source.setDatabaseName("hearth_s4_5"); source.setUser("postgres");
        source.setConnectTimeout(2); source.setSocketTimeout(5); source.setPrepareThreshold(0);
        source.setApplicationName("hearth-s4-5-isolated");
        store=newStore(); // 생성자는 DB에 연결하지 않습니다.
        pump=Bukkit.getScheduler().runTaskTimer(plugin,() -> {
            ticks++;
            for (int n=0; n<100; n++) { Runnable action=deliveries.poll(); if (action==null) break; action.run(); }
        },1,1);
        for (Class<?> library : List.of(org.postgresql.Driver.class,com.zaxxer.hikari.HikariDataSource.class,org.slf4j.LoggerFactory.class)) {
            logger.info("HEARTH_S45_LIBRARY "+library.getName()+" source="+library.getProtectionDomain().getCodeSource().getLocation()
                    +" loader="+library.getClassLoader().getClass().getName());
        }
        logger.info("HEARTH_S45_MODULE READY jdbc="+org.postgresql.Driver.class.getPackage().getImplementationVersion()
                +" hikari="+com.zaxxer.hikari.HikariDataSource.class.getPackage().getImplementationVersion());
    }

    private PostgresProgressStore newStore() {
        return new PostgresProgressStore(source,access,f -> {
            errorCount.incrementAndGet();
            logger.info("HEARTH_S45_EXPECTED_ERROR operation="+f.operation()+" code="+f.code()
                    +" thread="+Thread.currentThread().getName());
        });
    }

    public void start(String phase) {
        if (!Bukkit.isPrimaryThread() || busy || closed) { logger.warning("HEARTH_S45_COMMAND_REJECTED"); return; }
        busy=true;
        try {
            CompletionStage<Void> work=switch (phase) {
                case "prepare" -> prepare();
                case "resume" -> resume();
                case "revision" -> revisions();
                case "write-failure" -> writeFailure();
                case "schema-mismatch" -> schemaFailure("SCHEMA_VERSION_MISMATCH");
                case "schema-missing" -> schemaFailure("42P01");
                case "schema-restore" -> main(store.migrate(),"schema_restore").thenApply(v -> null);
                case "queue" -> queue();
                case "drain" -> drain();
                case "close" -> main(store.closeAsync(),"close").thenCompose(v -> expect(store.readShared(a1),"CLOSED","closed_requests_rejected"));
                case "disconnect-ready" -> {
                    probe=newStore();
                    yield main(probe.migrate(),"probe_connect").thenCompose(v -> main(probe.readShared(b1),"probe_read")).thenApply(v -> null);
                }
                case "disconnected" -> disconnected();
                default -> throw new IllegalArgumentException("Unknown DB test phase");
            };
            work.whenComplete((v,e) -> deliveries.add(() -> {
                busy=false;
                if (e==null) logger.info("HEARTH_S45_RESULT PASS "+phase);
                else logger.severe("HEARTH_S45_RESULT FAIL "+phase+" code="+code(e));
            }));
        } catch (Exception failure) { busy=false; logger.severe("HEARTH_S45_RESULT FAIL "+phase+" code="+code(failure)); }
    }

    private CompletionStage<Void> prepare() {
        return expect(store.readShared(a1),"SCHEMA_NOT_READY","before_migration_rejected")
            .thenCompose(v -> main(store.migrate(),"migrate"))
            .thenCompose(v -> main(store.createRun(a1.storyRunId(),"a0"),"create_a"))
            .thenCompose(v -> main(store.createRun(b1.storyRunId(),"b0"),"create_b"))
            .thenCompose(v -> main(store.createPlayer(a1,"a-p1-0"),"create_a1"))
            .thenCompose(v -> main(store.createPlayer(a2,"a-p2-0"),"create_a2"))
            .thenCompose(v -> main(store.createPlayer(b1,"b-p1-0"),"create_b1"))
            .thenCompose(v -> main(store.saveShared(a1,0,"shared-a"),"save_a"))
            .thenCompose(v -> main(store.saveShared(b1,0,"shared-b"),"save_b"))
            .thenCompose(v -> main(store.savePersonal(a1,0,"personal-a1"),"save_a1"))
            .thenCompose(v -> main(store.savePersonal(a2,0,"personal-a2"),"save_a2"))
            .thenCompose(v -> main(store.savePersonal(b1,0,"personal-b1"),"save_b1"))
            .thenCompose(v -> verifyStates()).thenRun(this::mapping);
    }
    private CompletionStage<Void> resume() {
        return main(store.migrate(),"migrate_existing").thenCompose(v -> verifyStates())
            .thenCompose(v -> expect(store.readShared(access.forVerifiedServerLink(new UUID(42,99),a1.playerId())),"MISSING_STATE","missing_run_not_created"))
            .thenCompose(v -> expect(store.readPersonal(new ServerProgressAccess().forVerifiedServerLink(a1.storyRunId(),a1.playerId())),"ACCESS_DENIED","foreign_context_rejected"))
            .thenCompose(v -> {
                var stale=main(store.readShared(a1),"stale_result");
                generation++; // 메인 스레드의 시험 세대 변경. 실제 로그인/가입 기능이 아닙니다.
                return expect(stale,"STALE_CONTEXT","old_generation_not_applied");
            }).thenRun(this::mapping);
    }
    private CompletionStage<Void> verifyStates() {
        return state(store.readShared(a1),"shared-a",1,"shared_a")
            .thenCompose(v -> state(store.readShared(a2),"shared-a",1,"same_run_shared_a2"))
            .thenCompose(v -> state(store.readShared(b1),"shared-b",1,"shared_b"))
            .thenCompose(v -> state(store.readPersonal(a1),"personal-a1",1,"personal_a1"))
            .thenCompose(v -> state(store.readPersonal(a2),"personal-a2",1,"personal_a2"))
            .thenCompose(v -> state(store.readPersonal(b1),"personal-b1",1,"personal_b1"));
    }
    private CompletionStage<Void> revisions() {
        var outcomes=new ConcurrentLinkedQueue<String>();
        var one=store.saveShared(a1,1,"winner").handle((v,e) -> { outcomes.add(e==null ? "OK" : code(e)); return null; }).toCompletableFuture();
        var two=store.saveShared(a2,1,"winner").handle((v,e) -> { outcomes.add(e==null ? "OK" : code(e)); return null; }).toCompletableFuture();
        return main(CompletableFuture.allOf(one,two),"concurrent_revision").thenRun(() -> {
            check(Collections.frequency(new ArrayList<>(outcomes),"OK")==1
                && Collections.frequency(new ArrayList<>(outcomes),"REVISION_CONFLICT_OR_MISSING")==1,"same_revision_single_winner_duplicate_rejected");
        }).thenCompose(v -> expect(store.saveShared(a1,0,"old"),"REVISION_CONFLICT_OR_MISSING","older_revision_rejected"))
          .thenCompose(v -> state(store.readShared(a1),"winner",2,"revision_result"))
          .thenCompose(v -> state(store.readShared(b1),"shared-b",1,"other_run_unchanged"));
    }
    private CompletionStage<Void> writeFailure() {
        int before=errorCount.get();
        return expect(store.savePersonal(a1,1,"not-saved"),"23514","write_failure_not_success")
            .thenCompose(v -> state(store.readPersonal(a1),"personal-a1",1,"write_failure_preserves_state"))
            .thenRun(() -> check(errorCount.get()>before,"write_failure_recorded"));
    }
    private CompletionStage<Void> schemaFailure(String expected) {
        return expect(store.migrate(),expected,"schema_refused_"+expected)
            .thenCompose(v -> expect(store.readShared(a1),"SCHEMA_NOT_READY","bad_schema_not_ready"));
    }
    private CompletionStage<Void> queue() {
        long before=ticks;
        var outcomes=new ConcurrentLinkedQueue<String>();
        List<CompletableFuture<?>> requests=new ArrayList<>();
        for (int i=0;i<40;i++) requests.add(store.readShared(a1).handle((v,e) -> { outcomes.add(e==null ? "OK" : code(e)); return null; }).toCompletableFuture());
        logger.info("HEARTH_S45_QUEUE_SUBMITTED");
        return main(CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)),"blocked_queue")
            .thenRun(() -> {
                check(outcomes.size()==40 && outcomes.stream().allMatch(s -> s.equals("OK") || s.equals("QUEUE_FULL"))
                    && outcomes.stream().filter("QUEUE_FULL"::equals).count()>=22,"queue_bounded_rejection");
                check(ticks-before>=2,"main_thread_ticks_while_DB_blocked");
            });
    }
    private CompletionStage<Void> drain() {
        var reads=CompletableFuture.allOf(store.readShared(a1).toCompletableFuture(),store.readShared(b1).toCompletableFuture());
        var closing=store.closeAsync();
        logger.info("HEARTH_S45_DRAIN_SUBMITTED");
        return expect(store.readShared(a1),"CLOSED","new_request_during_close_rejected")
            .thenCompose(v -> main(reads,"pending_reads_drained"))
            .thenCompose(v -> main(closing,"pool_closed"));
    }
    private CompletionStage<Void> disconnected() {
        if (probe==null) throw new IllegalStateException("Connected probe required");
        long before=ticks; int errorsBefore=errorCount.get();
        return main(probe.readShared(b1).handle((v,e) -> {
            if (e==null) throw new CompletionException(new IllegalStateException("DB failure reported success"));
            String code=code(e);
            if (!(code.startsWith("08") || code.equals("57P01"))) throw new CompletionException(e);
            return code;
        }),"connection_interrupted").thenCompose(v -> {
            check(errorCount.get()>errorsBefore && ticks>before,"connection_failure_recorded_without_main_wait");
            return main(probe.closeAsync(),"probe_closed");
        });
    }
    private CompletionStage<Void> state(CompletionStage<ProgressStore.State> work,String value,long revision,String label) {
        return main(work,"read_"+label).thenAccept(s -> {
            check(s.value().equals(value) && s.revision()==revision,label+"_value_revision");
            logger.info("HEARTH_S45_STATE "+label+" value="+s.value()+" revision="+s.revision());
        });
    }
    private CompletionStage<Void> expect(CompletionStage<?> work,String expected,String label) {
        return main(work.handle((v,e) -> {
            if (e==null || !code(e).equals(expected)) throw new CompletionException(new IllegalStateException("Expected "+expected+" got "+code(e)));
            return null;
        }),label).thenRun(() -> check(true,label));
    }
    private <T> CompletableFuture<T> main(CompletionStage<T> work,String label) {
        long epoch=generation, started=System.nanoTime();
        return work.handleAsync((value,error) -> {
            if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Callback is not on main thread");
            if (closed || epoch!=generation) throw new CompletionException(new StorageFailure("result","STALE_CONTEXT"));
            logger.info("HEARTH_S45_TIMING "+label+" ms="+(System.nanoTime()-started)/1_000_000+" main=true");
            if (error!=null) throw new CompletionException(error);
            return value;
        },deliveries::add).toCompletableFuture(); // DB worker는 Bukkit API 없이 큐에만 전달합니다.
    }
    private void mapping() {
        try {
            Properties p=new Properties();
            try (var in=Files.newInputStream(plugin.getDataFolder().toPath().resolve("restart-mapping.properties"))) { p.load(in); }
            check(a1.storyRunId().toString().equals(p.getProperty("restart_a.run"))
                && b1.storyRunId().toString().equals(p.getProperty("restart_b.run"))
                && !p.getProperty("restart_a.uuid").equals(p.getProperty("restart_b.uuid")),"logical_run_world_mapping");
        } catch (Exception e) { throw new CompletionException(e); }
    }
    private void check(boolean good,String label) {
        if (!good) throw new IllegalStateException(label);
        logger.info("HEARTH_S45_CHECK PASS "+label);
    }
    private static String code(Throwable error) {
        while (error instanceof CompletionException || error instanceof ExecutionException) error=error.getCause();
        return error instanceof StorageFailure f ? f.code() : error==null ? "SUCCESS" : error.getClass().getSimpleName();
    }
    @Override public void close() {
        closed=true; generation++; pump.cancel(); deliveries.clear();
        store.closeAsync().whenComplete((v,e) -> logger.info("HEARTH_S45_DISABLE_CLOSE "+(e==null ? "PASS" : "FAIL")));
        if (probe!=null) probe.closeAsync();
    }
}
