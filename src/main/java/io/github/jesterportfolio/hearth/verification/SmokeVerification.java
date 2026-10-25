package io.github.jesterportfolio.hearth.verification;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.domains.Association;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import io.github.jesterportfolio.hearth.world.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Synthetic console probe, not a production progression/lifecycle coordinator. */
public final class SmokeVerification implements AutoCloseable {
    private final JavaPlugin plugin;
    private final String serverVersion;
    private final WorldBackend backend = new PaperWorldBackend();
    private final TemplateStore templates = new TemplateStore();
    private final ExecutorService files = Executors.newSingleThreadExecutor(r -> new Thread(r, "hearth-verification-io"));
    private WorldBackend.Placement template, a, b;
    private String templateDigest;
    private boolean started;
    private volatile boolean closed;

    public SmokeVerification(JavaPlugin plugin) {
        this.plugin = plugin;
        this.serverVersion = Bukkit.getVersion();
    }

    public void start() {
        if (started) { plugin.getLogger().warning("Smoke run already started; refusing duplicate operation"); return; }
        started = true;
        main(() -> {
            template = backend.create(UUID.fromString("00000000-0000-0000-0000-000000000001"), key("template_v1"));
            world(template).getBlockAt(0, 64, 0).setType(Material.STONE, false);
            backend.saveAndUnload(template);
            pass("template_saved_unloaded");
            return null;
        }).thenCompose(v -> io(() -> {
            templateDigest = templates.digest(template.directory());
            return null;
        })).thenCompose(v -> main(() -> {
            a = backend.create(UUID.fromString("00000000-0000-0000-0000-00000000000a"), key("a"));
            b = backend.create(UUID.fromString("00000000-0000-0000-0000-00000000000b"), key("b"));
            check(!a.storyRunId().equals(b.storyRunId()) && !a.key().equals(b.key())
                    && !a.physicalId().equals(b.physicalId()) && !a.directory().equals(b.directory()), "distinct_identifiers");
            backend.saveAndUnload(a);
            backend.saveAndUnload(b);
            return null;
        })).thenCompose(v -> io(() -> {
            templates.copySpatial(template.directory(), a.directory(), templateDigest);
            templates.copySpatial(template.directory(), b.directory(), templateDigest);
            for (var placement : List.of(a, b)) {
                Files.writeString(placement.directory().resolve(".hearth-s4-spatial-ready"),
                        placement.storyRunId() + "/" + placement.key() + "/" + placement.physicalId());
            }
            pass("same_template_distinct_spatial_copies");
            writePlacements();
            return null;
        })).thenCompose(v -> main(() -> {
            World wa = backend.load(a);
            World wb = backend.load(b);
            check(wa.getBlockAt(0, 64, 0).getType() == Material.STONE
                    && wb.getBlockAt(0, 64, 0).getType() == Material.STONE, "template_content_loaded");
            wa.getBlockAt(0, 64, 0).setType(Material.GOLD_BLOCK, false);
            check(wb.getBlockAt(0, 64, 0).getType() == Material.STONE, "a_change_does_not_change_b");
            try {
                backend.create(a.storyRunId(), a.key());
                throw new IllegalStateException("Duplicate allocation accepted");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().startsWith("Refusing existing")) throw expected;
                pass("duplicate_allocation_rejected");
            }
            region(wa, StateFlag.State.DENY);
            region(wb, StateFlag.State.ALLOW);
            check(manager(wa) != manager(wb), "separate_worldguard_managers");
            checkFlags();
            return new RegionManager[] {manager(wa), manager(wb)};
        })).thenCompose(managers -> io(() -> {
            for (RegionManager manager : managers) manager.save();
            pass("worldguard_save_completed");
            return null;
        })).thenCompose(v -> main(() -> {
            backend.saveAndUnload(a);
            backend.saveAndUnload(b);
            pass("a_b_saved_unloaded");
            backend.load(a);
            backend.load(b);
            check(world(a).getBlockAt(0, 64, 0).getType() == Material.GOLD_BLOCK
                    && world(b).getBlockAt(0, 64, 0).getType() == Material.STONE, "reload_preserves_changes_and_ids");
            return new RegionManager[] {manager(world(a)), manager(world(b))};
        })).thenCompose(managers -> io(() -> {
            // Explicit disk reload proves persisted regions, not only surviving in-memory cache.
            for (RegionManager manager : managers) manager.load();
            return null;
        })).thenCompose(v -> main(() -> {
            checkFlags();
            check(world(a).getBlockAt(0, 64, 0).getType() == Material.GOLD_BLOCK
                    && world(b).getBlockAt(0, 64, 0).getType() == Material.STONE, "failure_did_not_change_other_world");
            backend.saveAndUnload(a);
            backend.saveAndUnload(b);
            return null;
        })).thenCompose(v -> io(() -> {
            check(templates.digest(template.directory()).equals(templateDigest), "template_preserved");
            Files.writeString(plugin.getDataFolder().toPath().resolve("smoke-result.txt"),
                    "PASS\nPaper=" + serverVersion + "\nTemplate=" + templateDigest + "\n"
                    + "Scope=console,spatial,region-api; no player events or DB tests\n");
            return null;
        })).whenComplete((v, error) -> {
            if (error == null) plugin.getLogger().info("HEARTH_S4_RESULT PASS");
            else plugin.getLogger().log(Level.SEVERE, "HEARTH_S4_RESULT FAIL; preserve worlds/template for inspection", error);
        });
    }

    private void writePlacements() throws Exception {
        String text = "templateVersion=synthetic-v1\ntemplateSha256=" + templateDigest + "\n";
        for (var p : List.of(template, a, b)) {
            text += p.storyRunId() + "=" + p.key() + "," + p.physicalId() + "," + p.directory() + "\n";
        }
        Files.writeString(plugin.getDataFolder().toPath().resolve("verification-placements.txt"), text);
    }

    private void region(World world, StateFlag.State state) {
        var region = new ProtectedCuboidRegion("probe", BlockVector3.at(-2, 60, -2), BlockVector3.at(2, 70, 2));
        region.setFlag(Flags.BUILD, state);
        RegionManager manager = manager(world);
        if (manager.hasRegion("probe")) throw new IllegalStateException("Existing verification region");
        manager.addRegion(region);
    }
    private void checkFlags() {
        // BUILD requires a subject. This is an explicit synthetic nonmember, not a connected player/event test.
        RegionAssociable subject = regions -> Association.NON_MEMBER;
        boolean allowA = manager(world(a)).getApplicableRegions(BlockVector3.at(0,64,0)).testState(subject, Flags.BUILD);
        boolean allowB = manager(world(b)).getApplicableRegions(BlockVector3.at(0,64,0)).testState(subject, Flags.BUILD);
        check(!allowA && allowB, "worldguard_same_region_id_distinct_flags");
    }
    private RegionManager manager(World world) {
        return Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(world)), "WorldGuard region manager not ready");
    }
    private static World world(WorldBackend.Placement p) { return Objects.requireNonNull(Bukkit.getWorld(p.key())); }
    private static NamespacedKey key(String name) { return new NamespacedKey("hearth_verify", name); }
    private void check(boolean condition, String label) {
        if (!condition) throw new IllegalStateException(label);
        pass(label);
    }
    private void pass(String label) { plugin.getLogger().info("HEARTH_S4_CHECK PASS " + label); }

    private <T> CompletableFuture<T> io(Callable<T> call) {
        return CompletableFuture.supplyAsync(() -> {
            if (closed) throw new CompletionException(new IllegalStateException("Verifier closed"));
            try { return call.call(); } catch (Exception e) { throw new CompletionException(e); }
        }, files);
    }
    private <T> CompletableFuture<T> main(Callable<T> call) {
        var result = new CompletableFuture<T>();
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Verifier closed"));
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                if (closed) { result.completeExceptionally(new IllegalStateException("Verifier closed")); return; }
                if (Bukkit.isTickingWorlds()) { Bukkit.getScheduler().runTask(plugin, this); return; }
                try { result.complete(call.call()); } catch (Exception e) { result.completeExceptionally(e); }
            }
        });
        return result;
    }
    @Override public void close() { closed = true; files.shutdown(); }
}
