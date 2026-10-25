package io.github.jesterportfolio.hearth.persistence;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** S4-5 전용 신규 DB 한 개와 실패 주입. 임의 URL/SQL 입력이나 운영 DB 접근 기능은 없습니다. */
public final class S45DatabaseFixture {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]).toRealPath();
        if (System.getProperty("user.name").equals("root") || !root.getParent().equals(Path.of("/tmp"))
                || !root.getFileName().toString().startsWith("hearth-s4-5.")
                || !Files.getOwner(root).getName().equals(System.getProperty("user.name"))
                || !Files.readString(root.resolve(".hearth-s45-isolated")).trim().equals("S4-5 isolated test only")
                || Files.exists(root.resolve("data"))) throw new IllegalStateException("Fresh owned S4-5 fixture required");
        Path sockets=Files.createDirectory(root.resolve("socket"));
        EmbeddedPostgres pg=null;
        Connection admin=null, lock=null;
        String checksum=null;
        try {
            pg=EmbeddedPostgres.builder().setDataDirectory(root.resolve("data")).setCleanDataDirectory(false)
                .setOverrideWorkingDirectory(root.resolve("binaries").toFile()).setRegisterShutdownHook(true)
                .setServerConfig("listen_addresses","127.0.0.1").setServerConfig("unix_socket_directories",sockets.toString())
                .setServerConfig("shared_buffers","16MB").setServerConfig("work_mem","1MB")
                .setServerConfig("max_connections","12").setServerConfig("max_parallel_workers","0")
                .setServerConfig("synchronous_commit","on").setServerConfig("fsync","on")
                .setLocaleConfig("locale","C").setConnectConfig("connectTimeout","2").setConnectConfig("socketTimeout","5").start();
            try (var c=pg.getPostgresDatabase().getConnection(); var st=c.createStatement()) {
                try (var rs=st.executeQuery("SHOW data_directory")) { rs.next(); if (!Path.of(rs.getString(1)).toRealPath().equals(root.resolve("data"))) throw new IllegalStateException("Wrong DB"); }
                st.execute("CREATE DATABASE hearth_s4_5");
            }
            var ds=pg.getDatabase("postgres","hearth_s4_5");
            admin=ds.getConnection();
            String marker=UUID.randomUUID().toString();
            Files.writeString(root.resolve(".hearth-s45-db"),marker,StandardOpenOption.CREATE_NEW);
            Files.writeString(root.resolve("db-endpoint.properties"),"port="+pg.getPort()+"\ndatabase=hearth_s4_5\nmarker="+marker+"\n",StandardOpenOption.CREATE_NEW);
            System.out.println("HEARTH_S45_DB READY port="+pg.getPort()); System.out.flush();
            try (var reader=new BufferedReader(new InputStreamReader(System.in))) {
                for (String command;(command=reader.readLine())!=null;) {
                    if (command.equals("stop-db")) {
                        if (lock!=null) { lock.rollback(); lock.close(); lock=null; }
                        admin.close(); admin=null;
                        pg.close(); pg=null;
                    } else if (command.equals("finish")) { break; }
                    else if (command.equals("lock")) {
                        if (lock!=null) throw new IllegalStateException("Already locked");
                        lock=ds.getConnection(); lock.setAutoCommit(false);
                        try (var st=lock.createStatement()) { st.setQueryTimeout(3); st.execute("LOCK TABLE hearth_progress.story_run IN ACCESS EXCLUSIVE MODE"); }
                    } else if (command.equals("unlock")) {
                        Objects.requireNonNull(lock).rollback(); lock.close(); lock=null;
                    } else {
                        try (var st=Objects.requireNonNull(admin).createStatement()) {
                            st.setQueryTimeout(3);
                            switch (command) {
                                case "write-fail-on" -> {
                                    st.execute("CREATE FUNCTION hearth_progress.reject_s45() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test write failure' USING ERRCODE='23514'; END $$");
                                    st.execute("CREATE TRIGGER reject_s45 BEFORE UPDATE ON hearth_progress.player_progress FOR EACH ROW EXECUTE FUNCTION hearth_progress.reject_s45()");
                                }
                                case "write-fail-off" -> st.execute("DROP TRIGGER reject_s45 ON hearth_progress.player_progress");
                                case "schema-corrupt" -> {
                                    try (var rs=st.executeQuery("SELECT checksum FROM hearth_progress.schema_version WHERE version=1")) { rs.next(); checksum=rs.getString(1); }
                                    st.executeUpdate("UPDATE hearth_progress.schema_version SET checksum='s45-injected-mismatch' WHERE version=1");
                                }
                                case "schema-restore" -> {
                                    try (var change=admin.prepareStatement("UPDATE hearth_progress.schema_version SET checksum=? WHERE version=1")) { change.setString(1,Objects.requireNonNull(checksum)); change.executeUpdate(); }
                                }
                                case "table-hide" -> st.execute("ALTER TABLE hearth_progress.player_progress RENAME TO s45_held_player_progress");
                                case "table-show" -> {
                                    try (var rs=st.executeQuery("SELECT to_regclass('hearth_progress.player_progress')")) { rs.next(); if (rs.getString(1)!=null) throw new IllegalStateException("Unexpected replacement table"); }
                                    st.execute("ALTER TABLE hearth_progress.s45_held_player_progress RENAME TO player_progress");
                                }
                                default -> throw new IllegalArgumentException("Unknown fixture command");
                            }
                        }
                    }
                    System.out.println("HEARTH_S45_DB OK "+command); System.out.flush();
                }
            }
        } finally {
            try { if (lock!=null) { lock.rollback(); lock.close(); } }
            finally { try { if (admin!=null) admin.close(); } finally { if (pg!=null) pg.close(); } }
            System.out.println("HEARTH_S45_DB CLOSED"); System.out.flush();
        }
    }
}
