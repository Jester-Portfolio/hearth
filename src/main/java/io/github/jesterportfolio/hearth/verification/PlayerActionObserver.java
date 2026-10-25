package io.github.jesterportfolio.hearth.verification;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Passive observations of connected players only. Never grants permission or cancels/manufactures events. */
public final class PlayerActionObserver implements Listener {
    private final JavaPlugin plugin;
    public PlayerActionObserver(JavaPlugin plugin) { this.plugin = plugin; }

    public static boolean bypass(Player player) {
        return WorldGuard.getInstance().getPlatform().getSessionManager()
                .hasBypass(WorldGuardPlugin.inst().wrapPlayer(player), BukkitAdapter.adapt(player.getWorld()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void placed(BlockPlaceEvent e) {
        observe(e.getPlayer(), e.getBlockPlaced(), "place", e.isCancelled(),
                "previous=" + e.getBlockReplacedState().getType() + " attempted=" + e.getBlockPlaced().getType()
                        + " canBuild=" + e.canBuild());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void broken(BlockBreakEvent e) {
        observe(e.getPlayer(), e.getBlock(), "break", e.isCancelled(), "previous=" + e.getBlock().getType());
    }
    private void observe(Player player, Block block, String action, boolean cancelled, String detail) {
        String key = block.getWorld().getKey().toString();
        if (!(key.equals("hearth_verify:restart_a") || key.equals("hearth_verify:restart_b"))
                || !player.isOnline() || Bukkit.getPlayer(player.getUniqueId()) != player) return;
        String id = UUID.randomUUID().toString();
        plugin.getLogger().info("HEARTH_S4_3_ACTION id=" + id + " player=" + player.getUniqueId()
                + " action=" + action + " world=" + key + " xyz=" + block.getX() + "," + block.getY() + "," + block.getZ()
                + " op=" + player.isOp() + " bypass=" + bypass(player) + " cancelled=" + cancelled + " " + detail);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (Bukkit.getWorld(block.getWorld().getUID()) == block.getWorld()) {
                plugin.getLogger().info("HEARTH_S4_3_STATE id=" + id + " final=" + block.getType());
            }
        }, 2);
    }
}
