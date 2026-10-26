package io.github.jesterportfolio.hearth.lifecycle;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** 검증된 한 진행의 owner 스레드 조정자. 예약/온라인 참가는 게임 내 가입 기능이 아닙니다. */
public final class WorldLifecycle {
    public enum State { OFFLINE, LOADING, READY, ACTIVE, IDLE, QUIESCING, SAVING, UNLOADING, ERROR_HOLD, RECOVERY_REQUIRED }
    public record Mapping(UUID storyRunId,UUID physicalId,String worldKey,Path directory) {
        public Mapping {
            Objects.requireNonNull(storyRunId); Objects.requireNonNull(physicalId); Objects.requireNonNull(worldKey); Objects.requireNonNull(directory);
            if (!directory.isAbsolute() || !directory.normalize().equals(directory)) throw new IllegalArgumentException("Normalized mapping required");
        }
    }
    public interface Operations {
        CompletionStage<Void> loadAndPrepare();
        CompletionStage<Void> quiesce();
        CompletionStage<Void> save();
        CompletionStage<Boolean> unload();
    }
    public interface Timer { Runnable schedule(Duration delay,Runnable action); }
    public record Admission(UUID requestId,UUID participant,long generation) {}
    private static final class Ticket {
        final UUID player; final CompletableFuture<Admission> future=new CompletableFuture<>();
        Admission admission;
        Ticket(UUID player) { this.player=player; }
    }
    private final Mapping mapping;
    private final Operations operations;
    private final Executor owner;
    private final Runnable checkOwner;
    private final Timer timer;
    private final Duration idleDelay;
    private final int capacity;
    private final Consumer<State> events;
    private State state=State.OFFLINE;
    private long generation,idleToken;
    private Runnable cancelTimer=() -> {};
    private final Set<UUID> participants=new HashSet<>();
    private final Map<UUID,Ticket> pending=new LinkedHashMap<>();
    private final LinkedHashMap<UUID,Admission> committed=new LinkedHashMap<>();
    private CompletableFuture<Void> load, cycle=CompletableFuture.completedFuture(null);
    private String failure;

    public WorldLifecycle(Mapping mapping,Operations operations,Executor owner,Runnable checkOwner,Timer timer,Duration idleDelay,int capacity,Consumer<State> events) {
        if (idleDelay.isNegative() || idleDelay.isZero() || capacity<1) throw new IllegalArgumentException("Explicit positive idle delay/capacity required");
        this.mapping=Objects.requireNonNull(mapping); this.operations=Objects.requireNonNull(operations); this.owner=Objects.requireNonNull(owner);
        this.checkOwner=Objects.requireNonNull(checkOwner); this.timer=Objects.requireNonNull(timer); this.idleDelay=idleDelay; this.capacity=capacity; this.events=Objects.requireNonNull(events);
    }
    public Mapping mapping() { return mapping; }
    public State state() { checkOwner.run(); return state; }
    public long generation() { checkOwner.run(); return generation; }
    public int participantCount() { checkOwner.run(); return participants.size(); }
    public int pendingCount() { checkOwner.run(); return pending.size(); }
    public int readyReservationCount() { checkOwner.run(); return (int)pending.values().stream().filter(t -> t.admission!=null && t.admission.generation()==generation).count(); }
    public String failureCode() { checkOwner.run(); return failure; }
    public CompletionStage<Void> currentSave() { checkOwner.run(); return cycle; }
    public boolean mayChange() { checkOwner.run(); return state==State.READY || state==State.ACTIVE || state==State.IDLE; }

    public CompletionStage<Admission> reserve(UUID request,UUID player) {
        checkOwner.run(); Objects.requireNonNull(request); Objects.requireNonNull(player);
        if (held()) return failed("HELD");
        Ticket existing=pending.get(request);
        if (existing!=null) return existing.player.equals(player) ? existing.future : failed("REQUEST_OWNER_MISMATCH");
        Admission prior=committed.get(request);
        if (prior!=null) return prior.participant().equals(player) && participants.contains(player) && prior.generation()==generation
                ? CompletableFuture.completedFuture(prior) : failed("COMPLETED_REQUEST");
        if (pending.size()>=capacity) return failed("RESERVATION_FULL");
        var ticket=new Ticket(player); pending.put(request,ticket);
        if (state==State.IDLE) { cancelIdle(); set(State.READY); }
        if (state==State.OFFLINE) beginLoad();
        else if (state==State.READY || state==State.ACTIVE) admit(request,ticket);
        // LOADING은 단일 작업을 공유하고 저장 중 요청에는 준비 완료를 반환하지 않습니다.
        return ticket.future;
    }
    public boolean commit(Admission admission) {
        checkOwner.run(); Objects.requireNonNull(admission);
        Admission done=committed.get(admission.requestId());
        if (done!=null) return done.equals(admission) && participants.contains(admission.participant()) && admission.generation()==generation;
        Ticket ticket=pending.get(admission.requestId());
        if (ticket==null || ticket.admission!=admission || admission.generation()!=generation || !(state==State.READY || state==State.ACTIVE)) return false;
        pending.remove(admission.requestId()); participants.add(admission.participant()); committed.put(admission.requestId(),admission);
        while (committed.size()>capacity) committed.remove(committed.keySet().iterator().next());
        set(State.ACTIVE); return true;
    }
    public void cancel(UUID request) {
        checkOwner.run(); var ticket=pending.remove(request);
        if (ticket!=null && !ticket.future.isDone()) ticket.future.completeExceptionally(new LifecycleFailure("CANCELLED"));
        idleIfEmpty();
    }
    public void leave(UUID player) { checkOwner.run(); participants.remove(player); idleIfEmpty(); }
    public void idleIfEmpty() {
        checkOwner.run();
        if (participants.isEmpty() && pending.isEmpty() && (state==State.ACTIVE || state==State.READY)) beginIdle();
    }
    public void invalidate(String reason) {
        checkOwner.run(); generation++; cancelIdle(); failure=reason; set(State.RECOVERY_REQUIRED);
        rejectPending("STALE_GENERATION");
        if (load!=null) load.completeExceptionally(new LifecycleFailure("STALE_GENERATION"));
        cycle.completeExceptionally(new LifecycleFailure("STALE_GENERATION"));
    }
    private boolean held() { return state==State.ERROR_HOLD || state==State.RECOVERY_REQUIRED; }
    private void beginLoad() {
        long epoch=++generation; set(State.LOADING); load=new CompletableFuture<>();
        CompletionStage<Void> work;
        try { work=operations.loadAndPrepare(); } catch (Exception error) { work=CompletableFuture.failedFuture(error); }
        work.whenCompleteAsync((v,error) -> {
            checkOwner.run();
            if (epoch!=generation || state!=State.LOADING) return;
            if (error!=null) { hold(State.RECOVERY_REQUIRED,error); load.completeExceptionally(error); return; }
            set(State.READY);
            for (var entry:new ArrayList<>(pending.entrySet())) admit(entry.getKey(),entry.getValue());
            load.complete(null);
            if (pending.isEmpty() && participants.isEmpty()) beginIdle();
        },owner);
    }
    private void admit(UUID request,Ticket ticket) {
        ticket.admission=new Admission(request,ticket.player,generation); ticket.future.complete(ticket.admission);
    }
    private void beginIdle() {
        set(State.IDLE); long token=++idleToken, epoch=generation;
        cancelTimer=timer.schedule(idleDelay,() -> {
            checkOwner.run();
            if (token!=idleToken || epoch!=generation || state!=State.IDLE || !participants.isEmpty() || !pending.isEmpty()) return;
            beginSave(epoch);
        });
    }
    private void cancelIdle() { idleToken++; cancelTimer.run(); cancelTimer=() -> {}; }
    private void beginSave(long epoch) {
        cancelIdle(); set(State.QUIESCING); cycle=new CompletableFuture<>();
        CompletionStage<Void> work;
        try { work=operations.quiesce(); } catch (Exception error) { work=CompletableFuture.failedFuture(error); }
        work.thenComposeAsync(v -> {
            require(epoch,State.QUIESCING); set(State.SAVING); return operations.save();
        },owner).thenComposeAsync(v -> {
            require(epoch,State.SAVING); set(State.UNLOADING); return operations.unload();
        },owner).whenCompleteAsync((ok,error) -> {
            checkOwner.run(); if (epoch!=generation || state==State.RECOVERY_REQUIRED) return;
            if (error!=null || !Boolean.TRUE.equals(ok)) { hold(State.ERROR_HOLD,error==null ? new LifecycleFailure("UNLOAD_FALSE") : error); cycle.completeExceptionally(new LifecycleFailure(failure)); return; }
            generation++; set(State.OFFLINE); cycle.complete(null);
            if (!pending.isEmpty()) beginLoad();
        },owner);
    }
    private void require(long epoch,State expected) {
        checkOwner.run(); if (epoch!=generation || state!=expected) throw new LifecycleFailure("STALE_GENERATION");
        if (!participants.isEmpty() || readyReservationCount()!=0) throw new LifecycleFailure("OCCUPIED");
    }
    private void hold(State target,Throwable error) {
        failure=LifecycleFailure.code(error); cancelIdle(); set(target); rejectPending(failure);
    }
    private void rejectPending(String reason) {
        pending.values().forEach(t -> t.future.completeExceptionally(new LifecycleFailure(reason))); pending.clear();
    }
    private void set(State next) { state=next; try { events.accept(next); } catch (RuntimeException ignored) { /* 관측 오류로 상태를 성공 처리하지 않습니다. */ } }
    private static <T> CompletionStage<T> failed(String code) { return CompletableFuture.failedFuture(new LifecycleFailure(code)); }
}
