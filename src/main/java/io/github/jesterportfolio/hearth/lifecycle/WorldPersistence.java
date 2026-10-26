package io.github.jesterportfolio.hearth.lifecycle;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.*;

/** 저장 순서만 조정합니다. Bukkit/DB/WorldGuard의 실제 스레드 계약은 경계 구현이 담당합니다. */
public final class WorldPersistence implements WorldLifecycle.Operations {
    public interface Boundary {
        CompletionStage<Void> verifyStoredMapping();
        CompletionStage<Void> loadWorld();
        CompletionStage<Void> restoreRegions();
        CompletionStage<Void> verifyProgressAndInventory();
        CompletionStage<Void> drainInventory();
        CompletionStage<Void> verifyProgress();
        CompletionStage<Void> saveRegions();
        CompletionStage<Void> saveWorld();
        CompletionStage<Void> checkUnloadBlockers();
        CompletionStage<Boolean> unloadWorld();
        void clearWorldReferences();
    }
    private final Boundary boundary;
    private final Executor owner;
    private final Runnable checkOwner;
    private final LongSupplier generation;
    public WorldPersistence(Boundary boundary,Executor owner,Runnable checkOwner,LongSupplier generation) {
        this.boundary=Objects.requireNonNull(boundary); this.owner=Objects.requireNonNull(owner);
        this.checkOwner=Objects.requireNonNull(checkOwner); this.generation=Objects.requireNonNull(generation);
    }
    private void guard(long epoch) { checkOwner.run(); if (generation.getAsLong()!=epoch) throw new LifecycleFailure("STALE_GENERATION"); }
    private <T> CompletionStage<T> start(long epoch,Supplier<CompletionStage<T>> call) {
        try { guard(epoch); return call.get(); } catch (Exception error) { return CompletableFuture.failedFuture(error); }
    }
    private CompletionStage<Void> next(CompletionStage<?> prior,long epoch,Supplier<CompletionStage<Void>> call) {
        return prior.thenComposeAsync(v -> start(epoch,call),owner);
    }
    @Override public CompletionStage<Void> loadAndPrepare() {
        checkOwner.run(); long epoch=generation.getAsLong();
        var work=start(epoch,boundary::verifyStoredMapping);
        work=next(work,epoch,boundary::loadWorld); work=next(work,epoch,boundary::restoreRegions);
        return next(work,epoch,boundary::verifyProgressAndInventory);
    }
    @Override public CompletionStage<Void> quiesce() { checkOwner.run(); return start(generation.getAsLong(),boundary::drainInventory); }
    @Override public CompletionStage<Void> save() {
        checkOwner.run(); long epoch=generation.getAsLong();
        var work=start(epoch,boundary::verifyProgress);
        work=next(work,epoch,boundary::saveRegions); return next(work,epoch,boundary::saveWorld);
    }
    @Override public CompletionStage<Boolean> unload() {
        checkOwner.run(); long epoch=generation.getAsLong();
        return start(epoch,boundary::checkUnloadBlockers).thenComposeAsync(v -> start(epoch,boundary::unloadWorld),owner)
            .thenApplyAsync(ok -> { guard(epoch); if (Boolean.TRUE.equals(ok)) boundary.clearWorldReferences(); return ok; },owner);
    }
}
