package io.github.jesterportfolio.hearth.lifecycle;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import io.github.jesterportfolio.hearth.world.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;

/** 기존 월드 전용 경계. World 전체를 I/O worker로 넘기지 않고 RegionManager의 허용된 I/O만 분리합니다. */
public final class PaperLifecycleBoundary implements WorldPersistence.Boundary {
    public interface Data {
        CompletionStage<Void> restore();
        CompletionStage<Void> drain();
        CompletionStage<Void> verifyShared();
        boolean ownReferencesClear();
        void release();
    }
    private final JavaPlugin plugin;
    private final WorldBackend.Placement placement;
    private final String metadataHash;
    private final PaperWorldBackend backend;
    private final Executor io, owner;
    private final LongSupplier generation;
    private final Data data;
    private final Consumer<RegionManager> validateRegions;
    private final Consumer<String> before;
    private RegionManager regions;
    private boolean registered;

    public PaperLifecycleBoundary(JavaPlugin plugin,WorldBackend.Placement placement,String metadataHash,PaperWorldBackend backend,Executor io,Executor owner,
            LongSupplier generation,Data data,Consumer<RegionManager> validateRegions,Consumer<String> before) {
        this.plugin=Objects.requireNonNull(plugin); this.placement=Objects.requireNonNull(placement); this.metadataHash=Objects.requireNonNull(metadataHash);
        this.backend=Objects.requireNonNull(backend); this.io=Objects.requireNonNull(io); this.owner=Objects.requireNonNull(owner); this.generation=Objects.requireNonNull(generation);
        this.data=Objects.requireNonNull(data); this.validateRegions=Objects.requireNonNull(validateRegions); this.before=Objects.requireNonNull(before);
    }
    private <T> CompletionStage<T> safe(String stage,Callable<T> call) {
        if (!Bukkit.isPrimaryThread()) return CompletableFuture.failedFuture(new LifecycleFailure("NOT_MAIN"));
        long epoch=generation.getAsLong(); var result=new CompletableFuture<T>();
        result.orTimeout(10,TimeUnit.SECONDS);
        Bukkit.getScheduler().runTask(plugin,new Runnable() {
            @Override public void run() {
                if (result.isDone()) return;
                if (!plugin.isEnabled()) { result.completeExceptionally(new LifecycleFailure("CLOSED")); return; }
                if (epoch!=generation.getAsLong()) { result.completeExceptionally(new LifecycleFailure("STALE_GENERATION")); return; }
                if (Bukkit.isTickingWorlds()) { Bukkit.getScheduler().runTaskLater(plugin,this,1); return; }
                try { before.accept(stage); result.complete(call.call()); } catch (Exception e) { result.completeExceptionally(e); }
            }
        });
        return result;
    }
    private <T> CompletionStage<T> file(String stage,Callable<T> call) {
        try { before.accept(stage); }
        catch (Exception e) { return CompletableFuture.failedFuture(e); }
        return CompletableFuture.supplyAsync(() -> {
            try { return call.call(); } catch (Exception e) { throw new CompletionException(e); }
        },io);
    }
    @Override public CompletionStage<Void> verifyStoredMapping() {
        return file("mapping",() -> {
            Path directory=placement.directory();
            if (!directory.toRealPath().equals(directory)) throw new LifecycleFailure("WORLD_PATH_ALIAS");
            String marker=Files.readString(directory.resolve(".hearth-s4-spatial-ready")).trim();
            if (!marker.equals(placement.storyRunId()+"/"+placement.key()+"/"+placement.physicalId())
                || !Files.isRegularFile(directory.resolve("region/r.0.0.mca"))) throw new LifecycleFailure("WORLD_MAPPING_MISMATCH");
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(directory.resolve("data/paper/metadata.dat"))));
            if (!metadataHash.equals(digest)) throw new LifecycleFailure("WORLD_METADATA_MISMATCH");
            return null;
        });
    }
    @Override public CompletionStage<Void> loadWorld() {
        return safe("load",() -> {
            if (!registered) { backend.registerExisting(placement); registered=true; }
            backend.load(placement); return null;
        });
    }
    @Override public CompletionStage<Void> restoreRegions() {
        return safe("region_manager",() -> {
            regions=Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world())),"Missing WorldGuard manager");
            return regions;
        }).thenCompose(manager -> file("regions_load",() -> { manager.load(); return null; }))
            .thenComposeAsync(v -> safe("region_verify",() -> { validateRegions.accept(regions); return null; }),owner);
    }
    @Override public CompletionStage<Void> verifyProgressAndInventory() { before.accept("data_load"); return data.restore(); }
    @Override public CompletionStage<Void> drainInventory() { before.accept("inventory_drain"); return data.drain(); }
    @Override public CompletionStage<Void> verifyProgress() { before.accept("progress_verify"); return data.verifyShared(); }
    @Override public CompletionStage<Void> saveRegions() {
        RegionManager manager=Objects.requireNonNull(regions,"WorldGuard manager not prepared");
        return file("regions_save",() -> { manager.save(); return null; });
    }
    @Override public CompletionStage<Void> saveWorld() { return safe("world_save",() -> { backend.save(placement); return null; }); }
    @Override public CompletionStage<Void> checkUnloadBlockers() {
        return safe("blockers",() -> {
            World world=world();
            if (!world.getPlayers().isEmpty() || !world.getPluginChunkTickets().isEmpty() || !world.getForceLoadedChunks().isEmpty() || !data.ownReferencesClear())
                throw new LifecycleFailure("UNLOAD_BLOCKERS");
            return null;
        });
    }
    @Override public CompletionStage<Boolean> unloadWorld() { return safe("unload",() -> backend.unload(placement)); }
    @Override public void clearWorldReferences() { regions=null; data.release(); }
    private World world() {
        World world=Objects.requireNonNull(Bukkit.getWorld(placement.key()),"World not loaded");
        if (!world.getUID().equals(placement.physicalId())) throw new LifecycleFailure("WORLD_UUID_MISMATCH");
        return world;
    }
}
