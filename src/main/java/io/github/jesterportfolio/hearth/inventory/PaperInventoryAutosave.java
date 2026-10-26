package io.github.jesterportfolio.hearth.inventory;

import java.time.Duration;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** 명시적으로 등록한 내부 세션만 처리합니다. 일반 로그인/아이템 이벤트를 자동 등록하지 않습니다. */
public final class PaperInventoryAutosave implements AutoCloseable {
    private final InventoryAutosave autosave;
    private final BukkitTask task;
    public PaperInventoryAutosave(JavaPlugin plugin,List<InventorySaveSession> sessions,Runnable observedCycle) {
        this(plugin,sessions,() -> {},observedCycle);
    }
    public PaperInventoryAutosave(JavaPlugin plugin,List<InventorySaveSession> sessions,Runnable beforeCycle,Runnable observedCycle) {
        main();
        long seconds=plugin.getConfig().getLong("inventory-autosave-seconds",30);
        autosave=new InventoryAutosave(Duration.ofSeconds(seconds),System::nanoTime,PaperInventoryAutosave::main,beforeCycle);
        sessions.forEach(autosave::add);
        task=Bukkit.getScheduler().runTaskTimer(plugin,() -> {
            if (autosave.tick()) observedCycle.run();
        },1,1);
    }
    public void add(InventorySaveSession session) { autosave.add(session); }
    public void remove(InventorySaveSession session) { autosave.remove(session); }
    public Duration period() { return autosave.period(); }
    @Override public void close() { main(); task.cancel(); autosave.stop(); }
    private static void main() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Inventory capture requires main thread"); }
}
