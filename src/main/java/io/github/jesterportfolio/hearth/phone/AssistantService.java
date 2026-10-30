package io.github.jesterportfolio.hearth.phone;

import java.util.concurrent.*;

/** 질문/제한된 대화/사용자가 선택한 공개 정보만 전달하며 도구 권한은 제공하지 않습니다. */
public interface AssistantService extends AutoCloseable {
    CompletionStage<String> ask(String question);
    default CompletionStage<String> ask(AssistantRequest request){return ask(request.prompt());}
    @Override default void close(){}
}
