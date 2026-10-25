package io.github.jesterportfolio.hearth.world;

import java.nio.file.Path;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.World;

/** Physical placement boundary. No Multiverse dependency or PostgreSQL policy here. */
public interface WorldBackend {
    record Placement(UUID storyRunId, NamespacedKey key, UUID physicalId, Path directory) {}
    Placement create(UUID runId, NamespacedKey key);
    World load(Placement placement);
    void saveAndUnload(Placement placement);
}
