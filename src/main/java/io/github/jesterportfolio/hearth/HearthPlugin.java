package io.github.jesterportfolio.hearth;

import io.github.jesterportfolio.hearth.verification.SmokeVerification;
import io.github.jesterportfolio.hearth.verification.RestartVerification;
import io.github.jesterportfolio.hearth.verification.PlayerActionObserver;
import io.github.jesterportfolio.hearth.verification.DatabaseVerification;
import io.github.jesterportfolio.hearth.verification.InventoryVerification;
import io.github.jesterportfolio.hearth.verification.LifecycleVerification;
import java.nio.file.*;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class HearthPlugin extends JavaPlugin {
    private SmokeVerification verification;
    private RestartVerification restartVerification;
    private boolean playerTest;
    private DatabaseVerification databaseVerification;
    private InventoryVerification inventoryVerification;
    private LifecycleVerification lifecycleVerification;

    @Override public void onEnable() {
        saveDefaultConfig();
        getLogger().info("Hearth enabled; production gameplay modules are not implemented.");
        if (getConfig().getBoolean("verification-enabled")) {
            try {
                Path cwd = Path.of("").toRealPath();
                String approved = System.getProperty("hearth.verification.root", "");
                boolean lifecycle = cwd.getParent().getFileName().toString().startsWith("hearth-s4-11.");
                boolean inventory = cwd.getParent().getFileName().toString().startsWith("hearth-s4-6.");
                boolean s45 = cwd.getParent().getFileName().toString().startsWith("hearth-s4-5.");
                boolean s42 = cwd.getParent().getFileName().toString().startsWith("hearth-s4-2.");
                if (approved.isBlank() || !cwd.equals(Path.of(approved).toRealPath())
                        || !cwd.getParent().getParent().equals(Path.of("/tmp"))
                        || !(lifecycle || inventory || s45 || s42 || cwd.getParent().getFileName().toString().startsWith("hearth-s4-1."))
                        || !Files.readString(cwd.resolve(".hearth-s4-isolated")).trim()
                                .equals((lifecycle ? "S4-11" : inventory ? "S4-6" : s45 ? "S4-5" : s42 ? "S4-2" : "S4-1") + " disposable test data only")) {
                    throw new IllegalStateException("Isolated runtime gate missing");
                }
                verification = new SmokeVerification(this);
                if (lifecycle || inventory || s42 || s45) restartVerification = new RestartVerification(this);
                playerTest = (s42 || lifecycle && getConfig().getBoolean("session-verification-enabled") && Boolean.getBoolean("hearth.verification.sessions"))
                        && Boolean.getBoolean("hearth.verification.players");
                if (playerTest) getServer().getPluginManager().registerEvents(new PlayerActionObserver(this), this);
                if (getConfig().getBoolean("lifecycle-verification-enabled")) {
                    if (!lifecycle || !Boolean.getBoolean("hearth.verification.lifecycle")) throw new IllegalStateException("Lifecycle gate missing");
                    lifecycleVerification = new LifecycleVerification(this,cwd.getParent());
                }
                if (getConfig().getBoolean("db-verification-enabled")) {
                    if (!s45 || !Boolean.getBoolean("hearth.verification.database")) throw new IllegalStateException("DB test gate missing");
                    databaseVerification = new DatabaseVerification(this, cwd.getParent());
                }
                if (getConfig().getBoolean("inventory-verification-enabled")) {
                    if (!inventory || !Boolean.getBoolean("hearth.verification.inventory")) throw new IllegalStateException("Inventory gate missing");
                    inventoryVerification = new InventoryVerification(this,cwd.getParent());
                }
            } catch (Exception e) {
                getLogger().severe("Verification refused: " + e.getMessage());
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }
        java.util.Objects.requireNonNull(getCommand("hearthquest")).setExecutor((sender,cmd,label,args)->{
            sender.sendMessage("격리 퀘스트는 준비된 플레이어의 NPC 대화에서만 사용할 수 있습니다.");return true;
        });
        var command = java.util.Objects.requireNonNull(getCommand("hearthverify"));
        command.setExecutor((sender, ignored, label, args) -> {
            if (!(sender instanceof ConsoleCommandSender) || verification == null) {
                sender.sendMessage("Only an explicitly gated isolated verification console can run this command.");
            } else if (args.length == 2 && (args[0].equals("session-fixture") || args[0].equals("session-link-existing")) && lifecycleVerification != null) {
                try { lifecycleVerification.fixturePlayer(args[1],args[0].equals("session-link-existing")); }
                catch (RuntimeException e) { sender.sendMessage("격리 fixture 준비 거부: "+e.getMessage()); }
            } else if (args.length == 2 && args[0].equals("session-status") && lifecycleVerification != null) {
                try { lifecycleVerification.playerStatus(args[1]); }
                catch (RuntimeException e) { sender.sendMessage("격리 상태 조회 거부: "+e.getMessage()); }
            } else if (args.length == 2 && args[0].equals("lifecycle") && lifecycleVerification != null) {
                lifecycleVerification.start(args[1]);
            } else if (args.length == 2 && args[0].equals("inventory") && inventoryVerification != null) {
                inventoryVerification.start(args[1]);
            } else if (args.length == 2 && args[0].equals("db") && databaseVerification != null) {
                databaseVerification.start(args[1]);
            } else if (args.length == 1 && args[0].equals("smoke")) {
                verification.start();
            } else if (args.length == 2 && args[0].equals("restart") && restartVerification != null
                    && (args[1].equals("prepare") || args[1].equals("resume") || playerTest && args[1].equals("prepare-player"))) {
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
        if (lifecycleVerification != null) lifecycleVerification.close();
        if (inventoryVerification != null) inventoryVerification.close();
        if (databaseVerification != null) databaseVerification.close();
        if (verification != null) verification.close();
        if (restartVerification != null) restartVerification.close();
    }
}
