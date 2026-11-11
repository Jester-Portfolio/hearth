package io.github.jesterportfolio.hearth.phone;

import com.google.gson.*;
import io.github.jesterportfolio.hearth.quest.GameInformation;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** 기본 과학 계산·후속 대화와 게임 정보 부재의 실제 관측. 일반 정답률/Player 수용의 대체가 아닙니다. */
public final class OllamaKnowledgeProbe {
    public static void main(String[] args)throws Exception{
        if(args.length!=2||!args[0].equals("http://127.0.0.1:11435/api/generate"))throw new IllegalArgumentException("TRIAL_ENDPOINT");
        var rows=new JsonArray();var report=new JsonObject();var owner=Executors.newSingleThreadExecutor();var requests=new ArrayList<AssistantRequest>();
        try(var backend=new MeasuredAssistant(new OllamaAssistant(args[0]))){
            var service=new AssistantService(){public boolean allowsCache(){return true;}public CompletionStage<String> confirmCached(String answer){return backend.confirmCached(answer);}public CompletionStage<String> ask(String text){throw new AssertionError();}public CompletionStage<String> ask(AssistantRequest request){requests.add(request);return backend.ask(request);}};
            var chat=new PhoneChat(service,owner);String mechanics="https://openstax.org/books/physics/pages/9-3-simple-machines";
            run(rows,"lever_100N",owner,chat,()->chat.submit("마찰 없는 지렛대의 작용 팔 2 m, 하중 팔 0.5 m, 하중 400 N이다. 필요한 힘을 식과 N 단위로 짧게 답해."),a->a.matches("(?s).*100\\s*(N|뉴턴).*"),mechanics);
            run(rows,"lever_followup_200N",owner,chat,()->chat.submit("다른 조건은 같고 작용 팔만 1 m로 줄이면 필요한 힘은? N 단위로 짧게 답해."),a->a.matches("(?s).*200\\s*(N|뉴턴).*"),mechanics);
            run(rows,"potential_energy_500J",owner,chat,()->chat.submit("질량 10 kg 물을 5 m 높였을 때 증가한 중력 위치 에너지는? g=10 m/s^2로 계산하고 식과 J 단위만 답해."),a->a.matches("(?s).*500\\s*(J|줄).*"),"https://openstax.org/books/college-physics/pages/7-3-gravitational-potential-energy");
            run(rows,"series_resistance_5ohm",owner,chat,()->chat.submit("이상적인 저항 2옴과 3옴을 직렬로 연결했다. 합성 저항을 옴 단위로 짧게 답해."),a->a.matches("(?s).*5\\s*(Ω|옴).*"),"https://openstax.org/books/college-physics/pages/21-1-resistors-in-series-and-parallel");
            run(rows,"pulley_work_conserved",owner,chat,()->chat.submit("마찰 없는 이상적인 도르래로 같은 물체를 같은 높이까지 올린다. 필요한 일(에너지)이 줄어드는가? 예/아니오와 한 문장으로 답해."),a->(a.contains("아니")||a.contains("줄어들지"))&&(a.contains("거리")||a.contains("보존")||a.contains("같")),mechanics);
            var scope=new PhoneGrounding.Scope("diary",List.of(new PhoneGrounding.Identity("diary","synthetic_game_record",new GameInformation(List.of(),List.of(),List.of(),List.of("합성 기록")),1,-1)));
            run(rows,"public_game_stays_grounded",owner,chat,()->chat.submit("공개 창고 이름과 자료 번호만 답해.","[자료 1] 합성 공개 창고 기록\n이 진행의 공개 창고 이름은 잣나무창고이다.","diary",()->true,()->CompletableFuture.completedFuture(true),scope),a->a.contains("잣나무창고")&&a.contains("[자료 1]"),null);
            String unknown="일반 질문: 지금 내 게임의 창고 이름은? 모르면 '게임 정보 없음'만 답해.";
            run(rows,"general_does_not_know_game_state",owner,chat,()->chat.submit(unknown),a->!a.contains("잣나무")&&a.replace(" ","").contains("게임정보없음"),null);
            owner.submit(()->chat.includeHistory(false)).get();run(rows,"independent_general_question",owner,chat,()->chat.submit(unknown),a->!a.contains("잣나무")&&a.replace(" ","").contains("게임정보없음"),null);
            long generated=backend.snapshot().usage().getFirst().started();run(rows,"general_cache",owner,chat,()->chat.submit(unknown),a->!a.contains("잣나무")&&a.replace(" ","").contains("게임정보없음"),null);
            report.addProperty("general_cache_reuses_without_generation",backend.snapshot().usage().getFirst().started()==generated&&backend.snapshot().usage().getLast().succeeded()>0);
            boolean boundary=requests.stream().filter(r->r.purpose()==AssistantRequest.Purpose.GENERAL_KNOWLEDGE).allMatch(r->r.context().isEmpty()&&r.sources()==0&&r.history().stream().noneMatch(t->t.answer().contains("잣나무")));
            report.addProperty("general_requests_no_game_context_references_or_game_history",boundary);report.add("purposes",new Gson().toJsonTree(requests.stream().map(AssistantRequest::purpose).toList()));report.add("history_sizes",new Gson().toJsonTree(requests.stream().map(r->r.history().size()).toList()));report.add("backend_usage",new Gson().toJsonTree(backend.snapshot()));
            owner.submit(chat::close).get();report.addProperty("cache_cleared_on_close",owner.submit(()->chat.cacheStats().entries()==0).get());boolean passed=boundary&&report.get("general_cache_reuses_without_generation").getAsBoolean();for(var row:rows)if(!row.getAsJsonObject().get("outcome").getAsString().equals("PASS"))passed=false;
            report.addProperty("required_checks_passed",passed);report.addProperty("real_model",true);report.addProperty("actual_minecraft_client",false);report.add("cases",rows);Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");System.out.println("HEARTH_S5_KNOWLEDGE_PROBE_FINISHED pass="+passed);
        }finally{owner.shutdownNow();owner.awaitTermination(2,TimeUnit.SECONDS);}
    }
    private static void run(JsonArray rows,String name,ExecutorService owner,PhoneChat chat,java.util.function.Supplier<CompletionStage<Void>> ask,Predicate<String> accept,String reference)throws Exception{
        var row=new JsonObject();row.addProperty("name",name);if(reference!=null)row.addProperty("reference",reference);long began=System.nanoTime();try{owner.submit(ask::get).get().toCompletableFuture().get(9,TimeUnit.SECONDS);String answer=owner.submit(()->chat.history().getLast().answer()).get();row.addProperty("answer",answer);row.addProperty("outcome",accept.test(answer)?"PASS":"QUALITY_REVIEW_REQUIRED");}catch(ExecutionException error){row.addProperty("outcome",AssistantFailure.reason(error).name());}row.addProperty("milliseconds",(System.nanoTime()-began)/1_000_000);rows.add(row);
    }
}
