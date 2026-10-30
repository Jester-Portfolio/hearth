package io.github.jesterportfolio.hearth.phone;

import io.github.jesterportfolio.hearth.quest.GameInformation;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PhoneRetrievalTest {
    static GameInformation data(){var quests=new ArrayList<GameInformation.Quest>();for(int i=0;i<20;i++)quests.add(new GameInformation.Quest("공개 조사 "+i,"안내원","진행 중",i==17?"석재 표본을 조사하세요.":"공개 길목을 방문하세요."));return new GameInformation(quests,List.of(new GameInformation.Place("?","공개 지역","대략적 탐색 구역","미발견",false)),List.of(new GameInformation.Diary("PERSON","인물 기록","허용 요약","허용 인물 상세",false)),List.of("현재 공유 단계 미등록"));}
    @Test void matchingSourcePastFirstPageIsReachableAndIndicesStayBoundToSnapshot(){var result=PhoneRetrieval.find(data(),"quests","석재 표본");assertEquals(1,result.sources().size());assertEquals(17,result.sources().getFirst().index());assertEquals("공개 조사 17",result.sources().getFirst().title());assertTrue(result.context().contains("석재 표본"));}
    @Test void scopeCannotSearchAnotherCategoryAndNoMatchIsExplicit(){var result=PhoneRetrieval.find(data(),"map","인물 상세");assertTrue(result.sources().isEmpty());assertTrue(result.context().contains("찾지 못했습니다"));assertFalse(result.context().contains("허용 인물 상세"));assertTrue(PhoneRetrieval.find(data(),"none","석재 표본").context().isEmpty());}
    @Test void hintsDoNotInventExactCoordinatesOrHiddenNames(){var result=PhoneRetrieval.find(data(),"map","탐색 구역");assertEquals("?",result.sources().getFirst().title());assertTrue(result.context().contains("대략적 탐색 구역"));assertFalse(result.context().contains("좌표"));assertFalse(result.context().contains("id="));}
    @Test void relevanceIsStableAndLimitedWithoutMutatingPublicData(){var info=data();String before=info.toString();var result=PhoneRetrieval.find(info,"quests","공개 조사");assertEquals(4,result.sources().size());assertEquals(List.of(0,1,2,3),result.sources().stream().map(PhoneRetrieval.Source::index).toList());assertEquals(before,info.toString());assertThrows(UnsupportedOperationException.class,()->result.sources().clear());}
    @Test void requestBudgetAndUnicodeStayBoundedForLongSources(){var info=new GameInformation(List.of(new GameInformation.Quest("석재"+"이름😀".repeat(100),"NPC","진행 중","석재"+"상세😀".repeat(2000))),List.of(),List.of(),List.of());var result=PhoneRetrieval.find(info,"quests","석재");assertTrue(result.context().codePointCount(0,result.context().length())<=2400);assertFalse(Character.isHighSurrogate(result.sources().getFirst().text().charAt(result.sources().getFirst().text().length()-1)));assertTrue(new AssistantRequest("석재",List.of(),result.context()).prompt().length()<5000);}
    @Test void invalidQuestionAndScopeDoNotProduceSources(){for(String question:List.of(""," ","가".repeat(257)))assertThrows(IllegalArgumentException.class,()->PhoneRetrieval.find(data(),"quests",question));assertThrows(IllegalArgumentException.class,()->PhoneRetrieval.find(data(),"private","질문"));}
}
