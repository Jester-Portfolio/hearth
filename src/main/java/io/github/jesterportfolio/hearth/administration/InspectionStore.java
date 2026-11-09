package io.github.jesterportfolio.hearth.administration;

import io.github.jesterportfolio.hearth.persistence.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** 한 SELECT의 MVCC 기준으로 개인 원본을 읽습니다. 저장/재확정/행 생성은 하지 않습니다. */
public final class InspectionStore {
    public static final int FEATURE_LIMIT=64, BYTE_LIMIT=4*1024*1024;
    public record Row(String domain,String key,long revision,int bytes,byte[] payload) {
        public Row {payload=payload==null?null:payload.clone();}
        public byte[] payload(){return payload==null?null:payload.clone();}
    }
    public record Snapshot(Instant observed,List<Row> rows,boolean truncated) {
        public Snapshot {rows=List.copyOf(rows);}
    }
    private final PostgresProgressStore store;private final ServerProgressAccess access;
    public InspectionStore(PostgresProgressStore store,ServerProgressAccess access){this.store=store;this.access=access;}
    public CompletionStage<Snapshot> read(ServerProgressAccess.Context context) {
        return read(context,java.util.function.Function.identity());
    }
    public CompletionStage<RecoveryInspection.Report> prepare(ServerProgressAccess.Context context,UUID request,RecoveryInspection.Runtime before,Map<String,io.github.jesterportfolio.hearth.features.SceneContent.Scene> scenes) {
        return read(context,snapshot->RecoveryInspection.build(request,context,snapshot,before,before,scenes));
    }
    private <T> CompletionStage<T> read(ServerProgressAccess.Context context,java.util.function.Function<Snapshot,T> transform) {
        return store.module("inspect_personal_recovery",context,c->{
            access.requireIssued(context);
            String sql="""
                WITH actor AS (
                  SELECT revision FROM hearth_progress.player_progress WHERE story_run_id=? AND player_uuid=?
                ), data AS (
                  SELECT 0 AS category,'player'::text AS domain,'progress'::text AS feature_key,revision,NULL::bytea AS payload,0 AS bytes FROM actor
                  UNION ALL SELECT 1,'run','progress',r.revision,NULL::bytea,0 FROM hearth_progress.story_run r CROSS JOIN actor WHERE r.story_run_id=?
                  UNION ALL SELECT 2,'inventory','inventory',i.revision,i.payload,octet_length(i.payload) FROM hearth_progress.story_inventory i CROSS JOIN actor WHERE i.story_run_id=? AND i.player_uuid=?
                  UNION ALL SELECT 3,f.feature,f.feature_key,f.revision,f.payload,octet_length(f.payload) FROM
                    (SELECT feature,feature_key,revision,payload FROM hearth_progress.personal_feature WHERE story_run_id=? AND player_uuid=? ORDER BY feature,feature_key LIMIT 65) f CROSS JOIN actor
                )
                SELECT domain,feature_key,revision,bytes,
                  CASE WHEN SUM(bytes) OVER (ORDER BY category,domain,feature_key)<=4194304 THEN payload END AS payload,
                  statement_timestamp() AS observed
                FROM data ORDER BY category,domain,feature_key LIMIT 68
                """;
            try(var query=c.prepareStatement(sql)) {
                query.setObject(1,context.storyRunId());query.setObject(2,context.playerId());query.setObject(3,context.storyRunId());
                query.setObject(4,context.storyRunId());query.setObject(5,context.playerId());query.setObject(6,context.storyRunId());query.setObject(7,context.playerId());
                query.setQueryTimeout(4);
                try(var result=query.executeQuery()) {
                    var rows=new ArrayList<Row>();Instant observed=null;int features=0;boolean truncated=false;
                    while(result.next()) {
                        String domain=result.getString("domain");
                        if(!Set.of("player","run","inventory").contains(domain)&&++features>FEATURE_LIMIT){truncated=true;continue;}
                        byte[] payload=result.getBytes("payload");int bytes=result.getInt("bytes");
                        if(bytes>0&&payload==null)truncated=true;
                        rows.add(new Row(domain,result.getString("feature_key"),result.getLong("revision"),bytes,payload));
                        observed=result.getObject("observed",java.time.OffsetDateTime.class).toInstant();
                    }
                    if(observed==null)throw new StorageFailure("inspection","MISSING_PLAYER");
                    return transform.apply(new Snapshot(observed,rows,truncated));
                }
            }
        });
    }
}
