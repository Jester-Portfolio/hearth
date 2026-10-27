package io.github.jesterportfolio.hearth.persistence;

import com.zaxxer.hikari.HikariDataSource;
import io.github.jesterportfolio.hearth.exploration.*;
import java.util.*;
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
public final class PostgresProgressStore implements ProgressStore, InventoryStore, QuestStore, QuestJournalStore, ExplorationStore, StoryStore {
    private volatile Map<String,ExplorationContent.Fact> questDiary=Map.of();
    public void configureQuestDiary(Map<String,ExplorationContent.Fact> bindings){questDiary=Map.copyOf(bindings);}
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
                    if(next==QuestStore.Phase.COMPLETED && questDiary.containsKey(quest))writeFact(c,ctx,questDiary.get(quest),false);
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
            try(var st=c.prepareStatement("SELECT hud_enabled,revision,minimap_enabled,quest_marker,npc_marker,player_marker,place_marker FROM hearth_progress.quest_preferences WHERE story_run_id=? AND player_uuid=?")){owner(st,ctx);try(var rs=st.executeQuery()){if(rs.next())preferences=new QuestJournalStore.Preferences(rs.getBoolean(1),rs.getLong(2),rs.getBoolean(3),rs.getBoolean(4),rs.getBoolean(5),rs.getBoolean(6),rs.getBoolean(7));}}
            try(var st=c.prepareStatement("SELECT quest_id,event_revision,kind,created_at,is_read FROM hearth_progress.quest_notification WHERE story_run_id=? AND player_uuid=? ORDER BY created_at DESC,quest_id,event_revision DESC")){owner(st,ctx);try(var rs=st.executeQuery()){while(rs.next())notices.add(new QuestJournalStore.Notice(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getTimestamp(4).toInstant(),rs.getBoolean(5)));}}
            try(var st=c.prepareStatement("SELECT place_id,created_at,is_read FROM hearth_progress.place_notification WHERE story_run_id=? AND player_uuid=?")){owner(st,ctx);try(var rs=st.executeQuery()){while(rs.next())notices.add(new QuestJournalStore.Notice(rs.getString(1),0,"PLACE_FOUND",rs.getTimestamp(2).toInstant(),rs.getBoolean(3),"PLACE"));}}
            notices.sort(Comparator.comparing(QuestJournalStore.Notice::created).reversed().thenComparing(QuestJournalStore.Notice::quest).thenComparing(Comparator.comparingLong(QuestJournalStore.Notice::revision).reversed()));
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
        try(var c=pool.getConnection()){playerExists(c,ctx);c.setAutoCommit(false);try{
            for(String table:List.of("quest_notification","place_notification"))try(var st=c.prepareStatement("UPDATE hearth_progress."+table+" SET is_read=true WHERE story_run_id=? AND player_uuid=?")){owner(st,ctx);st.executeUpdate();}c.commit();return null;
        }catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}}
    });}
    @Override public CompletionStage<Void> readPlaceNotice(ServerProgressAccess.Context ctx,String place){return submit("read_place_notice",ctx,()->{
        questAccess(ctx,place);try(var c=pool.getConnection();var st=c.prepareStatement("UPDATE hearth_progress.place_notification SET is_read=true WHERE story_run_id=? AND player_uuid=? AND place_id=?")){owner(st,ctx);st.setString(3,place);if(st.executeUpdate()!=1)throw new StorageFailure("notice","MISSING_NOTICE");return null;}
    });}
    @Override public CompletionStage<Void> setting(ServerProgressAccess.Context ctx,QuestJournalStore.Preferences expected,String key,boolean enabled){
        if(key.equals("hud"))return hud(ctx,expected,enabled);
        String column=switch(key){case "minimap"->"minimap_enabled";case "quest_marker"->"quest_marker";case "npc_marker"->"npc_marker";case "player_marker"->"player_marker";case "place_marker"->"place_marker";default->throw new IllegalArgumentException("Unknown setting");};
        return submit("save_marker_setting",ctx,()->{try(var c=pool.getConnection()){playerExists(c,ctx);
            String sql=expected.revision()==-1?"INSERT INTO hearth_progress.quest_preferences(story_run_id,player_uuid,"+column+") VALUES (?,?,?) ON CONFLICT DO NOTHING":"UPDATE hearth_progress.quest_preferences SET "+column+"=?,revision=revision+1 WHERE story_run_id=? AND player_uuid=? AND revision=?";
            try(var st=c.prepareStatement(sql)){if(expected.revision()==-1){owner(st,ctx);st.setBoolean(3,enabled);}else{st.setBoolean(1,enabled);st.setObject(2,ctx.storyRunId());st.setObject(3,ctx.playerId());st.setLong(4,expected.revision());}if(st.executeUpdate()!=1)throw new StorageFailure("settings","REVISION_CONFLICT");}return null;
        }});
    }
    @Override public CompletionStage<ExplorationStore.Data> readExploration(ServerProgressAccess.Context ctx){return submit("read_exploration",ctx,()->{
        try(var c=pool.getConnection()){
            playerExists(c,ctx);var places=new HashMap<String,ExplorationStore.PlaceState>();var facts=new ArrayList<ExplorationStore.Fact>();var events=new ArrayList<ExplorationStore.Event>();var story=new ExplorationStore.Story(null,null,null,-1);
            try(var st=c.prepareStatement("SELECT place_id,visited_at,discovered_at,hub_unlocked,revision FROM hearth_progress.exploration_place WHERE story_run_id=? AND player_uuid=?")){owner(st,ctx);try(var rs=st.executeQuery()){while(rs.next())places.put(rs.getString(1),new ExplorationStore.PlaceState(rs.getString(1),instant(rs.getTimestamp(2)),instant(rs.getTimestamp(3)),rs.getBoolean(4),rs.getLong(5)));}}
            for(boolean shared:List.of(false,true)){
                String suffix=shared?"shared":"personal",where=shared?"story_run_id=?":"story_run_id=? AND player_uuid=?";
                try(var st=c.prepareStatement("SELECT entry_id,category,title,summary,detail,version,first_seen,updated_at FROM hearth_progress.diary_"+suffix+"_fact WHERE "+where)){scope(st,ctx,shared);try(var rs=st.executeQuery()){while(rs.next())facts.add(new ExplorationStore.Fact(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getInt(6),instant(rs.getTimestamp(7)),instant(rs.getTimestamp(8)),shared));}}
                try(var st=c.prepareStatement("SELECT entry_id,version,category,title,summary,detail,occurred_at FROM hearth_progress.diary_"+suffix+"_event WHERE "+where)){scope(st,ctx,shared);try(var rs=st.executeQuery()){while(rs.next())events.add(new ExplorationStore.Event(rs.getString(1),rs.getInt(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),instant(rs.getTimestamp(7)),shared));}}
            }
            try(var st=c.prepareStatement("SELECT stage_id,stage_title,exploration_open,revision FROM hearth_progress.community_story WHERE story_run_id=?")){st.setObject(1,ctx.storyRunId());try(var rs=st.executeQuery()){if(rs.next())story=new ExplorationStore.Story(rs.getString(1),rs.getString(2),rs.getObject(3,Boolean.class),rs.getLong(4));}}
            facts.sort(Comparator.comparing(ExplorationStore.Fact::id).thenComparing(ExplorationStore.Fact::shared));events.sort(Comparator.comparing(ExplorationStore.Event::time).thenComparing(ExplorationStore.Event::id).thenComparingInt(ExplorationStore.Event::version));
            return new ExplorationStore.Data(places,facts,events,story);
        }
    });}
    private static java.time.Instant instant(Timestamp value){return value==null?null:value.toInstant();}
    private static int scope(PreparedStatement st,ServerProgressAccess.Context ctx,boolean shared)throws SQLException{st.setObject(1,ctx.storyRunId());if(!shared)st.setObject(2,ctx.playerId());return shared?2:3;}
    private boolean writeFact(Connection c,ServerProgressAccess.Context ctx,ExplorationContent.Fact fact,boolean shared)throws SQLException{
        if(fact==null || !fact.important())return false;
        if(!fact.id().matches("[a-z][a-z0-9_]{0,63}") || fact.version()<1 || !Set.of("STORY","PERSON","EXPLORATION").contains(fact.category()))throw new IllegalArgumentException("Invalid diary fact");
        for(String value:List.of(fact.title(),fact.summary(),fact.detail()))validateValue(value);
        String table="hearth_progress.diary_"+(shared?"shared":"personal"),columns=shared?"story_run_id":"story_run_id,player_uuid",where=shared?"story_run_id=? AND entry_id=?":"story_run_id=? AND player_uuid=? AND entry_id=?";
        boolean inserted;
        try(var st=c.prepareStatement("INSERT INTO "+table+"_fact("+columns+",entry_id,category,title,summary,detail,version) VALUES ("+(shared?"?":"?,?")+",?,?,?,?,?,?) ON CONFLICT DO NOTHING")){int i=scope(st,ctx,shared);st.setString(i++,fact.id());st.setString(i++,fact.category());st.setString(i++,fact.title());st.setString(i++,fact.summary());st.setString(i++,fact.detail());st.setInt(i,fact.version());inserted=st.executeUpdate()==1;}
        if(!inserted)try(var st=c.prepareStatement("SELECT version,category,title,summary,detail FROM "+table+"_fact WHERE "+where+" FOR UPDATE")){
            int i=scope(st,ctx,shared);st.setString(i,fact.id());try(var rs=st.executeQuery()){
                if(!rs.next())throw new StorageFailure("diary","MISSING_FACT");int version=rs.getInt(1);if(version>fact.version())return false;
                if(version==fact.version()){if(!List.of(rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5)).equals(List.of(fact.category(),fact.title(),fact.summary(),fact.detail())))throw new StorageFailure("diary","FACT_VERSION_CONFLICT");return false;}
            }
            try(var update=c.prepareStatement("UPDATE "+table+"_fact SET category=?,title=?,summary=?,detail=?,version=?,updated_at=clock_timestamp() WHERE "+where)){
                update.setString(1,fact.category());update.setString(2,fact.title());update.setString(3,fact.summary());update.setString(4,fact.detail());update.setInt(5,fact.version());int j=6;update.setObject(j++,ctx.storyRunId());if(!shared)update.setObject(j++,ctx.playerId());update.setString(j,fact.id());update.executeUpdate();
            }
        }
        try(var st=c.prepareStatement("INSERT INTO "+table+"_event("+columns+",entry_id,version,category,title,summary,detail) VALUES ("+(shared?"?":"?,?")+",?,?,?,?,?,?)")){
            int i=scope(st,ctx,shared);st.setString(i++,fact.id());st.setInt(i++,fact.version());st.setString(i++,fact.category());st.setString(i++,fact.title());st.setString(i++,fact.summary());st.setString(i,fact.detail());st.executeUpdate();
        }return true;
    }
    @Override public CompletionStage<Boolean> recordFact(ServerProgressAccess.Context ctx,ExplorationContent.Fact fact,boolean shared){return submit("record_diary",ctx,()->{
        try(var c=pool.getConnection()){playerExists(c,ctx);c.setAutoCommit(false);try{boolean result=writeFact(c,ctx,fact,shared);c.commit();return result;}catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}}
    });}
    @Override public CompletionStage<Boolean> recordPlace(ServerProgressAccess.Context ctx,ExplorationContent.Place place,ExplorationContent.Fact fact,boolean discover,boolean visit){return submit("record_place",ctx,()->{
        if(!discover && !visit)throw new IllegalArgumentException("No verified observation");questAccess(ctx,place.id());
        try(var c=pool.getConnection()){playerExists(c,ctx);c.setAutoCommit(false);try{
            try(var st=c.prepareStatement("INSERT INTO hearth_progress.exploration_place(story_run_id,player_uuid,place_id) VALUES (?,?,?) ON CONFLICT DO NOTHING")){owner(st,ctx);st.setString(3,place.id());st.executeUpdate();}
            boolean first;
            try(var st=c.prepareStatement("SELECT discovered_at,visited_at,hub_unlocked FROM hearth_progress.exploration_place WHERE story_run_id=? AND player_uuid=? AND place_id=? FOR UPDATE")){
                owner(st,ctx);st.setString(3,place.id());try(var rs=st.executeQuery()){rs.next();first=discover && rs.getTimestamp(1)==null;boolean changed=first || visit&&rs.getTimestamp(2)==null || visit&&place.hub()&&!rs.getBoolean(3);
                    if(changed)try(var update=c.prepareStatement("UPDATE hearth_progress.exploration_place SET discovered_at=CASE WHEN ? THEN COALESCE(discovered_at,clock_timestamp()) ELSE discovered_at END,visited_at=CASE WHEN ? THEN COALESCE(visited_at,clock_timestamp()) ELSE visited_at END,hub_unlocked=hub_unlocked OR ?,revision=revision+1 WHERE story_run_id=? AND player_uuid=? AND place_id=?")){
                        update.setBoolean(1,discover);update.setBoolean(2,visit);update.setBoolean(3,visit&&place.hub());update.setObject(4,ctx.storyRunId());update.setObject(5,ctx.playerId());update.setString(6,place.id());update.executeUpdate();
                    }
                }
            }
            if(first){try(var st=c.prepareStatement("INSERT INTO hearth_progress.place_notification(story_run_id,player_uuid,place_id) VALUES (?,?,?)")){owner(st,ctx);st.setString(3,place.id());st.executeUpdate();}writeFact(c,ctx,fact,false);}
            c.commit();return first;
        }catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}}
    });}
    @Override public CompletionStage<Void> saveStory(ServerProgressAccess.Context ctx,ExplorationStore.Story expected,String stage,String title,Boolean open){return submit("save_community_story",ctx,()->{
        if((stage==null)!=(title==null) || stage!=null && !stage.matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid shared stage");
        try(var c=pool.getConnection()){playerExists(c,ctx);
            try(var guard=c.prepareStatement("SELECT 1 FROM hearth_progress.community_story_request WHERE story_run_id=? LIMIT 1")){guard.setObject(1,ctx.storyRunId());try(var rows=guard.executeQuery()){if(rows.next())throw new StorageFailure("community","ENGINE_OWNED_STORY");}}
            String sql=expected.revision()==-1?"INSERT INTO hearth_progress.community_story(story_run_id,stage_id,stage_title,exploration_open) VALUES (?,?,?,?) ON CONFLICT DO NOTHING":"UPDATE hearth_progress.community_story SET stage_id=?,stage_title=?,exploration_open=?,revision=revision+1 WHERE story_run_id=? AND revision=?";
            try(var st=c.prepareStatement(sql)){if(expected.revision()==-1){st.setObject(1,ctx.storyRunId());st.setString(2,stage);st.setString(3,title);st.setObject(4,open,Types.BOOLEAN);}else{st.setString(1,stage);st.setString(2,title);st.setObject(3,open,Types.BOOLEAN);st.setObject(4,ctx.storyRunId());st.setLong(5,expected.revision());}if(st.executeUpdate()!=1)throw new StorageFailure("community","REVISION_CONFLICT");}return null;
        }
    });}

    private StoryStore.Snapshot storySnapshot(Connection c,ServerProgressAccess.Context ctx)throws SQLException{
        var story=new ExplorationStore.Story(null,null,null,-1);var complete=new HashSet<String>();var branches=new HashMap<String,StoryStore.Branch>();
        try(var st=c.prepareStatement("SELECT stage_id,stage_title,exploration_open,revision FROM hearth_progress.community_story WHERE story_run_id=?")){st.setObject(1,ctx.storyRunId());try(var rs=st.executeQuery()){if(rs.next())story=new ExplorationStore.Story(rs.getString(1),rs.getString(2),rs.getObject(3,Boolean.class),rs.getLong(4));}}
        try(var st=c.prepareStatement("SELECT stage_id FROM hearth_progress.community_story_completion WHERE story_run_id=?")){st.setObject(1,ctx.storyRunId());try(var rs=st.executeQuery()){while(rs.next())complete.add(rs.getString(1));}}
        try(var st=c.prepareStatement("SELECT stage_id,choice_id,choice_label,request_id FROM hearth_progress.community_story_branch WHERE story_run_id=?")){st.setObject(1,ctx.storyRunId());try(var rs=st.executeQuery()){while(rs.next())branches.put(rs.getString(1),new StoryStore.Branch(rs.getString(1),rs.getString(2),rs.getString(3),rs.getObject(4,UUID.class)));}}
        return new StoryStore.Snapshot(story,complete,branches);
    }
    @Override public CompletionStage<StoryStore.Snapshot> readStory(ServerProgressAccess.Context ctx){return submit("read_story",ctx,()->{try(var c=pool.getConnection()){playerExists(c,ctx);c.setAutoCommit(false);try{c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);var result=storySnapshot(c,ctx);c.commit();return result;}catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}}});}
    private Map<UUID,Boolean> conditionRows(Connection c,ServerProgressAccess.Context ctx,Set<UUID> players,Set<String> quests)throws SQLException{
        var result=new HashMap<UUID,Boolean>();for(UUID player:players){boolean met=true;
            try(var st=c.prepareStatement("SELECT 1 FROM hearth_progress.player_progress WHERE story_run_id=? AND player_uuid=?")){st.setObject(1,ctx.storyRunId());st.setObject(2,player);try(var rs=st.executeQuery()){if(!rs.next())throw new StorageFailure("story","MISSING_PLAYER");}}
            for(String quest:quests){questAccess(ctx,quest);try(var st=c.prepareStatement("SELECT phase FROM hearth_progress.personal_quest WHERE story_run_id=? AND player_uuid=? AND quest_id=? FOR SHARE")){st.setObject(1,ctx.storyRunId());st.setObject(2,player);st.setString(3,quest);try(var rs=st.executeQuery()){met&=rs.next()&&rs.getString(1).equals("COMPLETED");}}}result.put(player,met);
        }return Map.copyOf(result);
    }
    @Override public CompletionStage<Map<UUID,Boolean>> storyConditions(ServerProgressAccess.Context ctx,Set<UUID> players,Set<String> quests){return submit("story_conditions",ctx,()->{try(var c=pool.getConnection()){playerExists(c,ctx);return conditionRows(c,ctx,players,quests);}});}
    private Optional<String> receipt(Connection c,ServerProgressAccess.Context ctx,UUID id)throws SQLException{try(var st=c.prepareStatement("SELECT fingerprint FROM hearth_progress.community_story_request WHERE story_run_id=? AND request_id=?")){st.setObject(1,ctx.storyRunId());st.setObject(2,id);try(var rs=st.executeQuery()){return rs.next()?Optional.of(rs.getString(1)):Optional.empty();}}}
    @Override public CompletionStage<Optional<String>> storyReceipt(ServerProgressAccess.Context ctx,UUID id){return submit("story_receipt",ctx,()->{try(var c=pool.getConnection()){playerExists(c,ctx);return receipt(c,ctx,id);}});}
    @Override public CompletionStage<StoryStore.Snapshot> commitStory(ServerProgressAccess.Context ctx,StoryStore.Request r,java.util.function.BooleanSupplier fence){return submit("commit_story",ctx,()->{
        questAccess(ctx,r.to());if(r.from()!=null)questAccess(ctx,r.from());if(r.choice()!=null)questAccess(ctx,r.choice());validateValue(r.title());if(r.label()!=null)validateValue(r.label());String hash=StoryStore.fingerprint(r);
        try(var c=pool.getConnection()){playerExists(c,ctx);c.setAutoCommit(false);boolean committing=false;try{
            try(var st=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,7006))")){st.setString(1,ctx.storyRunId().toString());st.execute();}
            var previous=receipt(c,ctx,r.id());if(previous.isPresent()){if(!previous.get().equals(hash))throw new StorageFailure("story","REQUEST_MISMATCH");var result=storySnapshot(c,ctx);c.rollback();return result;}
            var before=storySnapshot(c,ctx);if(before.story().revision()!=r.revision()||!Objects.equals(before.story().stage(),r.from()))throw new StorageFailure("story","REVISION_CONFLICT");
            if(r.from()!=null&&before.completed().contains(r.from())||r.choice()!=null&&before.branches().containsKey(r.from()))throw new StorageFailure("story","ALREADY_FINAL");
            if(r.players().isEmpty()||conditionRows(c,ctx,r.players(),r.required()).values().stream().anyMatch(v->!v))throw new StorageFailure("story","CONDITIONS_UNMET");
            if(!fence.getAsBoolean())throw new StorageFailure("story","STALE_ROSTER");
            boolean open=Boolean.TRUE.equals(before.story().explorationOpen())||r.open();long next=r.revision()+1;
            try(var st=c.prepareStatement("INSERT INTO hearth_progress.community_story_request(story_run_id,request_id,fingerprint,resulting_revision) VALUES (?,?,?,?)")){st.setObject(1,ctx.storyRunId());st.setObject(2,r.id());st.setString(3,hash);st.setLong(4,next);st.executeUpdate();}
            String sql=r.revision()==-1?"INSERT INTO hearth_progress.community_story(story_run_id,stage_id,stage_title,exploration_open) VALUES (?,?,?,?)":"UPDATE hearth_progress.community_story SET stage_id=?,stage_title=?,exploration_open=?,revision=revision+1 WHERE story_run_id=? AND revision=?";
            try(var st=c.prepareStatement(sql)){if(r.revision()==-1){st.setObject(1,ctx.storyRunId());st.setString(2,r.to());st.setString(3,r.title());st.setBoolean(4,open);}else{st.setString(1,r.to());st.setString(2,r.title());st.setBoolean(3,open);st.setObject(4,ctx.storyRunId());st.setLong(5,r.revision());}if(st.executeUpdate()!=1)throw new StorageFailure("story","REVISION_CONFLICT");}
            if(r.from()!=null)try(var st=c.prepareStatement("INSERT INTO hearth_progress.community_story_completion(story_run_id,stage_id,request_id) VALUES (?,?,?)")){st.setObject(1,ctx.storyRunId());st.setString(2,r.from());st.setObject(3,r.id());st.executeUpdate();}
            if(r.choice()!=null)try(var st=c.prepareStatement("INSERT INTO hearth_progress.community_story_branch(story_run_id,stage_id,choice_id,choice_label,request_id) VALUES (?,?,?,?,?)")){st.setObject(1,ctx.storyRunId());st.setString(2,r.from());st.setString(3,r.choice());st.setString(4,r.label());st.setObject(5,r.id());st.executeUpdate();}
            for(var fact:r.facts())writeFact(c,ctx,fact,true);var result=storySnapshot(c,ctx);
            if(!fence.getAsBoolean())throw new StorageFailure("story","STALE_ROSTER");committing=true;c.commit();return result;
        }catch(Exception error){try{c.rollback();}catch(SQLException ignored){}if(committing&&error instanceof SQLException)throw new StorageFailure("story","COMMIT_UNKNOWN");throw error;}finally{c.setAutoCommit(true);}}
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
