package io.github.jesterportfolio.hearth.inventory;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.*;

/** 소유 스레드에 한정된 단일 in-flight 저장. 클릭별 SQL·무제한 대기열은 만들지 않습니다. */
public final class InventorySaveSession {
    private final InventoryStore store;
    private final ServerProgressAccess.Context context;
    private final Supplier<InventoryPayload> capture;
    private final Executor owner;
    private final Runnable checkOwner;
    private final BooleanSupplier current;
    private final Consumer<String> errors;
    private long revision, memoryVersion, savedVersion;
    private CompletableFuture<Void> inFlight,transaction;
    public boolean transacting(){checkOwner.run();return transaction!=null;}
    private String hold;
    private boolean frozen, closing;

    public InventorySaveSession(InventoryStore store,ServerProgressAccess.Context context,long revision,
            Supplier<InventoryPayload> capture,Executor owner,Runnable checkOwner,BooleanSupplier current,Consumer<String> errors) {
        if (revision<0) throw new IllegalArgumentException("Invalid revision");
        this.store=Objects.requireNonNull(store); this.context=Objects.requireNonNull(context); this.revision=revision;
        this.capture=Objects.requireNonNull(capture); this.owner=Objects.requireNonNull(owner);
        this.checkOwner=Objects.requireNonNull(checkOwner); this.current=Objects.requireNonNull(current); this.errors=Objects.requireNonNull(errors);
    }
    public ServerProgressAccess.Context context() { return context; }
    public long revision() { checkOwner.run(); return revision; }
    public long memoryVersion() { checkOwner.run(); return memoryVersion; }
    public long savedVersion() { checkOwner.run(); return savedVersion; }
    public boolean dirty() { checkOwner.run(); return memoryVersion>savedVersion; }
    public boolean saving() { checkOwner.run(); return inFlight!=null; }
    public String holdCode() { checkOwner.run(); return hold; }
    public void markDirty() { checkOwner.run(); requireCurrent(); if (frozen || closing) throw new StorageFailure("inventory",closing ? "CLOSED" : "FROZEN"); memoryVersion=Math.incrementExact(memoryVersion); }
    public CompletionStage<Void> flush() {
        checkOwner.run();
        if(transaction!=null)return transaction;
        if (closing) return CompletableFuture.failedFuture(new StorageFailure("inventory","CLOSED"));
        return flushInternal();
    }
    public void freeze() { checkOwner.run(); requireCurrent(); if (frozen || closing) throw new StorageFailure("inventory","BUSY"); frozen=true; }
    public CompletionStage<Void> flushLatest() {
        checkOwner.run();
        if (!frozen && !closing) return CompletableFuture.failedFuture(new StorageFailure("inventory","FREEZE_REQUIRED"));
        CompletionStage<Void> prior=transaction!=null?transaction:inFlight==null ? CompletableFuture.completedFuture(null) : inFlight;
        return prior.thenComposeAsync(v -> flushInternal(),owner);
    }
    public CompletionStage<Void> closeForLogout() { checkOwner.run(); closing=true; frozen=true; return flushLatest(); }
    /** 최신 일반 저장 후 단일 보상 DB 작업. 종료와 자동 저장도 이 결과를 기다립니다. */
    public CompletionStage<Void> transaction(Function<InventoryStore.Stored,CompletionStage<InventoryStore.Stored>> writer,Consumer<InventoryStore.Stored> apply){
        checkOwner.run();if(transaction!=null||frozen||closing||hold!=null)return CompletableFuture.failedFuture(new StorageFailure("inventory","BUSY_OR_HOLD"));
        freeze();var prior=flushLatest();var result=new CompletableFuture<Void>();transaction=result;final long[] expected={-1};final InventoryPayload[] before={null};
        prior.thenComposeAsync(v->{requireCurrent();expected[0]=revision;before[0]=capture.get();return writer.apply(new InventoryStore.Stored(before[0],revision));},owner).thenAcceptAsync(saved->{
            requireCurrent();if(saved==null||saved.revision()<expected[0]||saved.revision()>expected[0]+1||saved.revision()==expected[0]&&!saved.payload().equals(before[0]))throw new StorageFailure("inventory","INVALID_TRANSACTION_RESULT");
            apply.accept(saved);revision=saved.revision();savedVersion=memoryVersion;
        },owner).whenCompleteAsync((v,error)->{
            transaction=null;if(error==null){if(!closing)frozen=false;result.complete(null);}
            else{String code=code(error);if(Set.of("INVENTORY_FULL","REWARD_NOT_ELIGIBLE","REWARD_UNREGISTERED").contains(code)){if(!closing)frozen=false;}else fail(code);result.completeExceptionally(new StorageFailure("inventory",code));}
        },owner).exceptionally(error->{result.completeExceptionally(new StorageFailure("inventory","RESULT_DELIVERY_FAILED"));return null;});return result;
    }
    private CompletionStage<Void> flushInternal() {
        checkOwner.run();
        if (!current.getAsBoolean()) return CompletableFuture.failedFuture(new StorageFailure("inventory","STALE_CONTEXT"));
        if (inFlight!=null) return inFlight;
        if (hold!=null) return CompletableFuture.failedFuture(new StorageFailure("inventory",hold));
        if (!dirty()) return CompletableFuture.completedFuture(null);
        final InventoryPayload snapshot;
        try { snapshot=Objects.requireNonNull(capture.get()); }
        catch (RuntimeException invalid) { fail("CAPTURE_FAILED"); return CompletableFuture.failedFuture(new StorageFailure("inventory",hold)); }
        long captured=memoryVersion, expected=revision;
        var result=new CompletableFuture<Void>(); inFlight=result;
        try {
            store.saveInventory(context,expected,snapshot).whenCompleteAsync((saved,error) -> {
                checkOwner.run();
                String code=error==null ? null : code(error);
                if (!current.getAsBoolean()) code="STALE_CONTEXT";
                if (code==null && (saved==null || saved.revision()!=expected+1 || !saved.payload().equals(snapshot))) code="INVALID_SAVE_RESULT";
                inFlight=null;
                if (code!=null) { fail(code); result.completeExceptionally(new StorageFailure("inventory",code)); }
                else { revision=saved.revision(); savedVersion=captured; result.complete(null); }
            },owner).exceptionally(deliveryFailure -> {
                // 결과 전달 executor 거부/실패도 호출자의 완료를 무한 대기시키지 않습니다.
                result.completeExceptionally(new StorageFailure("inventory","RESULT_DELIVERY_FAILED"));
                return null;
            });
        } catch (RuntimeException failure) {
            inFlight=null; fail("SUBMIT_FAILED"); result.completeExceptionally(new StorageFailure("inventory",hold));
        }
        return result;
    }
    /** 확실한 SQL 거부/미제출만 수동 재시도합니다. 연결·commit 불명이나 CAS 충돌은 해제하지 않습니다. */
    public void acknowledgeKnownRollback() {
        checkOwner.run();
        if (inFlight!=null || hold==null || !(hold.startsWith("23") || hold.equals("QUEUE_FULL") || hold.equals("SCHEMA_NOT_READY")))
            throw new IllegalStateException("Verified rollback required");
        hold=null;
    }
    private void requireCurrent() { if (!current.getAsBoolean()) throw new StorageFailure("inventory","STALE_CONTEXT"); }
    private void fail(String code) { hold=code; try { errors.accept(code); } catch (RuntimeException ignored) { /* 저장 성공으로 바꾸지 않습니다. */ } }
    public static String code(Throwable error) {
        while (error instanceof CompletionException || error instanceof ExecutionException) error=error.getCause();
        return error instanceof StorageFailure f ? f.code() : "INVENTORY_FAILURE";
    }
}
