package io.github.jesterportfolio.hearth.exploration;

import java.util.*;

/** 저장 명단은 소유권, 실제 연결은 접속 상태만 공급합니다. 미등록 접속을 구성원으로 승격하지 않습니다. */
public final class CommunityRoster {
    private CommunityRoster(){}
    public static List<CommunityBoundary.Ref> merge(UUID run,Collection<CommunityBoundary.Ref> registered,Collection<CommunityBoundary.Ref> connections){
        var members=new TreeMap<UUID,CommunityBoundary.Ref>();for(var ref:registered)if(ref.run().equals(run)){if(members.putIfAbsent(ref.player(),new CommunityBoundary.Ref(run,ref.player(),ref.name(),false))!=null)throw new IllegalStateException("COMMUNITY_DUPLICATE_MEMBER");}
        var seen=new HashSet<UUID>();for(var ref:connections)if(ref.run().equals(run)&&members.containsKey(ref.player())){if(!seen.add(ref.player()))throw new IllegalStateException("COMMUNITY_DUPLICATE_CONNECTION");var stored=members.get(ref.player());members.put(ref.player(),new CommunityBoundary.Ref(run,ref.player(),ref.name()==null?stored.name():ref.name(),ref.online()));}return List.copyOf(members.values());
    }
}
