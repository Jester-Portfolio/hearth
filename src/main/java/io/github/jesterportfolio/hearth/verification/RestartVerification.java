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
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Two-process synthetic fixture. No player simulation, DB, lifecycle scheduler or protection engine. */
public final class RestartVerification implements AutoCloseable, Listener {
    private final JavaPlugin plugin;
    private final PaperWorldBackend backend = new PaperWorldBackend();
    private final TemplateStore templates = new TemplateStore();
    private final ExecutorService files = Executors.newSingleThreadExecutor(r -> new Thread(r, "hearth-restart-io"));
    private final Path mapping;
    private final Properties evidence = new Properties();
    private final Map<NamespacedKey, List<Boolean>> pistonEvents = new HashMap<>();
    private final String bootId = UUID.randomUUID().toString();
    private WorldBackend.Placement template, a, b;
    private String phase, templateDigest;
    private boolean started;
    private volatile boolean closed;

    public RestartVerification(JavaPlugin plugin) {
        this.plugin = plugin;
        mapping = plugin.getDataFolder().toPath().resolve("restart-mapping.properties");
    }

    public void start(String phase) {
        if (started) throw new IllegalStateException("One restart phase per JVM");
        started = true;
        this.phase = phase;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        CompletableFuture<Void> work = phase.equals("prepare") ? prepare() : resume();
        work.thenCompose(v -> main(() -> {
            apiChecks();
            snapshot();
            powerPistons();
            return null;
        })).thenCompose(v -> awaitPistons()).thenCompose(v -> main(() -> {
            checkPistons();
            clearPistons();
            snapshot();
            HandlerList.unregisterAll(this);
            return new RegionManager[] {manager(world(a)), manager(world(b))};
        })).thenCompose(managers -> io(() -> {
            for (RegionManager manager : managers) manager.save();
            pass("worldguard_save_completed");
            return null;
        })).thenCompose(v -> main(() -> {
            timed("save_unload_a", () -> { backend.saveAndUnload(a); return null; });
            timed("save_unload_b", () -> { backend.saveAndUnload(b); return null; });
            return null;
        })).thenCompose(v -> io(() -> {
            check(templates.digest(template.directory()).equals(templateDigest), "template_preserved");
            String digestA = templates.digest(a.directory());
            String digestB = templates.digest(b.directory());
            if (phase.equals("prepare")) {
                evidence.setProperty("templateDigest", templateDigest);
                evidence.setProperty("spatial.a", digestA);
                evidence.setProperty("spatial.b", digestB);
                evidence.setProperty("prepareBootId", bootId);
                evidence.setProperty("templateVersion", "synthetic-restart-v1");
                for (var p : List.of(template, a, b)) {
                    String id = p.key().getKey();
                    evidence.setProperty(id + ".run", p.storyRunId().toString());
                    evidence.setProperty(id + ".uuid", p.physicalId().toString());
                    evidence.setProperty(id + ".metadataSha256", metadataDigest(p));
                }
                try (var out = Files.newOutputStream(mapping, StandardOpenOption.CREATE_NEW)) {
                    evidence.store(out, "S4-2 test mapping only, not a PostgreSQL progress manager");
                }
                pass("mapping_and_spatial_saved");
            } else {
                // Chunk tick/save timestamps may legitimately change after load; retain new hashes, do not call this corruption.
                evidence.setProperty("postResumeSpatial.a", digestA);
                evidence.setProperty("postResumeSpatial.b", digestB);
            }
            Properties result = new Properties();
            result.putAll(evidence);
            result.setProperty("phase", phase);
            result.setProperty("bootId", bootId);
            result.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
            result.setProperty("scope", "region API and natural piston event; connected player tests NOT RUN");
            try (var out = Files.newOutputStream(mapping.resolveSibling("restart-" + phase + "-result.properties"),
                    StandardOpenOption.CREATE_NEW)) {
                result.store(out, "PASS");
            }
            return null;
        })).whenComplete((v, error) -> {
            if (error == null) plugin.getLogger().info("HEARTH_S4_2_RESULT PASS " + phase);
            else plugin.getLogger().log(Level.SEVERE, "HEARTH_S4_2_RESULT FAIL " + phase + "; preserve fixture", error);
        });
    }

    private CompletableFuture<Void> prepare() {
        return main(() -> {
            if (Files.exists(mapping)) throw new IllegalStateException("Refusing to overwrite mapping");
            template = timed("create_template", () -> backend.create(run(1), key("restart_template")));
            world(template).getBlockAt(0, 64, 0).setType(Material.STONE, false);
            // Load only the two chunks required for the real piston fixture into the template.
            world(template).getChunkAt(-1, 0);
            timed("save_unload_template", () -> { backend.saveAndUnload(template); return null; });
            a = timed("create_a", () -> backend.create(run(10), key("restart_a")));
            b = timed("create_b", () -> backend.create(run(11), key("restart_b")));
            check(!a.physicalId().equals(b.physicalId()) && !a.directory().equals(b.directory()), "distinct_world_identity");
            backend.saveAndUnload(a);
            backend.saveAndUnload(b);
            return null;
        }).thenCompose(v -> io(() -> {
            templateDigest = templates.digest(template.directory());
            for (var p : List.of(a, b)) {
                templates.copySpatial(template.directory(), p.directory(), templateDigest);
            }
            for (var p : List.of(template, a, b)) {
                Files.writeString(p.directory().resolve(".hearth-s4-spatial-ready"),
                        p.storyRunId() + "/" + p.key() + "/" + p.physicalId(), StandardOpenOption.CREATE_NEW);
            }
            pass("same_template_copied");
            return null;
        })).thenCompose(v -> main(() -> {
            timed("load_a", () -> backend.load(a));
            timed("load_b", () -> backend.load(b));
            check(world(a).getBlockAt(0,64,0).getType() == Material.STONE
                    && world(b).getBlockAt(0,64,0).getType() == Material.STONE, "initial_template_blocks");
            world(a).getBlockAt(0,64,0).setType(Material.GOLD_BLOCK, false);
            world(b).getBlockAt(0,64,0).setType(Material.DIAMOND_BLOCK, false);
            region(world(a), "protected", 0, 15, 0, StateFlag.State.DENY);
            region(world(a), "build", 6, 10, 10, StateFlag.State.ALLOW);
            region(world(a), "overlap", 8, 9, 20, StateFlag.State.DENY);
            region(world(b), "protected", 0, 15, 0, StateFlag.State.ALLOW);
            return null;
        }));
    }

    private CompletableFuture<Void> resume() {
        return resume(true);
    }

    /** Manual client sessions may have legitimately edited blocks; identity/template guards still apply. */
    public void openForPlayers() {
        if (started) throw new IllegalStateException("One verification phase per JVM");
        started = true;
        phase = "player";
        resume(false).thenCompose(v -> main(() -> {
            apiChecks(); // Reuse the existing region/priority gate before accepting manual fixture commands.
            return null;
        })).whenComplete((v, error) -> {
            if (error == null) plugin.getLogger().info("HEARTH_S4_3_READY; client actions NOT YET VERIFIED");
            else plugin.getLogger().log(Level.SEVERE, "HEARTH_S4_3_FAIL; preserve fixture", error);
        });
    }

    private CompletableFuture<Void> resume(boolean strictSpatial) {
        return io(() -> {
            try (var in = Files.newInputStream(mapping)) { evidence.load(in); }
            if (!"synthetic-restart-v1".equals(evidence.getProperty("templateVersion"))
                    || bootId.equals(evidence.getProperty("prepareBootId"))) {
                throw new IllegalStateException("Invalid template version or same boot");
            }
            templateDigest = Objects.requireNonNull(evidence.getProperty("templateDigest"));
            return null;
        }).thenCompose(v -> main(() -> {
            template = recorded("restart_template", 1);
            a = recorded("restart_a", 10);
            b = recorded("restart_b", 11);
            for (var p : List.of(template, a, b)) backend.registerExisting(p);
            Path missing = a.directory().resolveSibling("restart_missing");
            try {
                backend.registerExisting(new WorldBackend.Placement(run(99), key("restart_missing"), run(99), missing));
                throw new AssertionError("Missing world accepted");
            } catch (IllegalStateException expected) {
                check(!Files.exists(missing) && Bukkit.getWorld(key("restart_missing")) == null,
                        "missing_world_rejected_without_replacement");
            }
            pass("existing_placements_registered_no_allocation");
            return null;
        })).thenCompose(v -> io(() -> {
            for (var p : List.of(template, a, b)) {
                check(metadataDigest(p).equals(evidence.getProperty(p.key().getKey() + ".metadataSha256")),
                        "preload_identity_metadata_preserved_" + p.key().getKey());
            }
            check(templates.digest(template.directory()).equals(templateDigest), "preload_template_preserved");
            if (strictSpatial) {
                check(templates.digest(a.directory()).equals(evidence.getProperty("spatial.a"))
                        && templates.digest(b.directory()).equals(evidence.getProperty("spatial.b")), "preload_spatial_files_preserved");
            }
            return null;
        })).thenCompose(v -> main(() -> {
            // No call to backend.create or copySpatial is permitted on this phase.
            timed("restore_load_a", () -> backend.load(a));
            timed("restore_load_b", () -> backend.load(b));
            pass("existing_uuids_and_keys_restored");
            return null;
        }));
    }

    private WorldBackend.Placement recorded(String name, int number) {
        if (!run(number).toString().equals(evidence.getProperty(name + ".run"))) {
            throw new IllegalStateException("Unexpected story_run_id");
        }
        Path path = Bukkit.getServer().getLevelDirectory().resolve("dimensions/hearth_verify").resolve(name).toAbsolutePath().normalize();
        return new WorldBackend.Placement(run(number), key(name), UUID.fromString(evidence.getProperty(name + ".uuid")), path);
    }

    private void snapshot() {
        for (var p : List.of(a, b)) {
            plugin.getLogger().info("HEARTH_S4_2_SNAPSHOT phase=" + phase + " run=" + p.storyRunId()
                    + " key=" + p.key() + " uuid=" + world(p).getUID() + " block=" + world(p).getBlockAt(0,64,0).getType());
        }
        check(world(a).getBlockAt(0,64,0).getType() == Material.GOLD_BLOCK
                && world(b).getBlockAt(0,64,0).getType() == Material.DIAMOND_BLOCK, "distinct_blocks_preserved");
    }

    private void apiChecks() {
        RegionAssociable nonMember = regions -> Association.NON_MEMBER;
        check(!canBuild(world(a), 2, nonMember) && canBuild(world(b), 2, nonMember), "API_world_isolation");
        check(canBuild(world(a), 6, nonMember) && !canBuild(world(a), 8, nonMember), "API_overlap_priority");
        check(manager(world(a)) != manager(world(b)), "API_distinct_managers");
        check(manager(world(a)).getRegion("build").getPriority() == 10
                && manager(world(a)).getRegion("overlap").getPriority() == 20, "API_priorities_persisted");
    }

    private boolean canBuild(World world, int xz, RegionAssociable subject) {
        return manager(world).getApplicableRegions(BlockVector3.at(xz,64,xz)).testState(subject, Flags.BUILD);
    }

    private void region(World world, String id, int min, int max, int priority, StateFlag.State flag) {
        RegionManager m = manager(world);
        if (m.hasRegion(id)) throw new IllegalStateException("Existing region: " + id);
        var region = new ProtectedCuboidRegion(id, BlockVector3.at(min,60,min), BlockVector3.at(max,70,max));
        region.setPriority(priority);
        region.setFlag(Flags.BUILD, flag);
        m.addRegion(region);
    }

    private void powerPistons() {
        // This method does NOT call PluginManager.callEvent. Redstone/physics generate the event.
        for (var p : List.of(a, b)) {
            World w = world(p);
            w.addPluginChunkTicket(-1,0,plugin);
            w.addPluginChunkTicket(0,0,plugin);
            pistonEvents.put(w.getKey(), new ArrayList<>());
            w.getBlockAt(-2,64,8).setType(Material.PISTON, false);
            Directional data = (Directional) w.getBlockAt(-2,64,8).getBlockData();
            data.setFacing(BlockFace.EAST);
            w.getBlockAt(-2,64,8).setBlockData(data, false);
            w.getBlockAt(-1,64,8).setType(Material.STONE, false);
            w.getBlockAt(0,64,8).setType(Material.AIR, false);
            w.getBlockAt(-3,64,8).setType(Material.REDSTONE_BLOCK, true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void piston(BlockPistonExtendEvent event) {
        var block = event.getBlock();
        List<Boolean> seen = pistonEvents.get(block.getWorld().getKey());
        if (seen != null && block.getX() == -2 && block.getY() == 64 && block.getZ() == 8) {
            seen.add(event.isCancelled());
            plugin.getLogger().info("HEARTH_S4_2_EVENT natural_piston world=" + block.getWorld().getKey()
                    + " cancelled=" + event.isCancelled() + " affected=" + event.getBlocks().size());
        }
    }

    private void checkPistons() {
        check(pistonEvents.get(a.key()).contains(true) && world(a).getBlockAt(0,64,8).getType() == Material.AIR
                && world(a).getBlockAt(-1,64,8).getType() == Material.STONE, "EVENT_natural_piston_A_denied");
        check(pistonEvents.get(b.key()).contains(false) && world(b).getBlockAt(0,64,8).getType() == Material.STONE,
                "EVENT_natural_piston_B_allowed");
    }

    private void clearPistons() {
        for (var p : List.of(a,b)) {
            World w = world(p);
            for (int x = -3; x <= 0; x++) w.getBlockAt(x,64,8).setType(Material.AIR, false);
            w.removePluginChunkTicket(-1,0,plugin);
            w.removePluginChunkTicket(0,0,plugin);
        }
    }

    private CompletableFuture<Void> awaitPistons() {
        var result = new CompletableFuture<Void>();
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            private int remaining = 150;
            @Override public void run() {
                if (closed) { result.completeExceptionally(new IllegalStateException("Verifier closed")); return; }
                // Chunk ticket preparation is asynchronous; fixed tick delays can expire before physics runs.
                if (!pistonEvents.get(a.key()).isEmpty() && !pistonEvents.get(b.key()).isEmpty()
                        && world(b).getBlockAt(0,64,8).getType() == Material.STONE) {
                    result.complete(null);
                } else if (--remaining <= 0) {
                    result.completeExceptionally(new IllegalStateException("Natural piston event/settling timeout: " + pistonEvents));
                } else {
                    Bukkit.getScheduler().runTaskLater(plugin, this, 2);
                }
            }
        }, 2);
        return result;
    }
    private <T> T timed(String label, Callable<T> call) throws Exception {
        long start = System.nanoTime();
        try { return call.call(); }
        finally { plugin.getLogger().info("HEARTH_S4_2_TIMING phase=" + phase + " op=" + label
                + " ms=" + String.format(Locale.ROOT, "%.3f", (System.nanoTime()-start)/1_000_000.0)); }
    }
    private RegionManager manager(World w) {
        return Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(w)));
    }
    private static World world(WorldBackend.Placement p) { return Objects.requireNonNull(Bukkit.getWorld(p.key())); }
    private static NamespacedKey key(String name) { return new NamespacedKey("hearth_verify", name); }
    private static UUID run(int number) { return new UUID(42,number); }
    private static String metadataDigest(WorldBackend.Placement p) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(p.directory().resolve("data/paper/metadata.dat"))));
    }
    private void check(boolean ok, String label) { if (!ok) throw new IllegalStateException(label); pass(label); }
    private void pass(String label) { plugin.getLogger().info("HEARTH_S4_2_CHECK PASS " + phase + " " + label); }
    private <T> CompletableFuture<T> io(Callable<T> call) {
        return CompletableFuture.supplyAsync(() -> {
            if (closed) throw new CompletionException(new IllegalStateException("Verifier closed"));
            try { return call.call(); } catch (Exception e) { throw new CompletionException(e); }
        }, files);
    }
    private <T> CompletableFuture<T> main(Callable<T> call) {
        var result = new CompletableFuture<T>();
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                if (closed) { result.completeExceptionally(new IllegalStateException("Verifier closed")); return; }
                if (Bukkit.isTickingWorlds()) { Bukkit.getScheduler().runTask(plugin,this); return; }
                try { result.complete(call.call()); } catch (Exception e) { result.completeExceptionally(e); }
            }
        });
        return result;
    }
    @Override public void close() { closed = true; HandlerList.unregisterAll(this); files.shutdown(); }
}
