package io.github.jesterportfolio.hearth;

import io.github.jesterportfolio.hearth.verification.SmokeVerification;
import java.nio.file.*;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class HearthPlugin extends JavaPlugin {
    private SmokeVerification verification;

    @Override public void onEnable() {
        saveDefaultConfig();
        getLogger().info("Hearth enabled; production gameplay modules are not implemented.");
        if (getConfig().getBoolean("verification-enabled")) {
            try {
                Path cwd = Path.of("").toRealPath();
                String approved = System.getProperty("hearth.verification.root", "");
                if (approved.isBlank() || !cwd.equals(Path.of(approved).toRealPath())
                        || !cwd.toString().startsWith("/tmp/hearth-s4-1.")
                        || !Files.readString(cwd.resolve(".hearth-s4-isolated")).trim().equals("S4-1 disposable test data only")) {
                    throw new IllegalStateException("Isolated runtime gate missing");
                }
                verification = new SmokeVerification(this);
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
            } else {
                sender.sendMessage("Usage: hearthverify smoke (one run per fresh isolated server directory)");
            }
            return true;
        });
    }

    @Override public void onDisable() {
        if (verification != null) verification.close();
    }
}
