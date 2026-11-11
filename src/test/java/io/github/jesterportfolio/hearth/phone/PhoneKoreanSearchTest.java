package io.github.jesterportfolio.hearth.phone;

import io.github.jesterportfolio.hearth.quest.GameInformation;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

final class PhoneKoreanSearchTest {
    private record Case(String mode,String question,List<Integer> expected){}
    static GameInformation data(){return new GameInformation(
        List.of(new GameInformation.Quest("석재 조사","안내원","진행 중","표본을 모으세요"),new GameInformation.Quest("목재 운반","목수","대기","판자를 모으세요"),new GameInformation.Quest("IRON 광석","대장장이","대기","가열 전 분류"),new GameInformation.Quest("Irony 이야기","주민","대기","이야기를 들으세요")),
        List.of(new GameInformation.Place("창고","항구","64 부근","발견",false),new GameInformation.Place("농장","평원","640 부근","발견",false)),
        List.of(new GameInformation.Diary("PERSON","제련 기록","합성 요약","node_12 공개 정보",false),new GameInformation.Diary("PERSON","재배 기록","합성 요약","node_123 공개 정보",false)),List.of("공동체 다리 완공","공동체 밭 개방"));}
    @TestFactory Stream<DynamicTest> koreanParticleUnicodeAndIdentifierQualityCases(){
        return Stream.of(
            new Case("quests","석재는 어디에서 얻어?",List.of(0)),new Case("quests","목재를 알려주세요",List.of(1)),new Case("quests","석재로는 무엇을 할까",List.of(0)),
            new Case("quests","석재와는",List.of(0)),new Case("quests","목재부터",List.of(1)),new Case("quests","목재처럼",List.of(1)),
            new Case("map","창고에는 무엇이 있어?",List.of(0)),new Case("map","농장까지 어떻게 가나요?",List.of(1)),new Case("map","창고에서부터",List.of(0)),
            new Case("diary","제련의 기록을 알려줘",List.of(0,1)),new Case("story","다리는 어디에 있어?",List.of(0)),new Case("story","밭 개방을 알려주세요",List.of(1)),
            new Case("quests","ＩＲＯＮ",List.of(2)),new Case("quests","iron",List.of(2)),new Case("quests",Normalizer.normalize("석재",Normalizer.Form.NFD),List.of(0)),
            new Case("map","64",List.of(0)),new Case("diary","node_12",List.of(0)),new Case("diary","NODE_123",List.of(1)),
            new Case("quests","어디에서 알려주세요",List.of()),new Case("none","석재는",List.of()),new Case("map","석재는",List.of()),new Case("quests","미공개 왕궁은",List.of())
        ).map(c->DynamicTest.dynamicTest(c.mode()+":"+c.question(),()->{var info=data();String before=info.toString();var result=PhoneRetrieval.find(info,c.mode(),c.question());assertEquals(c.expected(),result.sources().stream().map(PhoneRetrieval.Source::index).toList());assertEquals(before,info.toString());assertTrue(result.context().codePointCount(0,result.context().length())<=2400);}));
    }
    @Test void boundedWholeTokensNoFragmentingLongWordsAndDuplicateParticlesDoNotBoostRanking(){
        assertTrue(PhoneSearchTerms.parse("가".repeat(33)).isEmpty());assertTrue(PhoneSearchTerms.parse("a".repeat(33)).isEmpty());assertEquals(16,PhoneSearchTerms.parse(String.join(" ",java.util.stream.IntStream.range(0,30).mapToObj(i->"token"+i).toList())).size());
        assertEquals(1,PhoneSearchTerms.parse("석재 석재는 석재를 석재까지").size());assertEquals(PhoneRetrieval.find(data(),"quests","석재").sources(),PhoneRetrieval.find(data(),"quests","석재 석재는 석재를").sources());
    }
    @Test void literalTitleRanksAboveParticleFallbackAndTiesKeepSnapshotIndices(){
        var info=new GameInformation(List.of(new GameInformation.Quest("창고는 어디","NPC","대기","자료"),new GameInformation.Quest("창고 안내","NPC","대기","자료"),new GameInformation.Quest("창고 시설","NPC","대기","자료")),List.of(),List.of(),List.of());
        assertEquals(List.of(0,1,2),PhoneRetrieval.find(info,"quests","창고는").sources().stream().map(PhoneRetrieval.Source::index).toList());
    }
}
