package io.github.jesterportfolio.hearth.session;

import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.lifecycle.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 서버가 검증한 연결만 받는 owner 스레드 조정자. 소켓 수명과 DB 저장 정리의 수명을 분리합니다. */
public final class PlayerSessions {
    public enum State { WAITING, PREPARING, MOVING, ACTIVE, CLOSING, CLOSED, HOLD }
    public interface Port {
        boolean connected();
        InventoryPayload capture();
        void apply(InventoryPayload value);
        CompletionStage<Boolean> move();
    }
    private record Key(UUID run,UUID player) {}
    public final class Session {
        private final ServerProgressAccess.Context context;
        private final WorldLifecycle world;
        private final Port port;
        private final UUID request=UUID.randomUUID();
        private final Key key;
        private final CompletableFuture<Void> ready=new CompletableFuture<>(),closed=new CompletableFuture<>();
        private CompletionStage<Void> opening;
        private WorldLifecycle.Admission admission;
        private State state=State.WAITING;
        private InventoryPayload snapshot;
        private InventorySaveSession save;
        private InventoryTransition transition;
        private boolean connected=true,leased,joined,closing,leaseValid=true;
        private String error;
        private Session(ServerProgressAccess.Context context,WorldLifecycle world,Port port) {
            this.context=context; this.world=world; this.port=port; key=new Key(context.storyRunId(),context.playerId());
        }
        public State state() { check.run(); return state; }
        public CompletionStage<Void> ready() { return ready; }
        public CompletionStage<Void> closed() { return closed; }
        public InventorySaveSession save() { check.run(); return save; }
        public ServerProgressAccess.Context context() { return context; }
        public String error() { check.run(); return error; }
    }
    private final ProgressStore progress;
    private final InventoryStore inventories;
    private final Executor owner;
    private final Runnable check;
    private final Consumer<InventoryPayload> validate;
    private final BiPredicate<InventoryPayload,InventoryPayload> equivalent;
    private final Consumer<String> errors;
    private final Consumer<InventorySaveSession> register,unregister;
    private final Map<UUID,Session> connections=new HashMap<>();
    private final Map<Key,Session> leases=new HashMap<>();
    private boolean stopping;
    public PlayerSessions(ProgressStore progress,InventoryStore inventories,Executor owner,Runnable check,Consumer<InventoryPayload> validate,
            Consumer<InventorySaveSession> register,Consumer<InventorySaveSession> unregister,Consumer<String> errors) {
        this(progress,inventories,owner,check,validate,InventoryPayload::equals,register,unregister,errors);
    }
    public PlayerSessions(ProgressStore progress,InventoryStore inventories,Executor owner,Runnable check,Consumer<InventoryPayload> validate,
            BiPredicate<InventoryPayload,InventoryPayload> equivalent,Consumer<InventorySaveSession> register,Consumer<InventorySaveSession> unregister,Consumer<String> errors) {
        this.equivalent=Objects.requireNonNull(equivalent);
        this.progress=Objects.requireNonNull(progress);this.inventories=Objects.requireNonNull(inventories);this.owner=Objects.requireNonNull(owner);
        this.check=Objects.requireNonNull(check);this.validate=Objects.requireNonNull(validate);this.register=Objects.requireNonNull(register);
        this.unregister=Objects.requireNonNull(unregister);this.errors=Objects.requireNonNull(errors);
    }
    public Session connect(ServerProgressAccess.Context ctx,WorldLifecycle world,Port port) {
        check.run();
        if (stopping || !world.mapping().storyRunId().equals(ctx.storyRunId()) || connections.containsKey(ctx.playerId())) throw new LifecycleFailure("CONNECTION_REJECTED");
        var s=new Session(ctx,world,port);connections.put(ctx.playerId(),s);
        var prior=leases.get(s.key);
        CompletionStage<Void> wait=prior==null ? CompletableFuture.completedFuture(null) : prior.closed;
        s.opening=wait.thenComposeAsync(v -> {
            requireClient(s);leases.put(s.key,s);s.leased=true;s.state=State.PREPARING;
            return world.reserve(s.request,ctx.playerId());
        },owner).thenComposeAsync(admission -> {
            s.admission=admission;requireAdmission(s);
            return progress.readPersonal(ctx).thenCompose(v -> inventories.readInventory(ctx))
                .thenComposeAsync(saved -> {
                    requireAdmission(s);validate.accept(saved.payload());s.snapshot=saved.payload();
                    s.save=makeSave(s,ctx,saved);
                    s.transition=new InventoryTransition(inventories,s.save,owner,check,validate,(target,row) -> {
                        requireClient(s);s.snapshot=row.payload();s.port.apply(row.payload());
                    },(target,row) -> makeSave(s,target,row),code -> report(code));
                    s.port.apply(saved.payload());s.state=State.MOVING;
                    return s.port.move().thenAcceptAsync(moved -> {
                        requireAdmission(s);
                        if (!Boolean.TRUE.equals(moved) || !world.commit(admission)) throw new LifecycleFailure("MOVE_FAILED");
                        s.joined=true;s.state=State.ACTIVE;register.accept(s.save);s.ready.complete(null);
                    },owner);
                },owner);
        },owner).whenCompleteAsync((v,error) -> {
            if (error==null) return;
            String code=LifecycleFailure.code(error);
            if (s.closing && (code.equals("STALE_SESSION") || code.equals("CANCELLED"))) s.ready.completeExceptionally(new LifecycleFailure(code));
            else hold(s,code);
        },owner);
        return s;
    }
    private InventorySaveSession makeSave(Session s,ServerProgressAccess.Context ctx,InventoryStore.Stored saved) {
        if (!ctx.storyRunId().equals(s.key.run()) || !ctx.playerId().equals(s.key.player())) throw new LifecycleFailure("OWNER_MISMATCH");
        return new InventorySaveSession(inventories,ctx,saved.revision(),() -> s.snapshot,owner,check,
            () -> leases.get(s.key)==s && s.leaseValid,code -> hold(s,code));
    }
    private void requireClient(Session s) {
        check.run();
        if (!s.connected || s.closing || connections.get(s.key.player())!=s || !s.port.connected()) throw new LifecycleFailure("STALE_SESSION");
    }
    private void requireAdmission(Session s) {
        requireClient(s);
        if (s.admission==null || s.world.generation()!=s.admission.generation()
                || !(s.world.state()==WorldLifecycle.State.READY || s.world.state()==WorldLifecycle.State.ACTIVE)) throw new LifecycleFailure("STALE_WORLD");
    }
    public boolean playable(Session s) { check.run();return s!=null && s.state==State.ACTIVE && s.connected && connections.get(s.key.player())==s
        && s.port.connected() && s.world.generation()==s.admission.generation() && s.world.state()==WorldLifecycle.State.ACTIVE; }
    public void refresh(Session s) {
        check.run();if (!playable(s)) return;
        try { update(s,s.port.capture()); } catch (Exception e) { hold(s,LifecycleFailure.code(e)); }
    }
    private void update(Session s,InventoryPayload value) {
        validate.accept(value);
        if (!equivalent.test(value,s.snapshot)) { s.snapshot=value;s.save.markDirty(); }
    }
    public void refreshAll() { check.run();for (var s:List.copyOf(connections.values())) refresh(s); }
    public CompletionStage<Void> disconnect(Session s) {
        check.run();Objects.requireNonNull(s);
        if (s.closing || s.closed.isDone()) return s.closed;
        s.closing=true;boolean active=s.state==State.ACTIVE,moving=s.state==State.MOVING;
        if (active) {
            try { update(s,s.port.capture()); } catch (Exception e) { hold(s,LifecycleFailure.code(e));return s.closed; }
        }
        s.connected=false;connections.remove(s.key.player(),s);
        if (s.save!=null) unregister.accept(s.save);
        s.state=State.CLOSING;
        // ACTIVE의 참가 카운트는 즉시 줄여도 퇴장 저장 lease는 장벽에 계속 남습니다.
        if (s.joined) { s.world.leave(s.key.player());s.joined=false; }
        if (active) closeSave(s);
        else {
            if (!moving) s.world.cancel(s.request);
            s.opening.whenCompleteAsync((v,error) -> { if (s.state!=State.HOLD) closeSave(s); },owner);
        }
        return s.closed;
    }
    private void closeSave(Session s) {
        check.run();s.world.cancel(s.request);
        CompletionStage<Void> work=s.transition==null ? CompletableFuture.completedFuture(null) : s.transition.logout();
        work.whenCompleteAsync((v,error) -> {
            if (error!=null) { hold(s,LifecycleFailure.code(error));return; }
            s.state=State.CLOSED;s.leaseValid=false;leases.remove(s.key,s);connections.remove(s.key.player(),s);s.closed.complete(null);
        },owner);
    }
    private void hold(Session s,String code) {
        check.run();s.state=State.HOLD;s.error=code;s.connected=false;connections.remove(s.key.player(),s);
        if (s.save!=null) unregister.accept(s.save);
        if (s.joined) { s.world.leave(s.key.player());s.joined=false; }
        s.world.cancel(s.request);s.ready.completeExceptionally(new LifecycleFailure(code));s.closed.completeExceptionally(new LifecycleFailure(code));report(code);
    }
    private void report(String code) { try { errors.accept(code); } catch (RuntimeException ignored) { /* 실패를 성공으로 바꾸지 않습니다. */ } }
    /** 활성 참가자는 방해 요소이며, 퇴장/준비 정리는 해당 run의 모든 lease를 기다립니다. */
    public CompletionStage<Void> barrier(UUID run) {
        check.run();var tasks=new ArrayList<CompletableFuture<?>>();
        for (var s:leases.values()) if (s.key.run().equals(run)) {
            if (s.connected && !s.closing && s.state!=State.HOLD) return CompletableFuture.failedFuture(new LifecycleFailure("ACTIVE_SESSION"));
            tasks.add(s.closed);
        }
        return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));
    }
    public boolean canUnload(UUID run) { check.run();return leases.values().stream().noneMatch(s -> s.key.run().equals(run) && (s.save!=null || s.state==State.HOLD)); }
    public boolean clear(UUID run) { check.run();return leases.keySet().stream().noneMatch(k -> k.run().equals(run)); }
    public CompletionStage<Void> shutdown() {
        check.run();stopping=true;
        for (var s:List.copyOf(connections.values())) disconnect(s);
        var tasks=leases.values().stream().map(s -> s.closed).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(tasks);
    }
}
