package io.github.jesterportfolio.hearth.phone;

import java.util.concurrent.*;

/** 이 프로토타입은 질문만 전달합니다. 비공개 콘텐츠·다른 플레이어 데이터는 조회하지 않습니다. */
public interface AssistantService extends AutoCloseable {
    CompletionStage<String> ask(String question);
    @Override default void close(){}
}
