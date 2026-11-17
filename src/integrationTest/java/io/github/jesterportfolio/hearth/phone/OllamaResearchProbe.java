package io.github.jesterportfolio.hearth.phone;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** 고정 과학 노트의 자료 기반 원리·후속 대화·범위 부재 관측. 일반 과학 정답률 시험은 아닙니다. */
public final class OllamaResearchProbe {
    public static void main(String[] args)throws Exception{
        if(args.length!=2||!args[0].equals("http://127.0.0.1:11435/api/generate"))throw new IllegalArgumentException("TRIAL_ENDPOINT");
        var rows=new JsonArray();var report=new JsonObject();var owner=Executors.newSingleThreadExecutor();var requests=new ArrayList<AssistantRequest>();
        try(var backend=new MeasuredAssistant(new OllamaAssistant(args[0]))){
            var service=new AssistantService(){public boolean allowsCache(){return true;}public CompletionStage<String> confirmCached(String answer){return backend.confirmCached(answer);}public CompletionStage<String> ask(String text){throw new AssertionError();}public CompletionStage<String> ask(AssistantRequest request){requests.add(request);return backend.ask(request);}};
            var chat=new PhoneChat(service,owner);
            run(rows,"lever_principle",owner,chat,ScienceReading.LEVER,"하중과 하중 팔이 같을 때 작용 팔을 길게 하면 필요한 힘은 어떻게 달라지는가?",a->a.contains("작아")||a.contains("줄어")||a.contains("감소"));
            run(rows,"lever_followup",owner,chat,ScienceReading.LEVER,"다른 조건은 같고 작용 팔만 절반으로 바꾸면 필요한 힘은 어떻게 달라지는가?",a->a.replace(" ","").contains("두배")||a.replace(" ","").contains("2배"));
            run(rows,"energy_inputs_units",owner,chat,ScienceReading.ENERGY,"위치 에너지 증가를 계산하는 데 필요한 세 수치와 결과 단위를 짧게 답해.",a->a.contains("질량")&&a.contains("중력")&&a.contains("높이")&&(a.contains("J")||a.contains("줄")));
            run(rows,"series_principle",owner,chat,ScienceReading.SERIES,"직렬로 연결한 두 저항의 합성 저항은 어떻게 구하는가?",a->a.contains("더하")||a.contains("더한")||a.contains("합산")||a.contains("합계")||a.contains("합한")||a.contains("합입니다")||a.contains("+"));
            run(rows,"game_state_absent",owner,chat,ScienceReading.SERIES,"지금 내 게임에서 발견한 창고 이름은? 이 노트에 없으면 자료에 없어 알 수 없다고 답해.",a->(a.contains("없")||a.contains("모르"))&&!a.contains("잣나무"));
            owner.submit(()->chat.includeHistory(false)).get();String independent="이 과학 노트가 내 게임의 제작 해금 여부를 알려 주는가?";
            run(rows,"independent_scope_question",owner,chat,ScienceReading.SERIES,independent,a->a.contains("없")||a.contains("알려 주지")||a.contains("알려주지")||a.contains("모르"));
            long started=backend.snapshot().usage().getFirst().started();run(rows,"research_cache",owner,chat,ScienceReading.SERIES,independent,a->a.contains("없")||a.contains("알려 주지")||a.contains("알려주지")||a.contains("모르"));
            boolean cache=backend.snapshot().usage().getFirst().started()==started&&backend.snapshot().usage().getLast().succeeded()>0;
            boolean boundary=requests.stream().allMatch(r->r.purpose()==AssistantRequest.Purpose.SCIENCE_REFERENCE&&r.sources()==1&&ScienceReading.approvedContext(r.context()));
            report.addProperty("all_requests_use_approved_science_context",boundary);report.addProperty("cache_reuses_after_backend_identity_check",cache);report.add("history_sizes",new Gson().toJsonTree(requests.stream().map(r->r.history().size()).toList()));report.add("requests",new Gson().toJsonTree(requests.stream().map(r->Map.of("purpose",r.purpose(),"sources",r.sources(),"context_chars",r.context().codePointCount(0,r.context().length()))).toList()));report.add("backend_usage",new Gson().toJsonTree(backend.snapshot()));
            owner.submit(chat::close).get();boolean cleared=owner.submit(()->chat.history().isEmpty()&&chat.cacheStats().entries()==0).get();report.addProperty("closed_session_clears_history_cache",cleared);
            boolean passed=boundary&&cache&&cleared;for(var row:rows)if(!row.getAsJsonObject().get("outcome").getAsString().equals("PASS"))passed=false;
            report.addProperty("required_checks_passed",passed);report.addProperty("real_model",true);report.addProperty("actual_minecraft_client",false);report.addProperty("unrestricted_science_quality_pass",false);report.add("cases",rows);Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");System.out.println("HEARTH_S5_RESEARCH_PROBE_FINISHED pass="+passed);
        }finally{owner.shutdownNow();owner.awaitTermination(2,TimeUnit.SECONDS);}
    }
    private static void run(JsonArray rows,String name,ExecutorService owner,PhoneChat chat,ScienceReading reading,String question,Predicate<String> accept)throws Exception{
        var row=new JsonObject();row.addProperty("name",name);row.addProperty("note",reading.name());row.addProperty("source",reading.url());long began=System.nanoTime();
        try{owner.submit(()->chat.submitScience(question,reading,()->true)).get().toCompletableFuture().get(9,TimeUnit.SECONDS);String answer=owner.submit(()->chat.history().getLast().answer()).get();row.addProperty("answer",answer);row.addProperty("outcome",answer.contains("[자료 1]")&&accept.test(answer)?"PASS":"QUALITY_REVIEW_REQUIRED");}
        catch(ExecutionException error){row.addProperty("outcome",AssistantFailure.reason(error).name());}row.addProperty("milliseconds",(System.nanoTime()-began)/1_000_000);rows.add(row);
    }
}
