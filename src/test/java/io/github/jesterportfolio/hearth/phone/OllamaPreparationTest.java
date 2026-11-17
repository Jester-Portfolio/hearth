package io.github.jesterportfolio.hearth.phone;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OllamaPreparationTest {
    @Test void loadedWeightsAloneDoNotAdmitAQuestionWithoutConfirmedWarmup()throws Exception{
        try(var f=new OllamaAssistantTest.Fixture();var service=new OllamaAssistant(f.endpoint())){
            assertTrue(f.loaded);assertEquals(AssistantFailure.Reason.NOT_READY,OllamaAssistantTest.rejected(service.ask("첫 질문")));assertEquals(0,f.generations.get());assertEquals(0,f.preparations.get());
            service.prepare().toCompletableFuture().get(2,TimeUnit.SECONDS);assertEquals(1,f.preparations.get());service.ask("준비 후 질문").toCompletableFuture().get(2,TimeUnit.SECONDS);assertEquals(1,f.generations.get());
        }
    }
    @Test void coldQuestionsDoNotStartLoadingAndKeepTheDraftWithoutPoisoningHistory()throws Exception{
        try(var f=new OllamaAssistantTest.Fixture();var service=new OllamaAssistant(f.endpoint())){
            f.loaded=false;var chat=new PhoneChat(service,Runnable::run);var failure=assertThrows(ExecutionException.class,()->chat.submit("과학 질문").toCompletableFuture().get(2,TimeUnit.SECONDS));assertEquals(AssistantFailure.Reason.NOT_READY,AssistantFailure.reason(failure));
            assertEquals(0,f.preparations.get());assertEquals(0,f.generations.get());assertTrue(chat.history().isEmpty());assertEquals("과학 질문",chat.draft());assertTrue(chat.error().contains("준비"));
        }
    }
    @Test void explicitPreparationOnlyLoadsThePinnedModelAndAlreadyReadyDoesNotReload()throws Exception{
        try(var f=new OllamaAssistantTest.Fixture();var service=new OllamaAssistant(f.endpoint())){
            f.loaded=false;assertEquals("PREPARED",service.prepare().toCompletableFuture().get(2,TimeUnit.SECONDS));assertEquals(1,f.preparations.get());assertEquals(0,f.generations.get());var sent=f.sent.getFirst();
            assertTrue(sent.get("prompt").getAsString().contains("Hearth AI 준비용 고정 요청"));assertTrue(sent.get("prompt").getAsString().contains(ScienceReading.LEVER.context()));assertEquals(OllamaAssistant.MODEL,sent.get("model").getAsString());assertEquals("60s",sent.get("keep_alive").getAsString());assertEquals(4096,sent.getAsJsonObject("options").get("num_ctx").getAsInt());assertEquals(128,sent.getAsJsonObject("options").get("num_predict").getAsInt());assertEquals(2,sent.getAsJsonObject("options").get("num_thread").getAsInt());
            service.prepare().toCompletableFuture().get(2,TimeUnit.SECONDS);assertEquals(1,f.preparations.get());assertEquals("한국어 응답",service.ask("준비 후 질문").toCompletableFuture().get(2,TimeUnit.SECONDS));assertEquals(1,f.generations.get());
        }
    }
    @Test void unloadingRequiresExplicitPreparationAgainButIdentityCheckedCacheCanStillBeUsed()throws Exception{
        try(var f=new OllamaAssistantTest.Fixture();var service=new OllamaAssistant(f.endpoint())){
            service.prepare().toCompletableFuture().get(2,TimeUnit.SECONDS);f.reset();service.ask("질문").toCompletableFuture().get(2,TimeUnit.SECONDS);f.loaded=false;
            assertEquals(AssistantFailure.Reason.NOT_READY,OllamaAssistantTest.rejected(service.ask("다음 질문")));assertEquals(1,f.generations.get());assertEquals(0,f.preparations.get());
            assertEquals("검증할 캐시",service.confirmCached("검증할 캐시").toCompletableFuture().get(2,TimeUnit.SECONDS));assertEquals(0,f.preparations.get());
        }
    }
    @Test void loadedDigestMismatchAndWrongContextCannotAdmitAQuestion()throws Exception{
        try(var f=new OllamaAssistantTest.Fixture();var service=new OllamaAssistant(f.endpoint())){
            f.loadedDigest="0".repeat(64);assertEquals(AssistantFailure.Reason.MODEL_MISMATCH,OllamaAssistantTest.rejected(service.ask("질문")));assertEquals(AssistantFailure.Reason.MODEL_MISMATCH,OllamaAssistantTest.rejected(service.prepare()));assertEquals(0,f.preparations.get());
            f.loadedDigest=OllamaAssistant.MODEL_DIGEST;f.context=8192;assertEquals(AssistantFailure.Reason.MODEL_MISMATCH,OllamaAssistantTest.rejected(service.ask("질문")));assertEquals(AssistantFailure.Reason.MODEL_MISMATCH,OllamaAssistantTest.rejected(service.prepare()));assertEquals(0,f.preparations.get());
        }
    }
    @Test void preparationExcludesQuestionsCacheAndAnotherPreparationAndCancellationReleasesIt()throws Exception{
        try(var f=new OllamaAssistantTest.Fixture();var service=new OllamaAssistant(f.endpoint())){
            f.loaded=false;f.block="/api/generate";var pending=service.prepare().toCompletableFuture();assertTrue(f.entered.await(2,TimeUnit.SECONDS));int reads=f.versions.get();
            assertEquals(AssistantFailure.Reason.PREPARING,OllamaAssistantTest.rejected(service.ask("질문")));assertEquals(AssistantFailure.Reason.PREPARING,OllamaAssistantTest.rejected(service.confirmCached("캐시")));assertEquals(AssistantFailure.Reason.PREPARING,OllamaAssistantTest.rejected(service.prepare()));assertEquals(reads,f.versions.get());assertEquals(1,f.preparations.get());assertEquals(0,f.generations.get());
            assertTrue(pending.cancel(true));f.release.countDown();service.prepare().toCompletableFuture().get(2,TimeUnit.SECONDS);assertEquals(2,f.preparations.get());
        }
    }
    @Test void activeQuestionBlocksPreparationAndCloseCancelsOwnedPreparation()throws Exception{
        try(var f=new OllamaAssistantTest.Fixture();var service=new OllamaAssistant(f.endpoint())){
            service.prepare().toCompletableFuture().get(2,TimeUnit.SECONDS);f.reset();f.block="/api/generate";var question=service.ask("질문").toCompletableFuture();assertTrue(f.entered.await(2,TimeUnit.SECONDS));assertEquals(AssistantFailure.Reason.BUSY,OllamaAssistantTest.rejected(service.prepare()));assertEquals(0,f.preparations.get());question.cancel(true);f.release.countDown();
        }
        try(var f=new OllamaAssistantTest.Fixture();var service=new OllamaAssistant(f.endpoint())){
            f.loaded=false;f.block="/api/generate";var pending=service.prepare();assertTrue(f.entered.await(2,TimeUnit.SECONDS));service.close();assertEquals(AssistantFailure.Reason.CLOSED,OllamaAssistantTest.rejected(pending));assertEquals(AssistantFailure.Reason.CLOSED,OllamaAssistantTest.rejected(service.prepare()));assertEquals(1,f.preparations.get());
        }
    }
    @Test void preparationTimeoutDoesNotExtendQuestionTimeoutAndUnconfirmedLoadIsNotReady()throws Exception{
        try(var f=new OllamaAssistantTest.Fixture();var service=new OllamaAssistant(f.endpoint(),Duration.ofMillis(200),Duration.ofMillis(500))){f.loaded=false;f.block="/api/generate";assertEquals(AssistantFailure.Reason.TIMEOUT,OllamaAssistantTest.rejected(service.prepare()));assertEquals(1,f.preparations.get());}
        try(var f=new OllamaAssistantTest.Fixture();var service=new OllamaAssistant(f.endpoint())){f.loaded=false;f.loadCompletes=false;assertEquals(AssistantFailure.Reason.NOT_READY,OllamaAssistantTest.rejected(service.prepare()));assertEquals(1,f.preparations.get());}
        assertThrows(IllegalArgumentException.class,()->new OllamaAssistant("http://127.0.0.1:1/api/generate",Duration.ofSeconds(7)));assertThrows(IllegalArgumentException.class,()->new OllamaAssistant("http://127.0.0.1:1/api/generate",Duration.ofSeconds(6),Duration.ofSeconds(91)));
    }
    @Test void versionChangedDuringTheLongLoadCannotPublishPreparationSuccess()throws Exception{
        try(var f=new OllamaAssistantTest.Fixture();var service=new OllamaAssistant(f.endpoint())){f.loaded=false;f.block="/api/generate";var work=service.prepare();assertTrue(f.entered.await(2,TimeUnit.SECONDS));f.version="wrong";f.release.countDown();assertEquals(AssistantFailure.Reason.MODEL_MISMATCH,OllamaAssistantTest.rejected(work));}
    }
    @Test void loadResponseAndRunningModelParsersRejectUntrustedShapes(){
        String warm=OllamaAssistantTest.answer().replace("한국어 응답","공개 원리 설명 [자료 1]");OllamaProtocol.prepared(warm);
        for(String bad:List.of(warm.replace("stop","load"),warm.replace("공개 원리 설명 [자료 1]",""),warm.replace("true","false"),warm.replace("[자료 1]","[자료 2]"),warm.replace("[자료 1]","")))assertThrows(AssistantFailure.class,()->OllamaProtocol.prepared(bad));
        assertFalse(OllamaProtocol.loaded("{\"models\":[]}"));for(String bad:List.of("{}","{\"models\":null}","{\"models\":[null]}","{\"models\":[],\"models\":[]}"))assertThrows(AssistantFailure.class,()->OllamaProtocol.loaded(bad));
        for(String incompatible:List.of("{\"models\":[{\"name\":\"other-model\"}]}","{\"models\":[{},{}]}"))assertEquals(AssistantFailure.Reason.MODEL_MISMATCH,assertThrows(AssistantFailure.class,()->OllamaProtocol.loaded(incompatible)).reason());
    }
    @Test void measuredPreparationHasItsOwnCountersAndCannotBecomeAChatAnswer()throws Exception{
        try(var f=new OllamaAssistantTest.Fixture();var service=new MeasuredAssistant(new OllamaAssistant(f.endpoint()))){f.loaded=false;service.prepare().toCompletableFuture().get(2,TimeUnit.SECONDS);var preparation=service.snapshot().usage().stream().filter(r->r.kind()==MeasuredAssistant.Kind.PREPARATION).findFirst().orElseThrow();assertEquals(1,preparation.succeeded());assertEquals(0,preparation.active());assertEquals(0,service.snapshot().usage().getFirst().started());assertEquals(0,service.snapshot().usage().getLast().started());assertEquals(0,f.generations.get());}
    }
}
