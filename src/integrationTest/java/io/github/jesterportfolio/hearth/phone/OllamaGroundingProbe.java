package io.github.jesterportfolio.hearth.phone;

import com.google.gson.*;
import io.github.jesterportfolio.hearth.quest.GameInformation;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 합성 공개 자료만 사용하는 실제 모델 확인. PG/Player 수용 판정과 구분합니다. */
public final class OllamaGroundingProbe {
    static PhoneGrounding.Scope scope(String key){return new PhoneGrounding.Scope("diary",List.of(new PhoneGrounding.Identity("diary",key,new GameInformation(List.of(),List.of(),List.of(),List.of()),1,-1)));}
    public static void main(String[] args)throws Exception{
        if(args.length!=2||!args[0].equals("http://127.0.0.1:11435/api/generate"))throw new IllegalArgumentException("TRIAL_ENDPOINT");
        var owner=Executors.newSingleThreadExecutor();var rows=new JsonArray();var report=new JsonObject();
        try(var service=new MeasuredAssistant(new OllamaAssistant(args[0]))){
            var a=new PhoneChat(service,owner);var b=new PhoneChat(service,owner);var same=new PhoneChat(service,owner);var none=new PhoneChat(service,owner);
            String question="상점 이름과 자료 번호만 적어.",context="[자료 1] 공개 상점 기록\n현재 진행의 상점 이름은 느티상점이다.";var identity=scope("private_A");
            for(int i=0;i<8;i++)run(rows,"repeat_"+i,owner,a,()->a.submit(question,context,"diary",()->true,()->CompletableFuture.completedFuture(true),identity),"느티상점",null);
            var cache=owner.submit(a::cacheStats).get();report.addProperty("cache_hits",cache.hits());report.addProperty("generations_after_repeats",service.snapshot().usage().getFirst().started());report.addProperty("cache_identity_checks",service.snapshot().usage().getLast().started());
            long prior=service.snapshot().usage().getFirst().started();run(rows,"same_text_new_session",owner,same,()->same.submit(question,context,"diary",()->true,()->CompletableFuture.completedFuture(true),identity),"느티상점",null);report.addProperty("new_session_generated",service.snapshot().usage().getFirst().started()==prior+1);
            run(rows,"other_run",owner,b,()->b.submit(question,"[자료 1] 공개 상점 기록\n현재 진행의 상점 이름은 바람상점이다.","diary",()->true,()->CompletableFuture.completedFuture(true),scope("private_B")),"바람상점","느티상점");
            run(rows,"no_sources",owner,none,()->none.submit("자료에 없는 성의 암호를 알려줘.","현재 공개 정보에서 일치하는 자료가 없습니다.","diary",()->true,()->CompletableFuture.completedFuture(true),new PhoneGrounding.Scope("diary",List.of())),null,null);
            var live=new AtomicBoolean(true);var validation=new CompletableFuture<Boolean>();var entered=new CountDownLatch(1);var before=owner.submit(a::history).get();
            var pending=owner.submit(()->a.submit(question,context,"diary",live::get,()->{entered.countDown();return validation;},identity)).get();
            if(!entered.await(8,TimeUnit.SECONDS))throw new IllegalStateException("VALIDATION_NOT_ENTERED");live.set(false);validation.complete(true);boolean rejected=false;try{pending.toCompletableFuture().get(8,TimeUnit.SECONDS);}catch(ExecutionException expected){rejected=true;}
            report.addProperty("stale_cached_answer_rejected",rejected&&owner.submit(()->a.history().equals(before)&&a.cacheStats().entries()==0).get());
            var overflow=new AssistantRequest("자료 요약",List.of(new PhoneChat.Turn("가".repeat(128),"힣".repeat(400)),new PhoneChat.Turn("나".repeat(128),"힣".repeat(400)),new PhoneChat.Turn("다".repeat(128),"힣".repeat(400))),java.util.stream.IntStream.range(0,2400).collect(StringBuilder::new,(text,i)->text.append((char)(0x4e00+i)),StringBuilder::append).toString());
            try{service.ask(overflow).toCompletableFuture().get(8,TimeUnit.SECONDS);report.addProperty("overflow","UNEXPECTED_SUCCESS");}catch(ExecutionException error){report.addProperty("overflow",AssistantFailure.reason(error).name());}
            owner.submit(()->{a.close();b.close();same.close();none.close();}).get();report.addProperty("cache_cleared_on_close",owner.submit(()->a.cacheStats().entries()==0&&b.cacheStats().entries()==0&&same.cacheStats().entries()==0).get());
            report.add("backend_usage",new Gson().toJsonTree(service.snapshot()));
            boolean passed=service.snapshot().usage().stream().allMatch(usage->usage.active()==0)&&cache.hits()>0&&report.get("new_session_generated").getAsBoolean()&&report.get("stale_cached_answer_rejected").getAsBoolean()&&report.get("overflow").getAsString().equals("CONTEXT_LIMIT");
            for(var element:rows)if(!element.getAsJsonObject().get("outcome").getAsString().equals("PASS"))passed=false;
            report.addProperty("required_checks_passed",passed);report.addProperty("real_model",true);report.addProperty("actual_minecraft_client",false);report.add("cases",rows);
            Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");System.out.println("HEARTH_S5_GROUNDED_PROBE_FINISHED pass="+passed);
        }finally{owner.shutdownNow();owner.awaitTermination(2,TimeUnit.SECONDS);}
    }
    private static void run(JsonArray rows,String name,ExecutorService owner,PhoneChat chat,java.util.function.Supplier<CompletionStage<Void>> request,String expected,String forbidden)throws Exception{
        var row=new JsonObject();row.addProperty("name",name);long started=System.nanoTime();try{owner.submit(request::get).get().toCompletableFuture().get(9,TimeUnit.SECONDS);String answer=owner.submit(()->chat.history().getLast().answer()).get();row.addProperty("answer",answer);row.addProperty("outcome",(expected==null||answer.contains(expected))&&(forbidden==null||!answer.contains(forbidden))?"PASS":"QUALITY_REVIEW_REQUIRED");}catch(ExecutionException failure){row.addProperty("outcome",AssistantFailure.reason(unwrap(failure)).name());}row.addProperty("milliseconds",(System.nanoTime()-started)/1_000_000);rows.add(row);
    }
    private static Throwable unwrap(Throwable error){while((error instanceof CompletionException||error instanceof ExecutionException)&&error.getCause()!=null)error=error.getCause();return error;}
}
