package io.github.jesterportfolio.hearth.features;

import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.inventory.InventoryPayload;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** 서버 발급 문맥·기존 bounded worker. 개인 기능은 공개 게임 정보 조회에 포함하지 않습니다. */
public final class FeatureStore {
    public record Saved(byte[] payload,long revision){public Saved{payload=payload.clone();if(payload.length<1||payload.length>1048576||revision<0)throw new IllegalArgumentException("FEATURE_PAYLOAD");}public byte[] payload(){return payload.clone();}}
    public record InventoryCommit(InventoryStore.Stored inventory,Saved feature){}
    private final PostgresProgressStore store;private final ServerProgressAccess access;
    public FeatureStore(PostgresProgressStore store,ServerProgressAccess access){this.store=store;this.access=access;}
    private void keys(ServerProgressAccess.Context ctx,String feature,String key){access.requireIssued(ctx);if(!Set.of("cutscene","ender","combat_return","device").contains(feature)||key==null||!key.matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("FEATURE_KEY");}
    public CompletionStage<Optional<Saved>> read(ServerProgressAccess.Context ctx,String feature,String key){return store.module("feature_read",ctx,c->{keys(ctx,feature,key);try(var p=c.prepareStatement("SELECT f.payload,f.revision FROM hearth_progress.player_progress p LEFT JOIN hearth_progress.personal_feature f ON f.story_run_id=p.story_run_id AND f.player_uuid=p.player_uuid AND f.feature=? AND f.feature_key=? WHERE p.story_run_id=? AND p.player_uuid=?")){p.setString(1,feature);p.setString(2,key);p.setObject(3,ctx.storyRunId());p.setObject(4,ctx.playerId());try(var r=p.executeQuery()){if(!r.next())throw new StorageFailure("feature","MISSING_PLAYER");byte[] bytes=r.getBytes(1);return bytes==null?Optional.empty():Optional.of(new Saved(bytes,r.getLong(2)));}}});}
    public CompletionStage<Saved> write(ServerProgressAccess.Context ctx,String feature,String key,long expected,byte[] payload){byte[] copy=payload.clone();return store.module("feature_write",ctx,c->{keys(ctx,feature,key);return write(c,ctx,feature,key,expected,copy);});}
    private Saved write(Connection c,ServerProgressAccess.Context ctx,String feature,String key,long expected,byte[] payload)throws Exception{
        if(expected< -1||expected==Long.MAX_VALUE)throw new IllegalArgumentException("FEATURE_REVISION");new Saved(payload,0);
        String sql=expected<0?"INSERT INTO hearth_progress.personal_feature(story_run_id,player_uuid,feature,feature_key,payload,revision) VALUES(?,?,?,?,?,0) ON CONFLICT DO NOTHING RETURNING revision":"UPDATE hearth_progress.personal_feature SET payload=?,revision=revision+1 WHERE story_run_id=? AND player_uuid=? AND feature=? AND feature_key=? AND revision=? RETURNING revision";
        try(var p=c.prepareStatement(sql)){if(expected<0){p.setObject(1,ctx.storyRunId());p.setObject(2,ctx.playerId());p.setString(3,feature);p.setString(4,key);p.setBytes(5,payload);}else{p.setBytes(1,payload);p.setObject(2,ctx.storyRunId());p.setObject(3,ctx.playerId());p.setString(4,feature);p.setString(5,key);p.setLong(6,expected);}try(var r=p.executeQuery()){if(!r.next())throw new StorageFailure("feature","FEATURE_CAS_CONFLICT");return new Saved(payload,r.getLong(1));}}
    }
    /** 개인 27칸과 기존41칸은 같은 DB 트랜잭션으로 확정합니다. 월드 보관함은 포함하지 않습니다. */
    public CompletionStage<InventoryCommit> inventory(ServerProgressAccess.Context ctx,long inventoryRevision,InventoryPayload inventory,long featureRevision,InventoryPayload ender){return commit(ctx,inventoryRevision,inventory,"ender","inventory",featureRevision,ender.bytes());}
    public CompletionStage<InventoryCommit> device(ServerProgressAccess.Context ctx,long revision,InventoryPayload inventory,long deviceRevision){return commit(ctx,revision,inventory,"device","phone",deviceRevision,new byte[]{1});}
    private CompletionStage<InventoryCommit> commit(ServerProgressAccess.Context ctx,long inventoryRevision,InventoryPayload inventory,String kind,String key,long featureRevision,byte[] payload){
        return store.module("feature_inventory_commit",ctx,c->{keys(ctx,kind,key);if(inventoryRevision<0)throw new IllegalArgumentException("INVENTORY_REVISION");c.setAutoCommit(false);try{
            long revision;try(var p=c.prepareStatement("UPDATE hearth_progress.story_inventory SET payload=?,revision=revision+1 WHERE story_run_id=? AND player_uuid=? AND revision=? RETURNING revision")){p.setBytes(1,inventory.bytes());p.setObject(2,ctx.storyRunId());p.setObject(3,ctx.playerId());p.setLong(4,inventoryRevision);try(var r=p.executeQuery()){if(!r.next())throw new StorageFailure("feature","INVENTORY_CAS_CONFLICT");revision=r.getLong(1);}}
            var feature=write(c,ctx,kind,key,featureRevision,payload);c.commit();return new InventoryCommit(new InventoryStore.Stored(inventory,revision),feature);
        }catch(Exception error){try{c.rollback();}catch(SQLException ignored){}throw error;}finally{c.setAutoCommit(true);}});
    }
}
