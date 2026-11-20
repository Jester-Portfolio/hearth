package io.github.jesterportfolio.hearth.verification;

import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;
import io.github.jesterportfolio.hearth.survival.*;

/** 실제 Player를 만들지 않는 Paper 월드/GUI 객체 검사. 클라이언트 성공을 주장하지 않습니다. */
public final class SurvivalVerification {
    private SurvivalVerification(){}
    public static void run(JavaPlugin plugin,SurvivalRuntime runtime)throws Exception {
        var root=IsolatedPaths.require(Path.of(System.getProperty("hearth.survival.verification.root","")),"hearth-survival-run.");if(!Path.of("").toRealPath().equals(root.resolve("server"))||!Files.readString(root.resolve(".hearth-survival-isolated")).equals("Hearth survival isolated v1\n")||!Bukkit.getOnlinePlayers().isEmpty()||runtime.phase()!=SurvivalRuntime.Phase.READY)throw new IllegalStateException("SURVIVAL_PROBE_GATE");
        if(!Bukkit.getOnlineMode()||!Bukkit.hasWhitelist()||!Bukkit.getIp().equals("127.0.0.1"))throw new IllegalStateException("SURVIVAL_PROBE_AUTH");
        if(!Objects.requireNonNull(plugin.getCommand("world")).getAliases().contains("월드"))throw new IllegalStateException("SURVIVAL_PROBE_COMMAND");
        var registry=plugin.getDataFolder().toPath().resolve("survival-worlds.properties");var before=Files.readAllBytes(registry);var identities=new Properties();
        for(var role:runtime.worlds().worlds().keySet()){var world=runtime.worlds().worlds().get(role);var entry=runtime.worlds().entry(role);if(!SurvivalWorlds.safe(entry)||world.getEnvironment()!=(role==SurvivalWorld.NETHER?World.Environment.NETHER:World.Environment.NORMAL)||!world.isAutoSave())throw new IllegalStateException("SURVIVAL_PROBE_WORLD");var map=PaperWorldMenu.items(role,w->true);if(Arrays.stream(map).filter(Objects::nonNull).count()!=3||map[role.slot()]==null||!map[role.slot()].hasItemMeta()||map[role.slot()].getItemMeta().lore()==null)throw new IllegalStateException("SURVIVAL_PROBE_GUI");identities.setProperty(role.id(),world.getUID().toString()+":"+world.getSeed()+":"+entry.getBlockX()+":"+entry.getBlockY()+":"+entry.getBlockZ()+":"+world.getBlockAt(entry.getBlockX(),entry.getBlockY()-1,entry.getBlockZ()).getType().name());}
        for(var listener:org.bukkit.event.HandlerList.getRegisteredListeners(plugin)){var name=listener.getListener().getClass().getName();if(name.contains("PaperSessionAdapter")||name.contains("PaperEconomy")||name.contains("CombatPlayers")||name.contains("StoryRuntime"))throw new IllegalStateException("SURVIVAL_RPG_LISTENER_REGISTERED");}
        var first=root.resolve("first-identities.properties");if(Files.exists(first)){var expected=new Properties();try(var r=Files.newBufferedReader(first)){expected.load(r);}if(!expected.equals(identities))throw new IllegalStateException("SURVIVAL_RESTART_CHANGED");}else try(var w=Files.newBufferedWriter(first,StandardOpenOption.CREATE_NEW)){identities.store(w,"Actual Paper world identities and entry floor");}
        if(!Arrays.equals(before,Files.readAllBytes(registry)))throw new IllegalStateException("SURVIVAL_PROBE_REGISTRY_CHANGED");
        plugin.getLogger().info("HEARTH_SURVIVAL_PROBE PASS worlds=3 safe_entries=true gui_objects=3 rpg_listeners=0 native_player_tests=NOT_RUN");
    }
}
