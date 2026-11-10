package io.github.jesterportfolio.hearth.phone;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** 두 로컬 프로토콜이 같은 요청·스레드·본문·종료 한도를 사용합니다. */
final class LocalAiHttp implements AutoCloseable {
    static final Duration TIMEOUT=Duration.ofSeconds(6);
    final URI endpoint;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"hearth-phone-ai");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final HttpClient client;private final Duration timeout;
    private final Set<CompletableFuture<String>> requests=new HashSet<>();private boolean closed;
    LocalAiHttp(String address,Duration timeout){
        if(timeout.isZero()||timeout.isNegative()||timeout.compareTo(TIMEOUT)>0)throw new IllegalArgumentException("LOCAL_AI_TIMEOUT");this.timeout=timeout;
        endpoint=URI.create(address);
        if(!"http".equals(endpoint.getScheme())||!Set.of("127.0.0.1","[::1]","::1").contains(endpoint.getHost())||endpoint.getUserInfo()!=null||endpoint.getFragment()!=null)throw new IllegalArgumentException("LOCAL_AI_ENDPOINT_REQUIRED");
        client=HttpClient.newBuilder().executor(worker).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    synchronized CompletableFuture<String> exchange(URI target,String method,String contentType,String body,Function<HttpResponse<String>,String> decode){
        if(closed)return CompletableFuture.failedFuture(new AssistantFailure(AssistantFailure.Reason.CLOSED));
        if(!Objects.equals(target.getHost(),endpoint.getHost())||target.getPort()!=endpoint.getPort()||!target.getScheme().equals(endpoint.getScheme()))throw new IllegalArgumentException("LOCAL_AI_ORIGIN");
        requests.removeIf(CompletableFuture::isDone);
        if(requests.size()>=4)return CompletableFuture.failedFuture(new AssistantFailure(AssistantFailure.Reason.BUSY));
        final CompletableFuture<HttpResponse<String>> response;
        try{
            var request=HttpRequest.newBuilder(target).timeout(timeout).header("Content-Type",contentType).method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
            response=client.sendAsync(request,info->new LocalAssistant.LimitedBody());
        }catch(RuntimeException failure){return CompletableFuture.failedFuture(new AssistantFailure(AssistantFailure.reason(failure)));}
        var timed=response.copy().orTimeout(timeout.toMillis(),TimeUnit.MILLISECONDS);var result=new CompletableFuture<String>();requests.add(result);
        result.whenComplete((value,error)->{synchronized(this){requests.remove(result);}if(error!=null){timed.cancel(true);response.cancel(true);}});
        timed.whenComplete((value,error)->{
            if(error!=null)result.completeExceptionally(new AssistantFailure(AssistantFailure.reason(error)));
            else try{result.complete(decode.apply(value));}catch(RuntimeException rejected){result.completeExceptionally(rejected instanceof AssistantFailure?rejected:new AssistantFailure(AssistantFailure.Reason.INVALID_RESPONSE));}
        });return result;
    }
    public void close(){List<CompletableFuture<String>> pending;synchronized(this){if(closed)return;closed=true;pending=List.copyOf(requests);}for(var result:pending)result.completeExceptionally(new AssistantFailure(AssistantFailure.Reason.CLOSED));client.shutdownNow();worker.shutdownNow();}
}
