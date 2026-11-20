package io.github.jesterportfolio.hearth.economy;

import java.util.*;
import java.util.concurrent.*;
import java.sql.*;
import io.github.jesterportfolio.hearth.persistence.*;

/** realm 단위 락·권한 발급자를 재사용한다. 오래된 flush가 제한을 단축하지 않는다. */
public final class CombatTagStore {
    private final PostgresProgressStore db;private final EconomyStore economy;private final UUID realm;
    public CombatTagStore(PostgresProgressStore db,EconomyStore economy,UUID realm){this.db=db;this.economy=economy;this.realm=realm;}
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws Exception;}
    private <T>CompletionStage<T> tx(ServerProgressAccess.Context context,Work<T> work){return db.module("combat_tag",c->{c.setAutoCommit(false);try{if(!realm.equals(context.storyRunId()))throw new StorageFailure("combat","COMBAT_REALM");economy.housingLock(c,context);T value=work.run(c);c.commit();return value;}catch(Exception error){c.rollback();throw error;}finally{c.setAutoCommit(true);}});}
    public CompletionStage<Map<UUID,Long>> load(long now){return tx(economy.context(realm,new UUID(0,0)),c->{var result=new HashMap<UUID,Long>();try(var s=c.prepareStatement("SELECT player_uuid,until_millis FROM hearth_economy.combat_tag WHERE realm_id=? AND until_millis>?")){s.setObject(1,realm);s.setLong(2,now);try(var r=s.executeQuery()){while(r.next()){if(result.size()>=100_000)throw new StorageFailure("combat","COMBAT_LOAD_LIMIT");result.put(r.getObject(1,UUID.class),r.getLong(2));}}}return Map.copyOf(result);});}
    public CompletionStage<Void> save(Map<UUID,Long> values){return save(economy.context(realm,new UUID(0,0)),values);}
    public CompletionStage<Void> save(ServerProgressAccess.Context context,Map<UUID,Long> values){var copy=Map.copyOf(values);if(copy.size()>256||copy.values().stream().anyMatch(v->v<=0))return CompletableFuture.failedFuture(new IllegalArgumentException("COMBAT_BATCH"));return tx(context,c->{try(var s=c.prepareStatement("INSERT INTO hearth_economy.combat_tag(realm_id,player_uuid,until_millis) VALUES(?,?,?) ON CONFLICT(realm_id,player_uuid) DO UPDATE SET until_millis=GREATEST(hearth_economy.combat_tag.until_millis,EXCLUDED.until_millis)")){for(var row:copy.entrySet()){s.setObject(1,realm);s.setObject(2,row.getKey());s.setLong(3,row.getValue());s.addBatch();}s.executeBatch();}return null;});}
}
