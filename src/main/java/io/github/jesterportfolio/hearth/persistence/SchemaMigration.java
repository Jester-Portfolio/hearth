package io.github.jesterportfolio.hearth.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** V001을 보존하는 순차 트랜잭션 마이그레이션. 미상 버전·기존 테이블의 자동 재설정은 금지합니다. */
final class SchemaMigration {
    private static final List<String> FILES=List.of("V001__progress.sql","V002__story_inventory.sql","V003__personal_quest.sql","V004__quest_steps_and_journal.sql","V005__exploration_diary_and_markers.sql","V006__shared_story_requests_and_branches.sql");
    private static void validateTables(Connection c,int version) throws SQLException {
        try (var st=c.createStatement()) {
            st.executeQuery("SELECT story_run_id,state_value,revision FROM hearth_progress.story_run LIMIT 0").close();
            st.executeQuery("SELECT story_run_id,player_uuid,state_value,revision FROM hearth_progress.player_progress LIMIT 0").close();
            if (version>=3) st.executeQuery("SELECT story_run_id,player_uuid,quest_id,phase,revision FROM hearth_progress.personal_quest LIMIT 0").close();
            if(version>=4){
                st.executeQuery("SELECT objective_index FROM hearth_progress.personal_quest LIMIT 0").close();
                st.executeQuery("SELECT story_run_id,player_uuid,quest_id FROM hearth_progress.quest_tracking LIMIT 0").close();
                st.executeQuery("SELECT hud_enabled,revision FROM hearth_progress.quest_preferences LIMIT 0").close();
                st.executeQuery("SELECT quest_id,event_revision,kind,created_at,is_read FROM hearth_progress.quest_notification LIMIT 0").close();
            }
            if(version>=5){
                st.executeQuery("SELECT place_id,visited_at,discovered_at,hub_unlocked,revision FROM hearth_progress.exploration_place LIMIT 0").close();
                st.executeQuery("SELECT place_id,is_read,created_at FROM hearth_progress.place_notification LIMIT 0").close();
                st.executeQuery("SELECT minimap_enabled,quest_marker,npc_marker,player_marker,place_marker FROM hearth_progress.quest_preferences LIMIT 0").close();
                for(String scope:List.of("personal","shared"))for(String type:List.of("fact","event"))st.executeQuery("SELECT entry_id,version,title,summary,detail FROM hearth_progress.diary_"+scope+"_"+type+" LIMIT 0").close();
                st.executeQuery("SELECT stage_id,stage_title,exploration_open,revision FROM hearth_progress.community_story LIMIT 0").close();
            }
            if(version>=6){
                st.executeQuery("SELECT story_run_id,request_id,fingerprint,resulting_revision FROM hearth_progress.community_story_request LIMIT 0").close();
                st.executeQuery("SELECT story_run_id,stage_id,request_id FROM hearth_progress.community_story_completion LIMIT 0").close();
                st.executeQuery("SELECT story_run_id,stage_id,choice_id,choice_label,request_id FROM hearth_progress.community_story_branch LIMIT 0").close();
            }
            if (version>=2) st.executeQuery("SELECT story_run_id,player_uuid,payload,revision FROM hearth_progress.story_inventory LIMIT 0").close();
        }
    }
    static void apply(Connection c) throws Exception {
        List<byte[]> sources=new ArrayList<>(); List<String> hashes=new ArrayList<>();
        for (String file:FILES) try (var in=SchemaMigration.class.getResourceAsStream("/db/migration/"+file)) {
            if (in==null) throw new IOException("Migration resource missing");
            byte[] bytes=in.readAllBytes(); sources.add(bytes);
            hashes.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
        c.setAutoCommit(false);
        try {
            int applied=0;
            try (var st=c.createStatement()) {
                st.setQueryTimeout(3); st.execute("SELECT pg_advisory_xact_lock(704404001)");
                st.execute("CREATE SCHEMA IF NOT EXISTS hearth_progress");
                st.execute("CREATE TABLE IF NOT EXISTS hearth_progress.schema_version (version integer PRIMARY KEY, checksum text NOT NULL)");
                try (var rows=st.executeQuery("SELECT version,checksum FROM hearth_progress.schema_version ORDER BY version")) {
                    while (rows.next()) {
                        if (applied>=FILES.size() || rows.getInt(1)!=applied+1 || !hashes.get(applied).equals(rows.getString(2)))
                            throw new StorageFailure("migrate","SCHEMA_VERSION_MISMATCH");
                        applied++;
                    }
                }
                if (applied>0) validateTables(c,applied);
                for (int index=applied;index<FILES.size();index++) {
                    for (String sql:new String(sources.get(index),StandardCharsets.UTF_8).split(";")) if (!sql.isBlank()) st.execute(sql);
                    try (var insert=c.prepareStatement("INSERT INTO hearth_progress.schema_version VALUES (?,?)")) {
                        insert.setInt(1,index+1); insert.setString(2,hashes.get(index)); insert.executeUpdate();
                    }
                }
                validateTables(c,FILES.size());
            }
            c.commit();
        } catch (Exception failure) {
            try { c.rollback(); } catch (SQLException ignored) { /* 원래 실패를 성공으로 바꾸지 않습니다. */ }
            throw failure;
        } finally { c.setAutoCommit(true); }
    }
}
