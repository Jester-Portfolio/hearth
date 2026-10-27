package io.github.jesterportfolio.hearth.persistence;

import com.zaxxer.hikari.HikariDataSource;
import io.github.jesterportfolio.hearth.inventory.InventoryPayload;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;

/** Bukkit API/메인 스레드 대기가 없는 최소 JDBC 저장 모듈. 플러그인 부팅에는 아직 연결하지 않습니다. */
public final class PostgresProgressStore implements ProgressStore, InventoryStore, QuestStore, QuestJournalStore {
    public record Failure(String operation, UUID storyRunId, UUID playerId, String code) {}
    private final ServerProgressAccess access;
    private final HikariDataSource pool;
    private final ThreadPoolExecutor worker;
    private final Consumer<Failure> failures;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean schemaReady = new AtomicBoolean();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();

    public PostgresProgressStore(DataSource trustedServerDataSource, ServerProgressAccess access, Consumer<Failure> failures) {
        this.access = Objects.requireNonNull(access);
        this.failures = Objects.requireNonNull(failures);
        pool = new HikariDataSource(); // 무인자 생성은 아직 연결하지 않습니다. 최초 getConnection도 worker에서만 호출합니다.
        pool.setDataSource(Objects.requireNonNull(trustedServerDataSource));
        pool.setPoolName("hearth-progress");
        pool.setMaximumPoolSize(2);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(1500);
        pool.setValidationTimeout(500);
        pool.setInitializationFailTimeout(-1);
        pool.setConnectionInitSql("SET statement_timeout = '3s'");
        var number = new AtomicInteger();
        worker = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
                task -> new Thread(task, "hearth-progress-db-" + number.incrementAndGet()), new ThreadPoolExecutor.AbortPolicy());
    }

    @Override public CompletionStage<Void> migrate() {
        schemaReady.set(false);
        return submit("migrate", null, () -> {
            try (var c = pool.getConnection()) { SchemaMigration.apply(c); }
            schemaReady.set(true); return null;
        });
    }
    @Override public CompletionStage<Void> createRun(UUID run, String value) {
        return submit("create_run", null, () -> {
            validateValue(value);
            requireReady();
            try (var c = pool.getConnection(); var st = c.prepareStatement(
                    "INSERT INTO hearth_progress.story_run(story_run_id,state_value) VALUES (?,?)")) {
                st.setObject(1, Objects.requireNonNull(run)); st.setString(2,value); st.executeUpdate();
            }
            return null;
        });
    }
    @Override public CompletionStage<Void> createPlayer(ServerProgressAccess.Context ctx, String value) {
        return submit("create_player", ctx, () -> {
            access.requireIssued(ctx); validateValue(value); requireReady();
            try (var c = pool.getConnection(); var st = c.prepareStatement(
                    "INSERT INTO hearth_progress.player_progress(story_run_id,player_uuid,state_value) VALUES (?,?,?)")) {
                st.setObject(1,ctx.storyRunId()); st.setObject(2,ctx.playerId()); st.setString(3,value); st.executeUpdate();
            }
            return null;
        });
    }
    @Override public CompletionStage<State> readShared(ServerProgressAccess.Context ctx) { return read(ctx,false); }
    @Override public CompletionStage<State> readPersonal(ServerProgressAccess.Context ctx) { return read(ctx,true); }
    private CompletionStage<State> read(ServerProgressAccess.Context ctx, boolean personal) {
        return submit(personal ? "read_personal" : "read_shared", ctx, () -> {
            access.requireIssued(ctx);
            requireReady();
            String table = personal ? "player_progress" : "story_run";
            try (var c = pool.getConnection(); var st = c.prepareStatement(
                    "SELECT state_value,revision FROM hearth_progress."+table+" WHERE story_run_id=?"+(personal ? " AND player_uuid=?" : ""))) {
                st.setObject(1,ctx.storyRunId()); if (personal) st.setObject(2,ctx.playerId());
                try (var rows = st.executeQuery()) {
                    if (!rows.next()) throw new StorageFailure("read", "MISSING_STATE");
                    return new State(rows.getString(1),rows.getLong(2));
                }
            }
        });
    }
    @Override public CompletionStage<State> saveShared(ServerProgressAccess.Context ctx, long revision, String value) {
        return save(ctx,revision,value,false);
    }
    @Override public CompletionStage<State> savePersonal(ServerProgressAccess.Context ctx, long revision, String value) {
        return save(ctx,revision,value,true);
    }
    private CompletionStage<State> save(ServerProgressAccess.Context ctx, long revision, String value, boolean personal) {
        return submit(personal ? "save_personal" : "save_shared", ctx, () -> {
            access.requireIssued(ctx); validateValue(value);
            if (revision < 0 || revision == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid revision");
            requireReady();
            String table = personal ? "player_progress" : "story_run";
            try (var c = pool.getConnection(); var st = c.prepareStatement(
                    "UPDATE hearth_progress."+table+" SET state_value=?,revision=revision+1 WHERE story_run_id=?"
                    +(personal ? " AND player_uuid=?" : "")+" AND revision=? RETURNING state_value,revision")) {
                st.setString(1,value); st.setObject(2,ctx.storyRunId());
                int position=3; if (personal) st.setObject(position++,ctx.playerId()); st.setLong(position,revision);
                try (var rows = st.executeQuery()) {
                    if (!rows.next()) throw new StorageFailure("save", "REVISION_CONFLICT_OR_MISSING");
                    return new State(rows.getString(1),rows.getLong(2));
                }
            }
        });
    }

    @Override public CompletionStage<Stored> createInventory(ServerProgressAccess.Context ctx,InventoryPayload payload) {
        return submit("create_inventory",ctx,() -> {
            access.requireIssued(ctx); requireReady(); Objects.requireNonNull(payload);
            try (var c=pool.getConnection(); var st=c.prepareStatement(
                    "INSERT INTO hearth_progress.story_inventory(story_run_id,player_uuid,payload) VALUES (?,?,?) RETURNING revision")) {
                st.setObject(1,ctx.storyRunId()); st.setObject(2,ctx.playerId()); st.setBytes(3,payload.bytes());
                try (var row=st.executeQuery()) { row.next(); return new Stored(payload,row.getLong(1)); }
            }
        });
    }
    @Override public CompletionStage<Stored> readInventory(ServerProgressAccess.Context ctx) {
        return submit("read_inventory",ctx,() -> {
            access.requireIssued(ctx); requireReady();
            try (var c=pool.getConnection(); var st=c.prepareStatement(
                    "SELECT payload,revision FROM hearth_progress.story_inventory WHERE story_run_id=? AND player_uuid=?")) {
                st.setObject(1,ctx.storyRunId()); st.setObject(2,ctx.playerId());
                try (var row=st.executeQuery()) {
                    if (!row.next()) throw new StorageFailure("inventory","MISSING_INVENTORY");
                    return new Stored(InventoryPayload.parse(row.getBytes(1)),row.getLong(2));
                }
            }
        });
    }
    @Override public CompletionStage<Stored> saveInventory(ServerProgressAccess.Context ctx,long revision,InventoryPayload payload) {
        return submit("save_inventory",ctx,() -> {
            access.requireIssued(ctx); requireReady(); Objects.requireNonNull(payload);
            if (revision<0 || revision==Long.MAX_VALUE) throw new IllegalArgumentException("Invalid revision");
            try (var c=pool.getConnection(); var st=c.prepareStatement(
                    "UPDATE hearth_progress.story_inventory SET payload=?,revision=revision+1 WHERE story_run_id=? AND player_uuid=? AND revision=? RETURNING revision")) {
                st.setBytes(1,payload.bytes()); st.setObject(2,ctx.storyRunId()); st.setObject(3,ctx.playerId()); st.setLong(4,revision);
                try (var row=st.executeQuery()) {
                    if (!row.next()) throw new StorageFailure("inventory","REVISION_CONFLICT_OR_MISSING");
                    return new Stored(payload,row.getLong(1));
                }
            }
        });
    }

    private void questAccess(ServerProgressAccess.Context ctx,String quest) {
        access.requireIssued(ctx);requireReady();
        if (quest==null || !quest.matches("[a-z][a-z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid quest id");
    }
    @Override public CompletionStage<QuestStore.Saved> readQuest(ServerProgressAccess.Context ctx,String quest) {
        return submit("read_quest",ctx,() -> {
            questAccess(ctx,quest);
            try(var c=pool.getConnection();var st=c.prepareStatement(
                    "SELECT q.phase,q.revision,q.objective_index FROM hearth_progress.player_progress p LEFT JOIN hearth_progress.personal_quest q ON q.story_run_id=p.story_run_id AND q.player_uuid=p.player_uuid AND q.quest_id=? WHERE p.story_run_id=? AND p.player_uuid=?")) {
                st.setString(1,quest);st.setObject(2,ctx.storyRunId());st.setObject(3,ctx.playerId());
                try(var row=st.executeQuery()) {
                    if(!row.next())throw new StorageFailure("quest","MISSING_PLAYER");
                    String phase=row.getString(1);
                    return phase==null ? new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1) : new QuestStore.Saved(QuestStore.Phase.valueOf(phase),row.getLong(2),row.getInt(3));
                }
            }
        });
    }
    @Override public CompletionStage<QuestStore.Saved> advanceQuest(ServerProgressAccess.Context ctx,String quest,QuestStore.Saved expected,QuestStore.Phase next) {
        return advanceQuest(ctx,quest,expected,next,expected.objectiveIndex());
    }
    @Override public CompletionStage<QuestStore.Saved> advanceQuest(ServerProgressAccess.Context ctx,String quest,QuestStore.Saved expected,QuestStore.Phase next,int index) {
        return submit("advance_quest",ctx,() -> {
            questAccess(ctx,quest);
            boolean step=expected.phase()==QuestStore.Phase.ACTIVE && next==QuestStore.Phase.ACTIVE && index==expected.objectiveIndex()+1;
            if((!step && (!QuestStore.follows(expected.phase(),next) || index!=expected.objectiveIndex())) || expected.revision()==Long.MAX_VALUE)throw new IllegalArgumentException("Invalid quest transition");
            boolean first=expected.phase()==QuestStore.Phase.NOT_ACCEPTED;
            String sql=first ? "INSERT INTO hearth_progress.personal_quest(story_run_id,player_uuid,quest_id,phase,objective_index) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING RETURNING revision"
                : "UPDATE hearth_progress.personal_quest SET phase=?,objective_index=?,revision=revision+1 WHERE story_run_id=? AND player_uuid=? AND quest_id=? AND phase=? AND revision=? AND objective_index=? RETURNING revision";
            try(var c=pool.getConnection()) {
                c.setAutoCommit(false);
                try {
                    long revision;
                    try(var st=c.prepareStatement(sql)){
                        if(first){st.setObject(1,ctx.storyRunId());st.setObject(2,ctx.playerId());st.setString(3,quest);st.setString(4,next.name());st.setInt(5,index);}
                        else {st.setString(1,next.name());st.setInt(2,index);st.setObject(3,ctx.storyRunId());st.setObject(4,ctx.playerId());st.setString(5,quest);st.setString(6,expected.phase().name());st.setLong(7,expected.revision());st.setInt(8,expected.objectiveIndex());}
                        try(var row=st.executeQuery()){if(!row.next())throw new StorageFailure("quest","REVISION_CONFLICT_OR_MISSING");revision=row.getLong(1);}
                    }
                    String kind=first?"STARTED":step?"OBJECTIVE_UPDATED":next==QuestStore.Phase.REPORT_READY?"REPORT_READY":"COMPLETED";
                    try(var st=c.prepareStatement("INSERT INTO hearth_progress.quest_notification(story_run_id,player_uuid,quest_id,event_revision,kind) VALUES (?,?,?,?,?)")){
                        st.setObject(1,ctx.storyRunId());st.setObject(2,ctx.playerId());st.setString(3,quest);st.setLong(4,revision);st.setString(5,kind);st.executeUpdate();
                    }
                    if(next==QuestStore.Phase.COMPLETED)try(var st=c.prepareStatement("DELETE FROM hearth_progress.quest_tracking WHERE story_run_id=? AND player_uuid=? AND quest_id=?")){owner(st,ctx);st.setString(3,quest);st.executeUpdate();}
                    c.commit();return new QuestStore.Saved(next,revision,index);
                }catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}
            }
        });
    }
    private static void owner(PreparedStatement st,ServerProgressAccess.Context ctx)throws SQLException{st.setObject(1,ctx.storyRunId());st.setObject(2,ctx.playerId());}
    private void playerExists(Connection c,ServerProgressAccess.Context ctx)throws SQLException{
        questAccess(ctx,"journal");try(var st=c.prepareStatement("SELECT 1 FROM hearth_progress.player_progress WHERE story_run_id=? AND player_uuid=?")){owner(st,ctx);try(var rs=st.executeQuery()){if(!rs.next())throw new StorageFailure("journal","MISSING_PLAYER");}}
    }
    @Override public CompletionStage<QuestJournalStore.Journal> readJournal(ServerProgressAccess.Context ctx){return submit("read_journal",ctx,()->{
        try(var c=pool.getConnection()){
            playerExists(c,ctx);var tracked=new java.util.HashSet<String>();var notices=new java.util.ArrayList<QuestJournalStore.Notice>();var preferences=new QuestJournalStore.Preferences(true,-1);
            try(var st=c.prepareStatement("SELECT quest_id FROM hearth_progress.quest_tracking WHERE story_run_id=? AND player_uuid=?")){owner(st,ctx);try(var rs=st.executeQuery()){while(rs.next())tracked.add(rs.getString(1));}}
            try(var st=c.prepareStatement("SELECT hud_enabled,revision FROM hearth_progress.quest_preferences WHERE story_run_id=? AND player_uuid=?")){owner(st,ctx);try(var rs=st.executeQuery()){if(rs.next())preferences=new QuestJournalStore.Preferences(rs.getBoolean(1),rs.getLong(2));}}
            try(var st=c.prepareStatement("SELECT quest_id,event_revision,kind,created_at,is_read FROM hearth_progress.quest_notification WHERE story_run_id=? AND player_uuid=? ORDER BY created_at DESC,quest_id,event_revision DESC")){owner(st,ctx);try(var rs=st.executeQuery()){while(rs.next())notices.add(new QuestJournalStore.Notice(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getTimestamp(4).toInstant(),rs.getBoolean(5)));}}
            return new QuestJournalStore.Journal(tracked,preferences,notices);
        }
    });}
    @Override public CompletionStage<Void> track(ServerProgressAccess.Context ctx,String quest,QuestStore.Saved expected,boolean enabled){return submit("track_quest",ctx,()->{
        questAccess(ctx,quest);try(var c=pool.getConnection()){
            c.setAutoCommit(false);try{
                try(var st=c.prepareStatement("SELECT phase,revision,objective_index FROM hearth_progress.personal_quest WHERE story_run_id=? AND player_uuid=? AND quest_id=? FOR UPDATE")){
                    owner(st,ctx);st.setString(3,quest);try(var rs=st.executeQuery()){
                        if(!rs.next() || !new QuestStore.Saved(QuestStore.Phase.valueOf(rs.getString(1)),rs.getLong(2),rs.getInt(3)).equals(expected)
                            || enabled && expected.phase()!=QuestStore.Phase.ACTIVE && expected.phase()!=QuestStore.Phase.REPORT_READY)throw new StorageFailure("tracking","STALE_OR_UNACCEPTED");
                    }
                }
                try(var st=c.prepareStatement(enabled?"INSERT INTO hearth_progress.quest_tracking VALUES (?,?,?) ON CONFLICT DO NOTHING":"DELETE FROM hearth_progress.quest_tracking WHERE story_run_id=? AND player_uuid=? AND quest_id=?")){owner(st,ctx);st.setString(3,quest);st.executeUpdate();}
                c.commit();return null;
            }catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}
        }
    });}
    @Override public CompletionStage<Void> hud(ServerProgressAccess.Context ctx,QuestJournalStore.Preferences expected,boolean enabled){return submit("save_hud",ctx,()->{
        try(var c=pool.getConnection()){
            playerExists(c,ctx);String sql=expected.revision()==-1?"INSERT INTO hearth_progress.quest_preferences(story_run_id,player_uuid,hud_enabled) VALUES (?,?,?) ON CONFLICT DO NOTHING":"UPDATE hearth_progress.quest_preferences SET hud_enabled=?,revision=revision+1 WHERE story_run_id=? AND player_uuid=? AND revision=?";
            try(var st=c.prepareStatement(sql)){
                if(expected.revision()==-1){owner(st,ctx);st.setBoolean(3,enabled);}else{st.setBoolean(1,enabled);st.setObject(2,ctx.storyRunId());st.setObject(3,ctx.playerId());st.setLong(4,expected.revision());}
                if(st.executeUpdate()!=1)throw new StorageFailure("preferences","REVISION_CONFLICT");
            }return null;
        }
    });}
    @Override public CompletionStage<Void> readNotice(ServerProgressAccess.Context ctx,String quest,long revision){return submit("read_notice",ctx,()->{
        questAccess(ctx,quest);try(var c=pool.getConnection();var st=c.prepareStatement("UPDATE hearth_progress.quest_notification SET is_read=true WHERE story_run_id=? AND player_uuid=? AND quest_id=? AND event_revision=?")){owner(st,ctx);st.setString(3,quest);st.setLong(4,revision);if(st.executeUpdate()!=1)throw new StorageFailure("notice","MISSING_NOTICE");return null;}
    });}
    @Override public CompletionStage<Void> readAllNotices(ServerProgressAccess.Context ctx){return submit("read_all_notices",ctx,()->{
        try(var c=pool.getConnection()){playerExists(c,ctx);try(var st=c.prepareStatement("UPDATE hearth_progress.quest_notification SET is_read=true WHERE story_run_id=? AND player_uuid=?")){owner(st,ctx);st.executeUpdate();}return null;}
    });}

    private static void validateValue(String value) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > 4096) {
            throw new IllegalArgumentException("State exceeds 4096 UTF-8 bytes or is null");
        }
    }
    private void requireReady() {
        if (!schemaReady.get()) throw new StorageFailure("schema", "SCHEMA_NOT_READY");
    }
    private <T> CompletionStage<T> submit(String operation, ServerProgressAccess.Context ctx, Callable<T> call) {
        var result = new CompletableFuture<T>();
        if (closing.get()) { fail(result,operation,ctx,"CLOSED"); return result; }
        pending.add(result);
        result.whenComplete((v,e) -> pending.remove(result));
        try {
            worker.execute(() -> {
                try { result.complete(call.call()); }
                catch (Exception failure) {
                    String code = failure instanceof SQLException sql ? Objects.toString(sql.getSQLState(),"SQL_FAILURE")
                            : failure instanceof StorageFailure storage ? storage.code()
                            : failure instanceof SecurityException ? "ACCESS_DENIED" : "INVALID_STATE";
                    fail(result,operation,ctx,code);
                }
            });
        } catch (RejectedExecutionException rejected) { fail(result,operation,ctx,closing.get() ? "CLOSED" : "QUEUE_FULL"); }
        return result;
    }
    private <T> void fail(CompletableFuture<T> result, String operation, ServerProgressAccess.Context ctx, String code) {
        try { failures.accept(new Failure(operation,ctx == null ? null : ctx.storyRunId(),ctx == null ? null : ctx.playerId(),code)); }
        catch (RuntimeException ignored) { /* 오류 기록기 실패도 저장 성공으로 바꾸지 않습니다. */ }
        result.completeExceptionally(new StorageFailure(operation,code));
    }

    @Override public CompletionStage<Void> closeAsync() {
        if (closing.compareAndSet(false,true)) {
            worker.shutdown();
            new Thread(() -> {
                try {
                    if (!worker.awaitTermination(30,TimeUnit.SECONDS)) {
                        worker.shutdownNow();
                        pending.forEach(f -> f.completeExceptionally(new StorageFailure("close","TIMEOUT")));
                        throw new StorageFailure("close","TIMEOUT");
                    }
                    pool.close();
                    closed.complete(null);
                } catch (Exception failure) {
                    pool.close(); closed.completeExceptionally(new StorageFailure("close","CLOSE_FAILURE"));
                }
            }, "hearth-progress-close").start();
        }
        return closed;
    }
}
