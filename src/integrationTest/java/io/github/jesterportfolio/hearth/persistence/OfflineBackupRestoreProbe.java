package io.github.jesterportfolio.hearth.persistence;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import io.github.jesterportfolio.hearth.verification.IsolatedPaths;
import java.nio.file.*;
import java.util.*;

/** 새 격리 복사본만 기동합니다. 원본 클러스터·Paper·운영 연결은 열지 않습니다. */
public final class OfflineBackupRestoreProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("One owned fixture root required");
        Path root=IsolatedPaths.require(Path.of(args[0]).toAbsolutePath(),"hearth-s4-11.");
        if(!Files.readString(root.resolve(".hearth-lifecycle-isolated")).equals("S4-11 isolated test only\n"))throw new IllegalStateException("Fixture marker");
        Path restored=root.resolve("restored"),data=restored.resolve("postgres");
        if(!restored.toRealPath().equals(restored)||!data.toRealPath().equals(data)||Files.exists(data.resolve("postmaster.pid"))||!Files.exists(restored.resolve("restore-receipt.json")))throw new IllegalStateException("Fresh restored cluster required");
        var expected=new Properties();try(var stream=Files.newInputStream(root.resolve("backup-db-snapshot.properties"))){expected.load(stream);}
        Path sockets=Files.createDirectory(root.resolve("restore-socket"));
        try(var pg=EmbeddedPostgres.builder().setDataDirectory(data).setCleanDataDirectory(false)
                .setOverrideWorkingDirectory(root.resolve("binaries").toFile()).setRegisterShutdownHook(true)
                .setServerConfig("listen_addresses","127.0.0.1").setServerConfig("unix_socket_directories",sockets.toString())
                .setServerConfig("shared_buffers","16MB").setServerConfig("work_mem","1MB")
                .setServerConfig("max_connections","12").setServerConfig("max_parallel_workers","0")
                .setServerConfig("synchronous_commit","on").setServerConfig("fsync","on")
                .setConnectConfig("connectTimeout","2").setConnectConfig("socketTimeout","5").start()){
            Files.writeString(root.resolve("restore-db-port.txt"),Integer.toString(pg.getPort()),StandardOpenOption.CREATE_NEW);
            try(var connection=pg.getDatabase("postgres","hearth_s4_11").getConnection()){
                try(var statement=connection.createStatement();var rows=statement.executeQuery("SHOW data_directory")){
                    if(!rows.next()||!Path.of(rows.getString(1)).toRealPath().equals(data))throw new IllegalStateException("Wrong restore data root");
                }
                var actual=BackupDatabaseSnapshot.capture(connection);
                if(!actual.equals(expected))throw new IllegalStateException("Restored table content differs");
                if(actual.size()<3)throw new IllegalStateException("Missing persisted game tables");
                System.out.println("HEARTH_BACKUP_RESTORE_DB PASS tables="+actual.size());
            }
        }
        if(Files.exists(data.resolve("postmaster.pid")))throw new IllegalStateException("Restored PG not closed");
        System.out.println("HEARTH_BACKUP_RESTORE_CLOSED PASS");
    }
}
