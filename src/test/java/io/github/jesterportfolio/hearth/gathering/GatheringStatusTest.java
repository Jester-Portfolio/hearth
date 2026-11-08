package io.github.jesterportfolio.hearth.gathering;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class GatheringStatusTest {
    static GatheringStore.State state(GatheringStore.Phase phase,boolean empty,long next){return new GatheringStore.State(UUID.randomUUID(),"test",UUID.randomUUID(),1,1,"area","wood_stone_v1","hash",phase,null,empty,next);}
    @Test void openStatusIncludesOwnRegistrationAndEmptyResetBoundary(){var s=state(GatheringStore.Phase.OPEN,true,10000);var lines=GatheringStatus.lines(s,0,false,9001);assertTrue(lines.stream().anyMatch(x->x.contains("다음 입장 전에 초기화")));assertTrue(lines.stream().anyMatch(x->x.contains("아니오")));assertTrue(lines.stream().anyMatch(x->x.contains("약 1초")));assertEquals(1,s.generation());}
    @Test void resettingAndHoldNeverDisplayOpenOrSuccessCountdown(){for(var phase:List.of(GatheringStore.Phase.RESETTING,GatheringStore.Phase.ERROR_HOLD)){var lines=GatheringStatus.lines(state(phase,false,10000),2,true,100);assertTrue(lines.stream().noneMatch(x->x.contains("이용 가능")||x.contains("약 ")));}}
    @Test void expirationAndLargeTimestampDoNotOverflow(){assertTrue(GatheringStatus.lines(state(GatheringStore.Phase.OPEN,false,10),0,false,11).stream().anyMatch(x->x.contains("약 0초")));assertTrue(GatheringStatus.lines(state(GatheringStore.Phase.OPEN,false,Long.MAX_VALUE),0,false,0).stream().noneMatch(x->x.contains("약 -")));}
}
