package io.github.jesterportfolio.hearth.phone;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 새 소유 격리 런타임에서만 실행합니다. 명시적 준비/해제와 정상 사용자 제한을 구분합니다. */
public final class OllamaPreparationProbe {
    public static void main(String[] args)throws Exception{
        if(args.length!=2||!args[0].equals("http://127.0.0.1:11435/api/generate"))throw new IllegalArgumentException("TRIAL_ENDPOINT");
        var workspace=io.github.jesterportfolio.hearth.verification.IsolatedPaths.require(Path.of(args[1]).toAbsolutePath().getParent(),"hearth-s5-trial.");
        if(!Files.readString(workspace.resolve(".hearth-s5-isolated")).equals("S5 isolated AI trial only\n"))throw new IllegalArgumentException("TRIAL_MARKER");
        var parent=ProcessHandle.current().parent().orElseThrow();var launch=List.of(parent.info().arguments().orElseThrow());int at=launch.indexOf("--workspace");
        if(at<0||at+1>=launch.size()||!launch.get(at+1).equals(workspace.toString())||launch.stream().noneMatch(a->a.endsWith("s5_ai_trial.py"))||parent.children().noneMatch(p->p.isAlive()&&p.info().command().filter(c->c.equals(workspace.resolve("runtime/bin/ollama").toString())).isPresent()&&List.of(p.info().arguments().orElse(new String[0])).contains("serve")))throw new IllegalStateException("TRIAL_RUNTIME_OWNERSHIP_REQUIRED");
        var report=new JsonObject();var owner=Executors.newSingleThreadExecutor();var cases=new JsonArray();long began=System.nanoTime();
        try(var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();var service=new MeasuredAssistant(new OllamaAssistant(args[0]))){
            var chat=new PhoneChat(service,owner);owner.submit(()->chat.includeHistory(false)).get();String question="하중과 하중 팔이 같을 때 작용 팔이 길어지면 필요한 힘은 어떻게 달라지는가?";var note=ScienceReading.LEVER;
            report.add("before",json(http,"/api/ps",null));check(cases,"fresh_runtime_not_loaded",!OllamaProtocol.loaded(report.get("before").toString()));
            var cold=owner.submit(()->chat.submitScience(question,note,()->true)).get();var reason=rejected(cold);check(cases,"cold_question_not_ready_draft_preserved",reason==AssistantFailure.Reason.NOT_READY&&owner.submit(()->chat.history().isEmpty()&&chat.draft().equals(question)).get());
            check(cases,"cold_question_did_not_load",!OllamaProtocol.loaded(json(http,"/api/ps",null).toString()));
            long prep=System.nanoTime();var preparation=service.prepare().toCompletableFuture();
            check(cases,"question_during_preparation_not_queued",rejected(service.ask("준비 중 질문"))==AssistantFailure.Reason.PREPARING);
            preparation.get(85,TimeUnit.SECONDS);report.addProperty("preparation_milliseconds",(System.nanoTime()-prep)/1_000_000);report.add("prepared",json(http,"/api/ps",null));check(cases,"pinned_model_loaded_with_approved_context",OllamaProtocol.loaded(report.get("prepared").toString()));
            service.prepare().toCompletableFuture().get(8,TimeUnit.SECONDS);check(cases,"already_ready_prepare_succeeds",true);
            long ask=System.nanoTime();owner.submit(()->chat.submitScience(question,note,()->true)).get().toCompletableFuture().get(8,TimeUnit.SECONDS);report.addProperty("question_milliseconds",(System.nanoTime()-ask)/1_000_000);String answer=owner.submit(()->chat.history().getLast().answer()).get();report.addProperty("answer",answer);check(cases,"ready_science_answer",answer.contains("[자료 1]")&&(answer.contains("줄어")||answer.contains("작아")||answer.contains("감소")));
            long started=service.snapshot().usage().getFirst().started();owner.submit(()->chat.submitScience(question,note,()->true)).get().toCompletableFuture().get(8,TimeUnit.SECONDS);check(cases,"prepared_cache_no_new_generation",service.snapshot().usage().getFirst().started()==started&&service.snapshot().usage().getLast().succeeded()>0);
            // 이 프로세스만 사용하는 새 11435 런타임의 모델을 시험 종료 전에 해제합니다. 운영 서버에는 사용하지 않습니다.
            var unload=new JsonObject();unload.addProperty("model",OllamaAssistant.MODEL);unload.addProperty("prompt","");unload.addProperty("stream",false);unload.addProperty("keep_alive",0);json(http,"/api/generate",unload.toString());
            report.add("after_unload",json(http,"/api/ps",null));check(cases,"owned_model_unloaded",!OllamaProtocol.loaded(report.get("after_unload").toString()));
            owner.submit(()->chat.submitScience(question,note,()->true)).get().toCompletableFuture().get(8,TimeUnit.SECONDS);check(cases,"identity_checked_cache_works_without_reloading",service.snapshot().usage().getFirst().started()==started);
            var before=owner.submit(chat::history).get();check(cases,"new_question_after_unload_not_ready",rejected(owner.submit(()->chat.submitScience("다른 원리 질문",note,()->true)).get())==AssistantFailure.Reason.NOT_READY&&owner.submit(()->chat.history().equals(before)&&chat.draft().equals("다른 원리 질문")).get());
            report.add("backend_usage",new Gson().toJsonTree(service.snapshot()));owner.submit(chat::close).get();check(cases,"session_history_cache_clear",owner.submit(()->chat.history().isEmpty()&&chat.cacheStats().entries()==0).get());
            boolean passed=true;for(var row:cases)if(!row.getAsJsonObject().get("passed").getAsBoolean())passed=false;report.addProperty("required_checks_passed",passed);report.addProperty("real_model",true);report.addProperty("actual_minecraft_client",false);report.addProperty("duration_milliseconds",(System.nanoTime()-began)/1_000_000);report.add("checks",cases);
            Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");System.out.println("HEARTH_S5_PREPARATION_PROBE_FINISHED pass="+passed);
        }finally{owner.shutdownNow();owner.awaitTermination(2,TimeUnit.SECONDS);}
    }
    private static void check(JsonArray cases,String name,boolean passed){var row=new JsonObject();row.addProperty("name",name);row.addProperty("passed",passed);cases.add(row);}
    private static AssistantFailure.Reason rejected(CompletionStage<?> work)throws Exception{try{work.toCompletableFuture().get(8,TimeUnit.SECONDS);throw new AssertionError("Expected rejection");}catch(ExecutionException failure){return AssistantFailure.reason(failure);}}
    private static JsonObject json(HttpClient http,String path,String body)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:11435"+path)).timeout(Duration.ofSeconds(6)).header("Content-Type","application/json").method(body==null?"GET":"POST",body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();var response=http.send(request,info->new LocalAssistant.LimitedBody());if(response.statusCode()!=200)throw new IllegalStateException("PROBE_HTTP_STATUS");return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}
