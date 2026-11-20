package io.github.jesterportfolio.hearth.survival.skills;

import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.registry.NamespacedId;
import dev.aurelium.auraskills.api.skill.Skill;
import dev.aurelium.auraskills.api.event.skill.SkillsLoadEvent;
import io.github.jesterportfolio.hearth.survival.skills.WorldAccessPolicy.Levels;

/** 공개 API만 사용한다. 로드/disable/콘텐츠 재로드 실패는 원본 보존 HOLD. */
final class AuraSkillsBridge implements SkillsBridge,Listener {
    private final WorldAccessPolicy policy;private final Runnable hold;private final CompletableFuture<Void> ready=new CompletableFuture<>();
    private final org.bukkit.scheduler.BukkitTask timeout;
    private Map<String,Skill> skills=Map.of();private boolean closed,loaded;
    AuraSkillsBridge(JavaPlugin plugin,WorldAccessPolicy policy,Runnable hold){this.policy=policy;this.hold=hold;Bukkit.getPluginManager().registerEvents(this,plugin);timeout=Bukkit.getScheduler().runTaskLater(plugin,()->{if(!closed&&!ready.isDone()){ready.completeExceptionally(new IllegalStateException("AURASKILLS_LOAD_TIMEOUT"));hold.run();}},200);}
    @EventHandler public void loaded(SkillsLoadEvent event){
        if(closed)return;
        try{var map=new HashMap<String,Skill>();var registry=AuraSkillsApi.get().getGlobalRegistry();for(String id:policy.requiredSkills()){var skill=registry.getSkill(NamespacedId.fromString(id));if(skill==null||!skill.isEnabled())throw new IllegalStateException("SKILLS_ACCESS_SKILL_UNAVAILABLE_"+id);map.put(id,skill);}skills=Map.copyOf(map);loaded=true;ready.complete(null);}catch(Exception error){loaded=false;ready.completeExceptionally(error);hold.run();}
    }
    @EventHandler public void disabled(PluginDisableEvent event){if(event.getPlugin().getName().equals("AuraSkills")){loaded=false;ready.completeExceptionally(new IllegalStateException("AURASKILLS_DISABLED"));if(!closed)hold.run();}}
    public CompletionStage<Void> ready(){return ready;}
    public Levels levels(UUID player){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("SKILLS_MAIN_THREAD_REQUIRED");
        if(closed||!loaded)return Levels.unavailable();
        try{if(skills.values().stream().anyMatch(skill->!skill.isEnabled())){loaded=false;hold.run();return Levels.unavailable();}
        var user=AuraSkillsApi.get().getUser(player);if(!user.isLoaded())return Levels.unavailable();
        var levels=new HashMap<String,Integer>();skills.forEach((id,skill)->levels.put(id,user.getSkillLevel(skill)));return new Levels(true,levels);}catch(RuntimeException error){loaded=false;hold.run();return Levels.unavailable();}
    }
    public void close(){closed=true;timeout.cancel();loaded=false;ready.completeExceptionally(new IllegalStateException("SKILLS_CLOSED"));HandlerList.unregisterAll(this);}
}
