package io.github.jesterportfolio.hearth.phone;

import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** 선택형 로컬 어댑터: POST text/plain UTF-8 → text/plain UTF-8. 임의 원격 주소·리다이렉트는 거부합니다. */
public final class LocalAssistant implements AssistantService {
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"hearth-phone-ai");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final URI endpoint;private final HttpClient client;private final Duration timeout;
    private final Set<CompletableFuture<String>> requests=new HashSet<>();private boolean closed;
    public LocalAssistant(String address){this(address,Duration.ofSeconds(6));}
    LocalAssistant(String address,Duration timeout){
        if(timeout.isZero()||timeout.isNegative()||timeout.compareTo(Duration.ofSeconds(6))>0)throw new IllegalArgumentException("LOCAL_AI_TIMEOUT");this.timeout=timeout;
        endpoint=URI.create(address);
        if(!"http".equals(endpoint.getScheme())||!Set.of("127.0.0.1","[::1]","::1").contains(endpoint.getHost())||endpoint.getUserInfo()!=null||endpoint.getFragment()!=null)throw new IllegalArgumentException("LOCAL_AI_ENDPOINT_REQUIRED");
        client=HttpClient.newBuilder().executor(worker).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public static AssistantService configured(String mode,String address){return switch(mode){case "mock"->mock();case "local"->new LocalAssistant(address);default->throw new IllegalArgumentException("UNKNOWN_PHONE_AI_MODE");};}
    public static AssistantService mock(){return new AssistantService(){
        public CompletionStage<String> ask(String question){return CompletableFuture.completedFuture("[Mock AI] 질문을 확인했습니다: "+question+"\n실제 로컬 AI 서비스는 아직 연결되지 않았습니다.");}
        public CompletionStage<String> ask(AssistantRequest request){return ask(request.question()).thenApply(answer->answer+"\n전달된 최근 대화: "+request.history().size()+"개 · 공개 정보: "+(request.context().isEmpty()?"없음":"선택한 범위만 포함"));}
    };}

    @Override public synchronized CompletionStage<String> ask(String question){
        if(closed)return CompletableFuture.failedFuture(new AssistantFailure(AssistantFailure.Reason.CLOSED));
        if(requests.size()>=4)return CompletableFuture.failedFuture(new AssistantFailure(AssistantFailure.Reason.BUSY));
        final CompletableFuture<HttpResponse<String>> response;
        try{
            var request=HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type","text/plain; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(question)).build();
            response=client.sendAsync(request,info->new LimitedBody());
        }catch(RuntimeException failure){return CompletableFuture.failedFuture(new AssistantFailure(AssistantFailure.reason(failure)));}
        var timed=response.copy().orTimeout(timeout.toMillis(),TimeUnit.MILLISECONDS);var result=new CompletableFuture<String>();requests.add(result);
        result.whenComplete((value,error)->{synchronized(this){requests.remove(result);}if(error!=null){timed.cancel(true);response.cancel(true);}});
        timed.whenComplete((value,error)->{
            if(error!=null)result.completeExceptionally(new AssistantFailure(AssistantFailure.reason(error)));
            else if(value.statusCode()!=200||!textResponse(value.headers())||value.body().isBlank())result.completeExceptionally(new AssistantFailure(AssistantFailure.Reason.INVALID_RESPONSE));else result.complete(value.body());
        });
        return result;
    }
    private static boolean textResponse(HttpHeaders headers){
        var type=headers.firstValue("Content-Type");if(type.isEmpty())return true;var parts=type.orElseThrow().split(";");if(!parts[0].strip().equalsIgnoreCase("text/plain"))return false;
        for(int i=1;i<parts.length;i++){var parameter=parts[i].strip().split("=",2);if(parameter[0].equalsIgnoreCase("charset")&&(parameter.length!=2||!parameter[1].strip().replace("\"","").equalsIgnoreCase("utf-8")))return false;}return true;
    }

    static final class LimitedBody implements HttpResponse.BodySubscriber<String>{
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();private Flow.Subscription subscription;private long size;
        public CompletionStage<String> getBody(){return delegate.getBody().thenApply(bytes->{try{return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException invalid){throw new AssistantFailure(AssistantFailure.Reason.INVALID_RESPONSE);}});}
        public void onSubscribe(Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
        public void onNext(List<ByteBuffer> chunks){for(var b:chunks)size+=b.remaining();if(size>8192){subscription.cancel();delegate.onError(new AssistantFailure(AssistantFailure.Reason.INVALID_RESPONSE));}else delegate.onNext(chunks);}
        public void onError(Throwable t){delegate.onError(t);}public void onComplete(){delegate.onComplete();}
    }
    @Override public void close(){List<CompletableFuture<String>> pending;synchronized(this){if(closed)return;closed=true;pending=List.copyOf(requests);}for(var result:pending)result.completeExceptionally(new AssistantFailure(AssistantFailure.Reason.CLOSED));client.shutdownNow();worker.shutdownNow();}
}
