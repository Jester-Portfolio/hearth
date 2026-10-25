package io.github.jesterportfolio.hearth.verification;

import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.postgresql.ds.PGSimpleDataSource;

/** 인증 플레이어가 없는 격리 콘솔 시험. 실제 Bukkit 인벤토리/ItemStack·실DB만 사용합니다. */
public final class InventoryVerification implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ConcurrentLinkedQueue<Runnable> deliveries=new ConcurrentLinkedQueue<>();
    private final ServerProgressAccess access=new ServerProgressAccess();
    private final ServerProgressAccess.Context a1=context(10,1),a2=context(10,2),b1=context(11,1),b2=context(11,2);
    private final PGSimpleDataSource source=new PGSimpleDataSource();
    private final PostgresProgressStore store;
    private final PaperInventoryCodec codec=new PaperInventoryCodec();
    private final Inventory view=Bukkit.createInventory(null,45,Component.text("격리 인벤토리 시험"));
    private final AtomicReference<UUID> activeRun=new AtomicReference<>(a1.storyRunId());
    private final BukkitTask pump;
    private InventorySaveSession session;
    private InventoryTransition transition;
    private PaperInventoryAutosave autosave;
    private CompletableFuture<Void> cycle;
    private long cycleStarted,ticks;
    private boolean busy,closed,cycleObserved,raced,writeFailed,s46passed;
    private int applied;

    public InventoryVerification(JavaPlugin plugin,Path root) throws Exception {
        this.plugin=plugin;
        if (!Bukkit.isPrimaryThread() || !root.toRealPath().equals(root) || !root.getParent().equals(Path.of("/tmp"))
                || !root.getFileName().toString().startsWith("hearth-s4-6.") || !Files.getOwner(root).getName().equals(System.getProperty("user.name"))) throw new IllegalStateException("Inventory root rejected");
        Properties endpoint=new Properties(); try (var in=Files.newInputStream(root.resolve("db-endpoint.properties"))) { endpoint.load(in); }
        var pid=Files.readAllLines(root.resolve("data/postmaster.pid"));
        int port=Integer.parseInt(endpoint.getProperty("port"));
        if (!Path.of(pid.get(1)).toRealPath().equals(root.resolve("data")) || port!=Integer.parseInt(pid.get(3))
                || !"hearth_s4_6".equals(endpoint.getProperty("database"))
                || !Files.readString(root.resolve(".hearth-s45-db")).trim().equals(endpoint.getProperty("marker"))) throw new IllegalStateException("Inventory DB identity rejected");
        source.setServerNames(new String[]{"127.0.0.1"}); source.setPortNumbers(new int[]{port}); source.setDatabaseName("hearth_s4_6"); source.setUser("postgres");
        source.setConnectTimeout(2); source.setSocketTimeout(5); source.setPrepareThreshold(0);
        var logger=plugin.getLogger(); store=new PostgresProgressStore(source,access,f -> logger.info("HEARTH_INV_ERROR "+f.operation()+" code="+f.code()+" thread="+Thread.currentThread().getName()));
        pump=Bukkit.getScheduler().runTaskTimer(plugin,() -> { ticks++; for (int i=0;i<200;i++) { var task=deliveries.poll(); if (task==null) break; task.run(); } },1,1);
        log("HEARTH_INV_MODULE READY");
    }
    private ServerProgressAccess.Context context(int run,int player) { return access.forVerifiedServerLink(new UUID(42,run),new UUID(46,player)); }
    private static void main() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Not main thread"); }
    private void log(String text) { plugin.getLogger().info(text); }
    private void check(boolean value,String name) { if (!value) throw new IllegalStateException(name); log("HEARTH_INV_CHECK PASS "+name); }
    private <T> CompletableFuture<T> main(CompletionStage<T> work) {
        return work.handleAsync((value,error) -> { main(); if (closed) throw new StorageFailure("inventory","CLOSED"); if (error!=null) throw new CompletionException(error); return value; },deliveries::add).toCompletableFuture();
    }
    private CompletionStage<Void> expected(CompletionStage<?> work,String code) {
        return main(work.handle((v,e) -> { if (e==null || !InventorySaveSession.code(e).equals(code)) throw new IllegalStateException("Expected "+code); return null; })).thenRun(() -> check(true,"rejected_"+code));
    }
    public void start(String phase) {
        main(); if (busy || closed) { log("HEARTH_INV_COMMAND_REJECTED"); return; } busy=true;
        try {
            CompletionStage<Void> work=switch (phase) {
                case "s46" -> prepare();
                case "race" -> race();
                case "write-failure" -> writeFailure();
                case "recover" -> { session.acknowledgeKnownRollback(); yield main(session.flush()); }
                case "s46-done" -> s46done();
                case "switch" -> switchRun();
                case "logout" -> { if (autosave!=null) autosave.close(); yield main(transition.logout()); }
                case "restore" -> restore();
                case "missing" -> probe("missing");
                case "corrupt" -> probe("corrupt");
                case "apply-failure" -> probe("apply");
                case "source-failure" -> probe("source");
                case "stale" -> probe("stale");
                case "queue" -> queue();
                case "repeat" -> repeat();
                case "logout-race" -> logoutRace();
                case "disconnected" -> disconnected();
                case "close" -> main(store.closeAsync()).thenCompose(v -> expected(store.readInventory(a1),"CLOSED"));
                default -> throw new IllegalArgumentException("Unknown inventory phase");
            };
            work.whenComplete((v,e) -> deliveries.add(() -> { busy=false; if (e==null) log("HEARTH_INV_RESULT PASS "+phase); else plugin.getLogger().severe("HEARTH_INV_RESULT FAIL "+phase+" code="+InventorySaveSession.code(e)+" detail="+failureLabel(e)); }));
        } catch (Exception e) { busy=false; plugin.getLogger().severe("HEARTH_INV_RESULT FAIL "+phase+" detail="+failureLabel(e)); }
    }
    private static String failureLabel(Throwable e) { while (e instanceof CompletionException || e instanceof ExecutionException) e=e.getCause(); return e.getMessage()==null ? e.getClass().getSimpleName() : e.getMessage(); }
    private ItemStack[] fixture(int count) {
        ItemStack[] slots=new ItemStack[41]; slots[0]=new ItemStack(Material.STONE,count); slots[8]=new ItemStack(Material.CARROT,7);
        var sword=new ItemStack(Material.DIAMOND_SWORD); var meta=sword.getItemMeta(); meta.displayName(Component.text("가상 검증 검")); meta.lore(List.of(Component.text("가상 설명")));
        meta.addEnchant(Objects.requireNonNull(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("sharpness"))),3,true); sword.setItemMeta(meta); slots[9]=sword;
        slots[35]=new ItemStack(Material.BREAD,2); slots[36]=new ItemStack(Material.DIAMOND_BOOTS); slots[37]=new ItemStack(Material.DIAMOND_LEGGINGS);
        slots[38]=new ItemStack(Material.DIAMOND_CHESTPLATE); slots[39]=new ItemStack(Material.DIAMOND_HELMET); slots[40]=new ItemStack(Material.SHIELD); return slots;
    }
    private InventoryPayload payload(int count) { return codec.encode(fixture(count)); }
    private void put(InventoryPayload payload) { ItemStack[] decoded=codec.decode(payload); for (int i=0;i<41;i++) view.setItem(i,decoded[i]); }
    private InventoryPayload capture() { return codec.encode(Arrays.copyOf(view.getContents(),41)); }
    private void count(int count) { view.setItem(0,new ItemStack(Material.STONE,count)); session.markDirty(); }
    private InventorySaveSession make(ServerProgressAccess.Context ctx,InventoryStore.Stored saved) {
        return new InventorySaveSession(store,ctx,saved.revision(),this::capture,deliveries::add,InventoryVerification::main,() -> activeRun.get().equals(ctx.storyRunId()),code -> log("HEARTH_INV_SESSION_ERROR "+code));
    }
    private CompletionStage<Void> verify(ServerProgressAccess.Context ctx,int amount,long revision) {
        return main(store.readInventory(ctx)).thenAccept(saved -> {
            check(saved.revision()==revision,"revision_"+ctx.storyRunId()+"_"+ctx.playerId()+"_"+revision);
            ItemStack[] actual=codec.decode(saved.payload()), desired=fixture(amount);
            check(Arrays.equals(actual,desired),"slot_amount_metadata_roundtrip_"+amount);
            log("HEARTH_INV_STATE run="+ctx.storyRunId()+" player="+ctx.playerId()+" amount="+amount+" revision="+saved.revision());
        });
    }
    private CompletionStage<Void> prepare() {
        ItemStack[] original=fixture(64); check(Arrays.equals(original,codec.decode(codec.encode(original))),"NBT_41_slots_metadata_empty_roundtrip");
        byte[][] bad=new byte[41][]; bad[0]=new byte[]{1,2,3};
        try { codec.decode(InventoryPayload.pack(bad)); throw new IllegalStateException("Bad NBT accepted"); } catch (IllegalArgumentException good) { check(true,"corrupt_NBT_rejected"); }
        return expected(store.createInventory(a1,payload(64)),"SCHEMA_NOT_READY")
            .thenCompose(v -> main(store.migrate())).thenCompose(v -> main(store.createRun(a1.storyRunId(),"a")))
            .thenCompose(v -> main(store.createRun(b1.storyRunId(),"b")))
            .thenCompose(v -> main(store.createPlayer(a1,"a1"))).thenCompose(v -> main(store.createPlayer(a2,"a2")))
            .thenCompose(v -> main(store.createPlayer(b1,"b1"))).thenCompose(v -> main(store.createPlayer(b2,"b2")))
            .thenCompose(v -> main(store.createInventory(a1,payload(64)))).thenCompose(v -> main(store.createInventory(a2,payload(7))))
            .thenCompose(v -> main(store.createInventory(b1,payload(15)))).thenCompose(v -> main(store.createInventory(b2,payload(20))))
            .thenCompose(v -> {
                put(payload(64)); session=make(a1,new InventoryStore.Stored(capture(),0));
                return main(session.flush());
            }).thenCompose(v -> verify(a1,64,0)).thenCompose(v -> {
                count(63); cycle=new CompletableFuture<>(); cycleStarted=System.nanoTime();
                autosave=new PaperInventoryAutosave(plugin,List.of(session),() -> {
                    if (cycleObserved) return; cycleObserved=true;
                    check(autosave.period().getSeconds()==30 && System.nanoTime()-cycleStarted>=30_000_000_000L,"actual_30_second_cycle");
                    log("HEARTH_INV_AUTOSAVE elapsed_ms="+(System.nanoTime()-cycleStarted)/1_000_000);
                    main(session.flush()).whenComplete((a,e) -> { if (e==null) cycle.complete(null); else cycle.completeExceptionally(e); });
                });
                return cycle;
            }).thenCompose(v -> verify(a1,63,1)).thenCompose(v -> verify(a2,7,0)).thenCompose(v -> verify(b1,15,0));
    }
    private CompletionStage<Void> race() {
        count(62); var first=session.flush(); check(first==session.flush(),"dirty_requests_coalesced");
        long before=ticks; var changed=new CompletableFuture<Void>();
        Bukkit.getScheduler().runTaskLater(plugin,() -> { count(61); changed.complete(null); },2);
        log("HEARTH_INV_RACE_SUBMITTED");
        return main(first).thenCompose(v -> main(changed)).thenCompose(v -> {
            check(session.dirty() && session.savedVersion()<session.memoryVersion() && ticks-before>=2,"late_save_preserves_new_dirty");
            return verify(a1,62,2);
        }).thenCompose(v -> main(session.flush())).thenCompose(v -> verify(a1,61,3)).thenRun(() -> raced=true);
    }
    private CompletionStage<Void> writeFailure() {
        count(60); return expected(session.flush(),"23514").thenCompose(v -> {
            check(session.dirty() && session.revision()==3,"failed_write_keeps_dirty_revision"); return verify(a1,61,3);
        }).thenRun(() -> writeFailed=true);
    }
    private CompletionStage<Void> s46done() {
        check(cycleObserved && raced && writeFailed && !session.dirty(),"s46_required_gates");
        var probe=new PostgresProgressStore(source,access,f -> log("HEARTH_INV_PROBE_ERROR "+f.code()));
        return main(probe.migrate()).thenCompose(v -> main(probe.readInventory(a1))).thenCompose(saved -> {
            check(saved.revision()==4 && Arrays.equals(codec.decode(saved.payload()),fixture(60)),"new_connection_inventory_restore");
            return main(probe.closeAsync());
        }).thenRun(() -> { s46passed=true; log("HEARTH_S46_GATE PASS"); if (autosave!=null) autosave.close(); });
    }
    private CompletionStage<Void> switchRun() {
        check(s46passed,"s46_before_s47"); count(59);
        transition=new InventoryTransition(store,session,deliveries::add,InventoryVerification::main,codec::decode,(ctx,saved) -> {
            put(saved.payload()); activeRun.set(ctx.storyRunId()); applied++;
        },this::make,code -> log("HEARTH_INV_TRANSITION_ERROR "+code));
        var move=transition.move(b1);
        return expected(transition.move(b1),"BUSY").thenCompose(v -> main(move)).thenCompose(next -> {
            session=next; check(applied==1 && session.context().storyRunId().equals(b1.storyRunId()) && session.revision()==0,"source_saved_before_target_applied");
            check(Arrays.equals(codec.decode(capture()),fixture(15)),"target_inventory_applied"); return verify(a1,59,5);
        }).thenRun(() -> { count(14); log("HEARTH_S47_GATE PASS"); });
    }
    private CompletionStage<Void> restore() {
        return main(store.migrate()).thenCompose(v -> verify(a1,59,5)).thenCompose(v -> verify(b1,14,1))
            .thenCompose(v -> verify(a2,7,0)).thenCompose(v -> verify(b2,20,0))
            .thenCompose(v -> expected(store.readInventory(new ServerProgressAccess().forVerifiedServerLink(a1.storyRunId(),a1.playerId())),"ACCESS_DENIED"))
            .thenCompose(v -> main(store.readInventory(a1))).thenAccept(saved -> { put(saved.payload()); session=make(a1,saved); });
    }
    private CompletionStage<Void> probe(String kind) {
        ServerProgressAccess.Context from=kind.equals("corrupt") ? a1 : a2;
        CompletionStage<InventoryStore.Stored> loaded=kind.equals("stale") ? CompletableFuture.completedFuture(new InventoryStore.Stored(payload(7),0)) : main(store.readInventory(from));
        return loaded.thenCompose(saved -> {
            var local=new AtomicReference<>(saved.payload());
            var s=new InventorySaveSession(store,from,saved.revision(),local::get,deliveries::add,InventoryVerification::main,() -> true,c -> log("HEARTH_INV_PROBE_HOLD "+c));
            var count=new int[1];
            var t=new InventoryTransition(store,s,deliveries::add,InventoryVerification::main,codec::decode,(ctx,target) -> { if (kind.equals("apply")) throw new IllegalStateException("Injected apply failure"); count[0]++; },
                (ctx,target) -> new InventorySaveSession(store,ctx,target.revision(),local::get,deliveries::add,InventoryVerification::main,() -> true,c -> {}),c -> log("HEARTH_INV_PROBE_TRANSITION "+c));
            var target=kind.equals("missing") ? access.forVerifiedServerLink(new UUID(42,99),from.playerId()) : kind.equals("corrupt") ? b1 : b2;
            if (kind.equals("source")) { local.set(payload(6)); s.markDirty(); }
            var moved=t.move(target);
            if (kind.equals("stale")) {
                Bukkit.getScheduler().runTaskLater(plugin,t::invalidate,2); log("HEARTH_INV_STALE_REQUESTED");
            }
            String code=switch(kind) { case "missing" -> "MISSING_INVENTORY"; case "corrupt" -> "INVALID_STATE"; case "source" -> "23514"; case "stale" -> "STALE_CONTEXT"; default -> "APPLY_FAILED"; };
            return expected(moved,code).thenRun(() -> check(count[0]==0 && t.state()==InventoryTransition.State.HOLD,"probe_no_target_apply_"+kind));
        });
    }
    private CompletionStage<Void> queue() {
        var codes=new ConcurrentLinkedQueue<String>(); List<CompletableFuture<?>> work=new ArrayList<>();
        for (int i=0;i<40;i++) work.add(store.readInventory(a1).handle((v,e) -> { codes.add(e==null ? "OK" : InventorySaveSession.code(e)); return null; }).toCompletableFuture());
        log("HEARTH_INV_QUEUE_SUBMITTED");
        return main(CompletableFuture.allOf(work.toArray(CompletableFuture[]::new))).thenRun(() -> check(codes.size()==40 && codes.stream().allMatch(c -> c.equals("OK") || c.equals("QUEUE_FULL")) && codes.stream().filter("QUEUE_FULL"::equals).count()>=22,"inventory_queue_bounded"));
    }
    private CompletionStage<Void> repeat() {
        CompletionStage<Void> work=CompletableFuture.completedFuture(null);
        for (int n=58;n>=56;n--) { int count=n; long revision=64-n;
            work=work.thenCompose(v -> { count(count); return main(session.flush()); }).thenCompose(v -> verify(a1,count,revision));
        }
        return work.thenCompose(v -> verify(b1,14,1));
    }
    private CompletionStage<Void> logoutRace() {
        count(55); var prior=session.flush(); count(54);
        transition=new InventoryTransition(store,session,deliveries::add,InventoryVerification::main,codec::decode,(a,b) -> {},this::make,c -> {});
        var finish=transition.logout(); log("HEARTH_INV_LOGOUT_SUBMITTED");
        return expected(session.flush(),"CLOSED").thenCompose(v -> main(prior)).thenCompose(v -> main(finish))
            .thenCompose(v -> verify(a1,54,10)).thenRun(() -> check(!session.dirty(),"logout_pending_latest_drained"));
    }
    private CompletionStage<Void> disconnected() {
        var saved=payload(7);
        var s=new InventorySaveSession(store,a2,0,() -> saved,deliveries::add,InventoryVerification::main,() -> true,c -> log("HEARTH_INV_DISCONNECT_HOLD "+c)); s.markDirty();
        return main(s.flush().handle((v,e) -> { if (e==null || !(InventorySaveSession.code(e).startsWith("08") || InventorySaveSession.code(e).equals("57P01"))) throw new IllegalStateException("Expected connection failure"); return null; }))
            .thenRun(() -> check(s.dirty() && s.holdCode()!=null,"connection_failure_keeps_dirty"));
    }
    @Override public void close() {
        if (autosave!=null) autosave.close(); closed=true; pump.cancel(); deliveries.clear();
        store.closeAsync().whenComplete((v,e) -> plugin.getLogger().info("HEARTH_INV_DISABLE_CLOSE "+(e==null ? "PASS" : "FAIL")));
    }
}
