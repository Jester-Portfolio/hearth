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
    private final LocalAiHttp http;
    public LocalAssistant(String address){this(address,Duration.ofSeconds(6));}
    LocalAssistant(String address,Duration timeout){
        http=new LocalAiHttp(address,timeout);
    }
    public static AssistantService configured(String mode,String address){return switch(mode){case "mock"->mock();case "local"->new LocalAssistant(address);case "ollama"->new OllamaAssistant(address);default->throw new IllegalArgumentException("UNKNOWN_PHONE_AI_MODE");};}
    public static AssistantService mock(){return new AssistantService(){
        public CompletionStage<String> ask(String question){return CompletableFuture.completedFuture("[Mock AI] 질문을 확인했습니다: "+question+"\n실제 로컬 AI 서비스는 아직 연결되지 않았습니다.");}
        public CompletionStage<String> ask(AssistantRequest request){return ask(request.question()).thenApply(answer->answer+"\n전달된 최근 대화: "+request.history().size()+"개 · 공개 정보: "+(request.context().isEmpty()?"없음":"선택한 범위만 포함")+(request.sources()>0?"\n[자료 1]":""));}
        public boolean allowsCache(){return true;}
        public CompletionStage<String> confirmCached(String answer){return CompletableFuture.completedFuture(answer);}
    };}

    @Override public CompletionStage<String> ask(String question){
        return http.exchange(http.endpoint,"POST","text/plain; charset=utf-8",question,value->{if(value.statusCode()!=200||!textResponse(value.headers())||value.body().isBlank())throw new AssistantFailure(AssistantFailure.Reason.INVALID_RESPONSE);return value.body();});
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
    @Override public void close(){http.close();}
}
