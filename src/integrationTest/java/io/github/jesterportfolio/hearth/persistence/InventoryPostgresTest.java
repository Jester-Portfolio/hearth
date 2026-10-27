package io.github.jesterportfolio.hearth.persistence;

import io.github.jesterportfolio.hearth.inventory.InventoryPayload;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 바이트 봉투/SQL의 실DB 검사입니다. 실제 ItemStack NBT 왕복은 Paper에서 별도로 검사합니다. */
final class InventoryPostgresTest {
    private static <T> T await(CompletionStage<T> future) throws Exception { return future.toCompletableFuture().get(10,TimeUnit.SECONDS); }
    private static String failure(CompletionStage<?> future) throws Exception {
        var e=assertThrows(ExecutionException.class,() -> future.toCompletableFuture().get(10,TimeUnit.SECONDS));
        return assertInstanceOf(StorageFailure.class,e.getCause()).code();
    }
    private static InventoryPayload payload(int n) { byte[][] slots=new byte[41][]; slots[0]=new byte[]{(byte)n}; return InventoryPayload.pack(slots); }
    @Test void v001UpgradeIsolationRevisionsFailuresAndReconnect() throws Exception {
        Path root=Path.of(System.getProperty("hearth.inventory.workspace")).toRealPath();
        assertEquals(Path.of("/tmp"),root.getParent()); assertTrue(root.getFileName().toString().startsWith("hearth-s4-6."));
        assertNotEquals("root",System.getProperty("user.name")); assertEquals(System.getProperty("user.name"),Files.getOwner(root).getName());
        assertEquals("S4-6 isolated test only",Files.readString(root.resolve(".hearth-inventory-isolated")).trim());
        assertFalse(Files.exists(root.resolve("data")));
        EmbeddedPostgres pg=null; PostgresProgressStore store=null; int port=-1; long pid=-1;
        try {
            Path socket=Files.createDirectory(root.resolve("socket"));
            pg=EmbeddedPostgres.builder().setDataDirectory(root.resolve("data")).setCleanDataDirectory(false)
                .setOverrideWorkingDirectory(root.resolve("binaries").toFile()).setRegisterShutdownHook(true)
                .setServerConfig("listen_addresses","127.0.0.1").setServerConfig("unix_socket_directories",socket.toString())
                .setServerConfig("shared_buffers","16MB").setServerConfig("work_mem","1MB").setServerConfig("max_connections","12")
                .setServerConfig("max_parallel_workers","0").setServerConfig("synchronous_commit","on").setServerConfig("fsync","on")
                .setLocaleConfig("locale","C").setConnectConfig("connectTimeout","2").setConnectConfig("socketTimeout","5").setConnectConfig("prepareThreshold","0").start();
            port=pg.getPort(); pid=Long.parseLong(Files.readAllLines(root.resolve("data/postmaster.pid")).getFirst());
            var ds=pg.getPostgresDatabase();
            byte[] v1;
            try (var in=getClass().getResourceAsStream("/db/migration/V001__progress.sql")) { v1=Objects.requireNonNull(in).readAllBytes(); }
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v1));
            UUID runA=UUID.randomUUID(), runB=UUID.randomUUID(), p1=UUID.randomUUID(), p2=UUID.randomUUID();
            try (var c=ds.getConnection(); var st=c.createStatement()) {
                st.execute("CREATE SCHEMA hearth_progress"); st.execute("CREATE TABLE hearth_progress.schema_version(version integer PRIMARY KEY,checksum text NOT NULL)");
                for (String sql:new String(v1,java.nio.charset.StandardCharsets.UTF_8).split(";")) if (!sql.isBlank()) st.execute(sql);
                try (var insert=c.prepareStatement("INSERT INTO hearth_progress.schema_version VALUES (1,?)")) { insert.setString(1,hash); insert.executeUpdate(); }
                try (var insert=c.prepareStatement("INSERT INTO hearth_progress.story_run(story_run_id,state_value) VALUES (?,?)")) { insert.setObject(1,runA); insert.setString(2,"v001-preserved"); insert.executeUpdate(); }
            }
            var access=new ServerProgressAccess(); var a1=access.forVerifiedServerLink(runA,p1); var a2=access.forVerifiedServerLink(runA,p2); var b1=access.forVerifiedServerLink(runB,p1);
            store=new PostgresProgressStore(ds,access,f -> {}); await(store.migrate()); await(store.migrate());
            assertEquals("v001-preserved",await(store.readShared(a1)).value());
            try (var c=ds.getConnection(); var st=c.createStatement(); var rows=st.executeQuery("SELECT version,checksum FROM hearth_progress.schema_version ORDER BY version")) {
                assertTrue(rows.next()); assertEquals(1,rows.getInt(1)); assertEquals(hash,rows.getString(2)); assertTrue(rows.next()); assertEquals(2,rows.getInt(1)); assertTrue(rows.next()); assertEquals(3,rows.getInt(1)); assertTrue(rows.next()); assertEquals(4,rows.getInt(1)); assertTrue(rows.next()); assertEquals(5,rows.getInt(1)); assertTrue(rows.next()); assertEquals(6,rows.getInt(1)); assertFalse(rows.next());
            }
            await(store.createRun(runB,"b")); for (var ctx:List.of(a1,a2,b1)) await(store.createPlayer(ctx,"player"));
            await(store.createInventory(a1,payload(1))); await(store.createInventory(a2,payload(2))); await(store.createInventory(b1,payload(3)));
            await(store.saveInventory(a1,0,payload(4)));
            assertEquals(new InventoryStore.Stored(payload(4),1),await(store.readInventory(a1)));
            assertEquals(new InventoryStore.Stored(payload(2),0),await(store.readInventory(a2)));
            assertEquals(new InventoryStore.Stored(payload(3),0),await(store.readInventory(b1)));
            assertEquals("REVISION_CONFLICT_OR_MISSING",failure(store.saveInventory(a1,0,payload(5))));
            assertEquals("23505",failure(store.createInventory(a1,payload(5))));
            assertEquals("23503",failure(store.createInventory(access.forVerifiedServerLink(UUID.randomUUID(),p1),payload(5))));
            assertEquals("ACCESS_DENIED",failure(store.readInventory(new ServerProgressAccess().forVerifiedServerLink(runA,p1))));
            try (var c=ds.getConnection(); var st=c.createStatement()) {
                st.execute("CREATE FUNCTION hearth_progress.reject_inventory() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test' USING ERRCODE='23514'; END $$");
                st.execute("CREATE TRIGGER reject_inventory BEFORE UPDATE ON hearth_progress.story_inventory FOR EACH ROW EXECUTE FUNCTION hearth_progress.reject_inventory()");
            }
            assertEquals("23514",failure(store.saveInventory(a1,1,payload(8))));
            assertEquals(new InventoryStore.Stored(payload(4),1),await(store.readInventory(a1)));
            await(store.closeAsync()); store=new PostgresProgressStore(ds,access,f -> {}); await(store.migrate());
            assertEquals(new InventoryStore.Stored(payload(4),1),await(store.readInventory(a1)));
            Files.writeString(root.resolve("s4-6-db-result.txt"),"PASS\nV001-preserved,V002-upgrade,A-B-P1-P2-isolation,CAS,FK,duplicate,SQL-failure,reconnect\n",StandardOpenOption.CREATE_NEW);
        } finally {
            try { if (store!=null) await(store.closeAsync()); } finally { if (pg!=null) pg.close(); }
            if (pid>0) assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            if (port>0) try (var probe=new java.net.ServerSocket(port,1,java.net.InetAddress.getByName("127.0.0.1"))) {
                Files.writeString(root.resolve("s4-6-db-stopped.txt"),"postmaster exited; port released\n",StandardOpenOption.CREATE_NEW);
            }
        }
    }
}
