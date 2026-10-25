package io.github.jesterportfolio.hearth.world;

import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.generator.ChunkGenerator;

/** S4-only allocation: rejects existing data; API calls must run outside world ticking. */
public final class PaperWorldBackend implements WorldBackend {
    private final Map<NamespacedKey, Placement> owned = new HashMap<>();
    private final ChunkGenerator generator = new ChunkGenerator() {
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
        @Override public Location getFixedSpawnLocation(World world, Random random) {
            return new Location(world, 0.5, 65, 0.5);
        }
    };

    @Override public Placement create(UUID runId, NamespacedKey key) {
        safeThread();
        if (!key.getNamespace().equals("hearth_verify")) throw new IllegalArgumentException("Verification key required");
        Path expected = Bukkit.getServer().getLevelDirectory().resolve("dimensions")
                .resolve(key.getNamespace()).resolve(key.getKey()).toAbsolutePath().normalize();
        if (owned.containsKey(key) || Bukkit.getWorld(key) != null || Files.exists(expected, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Refusing existing world/key: " + key);
        }
        World world = Objects.requireNonNull(Bukkit.createWorld(WorldCreator.ofKey(key)
                .generator(generator).generateStructures(false)), "createWorld returned null");
        var actual = world.getWorldPath().toAbsolutePath().normalize();
        if (!actual.equals(expected)) throw new IllegalStateException("Unexpected 26.2 world path: " + actual);
        world.setAutoSave(false);
        Placement placement = new Placement(runId, key, world.getUID(), actual);
        owned.put(key, placement);
        return placement;
    }

    @Override public World load(Placement placement) {
        safeThread();
        requireOwned(placement);
        try {
            String marker = Files.readString(placement.directory().resolve(".hearth-s4-spatial-ready")).trim();
            if (!marker.equals(placement.storyRunId() + "/" + placement.key() + "/" + placement.physicalId())
                    || !Files.isRegularFile(placement.directory().resolve("uid.dat"))
                    || !Files.isRegularFile(placement.directory().resolve("region/r.0.0.mca"))) {
                throw new IllegalStateException("Missing identity/spatial readiness; refusing empty replacement");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("World not prepared/readable; refusing empty replacement", e);
        }
        World world = Objects.requireNonNull(Bukkit.createWorld(WorldCreator.ofKey(placement.key()).generator(generator)),
                "load returned null");
        if (!world.getUID().equals(placement.physicalId()) || !world.getKey().equals(placement.key())) {
            throw new IllegalStateException("World identity changed: " + placement.key());
        }
        world.setAutoSave(false);
        return world;
    }

    @Override public void saveAndUnload(Placement placement) {
        safeThread();
        requireOwned(placement);
        World world = Objects.requireNonNull(Bukkit.getWorld(placement.key()), "World is not loaded");
        if (!world.getUID().equals(placement.physicalId()) || !world.getPlayers().isEmpty()) {
            throw new IllegalStateException("Identity mismatch or players present; refusing unload");
        }
        world.save(true);
        if (!Bukkit.unloadWorld(world, true) || Bukkit.getWorld(placement.key()) != null) {
            throw new IllegalStateException("Unload did not complete: " + placement.key());
        }
    }

    private void requireOwned(Placement p) {
        if (!p.equals(owned.get(p.key()))) throw new IllegalArgumentException("Unknown placement");
    }
    private static void safeThread() {
        if (!Bukkit.isPrimaryThread() || Bukkit.isTickingWorlds()) throw new IllegalStateException("Unsafe lifecycle call");
    }
}
