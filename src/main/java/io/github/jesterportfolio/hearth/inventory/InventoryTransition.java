package io.github.jesterportfolio.hearth.inventory;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.*;

/** 서버 내부 한 플레이어의 최소 전환 조정자. 로그인·가입·순간이동 시스템이 아닙니다. */
public final class InventoryTransition {
    public enum State { ACTIVE, TRANSITION, HOLD, CLOSED }
    private final InventoryStore store;
    private final Executor owner;
    private final Runnable checkOwner;
    private final Consumer<InventoryPayload> validate;
    private final BiConsumer<ServerProgressAccess.Context,InventoryStore.Stored> apply;
    private final BiFunction<ServerProgressAccess.Context,InventoryStore.Stored,InventorySaveSession> factory;
    private final Consumer<String> errors;
    private InventorySaveSession active;
    private State state=State.ACTIVE;
    private long generation;
    private boolean connected=true;

    public InventoryTransition(InventoryStore store,InventorySaveSession initial,Executor owner,Runnable checkOwner,
            Consumer<InventoryPayload> validate,BiConsumer<ServerProgressAccess.Context,InventoryStore.Stored> apply,
            BiFunction<ServerProgressAccess.Context,InventoryStore.Stored,InventorySaveSession> factory,Consumer<String> errors) {
        this.store=Objects.requireNonNull(store); this.active=Objects.requireNonNull(initial); this.owner=Objects.requireNonNull(owner);
        this.checkOwner=Objects.requireNonNull(checkOwner); this.validate=Objects.requireNonNull(validate);
        this.apply=Objects.requireNonNull(apply); this.factory=Objects.requireNonNull(factory); this.errors=Objects.requireNonNull(errors);
    }
    public State state() { checkOwner.run(); return state; }
    public InventorySaveSession active() { checkOwner.run(); return active; }
    public void invalidate() { checkOwner.run(); generation++; connected=false; }
    public CompletionStage<InventorySaveSession> move(ServerProgressAccess.Context target) {
        checkOwner.run();
        if (state!=State.ACTIVE || !connected) return rejected(state==State.TRANSITION ? "BUSY" : "NOT_ACTIVE");
        var source=active; long epoch=generation;
        if (target==null || !source.context().playerId().equals(target.playerId()) || source.context().storyRunId().equals(target.storyRunId())) return rejected("INVALID_TARGET");
        state=State.TRANSITION;
        try { source.freeze(); }
        catch (RuntimeException failure) { state=State.HOLD; return rejected("SOURCE_NOT_READY"); }
        var result=new CompletableFuture<InventorySaveSession>();
        source.flushLatest().thenComposeAsync(v -> {
            checkOwner.run(); requireCurrent(source,epoch);
            if (source.dirty() || source.saving() || source.holdCode()!=null) throw new StorageFailure("transition","SOURCE_NOT_SAVED");
            return store.readInventory(target);
        },owner).thenAcceptAsync(saved -> {
            checkOwner.run(); requireCurrent(source,epoch);
            try { validate.accept(saved.payload()); } catch (RuntimeException invalid) { throw new StorageFailure("transition","TARGET_INVALID"); }
            var next=Objects.requireNonNull(factory.apply(target,saved));
            if (!next.context().storyRunId().equals(target.storyRunId()) || !next.context().playerId().equals(target.playerId()) || next.revision()!=saved.revision()) throw new StorageFailure("transition","INVALID_TARGET");
            requireCurrent(source,epoch);
            try { apply.accept(target,saved); } catch (RuntimeException failed) { throw new StorageFailure("transition","APPLY_FAILED"); }
            active=next; state=State.ACTIVE; result.complete(next);
        },owner).whenComplete((v,error) -> {
            if (error!=null) owner.execute(() -> {
                state=State.HOLD;
                String code=InventorySaveSession.code(error); report(code);
                result.completeExceptionally(new StorageFailure("transition",code));
            });
        });
        return result;
    }
    public CompletionStage<Void> logout() {
        checkOwner.run();
        if (state==State.TRANSITION) return CompletableFuture.failedFuture(new StorageFailure("logout","BUSY"));
        if (state!=State.ACTIVE) return CompletableFuture.failedFuture(new StorageFailure("logout","NOT_ACTIVE"));
        state=State.CLOSED;
        return active.closeForLogout().whenCompleteAsync((v,error) -> {
            checkOwner.run(); generation++; connected=false;
            if (error!=null) { state=State.HOLD; report(InventorySaveSession.code(error)); }
        },owner);
    }
    private void report(String code) { try { errors.accept(code); } catch (RuntimeException ignored) { /* 오류 기록기 실패는 저장/전환 성공이 아닙니다. */ } }
    private void requireCurrent(InventorySaveSession source,long epoch) {
        if (!connected || generation!=epoch || active!=source || state!=State.TRANSITION) throw new StorageFailure("transition","STALE_CONTEXT");
    }
    private static <T> CompletionStage<T> rejected(String code) { return CompletableFuture.failedFuture(new StorageFailure("transition",code)); }
}
