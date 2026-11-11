package io.github.jesterportfolio.hearth.phone;

import io.github.jesterportfolio.hearth.quest.GameInformation;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class AssistantRequestTest {
    @Test void requestKeepsRecentOrderedHistoryAndBoundedUnicodePayload(){
        var turns=new ArrayList<PhoneChat.Turn>();for(int i=0;i<9;i++)turns.add(new PhoneChat.Turn("질문 "+i,"답😀".repeat(500)));
        var request=new AssistantRequest("현재 질문",turns,"가😀".repeat(2000));assertEquals(3,request.history().size());assertEquals("질문 6",request.history().getFirst().question());assertTrue(request.prompt().codePointCount(0,request.prompt().length())<4500);assertFalse(Character.isHighSurrogate(request.context().charAt(request.context().length()-1)));assertThrows(UnsupportedOperationException.class,()->request.history().clear());
    }
    @Test void contextModesOnlyReadTheSelectedPublicProjection(){
        var data=new GameInformation(List.of(new GameInformation.Quest("허용 퀘스트","안내원","진행 중","공개 목표")),List.of(new GameInformation.Place("?","공개 지역","대략적인 공개 범위","미발견",false)),List.of(new GameInformation.Diary("PERSON","본인 기록","허용 요약","허용 상세",false)),List.of("공유 단계 미등록"));
        assertEquals("",PhoneContext.from(data,"none"));assertTrue(PhoneContext.from(data,"quests").contains("허용 퀘스트"));assertFalse(PhoneContext.from(data,"quests").contains("본인 기록"));assertTrue(PhoneContext.from(data,"diary").contains("본인 기록"));assertFalse(PhoneContext.from(data,"map").contains("허용 상세"));assertThrows(IllegalArgumentException.class,()->PhoneContext.from(data,"private"));
    }
    @Test void plainRequestWithoutHistoryPreservesLegacyTextContract(){assertEquals("질문",new AssistantRequest("질문",List.of(),"").prompt());}
    @Test void groundedCitationRulesFollowTheQuestionAndKeepOnlyAllowedNumbers(){
        var request=new AssistantRequest("자료 번호만 답하세요.",List.of(new PhoneChat.Turn("지난 질문","옛 응답")),"공개 자료",2);String prompt=request.prompt();
        assertTrue(prompt.indexOf("[최종 응답 규칙]")>prompt.indexOf("[현재 질문]"));assertTrue(prompt.contains("[자료 1], [자료 2]"));assertFalse(prompt.contains("[자료 3]"));
        assertTrue(new AssistantRequest("암호?",List.of(),"일치 없음",0).prompt().endsWith("근거 번호를 만들지 마세요."));
    }
}
