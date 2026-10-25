package io.github.jesterportfolio.hearth.persistence;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.ServerSocket;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 신규 PostgreSQL 전용 시험. 운영 URL/인증 환경 변수를 읽거나 사용하지 않습니다. */
final class PostgresIsolationTest {
    private final List<String> checks = new ArrayList<>();
    private void pass(String name) { checks.add(name); System.out.println("HEARTH_S44_CHECK PASS " + name); }
    private static <T> T await(CompletionStage<T> future) throws Exception { return future.toCompletableFuture().get(10,TimeUnit.SECONDS); }
    private static StorageFailure failed(CompletionStage<?> future) {
        var e = assertThrows(ExecutionException.class,() -> future.toCompletableFuture().get(10,TimeUnit.SECONDS));
        return assertInstanceOf(StorageFailure.class,e.getCause());
    }

    @Test void realPostgresIsolationRevisionAndReconnect() throws Exception {
        Path root = Path.of(Objects.requireNonNull(System.getProperty("hearth.s44.workspace"))).toRealPath();
        assertEquals(Path.of("/tmp"),root.getParent());
        assertTrue(root.getFileName().toString().startsWith("hearth-s4-4."));
        assertNotEquals("root",System.getProperty("user.name"));
        assertEquals(System.getProperty("user.name"),Files.getOwner(root).getName());
        assertEquals("S4-4 isolated test only",Files.readString(root.resolve(".hearth-s44-isolated")).trim());
        Path data = root.resolve("data");
        assertFalse(Files.exists(data),"기존 DB/증거 데이터 재사용 금지");
        Path sockets = Files.createDirectory(root.resolve("socket"));
        var errors = new CopyOnWriteArrayList<PostgresProgressStore.Failure>();
        var access = new ServerProgressAccess();
        UUID runA=UUID.randomUUID(), runB=UUID.randomUUID(), p1=UUID.randomUUID(), p2=UUID.randomUUID();
        var a1=access.forVerifiedServerLink(runA,p1); var a2=access.forVerifiedServerLink(runA,p2);
        var b1=access.forVerifiedServerLink(runB,p1);
        PostgresProgressStore store = null, competing = null;
        long postmaster = -1; int port = -1;
        EmbeddedPostgres pg = null;
        try {
            pg = EmbeddedPostgres.builder().setDataDirectory(data).setCleanDataDirectory(false)
                    .setOverrideWorkingDirectory(root.resolve("binaries").toFile()).setRegisterShutdownHook(true)
                    .setServerConfig("listen_addresses","127.0.0.1")
                    .setServerConfig("unix_socket_directories",sockets.toString())
                    .setServerConfig("shared_buffers","16MB").setServerConfig("work_mem","1MB")
                    .setServerConfig("max_connections","12").setServerConfig("max_parallel_workers","0")
                    .setServerConfig("synchronous_commit","on").setServerConfig("fsync","on")
                    .setLocaleConfig("locale","C").setConnectConfig("connectTimeout","2")
                    .setConnectConfig("socketTimeout","5").setConnectConfig("prepareThreshold","0").start();
            port = pg.getPort();
            postmaster = Long.parseLong(Files.readAllLines(data.resolve("postmaster.pid")).getFirst());
            DataSource admin = pg.getPostgresDatabase();
            try (var c = admin.getConnection(); var st = c.createStatement()) {
                try (var rs = st.executeQuery("SHOW data_directory")) { assertTrue(rs.next()); assertEquals(data.toRealPath(),Path.of(rs.getString(1)).toRealPath()); }
                try (var rs = st.executeQuery("SHOW listen_addresses")) { assertTrue(rs.next()); assertEquals("127.0.0.1",rs.getString(1)); }
                st.execute("CREATE DATABASE hearth_s4_4");
            }
            DataSource ds = pg.getDatabase("postgres","hearth_s4_4");
            store = new PostgresProgressStore(ds,access,errors::add);
            await(store.migrate()); await(store.migrate()); pass("migration_and_idempotent_version");
            await(store.createRun(runA,"shared-a")); await(store.createRun(runB,"shared-b"));
            await(store.createPlayer(a1,"a-p1")); await(store.createPlayer(a2,"a-p2")); await(store.createPlayer(b1,"b-p1"));
            await(store.saveShared(a1,0,"shared-a-new"));
            assertEquals(new ProgressStore.State("shared-b",0),await(store.readShared(b1))); pass("shared_A_does_not_change_B");
            assertEquals(await(store.readShared(a1)),await(store.readShared(a2))); pass("same_run_shared_state_for_P1_P2");
            await(store.savePersonal(a1,0,"a-p1-new"));
            assertEquals(new ProgressStore.State("b-p1",0),await(store.readPersonal(b1))); pass("P1_A_does_not_change_P1_B");
            assertEquals(new ProgressStore.State("a-p2",0),await(store.readPersonal(a2))); pass("P1_A_does_not_change_P2_A");
            assertEquals("23505",failed(store.createRun(runA,"duplicate")).code());
            assertEquals("23505",failed(store.createPlayer(a1,"duplicate")).code()); pass("duplicate_keys_rejected");
            var unknown=access.forVerifiedServerLink(UUID.randomUUID(),p1);
            assertEquals("23503",failed(store.createPlayer(unknown,"orphan")).code());
            assertEquals("MISSING_STATE",failed(store.readShared(unknown)).code());
            assertEquals("REVISION_CONFLICT_OR_MISSING",failed(store.saveShared(unknown,0,"bad")).code());
            assertEquals("ACCESS_DENIED",failed(store.readPersonal(new ServerProgressAccess().forVerifiedServerLink(runA,p1))).code());
            pass("unknown_run_and_foreign_context_rejected");
            assertEquals("REVISION_CONFLICT_OR_MISSING",failed(store.saveShared(a1,0,"stale")).code());
            assertEquals("REVISION_CONFLICT_OR_MISSING",failed(store.savePersonal(a1,0,"stale")).code());
            assertEquals(new ProgressStore.State("a-p1-new",1),await(store.readPersonal(a1))); pass("stale_revision_rejected");

            competing = new PostgresProgressStore(ds,access,errors::add);
            await(competing.migrate());
            var first=store.saveShared(a1,1,"race-one").toCompletableFuture();
            var second=competing.saveShared(a2,1,"race-two").toCompletableFuture();
            int wins=0;
            for (var attempt : List.of(first,second)) {
                try { attempt.get(10,TimeUnit.SECONDS); wins++; }
                catch (ExecutionException e) { assertEquals("REVISION_CONFLICT_OR_MISSING",((StorageFailure)e.getCause()).code()); }
            }
            assertEquals(1,wins); assertEquals(2,await(store.readShared(a1)).revision()); pass("concurrent_CAS_single_winner");
            await(competing.closeAsync()); competing = null;

            try (var c = ds.getConnection(); var st = c.createStatement()) {
                st.execute("CREATE FUNCTION hearth_progress.reject_test_write() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected' USING ERRCODE='23514'; END $$");
                st.execute("CREATE TRIGGER reject_test BEFORE UPDATE ON hearth_progress.player_progress FOR EACH ROW EXECUTE FUNCTION hearth_progress.reject_test_write()");
            }
            assertEquals("23514",failed(store.savePersonal(a1,1,"must-not-save")).code());
            assertEquals(new ProgressStore.State("a-p1-new",1),await(store.readPersonal(a1)));
            assertTrue(errors.stream().anyMatch(e -> e.operation().equals("save_personal") && e.code().equals("23514"))); pass("SQL_write_failure_logged_not_success");
            try (var c = ds.getConnection(); var st = c.createStatement()) {
                st.execute("DROP TRIGGER reject_test ON hearth_progress.player_progress");
                st.execute("ALTER TABLE hearth_progress.player_progress RENAME TO temporarily_unavailable");
            }
            assertEquals("42P01",failed(store.readPersonal(a1)).code()); pass("SQL_read_failure_logged_not_success");
            String originalChecksum;
            try (var c = ds.getConnection(); var st = c.createStatement()) {
                try (var rs = st.executeQuery("SELECT checksum FROM hearth_progress.schema_version WHERE version=1")) {
                    assertTrue(rs.next()); originalChecksum=rs.getString(1);
                }
                st.execute("ALTER TABLE hearth_progress.temporarily_unavailable RENAME TO player_progress");
                st.execute("UPDATE hearth_progress.schema_version SET checksum='changed-for-test'");
            }
            assertEquals("SCHEMA_VERSION_MISMATCH",failed(store.migrate()).code()); pass("changed_migration_refused_without_reset");
            assertEquals("SCHEMA_NOT_READY",failed(store.readShared(a1)).code());
            try (var c = ds.getConnection(); var st = c.prepareStatement("UPDATE hearth_progress.schema_version SET checksum=? WHERE version=1")) {
                st.setString(1,originalChecksum); st.executeUpdate();
            }
            await(store.migrate());
            var savedShared=await(store.readShared(a1));
            await(store.closeAsync()); store=null;
            store = new PostgresProgressStore(ds,access,errors::add);
            await(store.migrate());
            assertEquals(savedShared,await(store.readShared(a2)));
            assertEquals(new ProgressStore.State("a-p1-new",1),await(store.readPersonal(a1)));
            assertEquals(new ProgressStore.State("b-p1",0),await(store.readPersonal(b1))); pass("new_pool_connections_restore_committed_state");
            await(store.closeAsync()); store=null;
            String version;
            try (var c = ds.getConnection(); var st = c.createStatement(); var rs = st.executeQuery("SHOW server_version")) { assertTrue(rs.next()); version=rs.getString(1); }
            // 새 시험 DB를 정상 종료한 뒤 실제 연결 실패도 성공으로 처리하지 않는지 검사합니다.
            pg.close(); pg=null;
            store = new PostgresProgressStore(ds,access,errors::add);
            StorageFailure unavailable = failed(store.migrate());
            assertTrue(unavailable.code().startsWith("08") || unavailable.code().equals("SQL_FAILURE"));
            assertTrue(errors.stream().anyMatch(e -> e.operation().equals("migrate") && e.code().equals(unavailable.code())));
            await(store.closeAsync()); store=null; pass("stopped_database_connection_failure_not_success");
            Files.writeString(root.resolve("db-result.txt"),"PASS\nPostgreSQL="+version+"\nChecks="+String.join(",",checks)+"\n",
                    StandardOpenOption.CREATE_NEW);
        } finally {
            // 풀 정리 오류가 있어도 새로 만든 시험 DB의 종료를 반드시 시도합니다.
            try {
                if (competing != null) await(competing.closeAsync());
            } finally {
                try {
                    if (store != null) await(store.closeAsync());
                } finally {
                    if (pg != null) pg.close();
                }
            }
            if (postmaster > 0) {
                long pid=postmaster;
                long until=System.nanoTime()+Duration.ofSeconds(8).toNanos();
                while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime()<until) Thread.sleep(50);
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"시험 postmaster 잔존");
            }
            if (port > 0) try (var probe = new ServerSocket(port,1,java.net.InetAddress.getByName("127.0.0.1"))) {
                Files.writeString(root.resolve("db-stopped.txt"),"postmaster exited; loopback port released\n",StandardOpenOption.CREATE_NEW);
            }
        }
    }
}
