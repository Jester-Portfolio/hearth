package io.github.jesterportfolio.hearth;

import io.github.jesterportfolio.hearth.verification.SmokeVerification;
import io.github.jesterportfolio.hearth.verification.RestartVerification;
import io.github.jesterportfolio.hearth.verification.PlayerActionObserver;
import java.nio.file.*;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class HearthPlugin extends JavaPlugin {
    private SmokeVerification verification;
    private RestartVerification restartVerification;
    private boolean playerTest;

    @Override public void onEnable() {
        saveDefaultConfig();
        getLogger().info("Hearth enabled; production gameplay modules are not implemented.");
        if (getConfig().getBoolean("verification-enabled")) {
            try {
                Path cwd = Path.of("").toRealPath();
                String approved = System.getProperty("hearth.verification.root", "");
                boolean s42 = cwd.getParent().getFileName().toString().startsWith("hearth-s4-2.");
                if (approved.isBlank() || !cwd.equals(Path.of(approved).toRealPath())
                        || !cwd.getParent().getParent().equals(Path.of("/tmp"))
                        || !(s42 || cwd.getParent().getFileName().toString().startsWith("hearth-s4-1."))
                        || !Files.readString(cwd.resolve(".hearth-s4-isolated")).trim()
                                .equals((s42 ? "S4-2" : "S4-1") + " disposable test data only")) {
                    throw new IllegalStateException("Isolated runtime gate missing");
                }
                verification = new SmokeVerification(this);
                if (s42) restartVerification = new RestartVerification(this);
                playerTest = s42 && Boolean.getBoolean("hearth.verification.players");
                if (playerTest) getServer().getPluginManager().registerEvents(new PlayerActionObserver(this), this);
            } catch (Exception e) {
                getLogger().severe("Verification refused: " + e.getMessage());
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }
        var command = java.util.Objects.requireNonNull(getCommand("hearthverify"));
        command.setExecutor((sender, ignored, label, args) -> {
            if (!(sender instanceof ConsoleCommandSender) || verification == null) {
                sender.sendMessage("Only an explicitly gated isolated verification console can run this command.");
            } else if (args.length == 1 && args[0].equals("smoke")) {
                verification.start();
            } else if (args.length == 2 && args[0].equals("restart") && restartVerification != null
                    && (args[1].equals("prepare") || args[1].equals("resume"))) {
                restartVerification.start(args[1]);
            } else if (playerTest && args.length == 1 && args[0].equals("player-open")) {
                restartVerification.openForPlayers();
            } else if (playerTest && args.length == 2 && args[0].equals("player-status")) {
                var player = getServer().getPlayerExact(args[1]);
                if (player == null) sender.sendMessage("NOT_CONNECTED");
                else sender.sendMessage("HEARTH_S4_3_STATUS uuid=" + player.getUniqueId() + " world=" + player.getWorld().getKey()
                        + " op=" + player.isOp() + " bypass=" + PlayerActionObserver.bypass(player));
            } else {
                sender.sendMessage("Usage: hearthverify smoke (one run per fresh isolated server directory)");
            }
            return true;
        });
    }

    @Override public void onDisable() {
        if (verification != null) verification.close();
        if (restartVerification != null) restartVerification.close();
    }
}
