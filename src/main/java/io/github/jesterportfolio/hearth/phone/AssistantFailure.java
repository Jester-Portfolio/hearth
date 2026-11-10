package io.github.jesterportfolio.hearth.phone;

import java.net.http.HttpTimeoutException;
import java.util.concurrent.*;

/** 응답 본문·주소·예외 원문을 UI에 노출하지 않는 요청 실패 분류입니다. */
public final class AssistantFailure extends IllegalStateException {
    public enum Reason { BUSY, TIMEOUT, UNAVAILABLE, INVALID_RESPONSE, CLOSED }
    private final Reason reason;
    public AssistantFailure(Reason reason){super("LOCAL_AI_"+reason.name());this.reason=reason;}
    public Reason reason(){return reason;}
    public static Reason reason(Throwable failure){
        while((failure instanceof CompletionException||failure instanceof ExecutionException)&&failure.getCause()!=null)failure=failure.getCause();
        if(failure instanceof AssistantFailure known)return known.reason;
        return failure instanceof TimeoutException||failure instanceof HttpTimeoutException?Reason.TIMEOUT:Reason.UNAVAILABLE;
    }
    public static String message(Throwable failure){return switch(reason(failure)){
        case BUSY -> "AI가 다른 요청을 처리 중입니다. 잠시 뒤 다시 시도해 주세요.";
        case TIMEOUT -> "AI 응답 시간이 초과됐습니다. 질문을 유지했으니 다시 시도해 주세요.";
        case UNAVAILABLE -> "AI에 연결하지 못했습니다. 질문을 유지했으니 다시 시도해 주세요.";
        case INVALID_RESPONSE -> "AI 응답을 읽지 못했습니다. 질문을 유지했으니 다시 시도해 주세요.";
        case CLOSED -> "AI 연결이 종료됐습니다. 다시 접속한 뒤 요청해 주세요.";
    };}
}
