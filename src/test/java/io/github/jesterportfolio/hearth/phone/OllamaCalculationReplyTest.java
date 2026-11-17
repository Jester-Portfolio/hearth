package io.github.jesterportfolio.hearth.phone;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OllamaCalculationReplyTest {
    private static AssistantRequest calculation(){return AssistantRequest.calculation("작용 팔과 필요한 힘의 관계를 설명해.",List.of(new PhoneChat.Turn("정확값은?","100 N [자료 2]")),ScienceCalculationSnapshot.from(ScienceCalculation.prepare(ScienceCalculation.Topic.LEVER,List.of("400","0.5","2"))));}
    private static String envelope(String response){var value=JsonParser.parseString(OllamaAssistantTest.answer()).getAsJsonObject();value.addProperty("response",response);return value.toString();}
    private static String reply(String answer){var value=new JsonObject();value.addProperty("answer",answer);return value.toString();}
    private static void rejected(AssistantFailure.Reason reason,String response){assertEquals(reason,assertThrows(AssistantFailure.class,()->OllamaProtocol.answer(envelope(response),calculation())).reason(),response);}

    @Test void onlyTypedCalculationRequestsHaveTheBoundedSchemaAndQuestionDirectedInstructions(){
        var request=JsonParser.parseString(OllamaProtocol.request(calculation())).getAsJsonObject();var schema=request.getAsJsonObject("format");assertEquals("object",schema.get("type").getAsString());assertEquals(Set.of("answer"),schema.getAsJsonObject("properties").keySet());assertEquals("answer",schema.getAsJsonArray("required").get(0).getAsString());assertFalse(schema.get("additionalProperties").getAsBoolean());assertEquals(160,schema.getAsJsonObject("properties").getAsJsonObject("answer").get("maxLength").getAsInt());
        String prompt=request.get("prompt").getAsString();assertTrue(prompt.contains(calculation().question()));assertTrue(prompt.contains("원리나 관계를 묻는 후속 질문"));assertTrue(prompt.contains("이전 정확값만 반복하지 마세요"));assertTrue(prompt.contains(schema.toString()));assertFalse(prompt.contains("80자"));
        assertEquals(128,request.getAsJsonObject("options").get("num_predict").getAsInt());assertEquals(4096,request.getAsJsonObject("options").get("num_ctx").getAsInt());
        for(var other:List.of(AssistantRequest.general("질문",List.of()),AssistantRequest.science("질문",List.of(),ScienceReading.LEVER),new AssistantRequest("질문",List.of(),"")))assertFalse(JsonParser.parseString(OllamaProtocol.request(other)).getAsJsonObject().has("format"));assertFalse(JsonParser.parseString(OllamaProtocol.request("문자열 질문")).getAsJsonObject().has("format"));assertFalse(JsonParser.parseString(OllamaProtocol.preparation()).getAsJsonObject().has("format"));
    }
    @Test void validInnerAnswerKeepsItsOwnTextAndMarkersWithoutAddingOrChangingValues(){
        String text="작용 팔이 길면 필요한 힘이 작아집니다. [자료 1] [자료 2]\n\"관계\" 😀";assertEquals(text,OllamaProtocol.answer(envelope(reply(text)),calculation()));
        String json="{\"answer\":\"자료의 원리를 확인하세요. \\u005b자료 2\\u005d\"}";assertEquals("자료의 원리를 확인하세요. [자료 2]",OllamaProtocol.answer(envelope(json),calculation()));
    }
    @Test void innerObjectsMustHaveExactlyOneStringAnswerAndStrictJsonWithNoRepair(){
        for(String value:List.of("{","[]","null","100 N [자료 2]","```json\n"+reply("설명 [자료 2]")+"\n```","{}","{\"answer\":null}","{\"answer\":1}","{\"answer\":[]}","{\"answer\":{}}","{\"answer\":\"\"}","{\"answer\":\"   \"}","{\"answer\":\"설명 [자료 2]\",\"sources\":[2]}","{\"answer\":\"숨길 값\",\"answer\":\"설명 [자료 2]\"}",reply("설명 [자료 2]")+"{}",reply("설명 [자료 2]").replace("설명","\\ud800"),reply("설명 [자료 2]").replace("설명","\\udc00")))rejected(AssistantFailure.Reason.INVALID_RESPONSE,value);
    }
    @Test void decodedCodePointsAreBoundedWithoutSilentlyTruncatingCitationOrSupplementaryUnicode(){
        String marker=" [자료 2]";String limit="😀".repeat(160-marker.length())+marker;assertEquals(limit,OllamaProtocol.answer(envelope(reply(limit)),calculation()));rejected(AssistantFailure.Reason.INVALID_RESPONSE,reply("😀"+limit));
    }
    @Test void typedBodyStillRequiresItsOwnCalculationCitationAndConsistentArithmetic(){
        for(String text:List.of("100 N","원리 [자료 1]","100 N [자료 3]","100 N [자료 2","100 N [자료 2] [자료 0]"))rejected(AssistantFailure.Reason.INVALID_REFERENCE,reply(text));rejected(AssistantFailure.Reason.INVALID_CALCULATION,reply("1+1=3 [자료 2]"));
    }
    @Test void ordinaryRepliesRemainLiteralAndEnvelopeLimitsAreCheckedBeforeDecodingTheBody(){
        String json=reply("설명 [자료 2]");for(var request:List.of(AssistantRequest.general("질문",List.of()),AssistantRequest.science("질문",List.of(),ScienceReading.LEVER),new AssistantRequest("질문",List.of(),"")))assertEquals(json,OllamaProtocol.answer(envelope(json),request));
        var outer=JsonParser.parseString(envelope(json)).getAsJsonObject();outer.addProperty("done_reason","length");assertEquals(AssistantFailure.Reason.OUTPUT_LIMIT,assertThrows(AssistantFailure.class,()->OllamaProtocol.answer(outer.toString(),calculation())).reason());outer.addProperty("done_reason","stop");outer.addProperty("done",false);assertEquals(AssistantFailure.Reason.INVALID_RESPONSE,assertThrows(AssistantFailure.class,()->OllamaProtocol.answer(outer.toString(),calculation())).reason());
    }
    @Test void httpAdapterUsesTheTypedDecoderAndNeverRepairsOrRetriesRejectedBodies()throws Exception{
        try(var fixture=new OllamaAssistantTest.Fixture();var service=fixture.prepared()){
            String answer="팔이 길수록 필요한 힘이 작아집니다. [자료 1] [자료 2]";fixture.response=envelope(reply(answer));assertEquals(answer,service.ask(calculation()).toCompletableFuture().get(2,TimeUnit.SECONDS));assertTrue(fixture.sent.getFirst().has("format"));
            fixture.response=envelope(reply("참고표시 없음"));assertEquals(AssistantFailure.Reason.INVALID_REFERENCE,OllamaAssistantTest.rejected(service.ask(calculation())));assertEquals(2,fixture.generations.get());
            fixture.response=envelope("그냥 본문 [자료 2]");assertEquals(AssistantFailure.Reason.INVALID_RESPONSE,OllamaAssistantTest.rejected(service.ask(calculation())));assertEquals(3,fixture.generations.get());assertFalse(service.allowsCalculationHelp());
            fixture.response=envelope(reply(answer));assertEquals(reply(answer),service.ask("기존 문자열 요청").toCompletableFuture().get(2,TimeUnit.SECONDS));assertFalse(fixture.sent.getLast().has("format"));
        }
    }
    @Test void rejectedHttpReplyCannotEnterChatHistoryOrCacheAndTheProductionGateRemainsClosed()throws Exception{
        try(var fixture=new OllamaAssistantTest.Fixture();var backend=fixture.prepared()){
            // HTTP 계약만 검증하는 합성 수신기. 제품의 실제 모델 품질 허용이 아닙니다.
            var synthetic=new AssistantService(){public boolean allowsCalculationHelp(){return true;}public boolean allowsCache(){return true;}public CompletionStage<String> ask(String text){throw new AssertionError();}public CompletionStage<String> ask(AssistantRequest request){return backend.ask(request);}public CompletionStage<String> confirmCached(String text){return backend.confirmCached(text);}};
            var chat=new PhoneChat(synthetic,Runnable::run);chat.includeHistory(false);var selected=calculation().calculation();fixture.response=envelope(reply("1+1=3 [자료 2]"));assertThrows(ExecutionException.class,()->chat.submitCalculation("보존할 질문",selected,()->true).toCompletableFuture().get(2,TimeUnit.SECONDS));assertEquals("보존할 질문",chat.draft());assertTrue(chat.history().isEmpty());assertEquals(0,chat.cacheStats().entries());assertFalse(chat.busy());assertEquals(1,fixture.generations.get());
            fixture.response=envelope(reply("팔이 길수록 필요한 힘이 작아집니다. [자료 1] [자료 2]"));chat.submitCalculation("같은 질문",selected,()->true).toCompletableFuture().get(2,TimeUnit.SECONDS);chat.submitCalculation("같은 질문",selected,()->true).toCompletableFuture().get(2,TimeUnit.SECONDS);assertEquals(2,fixture.generations.get());assertEquals(2,chat.history().size());assertFalse(chat.history().getFirst().answer().startsWith("{"));
            int versions=fixture.versions.get(),tags=fixture.tags.get(),ps=fixture.ps.get();var product=new PhoneChat(backend,Runnable::run);assertThrows(ExecutionException.class,()->product.submitCalculation("실제 전송 금지",selected,()->true).toCompletableFuture().get(2,TimeUnit.SECONDS));assertEquals(2,fixture.generations.get());assertEquals(versions,fixture.versions.get());assertEquals(tags,fixture.tags.get());assertEquals(ps,fixture.ps.get());assertEquals("실제 전송 금지",product.draft());assertTrue(product.history().isEmpty());chat.close();product.close();
        }
    }
}
