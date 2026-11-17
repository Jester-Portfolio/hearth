package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.inventory.InventoryPayload;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** 정식 실행의 불변 월드 연결과 인증 계정 연결. 기존 진행/슬롯의 UPDATE 경로는 없습니다. */
public final class StoryRegistry {
    private final PostgresProgressStore database;
    private final ServerProgressAccess access;
    public StoryRegistry(PostgresProgressStore database,ServerProgressAccess access){this.database=Objects.requireNonNull(database);this.access=Objects.requireNonNull(access);}
    public CompletionStage<Void> migrate(){return database.module("story_registry_schema",c->{
        byte[] source;try(var input=StoryRegistry.class.getResourceAsStream("/db/story-runtime-v1.sql")){source=Objects.requireNonNull(input).readAllBytes();}
        var checksum=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source));
        c.setAutoCommit(false);
        try(var st=c.createStatement()){
            st.setQueryTimeout(3);st.execute("SELECT pg_advisory_xact_lock(704404012)");
            st.execute("CREATE SCHEMA IF NOT EXISTS hearth_runtime");
            st.execute("CREATE TABLE IF NOT EXISTS hearth_runtime.schema_version (module text PRIMARY KEY,version integer NOT NULL,checksum text NOT NULL)");
            boolean applied=false;
            try(var rows=st.executeQuery("SELECT version,checksum FROM hearth_runtime.schema_version WHERE module='story'")){if(rows.next()){if(rows.getInt(1)!=1||!checksum.equals(rows.getString(2)))throw new IllegalStateException("STORY_REGISTRY_SCHEMA_CONFLICT");applied=true;}}
            if(!applied){
                try(var rows=st.executeQuery("SELECT to_regclass('hearth_runtime.story_binding'),to_regclass('hearth_runtime.story_member')")){rows.next();if(rows.getObject(1)!=null||rows.getObject(2)!=null)throw new IllegalStateException("STORY_REGISTRY_UNVERSIONED");}
                for(String sql:new String(source,java.nio.charset.StandardCharsets.UTF_8).split(";"))if(!sql.isBlank())st.execute(sql);
                try(var insert=c.prepareStatement("INSERT INTO hearth_runtime.schema_version VALUES ('story',1,?)")){insert.setString(1,checksum);insert.executeUpdate();}
            }
            st.executeQuery("SELECT story_run_id,world_key,world_uuid,template_version,template_hash,content_hash FROM hearth_runtime.story_binding LIMIT 0").close();
            st.executeQuery("SELECT player_uuid,story_run_id FROM hearth_runtime.story_member LIMIT 0").close();
            c.commit();return null;
        }catch(Exception failure){c.rollback();throw failure;}finally{c.setAutoCommit(true);}
    });}
    /** 새 설정으로 기존 저장을 덮어쓰지 않습니다. 경합/commit 불명은 호출자가 HOLD합니다. */
    public CompletionStage<Void> ensure(StoryBinding binding){return database.module("story_registry_bind",c->{
        c.setAutoCommit(false);
        try{
            // 신규 등록을 직렬화하며, 물리 키/UUID 경합도 원본을 보존해 거부합니다.
            try(var lock=c.createStatement()){lock.setQueryTimeout(3);lock.execute("SELECT pg_advisory_xact_lock(704404013)");}
            try(var st=c.prepareStatement("INSERT INTO hearth_progress.story_run(story_run_id,state_value) VALUES (?,?) ON CONFLICT DO NOTHING")){st.setObject(1,binding.run());st.setString(2,"story");st.executeUpdate();}
            try(var st=c.prepareStatement("INSERT INTO hearth_runtime.story_binding VALUES (?,?,?,?,?,?) ON CONFLICT(story_run_id) DO NOTHING")){
                st.setObject(1,binding.run());st.setString(2,binding.worldKey());st.setObject(3,binding.worldId());st.setString(4,binding.templateVersion());st.setString(5,binding.templateHash());st.setString(6,binding.contentHash());st.executeUpdate();
            }
            if(!binding.equals(read(c,binding.run())))throw new IllegalStateException("STORY_BINDING_CONFLICT");
            c.commit();return null;
        }catch(Exception failure){c.rollback();throw failure;}finally{c.setAutoCommit(true);}
    });}
    public CompletionStage<StoryBinding> read(UUID run){return database.module("story_registry_read",c->read(c,run));}
    private static StoryBinding read(Connection c,UUID run)throws SQLException{
        try(var st=c.prepareStatement("SELECT world_key,world_uuid,template_version,template_hash,content_hash FROM hearth_runtime.story_binding WHERE story_run_id=?")){
            st.setObject(1,run);try(var r=st.executeQuery()){if(!r.next())throw new IllegalStateException("STORY_BINDING_MISSING");return new StoryBinding(run,r.getString(1),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getString(5));}
        }
    }
    public CompletionStage<InventoryStore.Stored> admit(ServerProgressAccess.Context context,StoryBinding binding,boolean allowEmptyCreate,InventoryPayload empty){
        access.requireIssued(context);Objects.requireNonNull(binding);Objects.requireNonNull(empty);
        if(!binding.run().equals(context.storyRunId())||Arrays.stream(empty.slots()).anyMatch(Objects::nonNull))return CompletableFuture.failedFuture(new IllegalArgumentException("STORY_ADMISSION_INVALID"));
        return database.module("story_registry_admit",context,c->{
            c.setAutoCommit(false);
            try{
                // 동일 계정의 다른 진행 동시 접속을 고정하며, 다른 계정의 접속은 직렬화하지 않습니다.
                try(var st=c.prepareStatement("SELECT pg_advisory_xact_lock(?)")){st.setLong(1,context.playerId().getMostSignificantBits()^context.playerId().getLeastSignificantBits());st.execute();}
                if(!binding.equals(read(c,binding.run())))throw new IllegalStateException("STORY_BINDING_CONFLICT");
                UUID member=null;
                try(var st=c.prepareStatement("SELECT story_run_id FROM hearth_runtime.story_member WHERE player_uuid=?")){st.setObject(1,context.playerId());try(var r=st.executeQuery()){if(r.next())member=r.getObject(1,UUID.class);}}
                if(member!=null&&!member.equals(binding.run()))throw new IllegalStateException("STORY_MEMBER_CONFLICT");
                if(allowEmptyCreate){
                    try(var st=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM hearth_progress.player_progress WHERE story_run_id=? AND player_uuid=?),EXISTS(SELECT 1 FROM hearth_progress.story_inventory WHERE story_run_id=? AND player_uuid=?)")){
                        owner(st,context);st.setObject(3,context.storyRunId());st.setObject(4,context.playerId());try(var r=st.executeQuery()){r.next();if(r.getBoolean(1)&&!r.getBoolean(2)||member!=null&&!r.getBoolean(1))throw new IllegalStateException("STORY_EXISTING_INVENTORY_MISSING");}
                    }
                    try(var st=c.prepareStatement("INSERT INTO hearth_progress.player_progress(story_run_id,player_uuid,state_value) VALUES (?,?,'story') ON CONFLICT DO NOTHING")){owner(st,context);st.executeUpdate();}
                    try(var st=c.prepareStatement("INSERT INTO hearth_progress.story_inventory(story_run_id,player_uuid,payload) VALUES (?,?,?) ON CONFLICT DO NOTHING")){owner(st,context);st.setBytes(3,empty.bytes());st.executeUpdate();}
                    try(var st=c.prepareStatement("INSERT INTO hearth_runtime.story_member VALUES (?,?) ON CONFLICT DO NOTHING")){st.setObject(1,context.playerId());st.setObject(2,binding.run());st.executeUpdate();}
                }else if(member==null)throw new IllegalStateException("STORY_MEMBER_MISSING");
                InventoryStore.Stored saved;
                try(var st=c.prepareStatement("SELECT payload,revision FROM hearth_progress.story_inventory WHERE story_run_id=? AND player_uuid=?")){owner(st,context);try(var r=st.executeQuery()){if(!r.next())throw new IllegalStateException("STORY_INVENTORY_MISSING");saved=new InventoryStore.Stored(InventoryPayload.parse(r.getBytes(1)),r.getLong(2));}}
                c.commit();return saved;
            }catch(Exception failure){c.rollback();throw failure;}finally{c.setAutoCommit(true);}
        });
    }
    private static void owner(PreparedStatement st,ServerProgressAccess.Context c)throws SQLException{st.setObject(1,c.storyRunId());st.setObject(2,c.playerId());}
}
