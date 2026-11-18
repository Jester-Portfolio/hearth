package io.github.jesterportfolio.hearth.survival.market;

import io.github.jesterportfolio.hearth.economy.*;
import io.github.jesterportfolio.hearth.economy.MarketAutomation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** LLM은 Tick을 기다리게 하지 않습니다. 한 요청만 유지하고 현재 정책/수명/기한이 맞는 제안만 캐시합니다. */
public final class MarketDirector implements AutoCloseable {
 private final MarketSettings settings;private final MarketAdviser adviser;private final Executor owner;private final BooleanSupplier active;private Advice advice;private CompletableFuture<?> pending;private long lastAsked=-1,generation;private boolean closed,preparing;private String status="DISABLED",lastFailure="NONE";private long accepted,rejected;
 public MarketDirector(MarketSettings settings,MarketAdviser adviser,Executor owner,BooleanSupplier active){this.settings=settings;this.adviser=adviser;this.owner=owner;this.active=active;status=adviser==null?"DISABLED":"NOT_PREPARED";}
 public Optional<Advice> advice(long tick){if(closed||advice==null||advice.tick()>tick||tick-advice.tick()>settings.policy().interval())return Optional.empty();return Optional.of(advice);}
 public void observe(Observation observation){if(closed||adviser==null||preparing||pending!=null||!active.getAsBoolean()||observation.candidates().size()<2||lastAsked>=0&&observation.tick()-lastAsked<Math.max(12,settings.policy().interval()))return;if(!adviser.prepared()){status="NOT_PREPARED";return;}lastAsked=observation.tick();long token=generation;status="PENDING";try{var request=adviser.choose(observation).toCompletableFuture().orTimeout(6,TimeUnit.SECONDS);pending=request;request.whenCompleteAsync((choice,error)->{if(closed||generation!=token||pending!=request||!active.getAsBoolean())return;pending=null;if(error!=null||choice==null||!observation.candidates().contains(choice)){rejected++;lastFailure=error==null?"INVALID_CHOICE":failure(error);status="FALLBACK";return;}advice=new Advice(choice,observation.tick(),settings.policy().fingerprint());accepted++;status="READY";},owner);}catch(RuntimeException failure){pending=null;rejected++;lastFailure=failure(failure);status="FALLBACK";}}
 public CompletionStage<String> prepare(){if(closed||adviser==null||preparing||pending!=null||!active.getAsBoolean())return CompletableFuture.failedFuture(new IllegalStateException("MARKET_AI_PREPARE_UNAVAILABLE"));preparing=true;status="PREPARING";long token=generation;return adviser.prepare().handleAsync((value,error)->{if(closed||token!=generation)throw new CompletionException(new IllegalStateException("MARKET_AI_CLOSED"));preparing=false;if(error!=null){status="NOT_PREPARED";throw new CompletionException(error);}status="READY";return value;},owner);}
 private static String failure(Throwable error){while(error instanceof CompletionException||error instanceof ExecutionException)error=error.getCause();return error instanceof IllegalArgumentException?"INVALID_CHOICE":io.github.jesterportfolio.hearth.phone.AssistantFailure.reason(error).name();}
 public boolean preparationRecorded(){return !closed&&adviser!=null&&adviser.prepared();}
 public String lastFailure(){return lastFailure;}
 public String status(){return status;}public long accepted(){return accepted;}public long rejected(){return rejected;}
 @Override public void close(){closed=true;generation++;advice=null;if(pending!=null)pending.cancel(true);pending=null;if(adviser!=null)adviser.close();status="CLOSED";}
}
