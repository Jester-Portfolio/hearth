package io.github.jesterportfolio.hearth.survival.skills;

import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import io.github.jesterportfolio.hearth.survival.skills.WorldAccessPolicy.Levels;

/** 선택 기능이 꺼졌을 때 Aura API 클래스를 로드하지 않는 경계. */
public interface SkillsBridge extends AutoCloseable {
    CompletionStage<Void> ready();
    Levels levels(UUID player);
    void close();
    static SkillsBridge disabled(){return new SkillsBridge(){public CompletionStage<Void> ready(){return CompletableFuture.completedFuture(null);}public Levels levels(UUID player){return new Levels(true,Map.of());}public void close(){}};}
    static SkillsBridge enabled(JavaPlugin plugin,WorldAccessPolicy policy,Runnable hold){
        var aura=Bukkit.getPluginManager().getPlugin("AuraSkills");
        if(aura==null||!aura.isEnabled()||!aura.getDescription().getVersion().equals("2.4.0"))throw new IllegalStateException("AURASKILLS_2_4_0_REQUIRED");
        return new AuraSkillsBridge(plugin,policy,hold);
    }
}
