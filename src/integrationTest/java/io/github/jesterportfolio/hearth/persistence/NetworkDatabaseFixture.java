package io.github.jesterportfolio.hearth.persistence;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import io.github.jesterportfolio.hearth.verification.IsolatedPaths;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 완료한 비활성 격리 PG의 새 복사본 전용. 운영 URL/임의 SQL 입력이 없습니다. */
public final class NetworkDatabaseFixture {
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException();var root=IsolatedPaths.require(Path.of(args[0]).toAbsolutePath(),"hearth-network-run.");if(!Files.readString(root.resolve(".hearth-network-isolated")).equals("Hearth network isolated v1\n"))throw new IllegalStateException("NETWORK_FIXTURE_ROOT");var data=root.resolve("data");if(!data.toRealPath().equals(data)||Files.exists(data.resolve("postmaster.pid"))||!Files.isRegularFile(data.resolve("PG_VERSION")))throw new IllegalStateException("NETWORK_CLONED_PG_REQUIRED");
        var socket=Files.createDirectory(root.resolve("socket"));try(var pg=EmbeddedPostgres.builder().setDataDirectory(data).setCleanDataDirectory(false).setOverrideWorkingDirectory(root.resolve("binaries").toFile()).setRegisterShutdownHook(true).setServerConfig("listen_addresses","127.0.0.1").setServerConfig("unix_socket_directories",socket.toString()).setServerConfig("shared_buffers","16MB").setServerConfig("work_mem","1MB").setServerConfig("max_connections","12").setServerConfig("max_parallel_workers","0").setServerConfig("fsync","on").setServerConfig("synchronous_commit","on").setConnectConfig("connectTimeout","2").setConnectConfig("socketTimeout","5").start();var db=pg.getDatabase("postgres","hearth_s4_11").getConnection()){
            try(var st=db.createStatement();var rows=st.executeQuery("SHOW data_directory")){if(!rows.next()||!Path.of(rows.getString(1)).toRealPath().equals(data))throw new IllegalStateException("NETWORK_DB_DIRECTORY");}
            var before=BackupDatabaseSnapshot.capture(db);try(var output=Files.newOutputStream(root.resolve("db-before.properties"),StandardOpenOption.CREATE_NEW)){before.store(output,"Synthetic clone before network only; table hashes");}Files.writeString(root.resolve("db-port.txt"),Integer.toString(pg.getPort()),StandardOpenOption.CREATE_NEW);System.out.println("HEARTH_NETWORK_DB_READY");System.out.flush();
            try(var reader=new BufferedReader(new InputStreamReader(System.in))){for(String command;(command=reader.readLine())!=null;){if(command.equals("finish"))break;if(!command.equals("compare"))throw new IllegalArgumentException("NETWORK_FIXTURE_COMMAND");var after=BackupDatabaseSnapshot.capture(db);for(var key:before.stringPropertyNames())if((key.startsWith("hearth_progress.")||key.startsWith("hearth_runtime."))&&!before.getProperty(key).equals(after.getProperty(key)))throw new IllegalStateException("NETWORK_STORY_ORIGINAL_CHANGED");System.out.println("HEARTH_NETWORK_DB_COMPARE_PASS");System.out.flush();}}
        }if(Files.exists(data.resolve("postmaster.pid")))throw new IllegalStateException("NETWORK_PG_NOT_CLOSED");System.out.println("HEARTH_NETWORK_DB_CLOSED");
    }
}
