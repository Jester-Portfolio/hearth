package io.github.jesterportfolio.hearth.persistence;

import java.io.PrintWriter;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PostgresProgressStoreTest {
    private static ServerProgressAccess.Context context(ServerProgressAccess access) {
        return access.forVerifiedServerLink(UUID.randomUUID(),UUID.randomUUID());
    }

    @Test void foreignContextAndClosedStoreNeverConnect() throws Exception {
        var calls = new AtomicInteger();
        var failures = new CopyOnWriteArrayList<PostgresProgressStore.Failure>();
        var source = new BrokenSource(() -> calls.incrementAndGet());
        var access = new ServerProgressAccess();
        var store = new PostgresProgressStore(source,access,failures::add);
        assertEquals(0,calls.get(),"생성자는 연결하지 않아야 합니다");
        try {
            StorageFailure failure = failed(store.readShared(context(new ServerProgressAccess())));
            assertEquals("ACCESS_DENIED",failure.code());
            assertEquals(0,calls.get());
        } finally { store.closeAsync().toCompletableFuture().get(5,TimeUnit.SECONDS); }
        assertEquals("CLOSED",failed(store.readPersonal(context(access))).code());
        assertEquals(0,calls.get());
        assertEquals(2,failures.size());
    }

    @Test void blockedConnectionDoesNotBlockCallerAndFailureIsNotSuccess() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        String caller = Thread.currentThread().getName();
        var threads = new CopyOnWriteArrayList<String>();
        var errors = new CopyOnWriteArrayList<PostgresProgressStore.Failure>();
        var access = new ServerProgressAccess();
        var source = new BrokenSource(() -> {
            threads.add(Thread.currentThread().getName());
            entered.countDown();
            try { if (!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("시험 연결 대기 한도 초과"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        var store = new PostgresProgressStore(source,access,errors::add);
        try {
            var future = store.migrate();
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            assertFalse(future.toCompletableFuture().isDone(),"호출자는 연결 대기 중에도 제어를 돌려받아야 합니다");
            release.countDown();
            StorageFailure failure = failed(future);
            assertFalse(failure.getMessage().contains("private-marker"));
            assertNull(failure.getCause(),"원문 SQL/접속 정보는 공개 오류로 전달하지 않습니다");
            assertFalse(threads.isEmpty());
            assertTrue(threads.stream().noneMatch(caller::equals));
            assertEquals(1,errors.size());
        } finally {
            release.countDown();
            store.closeAsync().toCompletableFuture().get(10,TimeUnit.SECONDS);
        }
    }

    @Test void writesBeforeMigrationNeverReachDatabase() throws Exception {
        var calls = new AtomicInteger();
        var access = new ServerProgressAccess();
        var ctx = context(access);
        var store = new PostgresProgressStore(new BrokenSource(calls::incrementAndGet),access,f -> {});
        try {
            assertEquals("SCHEMA_NOT_READY",failed(store.createRun(ctx.storyRunId(),"initial")).code());
            assertEquals("SCHEMA_NOT_READY",failed(store.createPlayer(ctx,"initial")).code());
            assertEquals("SCHEMA_NOT_READY",failed(store.saveShared(ctx,0,"unready")).code());
            assertEquals("SCHEMA_NOT_READY",failed(store.savePersonal(ctx,0,"unready")).code());
            assertEquals(0,calls.get(),"미준비 상태의 쓰기가 JDBC에 도달하면 안 됩니다");
        } finally { store.closeAsync().toCompletableFuture().get(5,TimeUnit.SECONDS); }
    }

    @Test void invalidPayloadFailsBeforeConnection() throws Exception {
        var calls = new AtomicInteger();
        var access = new ServerProgressAccess();
        var store = new PostgresProgressStore(new BrokenSource(calls::incrementAndGet),access,f -> {});
        try {
            assertEquals("INVALID_STATE",failed(store.savePersonal(context(access),0,"가".repeat(2000))).code());
            assertEquals("INVALID_STATE",failed(store.saveShared(context(access),-1,"value")).code());
            assertEquals(0,calls.get());
        } finally { store.closeAsync().toCompletableFuture().get(5,TimeUnit.SECONDS); }
    }

    private static StorageFailure failed(CompletionStage<?> stage) throws Exception {
        var e = assertThrows(ExecutionException.class,() -> stage.toCompletableFuture().get(8,TimeUnit.SECONDS));
        return assertInstanceOf(StorageFailure.class,e.getCause());
    }
    private record BrokenSource(Runnable attempted) implements DataSource {
        @Override public Connection getConnection() throws SQLException {
            attempted.run(); throw new SQLException("private-marker", "08006");
        }
        @Override public Connection getConnection(String u,String p) throws SQLException { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter p) {}
        @Override public void setLoginTimeout(int s) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getLogger("hearth-test"); }
        @Override public <T> T unwrap(Class<T> t) throws SQLException { throw new SQLException("unsupported"); }
        @Override public boolean isWrapperFor(Class<?> t) { return false; }
    }
}
