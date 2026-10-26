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
        if (!key.getNamespace().equals("hearth_verify") || !key.getKey().matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Verification key required");
        }
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
        requireStored(placement);
        World world = Objects.requireNonNull(Bukkit.createWorld(WorldCreator.ofKey(placement.key()).generator(generator)),
                "load returned null");
        if (!world.getUID().equals(placement.physicalId()) || !world.getKey().equals(placement.key())) {
            throw new IllegalStateException("World identity changed: " + placement.key());
        }
        world.setAutoSave(false);
        return world;
    }

    /** Reattach a recorded test placement after JVM restart; never allocates or copies a world. */
    @Override public void registerExisting(Placement placement) {
        safeThread();
        Path expected = Bukkit.getServer().getLevelDirectory().resolve("dimensions")
                .resolve(placement.key().getNamespace()).resolve(placement.key().getKey()).toAbsolutePath().normalize();
        if (!placement.key().getNamespace().equals("hearth_verify") || !placement.key().getKey().matches("[a-z0-9_]+")
                || !placement.directory().equals(expected)
                || owned.containsKey(placement.key())) throw new IllegalArgumentException("Invalid existing placement");
        requireStored(placement);
        World loaded = Bukkit.getWorld(placement.key());
        if (loaded != null && (!loaded.getUID().equals(placement.physicalId())
                || !loaded.getWorldPath().toAbsolutePath().normalize().equals(expected))) {
            throw new IllegalStateException("Loaded world identity mismatch");
        }
        owned.put(placement.key(), placement);
    }

    private void requireStored(Placement placement) {
        try {
            if (!placement.directory().toRealPath().equals(placement.directory())) {
                throw new IllegalStateException("World path contains symlink/alias");
            }
            String marker = Files.readString(placement.directory().resolve(".hearth-s4-spatial-ready")).trim();
            if (!marker.equals(placement.storyRunId() + "/" + placement.key() + "/" + placement.physicalId())
                    // Paper 26.2 persists UUID in saved data, not the pre-26.2 uid.dat.
                    || !Files.isRegularFile(placement.directory().resolve("data/paper/metadata.dat"), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(placement.directory().resolve("region/r.0.0.mca"))) {
                throw new IllegalStateException("Missing identity/spatial readiness; refusing empty replacement");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("World not prepared/readable; refusing empty replacement", e);
        }
    }

    /** 월드 저장은 안전한 메인 시점에서만 실행합니다. DB/구역 저장은 호출자의 선행 조건입니다. */
    @Override public void save(Placement placement) {
        safeThread(); requireOwned(placement);
        World world=loadedFor(placement);
        if (!world.getPlayers().isEmpty()) throw new IllegalStateException("Players present; refusing save boundary");
        world.save(true);
    }
    /** 다른 플러그인의 티켓을 강제로 제거하지 않습니다. false면 호출자가 HOLD로 보존해야 합니다. */
    @Override public boolean unload(Placement placement) {
        safeThread(); requireOwned(placement); World world=loadedFor(placement);
        if (!world.getPlayers().isEmpty() || !world.getPluginChunkTickets().isEmpty() || !world.getForceLoadedChunks().isEmpty())
            throw new IllegalStateException("Unload blockers present");
        boolean result=Bukkit.unloadWorld(world,false); // 선행 save 완료 후 중복 저장을 피합니다.
        if (result && Bukkit.getWorld(placement.key())!=null) throw new IllegalStateException("Unload reported success but world remains loaded");
        return result;
    }
    @Override public void saveAndUnload(Placement placement) {
        save(placement);
        if (!unload(placement)) throw new IllegalStateException("Unload did not complete: "+placement.key());
    }
    private World loadedFor(Placement placement) {
        World world=Objects.requireNonNull(Bukkit.getWorld(placement.key()),"World is not loaded");
        if (!world.getUID().equals(placement.physicalId()) || !world.getWorldPath().toAbsolutePath().normalize().equals(placement.directory()))
            throw new IllegalStateException("Loaded world identity mismatch");
        return world;
    }

    private void requireOwned(Placement p) {
        if (!p.equals(owned.get(p.key()))) throw new IllegalArgumentException("Unknown placement");
    }
    private static void safeThread() {
        if (!Bukkit.isPrimaryThread() || Bukkit.isTickingWorlds()) throw new IllegalStateException("Unsafe lifecycle call");
    }
}
