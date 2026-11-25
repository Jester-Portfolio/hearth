package io.github.jesterportfolio.hearth.survival.economy;
import java.util.concurrent.*;import java.util.function.Supplier;
/** Housing drain may kick players, so durable death work must settle before it starts. */
public final class NativeDrainOrder {
 private NativeDrainOrder(){}
 public static CompletionStage<Void> beforeHousing(Supplier<CompletionStage<Void>> death,Supplier<CompletionStage<Void>> housing,Executor owner){try{return death.get().thenComposeAsync(v->housing.get(),owner);}catch(Exception e){return CompletableFuture.failedFuture(e);}}
}
