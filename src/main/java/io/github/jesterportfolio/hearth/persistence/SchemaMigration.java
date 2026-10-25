package io.github.jesterportfolio.hearth.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.HexFormat;

/** 한 개의 버전부터 시작하는 트랜잭션 마이그레이션. 기존 SQL 수정/자동 복구/초기화는 금지합니다. */
final class SchemaMigration {
    // 버전 행만 정상이고 필수 테이블/열이 없을 때 준비 완료로 오인하지 않습니다.
    private static void validateTables(Connection connection) throws SQLException {
        try (var st = connection.createStatement()) {
            st.executeQuery("SELECT story_run_id,state_value,revision FROM hearth_progress.story_run LIMIT 0").close();
            st.executeQuery("SELECT story_run_id,player_uuid,state_value,revision FROM hearth_progress.player_progress LIMIT 0").close();
        }
    }

    static void apply(Connection connection) throws Exception {
        byte[] bytes;
        try (var input = SchemaMigration.class.getResourceAsStream("/db/migration/V001__progress.sql")) {
            if (input == null) throw new IOException("Migration resource missing");
            bytes = input.readAllBytes();
        }
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        connection.setAutoCommit(false);
        try {
            try (var st = connection.createStatement()) {
                st.setQueryTimeout(3);
                // 협력하는 마이그레이션 실행을 DB 트랜잭션 범위에서 순서화합니다.
                st.execute("SELECT pg_advisory_xact_lock(704404001)");
                st.execute("CREATE SCHEMA IF NOT EXISTS hearth_progress");
                st.execute("CREATE TABLE IF NOT EXISTS hearth_progress.schema_version (version integer PRIMARY KEY, checksum text NOT NULL)");
                try (var rows = st.executeQuery("SELECT version, checksum FROM hearth_progress.schema_version ORDER BY version")) {
                    if (rows.next()) {
                        if (rows.getInt(1) != 1 || !rows.getString(2).equals(checksum) || rows.next()) {
                            throw new StorageFailure("migrate", "SCHEMA_VERSION_MISMATCH");
                        }
                        validateTables(connection);
                        connection.commit();
                        return;
                    }
                }
                // 관리 이력이 없는 기존 테이블을 초기화하거나 채택하지 않습니다.
                for (String statement : new String(bytes, StandardCharsets.UTF_8).split(";")) {
                    if (!statement.isBlank()) st.execute(statement);
                }
            }
            try (var st = connection.prepareStatement("INSERT INTO hearth_progress.schema_version VALUES (1, ?)")) {
                st.setString(1, checksum);
                st.executeUpdate();
            }
            connection.commit();
        } catch (Exception failure) {
            try { connection.rollback(); } catch (SQLException rollbackFailure) { /* 원래 실패를 성공으로 바꾸지 않습니다. */ }
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }
}
