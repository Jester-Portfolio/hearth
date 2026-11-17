package io.github.jesterportfolio.hearth.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.*;

/** 격리 백업의 원본/복원 DB 비교용. 행 내용 대신 테이블별 행 수·해시만 남깁니다. */
final class BackupDatabaseSnapshot {
    private static String quoted(String value){return "\""+value.replace("\"","\"\"")+"\"";}
    static Properties capture(Connection connection) throws Exception {
        var result=new Properties();var tables=new ArrayList<String[]>();
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setReadOnly(true);connection.setAutoCommit(false);
        try {
            try(var statement=connection.createStatement()){
                statement.setQueryTimeout(5);
                try(var rows=statement.executeQuery("SELECT table_schema,table_name FROM information_schema.tables WHERE table_type='BASE TABLE' AND table_schema NOT IN ('pg_catalog','information_schema') AND table_schema NOT LIKE 'pg_%' ORDER BY table_schema,table_name")){
                    while(rows.next())tables.add(new String[]{rows.getString(1),rows.getString(2)});
                }
            }
            if(tables.isEmpty()||tables.size()>200)throw new IllegalStateException("Snapshot table budget");
            long bytes=0,allRows=0;
            for(var table:tables){
                var digest=MessageDigest.getInstance("SHA-256");long count=0;
                try(var statement=connection.createStatement()){
                    statement.setQueryTimeout(5);
                    try(var rows=statement.executeQuery("SELECT row_to_json(t)::text AS value FROM "+quoted(table[0])+"."+quoted(table[1])+" t ORDER BY row_to_json(t)::text COLLATE \"C\"")){
                        while(rows.next()){
                            byte[] value=rows.getString(1).getBytes(StandardCharsets.UTF_8);bytes+=value.length;allRows++;count++;
                            if(bytes>32*1024*1024||allRows>100_000)throw new IllegalStateException("Snapshot row budget");
                            digest.update(value);digest.update((byte)'\n');
                        }
                    }
                }
                result.setProperty(table[0]+"."+table[1],count+":"+HexFormat.of().formatHex(digest.digest()));
            }
            return result;
        } finally {connection.rollback();connection.setAutoCommit(true);connection.setReadOnly(false);}
    }
}
