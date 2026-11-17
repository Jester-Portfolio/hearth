package io.github.jesterportfolio.hearth.exploration;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CommunityRosterTest {
    final UUID run=new UUID(1,1),other=new UUID(1,2),a=new UUID(2,1),b=new UUID(2,2);
    CommunityBoundary.Ref ref(UUID r,UUID p,String name,boolean online){return new CommunityBoundary.Ref(r,p,name,online);}
    @Test void storedMembersRemainOfflineWithoutCurrentConnections(){var refs=CommunityRoster.merge(run,List.of(ref(run,a,null,true),ref(run,b,"친구",true)),List.of());assertEquals(2,refs.size());assertTrue(refs.stream().noneMatch(CommunityBoundary.Ref::online));}
    @Test void currentConnectionSuppliesNameAndOnlineStateForRegisteredMemberOnly(){var refs=CommunityRoster.merge(run,List.of(ref(run,a,null,false),ref(run,b,"이전 이름",false)),List.of(ref(run,a,"실제 이름",true),ref(run,b,null,false)));assertEquals("실제 이름",refs.getFirst().name());assertTrue(refs.getFirst().online());assertEquals("이전 이름",refs.get(1).name());assertFalse(refs.get(1).online());}
    @Test void anotherRunAndUnregisteredOnlineAccountCannotJoinOrVote(){var refs=CommunityRoster.merge(run,List.of(ref(run,a,null,false),ref(other,b,"다른 진행",false)),List.of(ref(other,a,"다른 연결",true),ref(run,b,"미등록",true)));assertEquals(List.of(ref(run,a,null,false)),refs);}
    @Test void duplicateOrConflictingSourcesFailRatherThanInventOnlineStatus(){assertThrows(IllegalStateException.class,()->CommunityRoster.merge(run,List.of(ref(run,a,null,false),ref(run,a,null,false)),List.of()));assertThrows(IllegalStateException.class,()->CommunityRoster.merge(run,List.of(ref(run,a,null,false)),List.of(ref(run,a,"하나",true),ref(run,a,"둘",false))));}
}
