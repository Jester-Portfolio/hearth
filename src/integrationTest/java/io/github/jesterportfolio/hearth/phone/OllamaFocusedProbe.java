package io.github.jesterportfolio.hearth.phone;

import com.google.gson.*;
import io.github.jesterportfolio.hearth.quest.GameInformation;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 고정 공개 자료 묶음으로 다른 질문·이전 답변 참조·번호/캐시를 확인합니다. 실제 PG/Player 검증은 아닙니다. */
public final class OllamaFocusedProbe {
    public static void main(String[] args)throws Exception{
        if(args.length!=2||!args[0].equals("http://127.0.0.1:11435/api/generate"))throw new IllegalArgumentException("TRIAL_ENDPOINT");
        var names=List.of("판자","석재","실타래","철못");var facts=new ArrayList<GameInformation.Diary>();
        for(int i=0;i<4;i++)facts.add(new GameInformation.Diary("PERSON","공방 "+(i+1),"합성 공개 기록","공방 배경입니다. ".repeat(100)+"\n공방 "+(i+1)+"의 운반품은 "+names.get(i)+"이다.\n"+"나머지 안내입니다. ".repeat(100),false));
        var data=new GameInformation(List.of(),List.of(),facts,List.of());String question="공방별 운반품만 자료 순서대로 한 줄씩 쓰고 각 줄에 해당 [자료 번호]를 붙여줘.";var found=PhoneRetrieval.find(data,"diary",question);var materials=new ArrayList<PhoneGrounding.Material>();
        for(var source:found.sources()){var selected=new GameInformation(List.of(),List.of(),List.of(data.diary().get(source.index())),List.of());materials.add(new PhoneGrounding.Material(new PhoneHelp.Selection("diary","synthetic_"+source.index(),selected,source),1,-1));}
        var base=new PhoneGrounding.Plan("diary",question,false,materials,materials.stream().map(m->m.item().key()).collect(java.util.stream.Collectors.toSet()));var reduced=base.toggle("synthetic_1").toggle("synthetic_3").sent();
        var report=new JsonObject();var rows=new JsonArray();var owner=Executors.newSingleThreadExecutor();var sent=new ArrayList<AssistantRequest>();
        try(var backend=new MeasuredAssistant(new OllamaAssistant(args[0]))){
            backend.prepare().toCompletableFuture().get(85,TimeUnit.SECONDS);
            var service=new AssistantService(){public boolean allowsCache(){return true;}public CompletionStage<String> confirmCached(String answer){return backend.confirmCached(answer);}public CompletionStage<String> ask(String prompt){throw new AssertionError();}public CompletionStage<String> ask(AssistantRequest request){sent.add(request);return backend.ask(request);}};
            var chat=new PhoneChat(service,owner);
            run(rows,"four_sources_initial",owner,chat,base,Map.of(1,"판자",2,"석재",3,"실타래",4,"철못"),List.of());
            run(rows,"same_bundle_new_question",owner,chat,base.followUp("그중 첫 번째와 세 번째 공방의 운반품만 적고 원래 자료 번호를 붙여줘."),Map.of(1,"판자",3,"실타래"),List.of("석재","철못"));
            run(rows,"same_bundle_followup_refers_to_previous_answer",owner,chat,base.followUp("방금 골랐던 두 운반품만 다시 적고 원래 자료 번호를 유지해."),Map.of(1,"판자",3,"실타래"),List.of("석재","철못"));
            run(rows,"same_bundle_other_two_sources",owner,chat,base.followUp("이번에는 두 번째와 네 번째 공방의 운반품만 적고 원래 자료 번호를 붙여줘."),Map.of(2,"석재",4,"철못"),List.of("판자","실타래"));
            run(rows,"reduced_bundle_resets_history_and_renumbers",owner,chat,reduced.followUp("선택한 두 공방의 운반품만 자료 순서대로 적고 자료 번호를 붙여줘."),Map.of(1,"판자",2,"실타래"),List.of("석재","철못"));
            owner.submit(()->chat.includeHistory(false)).get();var independent=reduced.followUp("같은 두 공방의 운반품을 자료 순서대로 다시 적고 자료 번호를 붙여줘.");run(rows,"followup_history_off",owner,chat,independent,Map.of(1,"판자",2,"실타래"),List.of("석재","철못"));long generated=backend.snapshot().usage().getFirst().started();run(rows,"followup_history_off_cached",owner,chat,independent,Map.of(1,"판자",2,"실타래"),List.of("석재","철못"));boolean cache=backend.snapshot().usage().getFirst().started()==generated;
            run(rows,"bundle_does_not_confirm_crafting_state",owner,chat,reduced.followUp("이 자료로 지금 내 게임 제작이 완료됐는지 알 수 있나? 없다면 모른다고 답하고 근거 번호를 붙여줘."),Map.of(),List.of());
            var sizes=sent.stream().map(r->r.history().size()).toList();boolean bounded=sent.stream().allMatch(r->r.purpose()==AssistantRequest.Purpose.PUBLIC_GAME&&r.sources()<=4&&!r.prompt().contains("synthetic_"));boolean stable=sent.size()>=4&&sent.subList(0,4).stream().allMatch(r->r.context().equals(base.result().context()));boolean passed=cache&&bounded&&stable&&sizes.equals(List.of(0,1,2,3,0,0,0));for(var row:rows)if(!row.getAsJsonObject().get("outcome").getAsString().equals("PASS"))passed=false;
            report.addProperty("fixed_bundle_context_unchanged_for_new_questions",stable);report.addProperty("only_selected_public_materials_no_internal_keys",bounded);report.addProperty("history_off_cached_without_generation",cache);report.add("sent_history_sizes",new Gson().toJsonTree(sizes));report.add("backend_usage",new Gson().toJsonTree(backend.snapshot()));report.add("cases",rows);report.addProperty("required_checks_passed",passed);report.addProperty("real_model",true);report.addProperty("actual_minecraft_client",false);report.addProperty("unrestricted_quality_pass",false);owner.submit(chat::close).get();report.addProperty("cache_cleared_on_close",owner.submit(()->chat.cacheStats().entries()==0).get());Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");System.out.println("HEARTH_S5_FOCUSED_PROBE_FINISHED pass="+passed);
        }finally{owner.shutdownNow();owner.awaitTermination(2,TimeUnit.SECONDS);}
    }
    private static void run(JsonArray rows,String name,ExecutorService owner,PhoneChat chat,PhoneGrounding.Plan plan,Map<Integer,String> expected,List<String> forbidden)throws Exception{
        var row=new JsonObject();row.addProperty("name",name);long began=System.nanoTime();
        try{owner.submit(()->chat.submitGroundedPreview(chat.previewGrounded(plan),plan,()->true,()->CompletableFuture.completedFuture(true))).get().toCompletableFuture().get(8,TimeUnit.SECONDS);String answer=owner.submit(()->chat.history().getLast().answer()).get();boolean accepted=forbidden.stream().noneMatch(answer::contains);
            for(var item:expected.entrySet())accepted&=java.util.regex.Pattern.compile("(?m)[^\\n]*(?:"+java.util.regex.Pattern.quote(item.getValue())+"[^\\n]*\\[자료 "+item.getKey()+"\\]|\\[자료 "+item.getKey()+"\\][^\\n]*"+java.util.regex.Pattern.quote(item.getValue())+")").matcher(answer).find();if(expected.isEmpty())accepted&=answer.contains("없")||answer.contains("모르")||answer.contains("알 수");row.addProperty("answer",answer);row.addProperty("outcome",accepted?"PASS":"QUALITY_REVIEW_REQUIRED");}
        catch(ExecutionException failure){var reason=AssistantFailure.reason(failure);if(!Set.of(AssistantFailure.Reason.INVALID_REFERENCE,AssistantFailure.Reason.INVALID_CALCULATION,AssistantFailure.Reason.OUTPUT_LIMIT).contains(reason))throw failure;row.addProperty("outcome",reason.name());}row.addProperty("milliseconds",(System.nanoTime()-began)/1_000_000);rows.add(row);
    }
}
