package io.github.jesterportfolio.hearth.phone;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 소유한 격리 Ollama만 사용합니다. Bukkit Player·DB·실제 스토리에 접근하지 않습니다. */
public final class OllamaTrialProbe {
    public static void main(String[] args)throws Exception{
        if(args.length!=2||!args[0].equals("http://127.0.0.1:11435/api/generate"))throw new IllegalArgumentException("TRIAL_ENDPOINT");
        var report=new JsonObject();var cases=new JsonArray();report.add("cases",cases);
        var owner=Executors.newSingleThreadExecutor();
        try(var service=new OllamaAssistant(args[0])){
            var first=new PhoneChat(service,owner);var second=new PhoneChat(service,owner);
            run(cases,"korean_short",owner,first,()->first.submit("한국어로 준비 완료 라는 말만 답해."),"준비 완료",null);
            run(cases,"personal_A",owner,first,()->first.submit("자료 속 상점 이름만 답해.","현재 공개 기록: 이 진행의 상점 이름은 느티상점이다.","diary",()->true),"느티상점","바람상점");
            run(cases,"other_run_B",owner,second,()->second.submit("자료 속 상점 이름만 답해.","현재 공개 기록: 이 진행의 상점 이름은 바람상점이다.","diary",()->true),"바람상점","느티상점");
            run(cases,"unknown_information",owner,second,()->second.submit("자료에 없는 성의 암호를 알려줘.","현재 공개 기록: 상점만 등록되어 있고 성의 암호는 제공되지 않았다.","diary",()->true),null,null);
            var longRequest=new AssistantRequest("제공 자료를 한 문장으로 요약해.",List.of(new PhoneChat.Turn("가".repeat(128),"힣".repeat(400)),new PhoneChat.Turn("나".repeat(128),"힣".repeat(400)),new PhoneChat.Turn("다".repeat(128),"힣".repeat(400))),java.util.stream.IntStream.range(0,2400).collect(StringBuilder::new,(b,i)->b.append((char)(0x4e00+i)),StringBuilder::append).toString());
            long started=System.nanoTime();var context=new JsonObject();context.addProperty("name","context_overflow");try{service.ask(longRequest).toCompletableFuture().get(8,TimeUnit.SECONDS);context.addProperty("outcome","UNEXPECTED_SUCCESS");}catch(Exception error){context.addProperty("outcome",AssistantFailure.reason(unwrap(error)).name());}context.addProperty("milliseconds",(System.nanoTime()-started)/1_000_000);cases.add(context);
            var cancel=new PhoneChat(service,owner);var pending=owner.submit(()->cancel.submit("1부터 1000까지 숫자를 순서대로 가능한 한 많이 적어.").toCompletableFuture()).get();
            Thread.sleep(150);owner.submit(cancel::cancel).get();var cancelled=new JsonObject();cancelled.addProperty("name","cancel_then_next");cancelled.addProperty("cancelled",pending.isCancelled());cancelled.addProperty("history_empty",owner.submit(()->cancel.history().isEmpty()).get());cases.add(cancelled);
            run(cases,"after_cancel",owner,cancel,()->cancel.submit("한국어로 준비 완료 라는 말만 답해."),"준비 완료",null);
            var load=new JsonObject();load.addProperty("name","four_concurrent");var outcomes=new JsonArray();var requests=new ArrayList<CompletableFuture<String>>();for(int i=0;i<4;i++)requests.add(service.ask("한국어로 준비 완료 라는 말만 답해.").toCompletableFuture());
            for(var future:requests)try{future.get(8,TimeUnit.SECONDS);outcomes.add("SUCCESS");}catch(Exception error){outcomes.add(AssistantFailure.reason(unwrap(error)).name());}load.add("outcomes",outcomes);cases.add(load);
            owner.submit(()->{first.close();second.close();cancel.close();}).get();
        }finally{owner.shutdownNow();owner.awaitTermination(2,TimeUnit.SECONDS);}
        report.addProperty("model",OllamaAssistant.MODEL);report.addProperty("version",OllamaAssistant.VERSION);report.addProperty("real_model",true);report.addProperty("actual_minecraft_client",false);
        Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
        System.out.println("HEARTH_S5_PROBE_FINISHED cases="+cases.size());
    }
    private static void run(JsonArray cases,String name,ExecutorService owner,PhoneChat chat,java.util.function.Supplier<CompletionStage<Void>> request,String expected,String forbidden)throws Exception{
        long started=System.nanoTime();var row=new JsonObject();row.addProperty("name",name);
        try{
            owner.submit(request::get).get().toCompletableFuture().get(9,TimeUnit.SECONDS);
            var answer=owner.submit(()->chat.history().getLast().answer()).get();row.addProperty("answer",answer);
            row.addProperty("outcome",(expected==null||answer.contains(expected))&&(forbidden==null||!answer.contains(forbidden))?"PASS":"QUALITY_REVIEW_REQUIRED");
        }catch(Exception failure){row.addProperty("outcome",AssistantFailure.reason(unwrap(failure)).name());row.addProperty("history_size",owner.submit(()->chat.history().size()).get());}
        row.addProperty("milliseconds",(System.nanoTime()-started)/1_000_000);cases.add(row);
    }
    private static Throwable unwrap(Throwable error){while((error instanceof CompletionException||error instanceof ExecutionException)&&error.getCause()!=null)error=error.getCause();return error;}
}
