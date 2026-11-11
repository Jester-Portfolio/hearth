package io.github.jesterportfolio.hearth.phone;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** 선택형 고정 Ollama API. 버전/모델 확인부터 생성까지 합쳐 기존 6초 안에서 처리합니다. */
public final class OllamaAssistant implements AssistantService {
    public static final String MODEL=OllamaProtocol.MODEL,VERSION=OllamaProtocol.VERSION,MODEL_DIGEST=OllamaProtocol.DIGEST;
    private static final class Work {final CompletableFuture<String> result=new CompletableFuture<>();CompletableFuture<String> phase;}
    private final LocalAiHttp http;private final Duration timeout;private final Set<Work> requests=new HashSet<>();private boolean closed;
    public OllamaAssistant(String address){this(address,LocalAiHttp.TIMEOUT);}
    OllamaAssistant(String address,Duration timeout){
        var endpoint=URI.create(address);if(!"/api/generate".equals(endpoint.getRawPath())||endpoint.getRawQuery()!=null)throw new IllegalArgumentException("OLLAMA_GENERATE_ENDPOINT_REQUIRED");
        http=new LocalAiHttp(address,timeout);this.timeout=timeout;
    }
    @Override public synchronized CompletionStage<String> ask(String prompt){
        final String payload;try{payload=OllamaProtocol.request(prompt);}catch(RuntimeException invalid){return CompletableFuture.failedFuture(invalid);}
        return operation(payload,null);
    }
    @Override public synchronized CompletionStage<String> ask(AssistantRequest request){
        final String payload;try{payload=OllamaProtocol.request(request);}catch(RuntimeException invalid){return CompletableFuture.failedFuture(invalid);}
        return operation(payload,null);
    }
    @Override public boolean allowsCache(){return true;}
    @Override public CompletionStage<String> confirmCached(String answer){return operation(null,Objects.requireNonNull(answer));}
    private synchronized CompletionStage<String> operation(String payload,String cached){
        if(closed)return CompletableFuture.failedFuture(OllamaProtocol.failure(AssistantFailure.Reason.CLOSED));
        requests.removeIf(w->w.result.isDone());if(requests.size()>=4)return CompletableFuture.failedFuture(OllamaProtocol.failure(AssistantFailure.Reason.BUSY));
        var work=new Work();requests.add(work);
        work.result.whenComplete((value,error)->{synchronized(this){requests.remove(work);if(error!=null&&work.phase!=null)work.phase.cancel(true);}});
        work.result.copy().orTimeout(timeout.toMillis(),TimeUnit.MILLISECONDS).whenComplete((value,error)->{if(error!=null&&!work.result.isDone())work.result.completeExceptionally(OllamaProtocol.failure(AssistantFailure.reason(error)));});
        step(work,"/api/version","GET",null,version->{OllamaProtocol.version(version);step(work,"/api/tags","GET",null,models->{OllamaProtocol.model(models);if(cached!=null)work.result.complete(cached);else step(work,"/api/generate","POST",payload,answer->work.result.complete(OllamaProtocol.answer(answer)));});});return work.result;
    }
    private synchronized void step(Work work,String path,String method,String payload,Consumer<String> accept){
        if(work.result.isDone())return;
        work.phase=http.exchange(http.endpoint.resolve(path),method,"application/json; charset=utf-8",payload,OllamaAssistant::json);
        work.phase.whenComplete((value,error)->{if(work.result.isDone())return;try{if(error!=null)throw OllamaProtocol.failure(AssistantFailure.reason(error));accept.accept(value);}catch(RuntimeException rejected){work.result.completeExceptionally(rejected instanceof AssistantFailure?rejected:OllamaProtocol.invalid());}});
    }
    private static String json(HttpResponse<String> response){
        int status=response.statusCode();if(status==429||status==503)throw OllamaProtocol.failure(AssistantFailure.Reason.BUSY);if(status==413||status==400&&response.body().matches("(?s).*request \\([0-9]+ tokens\\) exceeds the available context size \\([0-9]+ tokens\\).*"))throw OllamaProtocol.failure(AssistantFailure.Reason.CONTEXT_LIMIT);if(status!=200)throw OllamaProtocol.invalid();
        var content=response.headers().firstValue("Content-Type").orElse("").split(";");if(!content[0].strip().equalsIgnoreCase("application/json"))throw OllamaProtocol.invalid();
        for(int i=1;i<content.length;i++){var parameter=content[i].strip().split("=",2);if(parameter[0].equalsIgnoreCase("charset")&&(parameter.length!=2||!parameter[1].strip().replace("\"","").equalsIgnoreCase("utf-8")))throw OllamaProtocol.invalid();}return response.body();
    }
    @Override public void close(){List<Work> active;synchronized(this){if(closed)return;closed=true;active=List.copyOf(requests);}for(var work:active)work.result.completeExceptionally(OllamaProtocol.failure(AssistantFailure.Reason.CLOSED));http.close();}
}
