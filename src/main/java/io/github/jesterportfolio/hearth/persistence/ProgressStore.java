package io.github.jesterportfolio.hearth.persistence;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** 논리 진행/개인 키만 사용합니다. 모든 DB 작업과 종료 정리는 비동기입니다. */
public interface ProgressStore {
    record State(String value, long revision) {}
    CompletionStage<Void> migrate();
    CompletionStage<Void> createRun(UUID storyRunId, String initialValue);
    CompletionStage<Void> createPlayer(ServerProgressAccess.Context context, String initialValue);
    CompletionStage<State> readShared(ServerProgressAccess.Context context);
    CompletionStage<State> readPersonal(ServerProgressAccess.Context context);
    CompletionStage<State> saveShared(ServerProgressAccess.Context context, long expectedRevision, String value);
    CompletionStage<State> savePersonal(ServerProgressAccess.Context context, long expectedRevision, String value);
    CompletionStage<Void> closeAsync();
}
