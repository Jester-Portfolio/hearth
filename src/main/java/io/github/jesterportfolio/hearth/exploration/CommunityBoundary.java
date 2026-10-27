package io.github.jesterportfolio.hearth.exploration;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.function.*;

/** 검증된 연결과 등록된 조건 엔진의 읽기 경계입니다. 멤버십/투표 기능이 아닙니다. */
public final class CommunityBoundary {
    public record Ref(UUID run,UUID player,String name,boolean online){}
    public record Member(UUID player,String name,boolean online,boolean self){}
    public record Condition(String stage,Map<UUID,Boolean> met){public Condition{met=Map.copyOf(met);}}
    public record Status(boolean registered,List<String> lines){public Status{lines=List.copyOf(lines);}}
    private final Function<ServerProgressAccess.Context,List<Ref>> links;
    private final BiFunction<ServerProgressAccess.Context,Set<UUID>,Optional<Condition>> conditions;
    public CommunityBoundary(Function<ServerProgressAccess.Context,List<Ref>> links,BiFunction<ServerProgressAccess.Context,Set<UUID>,Optional<Condition>> conditions){this.links=links;this.conditions=conditions;}
    public List<Member> members(ServerProgressAccess.Context ctx){return links.apply(ctx).stream().filter(r->r.run().equals(ctx.storyRunId())).map(r->new Member(r.player(),r.name()==null?"이름 미확인":r.name(),r.online(),r.player().equals(ctx.playerId()))).distinct().sorted(Comparator.comparing(Member::online).reversed().thenComparing(Member::name).thenComparing(m->m.player().toString())).toList();}
    public Status status(ServerProgressAccess.Context ctx,ExplorationStore.Story story,List<Member> members){
        if(story.stage()==null)return new Status(false,List.of("진행 조건 미등록"));
        var online=new HashSet<UUID>();members.stream().filter(Member::online).forEach(m->online.add(m.player()));
        var condition=conditions.apply(ctx,Set.copyOf(online));
        if(condition.isEmpty())return new Status(false,List.of("진행 조건 미등록"));
        var c=condition.get();if(!story.stage().equals(c.stage()) || !c.met().keySet().equals(online))return new Status(false,List.of("접속 상태 변경으로 조건 확인 불가"));
        return new Status(true,online.isEmpty()?List.of("현재 접속 검사 대상 없음"):members.stream().filter(Member::online).map(m->m.name()+": "+(Boolean.TRUE.equals(c.met().get(m.player()))?"조건 충족":"조건 미충족")).toList());
    }
}
