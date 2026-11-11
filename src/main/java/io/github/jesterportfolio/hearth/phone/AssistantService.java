package io.github.jesterportfolio.hearth.phone;

import java.util.concurrent.*;

/** 질문/제한된 대화/사용자가 선택한 공개 정보만 전달하며 도구 권한은 제공하지 않습니다. */
public interface AssistantService extends AutoCloseable {
    CompletionStage<String> ask(String question);
    default CompletionStage<String> ask(AssistantRequest request){return ask(request.prompt());}
    /** 세션 소유 캐시만 허용하며, 재사용 전 백엔드의 현재 신원을 확인합니다. */
    default boolean allowsCache(){return false;}
    default CompletionStage<String> confirmCached(String answer){return CompletableFuture.failedFuture(new IllegalStateException("AI_CACHE_UNSUPPORTED"));}
    @Override default void close(){}
}
