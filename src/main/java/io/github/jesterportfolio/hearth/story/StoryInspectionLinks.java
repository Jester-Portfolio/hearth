package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;

/** 설정과 PG의 기존 회원/불변 연결이 모두 일치하는 대상만 읽습니다. 개인 행을 만들지 않습니다. */
public final class StoryInspectionLinks {
    public record Member(UUID run,UUID player){}
    private final StoryRuntimeSettings settings;private final PostgresProgressStore database;private final ServerProgressAccess access;
    private Map<Member,ServerProgressAccess.Context> contexts=Map.of();
    public StoryInspectionLinks(StoryRuntimeSettings settings,PostgresProgressStore database,ServerProgressAccess access){this.settings=settings;this.database=database;this.access=access;}
    public CompletionStage<List<Member>> read(){return database.module("story_inspection_members",c->{
        c.setAutoCommit(false);c.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        var runs=c.createArrayOf("uuid",settings.runs().keySet().toArray(UUID[]::new));var players=c.createArrayOf("uuid",settings.members().keySet().toArray(UUID[]::new));
        try{
            var found=new HashSet<UUID>();
            try(var st=c.prepareStatement("SELECT story_run_id,world_key,world_uuid,template_version,template_hash,content_hash FROM hearth_runtime.story_binding WHERE story_run_id=ANY(?) LIMIT 65")){st.setArray(1,runs);st.setQueryTimeout(3);try(var row=st.executeQuery()){while(row.next()){var id=row.getObject(1,UUID.class);var expected=settings.runs().get(id);if(expected==null||!found.add(id)||!expected.binding().equals(new StoryBinding(id,row.getString(2),row.getObject(3,UUID.class),row.getString(4),row.getString(5),row.getString(6))))throw new IllegalStateException("STORY_INSPECTION_BINDING_CONFLICT");}}}
            if(found.size()!=settings.runs().size())throw new IllegalStateException("STORY_INSPECTION_BINDING_MISSING");
            var result=new ArrayList<Member>();
            try(var st=c.prepareStatement("SELECT story_run_id,player_uuid FROM hearth_runtime.story_member WHERE story_run_id=ANY(?) AND player_uuid=ANY(?) ORDER BY story_run_id,player_uuid LIMIT 4097")){st.setArray(1,runs);st.setArray(2,players);st.setQueryTimeout(3);try(var row=st.executeQuery()){while(row.next()){var member=new Member(row.getObject(1,UUID.class),row.getObject(2,UUID.class));if(!Objects.equals(settings.members().get(member.player()),member.run()))throw new SecurityException("STORY_INSPECTION_MEMBER_SCOPE");result.add(member);if(result.size()>4096)throw new IllegalStateException("STORY_INSPECTION_MEMBER_LIMIT");}}}
            c.commit();return List.copyOf(result);
        }catch(Exception error){c.rollback();throw error;}finally{runs.free();players.free();c.setAutoCommit(true);}

    });}
    /** owner 스레드에서만 교체. 같은 연결의 보고서 비교용 context identity를 유지합니다. */
    public void replace(List<Member> members){
        var next=new LinkedHashMap<Member,ServerProgressAccess.Context>();for(var member:members){if(!Objects.equals(settings.members().get(member.player()),member.run())||next.containsKey(member))throw new SecurityException("STORY_INSPECTION_MEMBER_SCOPE");next.put(member,contexts.containsKey(member)?contexts.get(member):access.forVerifiedServerLink(member.run(),member.player()));}contexts=Map.copyOf(next);
    }
    public List<ServerProgressAccess.Context> contexts(){return List.copyOf(contexts.values());}
    public void clear(){contexts=Map.of();}
}
