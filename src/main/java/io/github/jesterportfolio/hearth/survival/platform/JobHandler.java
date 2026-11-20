package io.github.jesterportfolio.hearth.survival.platform;

import java.util.*;import java.util.concurrent.*;import java.util.function.BooleanSupplier;
import io.github.jesterportfolio.hearth.economy.JobStore;

/** 신뢰하는 Java 모듈 확장. 명령/스크립트 실행기를 제공하지 않는다. */
public interface JobHandler {
    enum Outcome {NOT_STARTED,PENDING,SUCCEEDED,CANCELLED}
    Map<String,String> prepare(String target,Map<String,String> template);
    CompletionStage<Void> execute(JobStore.Job job,BooleanSupplier current);
    CompletionStage<Outcome> inspect(JobStore.Job job);
}
