package io.github.jesterportfolio.hearth.phone;

import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** 선택형 로컬 어댑터: POST text/plain UTF-8 → text/plain UTF-8. 임의 원격 주소·리다이렉트는 거부합니다. */
public final class LocalAssistant implements AssistantService {
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"hearth-phone-ai");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final URI endpoint;private final HttpClient client;private final Semaphore requests=new Semaphore(4);
    public LocalAssistant(String address){
        endpoint=URI.create(address);
        if(!"http".equals(endpoint.getScheme())||!Set.of("127.0.0.1","[::1]","::1").contains(endpoint.getHost())||endpoint.getUserInfo()!=null||endpoint.getFragment()!=null)throw new IllegalArgumentException("LOCAL_AI_ENDPOINT_REQUIRED");
        client=HttpClient.newBuilder().executor(worker).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public static AssistantService mock(){return question->CompletableFuture.completedFuture("[Mock AI] 질문을 확인했습니다: "+question+"\n실제 로컬 AI 서비스는 아직 연결되지 않았습니다.");}
    @Override public CompletionStage<String> ask(String question){
        if(!requests.tryAcquire())return CompletableFuture.failedFuture(new IllegalStateException("AI_QUEUE_FULL"));
        final CompletableFuture<HttpResponse<String>> response;
        try{
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(6)).header("Content-Type","text/plain; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(question)).build();
            response=client.sendAsync(request,info->new LimitedBody());
        }catch(RuntimeException failure){requests.release();return CompletableFuture.failedFuture(new IllegalStateException("LOCAL_AI_REQUEST_FAILED"));}
        return response.copy().orTimeout(6,TimeUnit.SECONDS).handle((value,error)->{
            requests.release();if(error!=null){response.cancel(true);throw new CompletionException(new IllegalStateException("LOCAL_AI_REQUEST_FAILED"));}
            if(value.statusCode()!=200)throw new CompletionException(new IllegalStateException("AI_HTTP_STATUS"));return value.body();
        });
    }

    static final class LimitedBody implements HttpResponse.BodySubscriber<String>{
        private final HttpResponse.BodySubscriber<String> delegate=HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);private Flow.Subscription subscription;private int size;
        public CompletionStage<String> getBody(){return delegate.getBody();}
        public void onSubscribe(Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
        public void onNext(List<ByteBuffer> chunks){for(var b:chunks)size+=b.remaining();if(size>8192){subscription.cancel();delegate.onError(new IllegalStateException("AI_BODY_TOO_LARGE"));}else delegate.onNext(chunks);}
        public void onError(Throwable t){delegate.onError(t);}public void onComplete(){delegate.onComplete();}
    }
    @Override public void close(){worker.shutdownNow();client.shutdownNow();}
}
