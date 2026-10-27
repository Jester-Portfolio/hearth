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
        boolean lifecycle=args.length==2 && args[1].equals("--lifecycle");
        boolean inventory=lifecycle || args.length==2 && args[1].equals("--inventory");
        String prefix=lifecycle ? "hearth-s4-11." : inventory ? "hearth-s4-6." : "hearth-s4-5.";
        String database=lifecycle ? "hearth_s4_11" : inventory ? "hearth_s4_6" : "hearth_s4_5";
        String table=inventory ? "story_inventory" : "player_progress";
        byte[] originalInventory=null;
        if (System.getProperty("user.name").equals("root") || !root.getParent().equals(Path.of("/tmp"))
                || !root.getFileName().toString().startsWith(prefix)
                || !Files.getOwner(root).getName().equals(System.getProperty("user.name"))
                || !Files.readString(root.resolve(lifecycle ? ".hearth-lifecycle-isolated" : inventory ? ".hearth-inventory-isolated" : ".hearth-s45-isolated")).trim().equals(lifecycle ? "S4-11 isolated test only" : inventory ? "S4-6 isolated test only" : "S4-5 isolated test only")
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
                st.execute("CREATE DATABASE "+database);
            }
            var ds=pg.getDatabase("postgres",database);
            admin=ds.getConnection();
            String marker=UUID.randomUUID().toString();
            Files.writeString(root.resolve(".hearth-s45-db"),marker,StandardOpenOption.CREATE_NEW);
            Files.writeString(root.resolve("db-endpoint.properties"),"port="+pg.getPort()+"\ndatabase="+database+"\nmarker="+marker+"\n",StandardOpenOption.CREATE_NEW);
            System.out.println("HEARTH_S45_DB READY port="+pg.getPort()); System.out.flush();
            try (var reader=new BufferedReader(new InputStreamReader(System.in))) {
                for (String command;(command=reader.readLine())!=null;) {
                    if (command.equals("stop-db")) {
                        if (lock!=null) { lock.rollback(); lock.close(); lock=null; }
                        admin.close(); admin=null;
                        pg.close(); pg=null;
                    } else if (command.equals("finish")) { break; }
                    else if ((command.equals("lock") || command.equals("lock-personal") || command.equals("lock-quest") || command.equals("lock-story"))) {
                        if (lock!=null) throw new IllegalStateException("Already locked");
                        lock=ds.getConnection(); lock.setAutoCommit(false);
                        try (var st=lock.createStatement()) { st.setQueryTimeout(3); st.execute("LOCK TABLE hearth_progress."+(command.equals("lock-story") ? "community_story" : command.equals("lock-quest") ? "personal_quest" : command.equals("lock-personal") ? "player_progress" : inventory ? "story_inventory" : "story_run")+" IN ACCESS EXCLUSIVE MODE"); }
                    } else if (command.equals("unlock")) {
                        Objects.requireNonNull(lock).rollback(); lock.close(); lock=null;
                    } else {
                        try (var st=Objects.requireNonNull(admin).createStatement()) {
                            st.setQueryTimeout(3);
                            switch (command) {
                                case "quest-legacy", "explore-legacy", "story-legacy" -> {
                                    // 새 격리 DB에만 V003 상태를 준비해 실제 순차 업그레이드를 시험합니다.
                                    admin.setAutoCommit(false);
                                    try{
                                        st.execute("CREATE SCHEMA hearth_progress");st.execute("CREATE TABLE hearth_progress.schema_version(version integer PRIMARY KEY,checksum text NOT NULL)");
                                        for(int version=1;version<=(command.equals("story-legacy")?5:command.equals("explore-legacy")?4:3);version++){
                                            String name=List.of("V001__progress.sql","V002__story_inventory.sql","V003__personal_quest.sql","V004__quest_steps_and_journal.sql","V005__exploration_diary_and_markers.sql").get(version-1);
                                            byte[] bytes=Files.readAllBytes(root.resolve("legacy-migrations").resolve(name));
                                            for(String sql:new String(bytes,java.nio.charset.StandardCharsets.UTF_8).split(";"))if(!sql.isBlank())st.execute(sql);
                                            try(var insert=admin.prepareStatement("INSERT INTO hearth_progress.schema_version VALUES (?,?)")){insert.setInt(1,version);insert.setString(2,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));insert.executeUpdate();}
                                        }
                                        String run=new UUID(42,99).toString(),player=new UUID(49,99).toString();
                                        st.execute("INSERT INTO hearth_progress.story_run(story_run_id,state_value) VALUES ('"+run+"','legacy')");
                                        st.execute("INSERT INTO hearth_progress.player_progress(story_run_id,player_uuid,state_value) VALUES ('"+run+"','"+player+"','legacy')");
                                        st.execute("INSERT INTO hearth_progress.personal_quest(story_run_id,player_uuid,quest_id,phase,revision) VALUES ('"+run+"','"+player+"','survey_marker','COMPLETED',2)");
                                        if(command.equals("explore-legacy")||command.equals("story-legacy")){
                                            st.execute("INSERT INTO hearth_progress.quest_preferences(story_run_id,player_uuid,hud_enabled) VALUES ('"+run+"','"+player+"',false)");
                                            st.execute("INSERT INTO hearth_progress.quest_notification(story_run_id,player_uuid,quest_id,event_revision,kind) VALUES ('"+run+"','"+player+"','survey_marker',2,'COMPLETED')");
                                        }
                                        if(command.equals("story-legacy")){
                                            st.execute("INSERT INTO hearth_progress.community_story(story_run_id,stage_id,stage_title,exploration_open,revision) VALUES ('"+run+"','public_test_stage','기존 공개 단계',false,3)");
                                            st.execute("INSERT INTO hearth_progress.exploration_place(story_run_id,player_uuid,place_id,visited_at,discovered_at,hub_unlocked,revision) VALUES ('"+run+"','"+player+"','public_village',clock_timestamp(),clock_timestamp(),true,1)");
                                        }
                                        admin.commit();
                                    }catch(Exception e){admin.rollback();throw e;}finally{admin.setAutoCommit(true);}
                                }
                                case "notice-fail-on", "preferences-fail-on", "diary-event-fail-on", "story-fail-on" -> {
                                    st.execute("CREATE OR REPLACE FUNCTION hearth_progress.reject_batch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated batch failure' USING ERRCODE='23514'; END $$");
                                    String target=command.equals("story-fail-on")?"community_story_request":command.equals("diary-event-fail-on")?"diary_personal_event":command.equals("notice-fail-on")?"quest_notification":"quest_preferences";
                                    st.execute("CREATE TRIGGER reject_batch BEFORE INSERT OR UPDATE ON hearth_progress."+target+" FOR EACH ROW EXECUTE FUNCTION hearth_progress.reject_batch()");
                                }
                                case "notice-fail-off", "preferences-fail-off", "diary-event-fail-off", "story-fail-off" -> st.execute("DROP TRIGGER reject_batch ON hearth_progress."+(command.equals("story-fail-off")?"community_story_request":command.equals("diary-event-fail-off")?"diary_personal_event":command.equals("notice-fail-off")?"quest_notification":"quest_preferences"));
                                case "quest-fail-on" -> {
                                    st.execute("CREATE OR REPLACE FUNCTION hearth_progress.reject_quest() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated quest write failure' USING ERRCODE='23514'; END $$");
                                    st.execute("CREATE TRIGGER reject_quest BEFORE INSERT OR UPDATE ON hearth_progress.personal_quest FOR EACH ROW EXECUTE FUNCTION hearth_progress.reject_quest()");
                                }
                                case "quest-fail-off" -> st.execute("DROP TRIGGER reject_quest ON hearth_progress.personal_quest");
                                case "write-fail-on" -> {
                                    st.execute("CREATE OR REPLACE FUNCTION hearth_progress.reject_s45() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test write failure' USING ERRCODE='23514'; END $$");
                                    st.execute("CREATE TRIGGER reject_s45 BEFORE UPDATE ON hearth_progress."+table+" FOR EACH ROW EXECUTE FUNCTION hearth_progress.reject_s45()");
                                }
                                case "write-fail-off" -> st.execute("DROP TRIGGER reject_s45 ON hearth_progress."+table);
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
                                case "inventory-corrupt", "inventory-repair" -> {
                                    if (!inventory) throw new IllegalArgumentException("Inventory fixture required");
                                    var run=new UUID(42,11); var player=new UUID(46,1);
                                    if (command.equals("inventory-corrupt")) {
                                        try (var read=admin.prepareStatement("SELECT payload FROM hearth_progress.story_inventory WHERE story_run_id=? AND player_uuid=?")) {
                                            read.setObject(1,run); read.setObject(2,player);
                                            try (var row=read.executeQuery()) { if (!row.next()) throw new IllegalStateException("Missing test inventory"); originalInventory=row.getBytes(1); }
                                        }
                                    }
                                    try (var write=admin.prepareStatement("UPDATE hearth_progress.story_inventory SET payload=? WHERE story_run_id=? AND player_uuid=?")) {
                                        write.setBytes(1,command.equals("inventory-corrupt") ? new byte[208] : Objects.requireNonNull(originalInventory));
                                        write.setObject(2,run); write.setObject(3,player); write.executeUpdate();
                                    }
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
