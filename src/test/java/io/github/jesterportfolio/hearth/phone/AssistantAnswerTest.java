package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import io.github.jesterportfolio.hearth.quest.GameInformation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class AssistantAnswerTest {
    @Test void permitsOnlySuppliedNumbersAndRequiresVisibleCitation(){
        var request=new AssistantRequest("질문",List.of(),"자료",2);assertEquals("답변 [자료 2] [자료1]",AssistantAnswer.present(request,"답변 [자료 2] [자료1]"));
        for(String answer:List.of("근거 없음","답변 [자료 0]","답변 [자료 -1]","답변 [자료 3]","답변 [자료 99999999999999999999]","x".repeat(1201)+"[자료 1]"))assertEquals(AssistantFailure.Reason.INVALID_REFERENCE,assertThrows(AssistantFailure.class,()->AssistantAnswer.present(request,answer)).reason());
    }
    @Test void zeroSourcesCannotAcquireFabricatedReferenceAndLegacyIsPreserved(){
        var none=new AssistantRequest("질문",List.of(),"일치 자료 없음",0);assertEquals("알 수 없습니다.",AssistantAnswer.present(none,"알 수 없습니다."));assertThrows(AssistantFailure.class,()->AssistantAnswer.present(none,"답변 [자료 1]"));assertEquals("[자료 9] 형식 설명",AssistantAnswer.present(new AssistantRequest("일반 질문",List.of(),""),"[자료 9] 형식 설명"));
    }
    @Test void boundedRequestCarriesOnlyOrdinalContractAndKeepsOriginalText(){
        var request=new AssistantRequest("질문 😀",List.of(),"본문 그대로",2);assertTrue(request.prompt().contains("본문 그대로"));assertTrue(request.prompt().contains("1부터 2까지"));assertFalse(new AssistantRequest("질문",List.of(),"자료 없음",0).prompt().contains("[자료 1]"));for(int count:List.of(-2,5))assertThrows(IllegalArgumentException.class,()->new AssistantRequest("질문",List.of(),"자료",count));assertThrows(IllegalArgumentException.class,()->new AssistantRequest("질문",List.of(),"",1));
    }
    @Test void visibleLongAnswerDoesNotLoseOnlyCitation(){
        var request=new AssistantRequest("질문",List.of(),"자료",1);var answer=AssistantAnswer.present(request,"[자료 1] "+"가".repeat(1300));assertTrue(answer.startsWith("[자료 1]"));assertTrue(answer.endsWith("[응답 길이 제한]"));assertThrows(AssistantFailure.class,()->AssistantAnswer.present(request," "));
    }
    @Test void selectedSourcesAreRenumberedAndOnlyPublicTitlesAreShown(){
        var empty=new GameInformation(List.of(),List.of(),List.of(),List.of());var materials=new ArrayList<PhoneGrounding.Material>();
        for(int i=1;i<=3;i++){var source=new PhoneRetrieval.Source("diary",i,"공개 제목 "+i,"본문");var item=new PhoneHelp.Selection("diary","internal_"+i,empty,source);materials.add(new PhoneGrounding.Material(item,i,-1));}
        var plan=new PhoneGrounding.Plan("diary","질문",false,materials,Set.of("internal_1","internal_3"));assertEquals("[자료 2] 공개 제목 3",PhoneReferences.label(plan,"internal_3"));assertThrows(IllegalStateException.class,()->PhoneReferences.label(plan,"internal_2"));assertFalse(PhoneReferences.label(plan,"internal_1").contains("internal"));
    }
}
