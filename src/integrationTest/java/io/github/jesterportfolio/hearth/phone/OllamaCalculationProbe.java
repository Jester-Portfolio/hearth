package io.github.jesterportfolio.hearth.phone;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** 명시적으로 선택한 합성 계산만 설명 자료로 전송합니다. 게임/측정값이나 일반 정답률 수용이 아닙니다. */
public final class OllamaCalculationProbe {
    private record Choice(PhoneCalculator.Entry entry,ScienceCalculationSnapshot snapshot){}
    public static void main(String[] args)throws Exception{
        if(args.length!=2||!args[0].equals("http://127.0.0.1:11435/api/generate"))throw new IllegalArgumentException("TRIAL_ENDPOINT");
        var report=new JsonObject();var rows=new JsonArray();var owner=Executors.newSingleThreadExecutor();var sent=new ArrayList<AssistantRequest>();
        try(var backend=new MeasuredAssistant(new OllamaAssistant(args[0]));var calculator=new PhoneCalculator()){
            backend.prepare().toCompletableFuture().get(85,TimeUnit.SECONDS);
            // 현재 고정 백엔드의 제품 허용 경계를 유지하며 승인된 격리 품질 측정의 전송 자료만 기록합니다.
            var service=new AssistantService(){public boolean allowsCalculationHelp(){return backend.allowsCalculationHelp();}public CompletionStage<String> ask(String text){throw new AssertionError();}public CompletionStage<String> ask(AssistantRequest request){sent.add(request);return backend.ask(request);}public boolean allowsCache(){return true;}public CompletionStage<String> confirmCached(String answer){return backend.confirmCached(answer);}};var chat=new PhoneChat(service,owner);
            var first=owner.submit(()->choice(calculator,ScienceCalculation.Topic.LEVER,"400","0.5","2")).get();run(rows,"lever_100N",owner,chat,calculator,first,"선택한 계산의 필요한 힘 정확값과 단위를 계산 기록에 근거해 한 문장으로 말해.",a->a.contains("100")&&(a.contains("N")||a.contains("뉴턴"))&&!a.contains("200"));
            var second=owner.submit(()->choice(calculator,ScienceCalculation.Topic.LEVER,"400","0.5","1")).get();run(rows,"changed_arm_200N",owner,chat,calculator,second,"선택한 새 계산의 필요한 힘 정확값과 단위를 한 문장으로 말해.",a->a.contains("200")&&(a.contains("N")||a.contains("뉴턴"))&&!a.contains("100"));
            run(rows,"same_calculation_followup",owner,chat,calculator,second,"작용 팔과 필요한 힘의 관계를 이 계산의 원리로 짧게 설명해.",a->a.contains("팔")&&a.contains("힘"));
            var energy=owner.submit(()->choice(calculator,ScienceCalculation.Topic.ENERGY,"10","5","10")).get();run(rows,"energy_500J",owner,chat,calculator,energy,"선택한 계산의 에너지 증가 정확값과 단위를 한 문장으로 말해.",a->a.contains("500")&&(a.contains("J")||a.contains("줄")));
            var series=owner.submit(()->choice(calculator,ScienceCalculation.Topic.SERIES,"2","3")).get();run(rows,"series_5ohm",owner,chat,calculator,series,"선택한 계산의 합성 저항 정확값과 단위를 한 문장으로 말해.",a->a.contains("5")&&(a.contains("Ω")||a.contains("옴")));
            run(rows,"does_not_confirm_game_state",owner,chat,calculator,series,"이 계산으로 지금 내 게임의 제작 완료 여부를 알 수 있나? 제공되지 않았다면 모른다고 답해.",a->a.contains("없")||a.contains("모르"));
            owner.submit(()->chat.includeHistory(false)).get();String independent="이 계산의 정확값과 단위만 다시 말해.";run(rows,"independent_calculation",owner,chat,calculator,series,independent,a->a.contains("5")&&(a.contains("Ω")||a.contains("옴")));
            long started=backend.snapshot().usage().getFirst().started();run(rows,"calculation_cache",owner,chat,calculator,series,independent,a->a.contains("5")&&(a.contains("Ω")||a.contains("옴")));boolean cache=backend.snapshot().usage().getFirst().started()==started;
            var before=owner.submit(chat::history).get();owner.submit(calculator::clear).get();int transmitted=sent.size();boolean stale=false;try{owner.submit(()->chat.submitCalculation(independent,series.snapshot(),()->calculator.current(series.entry()))).get().toCompletableFuture().get(8,TimeUnit.SECONDS);}catch(ExecutionException error){stale=String.valueOf(error.getCause().getMessage()).contains("STALE_AI_CONTEXT");}
            boolean revoked=stale&&sent.size()==transmitted&&owner.submit(()->chat.history().equals(before)&&chat.draft().equals(independent)&&chat.cacheStats().entries()==0).get();
            boolean bounded=sent.stream().allMatch(r->r.purpose()==AssistantRequest.Purpose.SCIENCE_CALCULATION&&r.sources()==2&&r.calculation()!=null&&r.context().equals(r.calculation().context()));
            boolean passed=cache&&revoked&&bounded;for(var row:rows)if(!row.getAsJsonObject().get("outcome").getAsString().equals("PASS"))passed=false;
            report.addProperty("same_calculation_cache_no_new_generation",cache);report.addProperty("cleared_original_rejects_before_backend_and_preserves_draft_history",revoked);report.addProperty("only_approved_calculation_data_and_note_transmitted",bounded);report.add("history_sizes",new Gson().toJsonTree(sent.stream().map(r->r.history().size()).toList()));report.add("backend_usage",new Gson().toJsonTree(backend.snapshot()));report.add("cases",rows);report.addProperty("required_checks_passed",passed);report.addProperty("real_model",true);report.addProperty("actual_minecraft_client",false);report.addProperty("unrestricted_science_quality_pass",false);owner.submit(chat::close).get();Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");System.out.println("HEARTH_S5_CALCULATION_PROBE_FINISHED pass="+passed);
        }finally{owner.shutdownNow();owner.awaitTermination(2,TimeUnit.SECONDS);}
    }
    private static Choice choice(PhoneCalculator calculator,ScienceCalculation.Topic topic,String... inputs){if(!calculator.calculateScience(ScienceCalculation.prepare(topic,List.of(inputs))))throw new AssertionError("SYNTHETIC_CALCULATION_FAILED");var entry=calculator.history().getLast();return new Choice(entry,calculator.select(entry));}
    private static void run(JsonArray rows,String name,ExecutorService owner,PhoneChat chat,PhoneCalculator calculator,Choice choice,String question,Predicate<String> accept)throws Exception{
        var row=new JsonObject();row.addProperty("name",name);row.addProperty("selected_exact_value",choice.snapshot().exact());long began=System.nanoTime();
        try{owner.submit(()->chat.submitCalculation(question,choice.snapshot(),()->calculator.current(choice.entry()))).get().toCompletableFuture().get(8,TimeUnit.SECONDS);String answer=owner.submit(()->chat.history().getLast().answer()).get();row.addProperty("answer",answer);row.addProperty("outcome",accept.test(answer)?"PASS":"QUALITY_REVIEW_REQUIRED");}
        catch(ExecutionException failure){var reason=AssistantFailure.reason(failure);if(!Set.of(AssistantFailure.Reason.INVALID_REFERENCE,AssistantFailure.Reason.INVALID_CALCULATION,AssistantFailure.Reason.OUTPUT_LIMIT).contains(reason))throw failure;row.addProperty("outcome",reason.name());}
        row.addProperty("milliseconds",(System.nanoTime()-began)/1_000_000);rows.add(row);
    }
}
