package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.exploration.CommunityBoundary;
import io.github.jesterportfolio.hearth.persistence.ServerProgressAccess;
import java.util.*;

/** 설정한 진행의 실제 저장 명단. 미접속 설정 계정은 만들지 않고 과거 구성원도 제외하지 않습니다. */
public final class StoryCommunity {
    private final StoryRuntimeSettings settings;private final ServerProgressAccess access;private List<CommunityBoundary.Ref> refs=List.of();private boolean loaded;private Map<UUID,String> names=Map.of();
    public StoryCommunity(StoryRuntimeSettings settings,ServerProgressAccess access){this.settings=settings;this.access=access;}
    public void replace(List<StoryRegistry.Member> members){var seen=new HashSet<UUID>();var next=new ArrayList<CommunityBoundary.Ref>();if(members.size()>4096)throw new IllegalStateException("COMMUNITY_MEMBER_LIMIT");for(var member:members){if(!settings.runs().containsKey(member.run())||!seen.add(member.player()))throw new SecurityException("COMMUNITY_MEMBER_SCOPE");var configured=settings.members().get(member.player());if(configured!=null&&!configured.equals(member.run()))throw new SecurityException("COMMUNITY_MEMBER_SCOPE");next.add(new CommunityBoundary.Ref(member.run(),member.player(),names.get(member.player()),false));}refs=List.copyOf(next);loaded=true;}
    public void confirmed(ServerProgressAccess.Context context){access.requireIssued(context);if(!loaded||!Objects.equals(settings.members().get(context.playerId()),context.storyRunId()))throw new SecurityException("COMMUNITY_ADMISSION_SCOPE");var next=new ArrayList<>(refs);for(var ref:next)if(ref.player().equals(context.playerId())){if(!ref.run().equals(context.storyRunId()))throw new SecurityException("COMMUNITY_MEMBER_SCOPE");return;}if(next.size()>=4096)throw new IllegalStateException("COMMUNITY_MEMBER_LIMIT");next.add(new CommunityBoundary.Ref(context.storyRunId(),context.playerId(),names.get(context.playerId()),false));refs=List.copyOf(next);}
    public void names(Map<UUID,String> cached){if(!loaded)throw new IllegalStateException("COMMUNITY_NOT_LOADED");var allowed=new HashSet<>(settings.members().keySet());refs.forEach(r->allowed.add(r.player()));var next=new HashMap<UUID,String>();cached.forEach((id,name)->{if(allowed.contains(id)&&name!=null&&!name.isBlank()&&name.length()<=64&&name.chars().noneMatch(Character::isISOControl))next.put(id,name);});names=Map.copyOf(next);refs=refs.stream().map(r->new CommunityBoundary.Ref(r.run(),r.player(),names.getOrDefault(r.player(),r.name()),false)).toList();}
    public List<CommunityBoundary.Ref> refs(){if(!loaded)throw new IllegalStateException("COMMUNITY_NOT_LOADED");return refs;}
}
