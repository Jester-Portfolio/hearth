package io.github.jesterportfolio.hearth.phone;

import com.google.gson.*;
import io.github.jesterportfolio.hearth.quest.GameInformation;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 긴 합성 공개 기록/복수 자료/명시적 대화 선택을 실제 모델에 연결합니다. 실제 PG/Player 판정은 아닙니다. */
public final class OllamaFocusedProbe {
    public static void main(String[] args)throws Exception{
        if(args.length!=2||!args[0].equals("http://127.0.0.1:11435/api/generate"))throw new IllegalArgumentException("TRIAL_ENDPOINT");
        var names=List.of("판자","석재","실타래","철못");var facts=new ArrayList<GameInformation.Diary>();
        for(int i=0;i<4;i++)facts.add(new GameInformation.Diary("PERSON","공방 "+(i+1),"합성 공개 기록","공방 배경입니다. ".repeat(100)+"\n공방 "+(i+1)+"의 운반품은 "+names.get(i)+"이다.\n"+"나머지 안내입니다. ".repeat(100),false));
        var data=new GameInformation(List.of(),List.of(),facts,List.of());String question="공방별 운반품만 자료 순서대로 한 줄씩 쓰고 각 줄에 해당 [자료 번호]를 붙여줘.";
        var found=PhoneRetrieval.find(data,"diary",question);var full=PhoneRetrieval.present(found.sources());var reduced=PhoneRetrieval.present(List.of(found.sources().get(0),found.sources().get(2)));
        var report=new JsonObject();var rows=new JsonArray();var owner=Executors.newSingleThreadExecutor();var sentHistory=new ArrayList<Integer>();
        try(var backend=new MeasuredAssistant(new OllamaAssistant(args[0]))){
            var service=new AssistantService(){
                public boolean allowsCache(){return true;}public CompletionStage<String> confirmCached(String answer){return backend.confirmCached(answer);}
                public CompletionStage<String> ask(String prompt){throw new AssertionError();}
                public CompletionStage<String> ask(AssistantRequest request){sentHistory.add(request.history().size());return backend.ask(request);}
            };
            var chat=new PhoneChat(service,owner);var scope=scope(data,List.of(0,1,2,3));
            for(int i=0;i<4;i++)run(rows,"four_sources_history_"+i,owner,chat,question,full,scope,names,List.of());
            run(rows,"two_sources_excluded_and_renumbered",owner,chat,question,reduced,scope(data,List.of(0,2)),List.of("판자","실타래"),List.of("석재","철못"));
            owner.submit(()->chat.includeHistory(false)).get();run(rows,"history_off",owner,chat,question,full,scope,names,List.of());
            long generated=backend.snapshot().usage().getFirst().started();run(rows,"history_off_cached",owner,chat,question,full,scope,names,List.of());
            report.addProperty("history_off_cached_without_generation",backend.snapshot().usage().getFirst().started()==generated&&backend.snapshot().usage().getLast().succeeded()>0);
            report.addProperty("full_context_codepoints",full.context().codePointCount(0,full.context().length()));report.add("sent_history_sizes",new Gson().toJsonTree(sentHistory));report.add("backend_usage",new Gson().toJsonTree(backend.snapshot()));
            owner.submit(chat::close).get();report.addProperty("cache_cleared_on_close",owner.submit(()->chat.cacheStats().entries()==0).get());
            boolean passed=found.sources().size()==4&&report.get("history_off_cached_without_generation").getAsBoolean()&&sentHistory.equals(List.of(0,1,2,3,0,0));
            for(var row:rows)if(!row.getAsJsonObject().get("outcome").getAsString().equals("PASS"))passed=false;
            report.addProperty("required_checks_passed",passed);report.addProperty("real_model",true);report.addProperty("actual_minecraft_client",false);report.add("cases",rows);
            Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");System.out.println("HEARTH_S5_FOCUSED_PROBE_FINISHED pass="+passed);
        }finally{owner.shutdownNow();owner.awaitTermination(2,TimeUnit.SECONDS);}
    }
    private static PhoneGrounding.Scope scope(GameInformation data,List<Integer> indices){return new PhoneGrounding.Scope("diary",indices.stream().map(i->new PhoneGrounding.Identity("diary","synthetic_"+i,new GameInformation(List.of(),List.of(),List.of(data.diary().get(i)),List.of()),1,-1)).toList());}
    private static void run(JsonArray rows,String name,ExecutorService owner,PhoneChat chat,String question,PhoneRetrieval.Result context,PhoneGrounding.Scope scope,List<String> expected,List<String> forbidden)throws Exception{
        var row=new JsonObject();row.addProperty("name",name);long began=System.nanoTime();
        try{owner.submit(()->chat.submit(question,context.context(),"diary",()->true,()->CompletableFuture.completedFuture(true),scope)).get().toCompletableFuture().get(9,TimeUnit.SECONDS);String answer=owner.submit(()->chat.history().getLast().answer()).get();row.addProperty("answer",answer);boolean passed=expected.stream().allMatch(answer::contains)&&forbidden.stream().noneMatch(answer::contains);for(int i=1;i<=expected.size();i++)passed&=answer.contains("[자료 "+i+"]");row.addProperty("outcome",passed?"PASS":"QUALITY_REVIEW_REQUIRED");}
        catch(ExecutionException failure){row.addProperty("outcome",AssistantFailure.reason(failure).name());}row.addProperty("milliseconds",(System.nanoTime()-began)/1_000_000);rows.add(row);
    }
}
