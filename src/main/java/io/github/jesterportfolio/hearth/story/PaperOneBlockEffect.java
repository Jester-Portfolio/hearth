package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.lifecycle.WorldLifecycle;
import io.github.jesterportfolio.hearth.persistence.StoryStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;

/** 새 격리 월드의 검증된 매핑/로드 세대에만 연결하는 좁은 Bukkit 어댑터. */
public final class PaperOneBlockEffect implements SharedStory.Effects {
    private final JavaPlugin plugin;private final OneBlockEffect.Definition definition;private final WorldLifecycle world;private final BiFunction<UUID,StoryStore.Snapshot,BooleanSupplier> confirmed;
    private long prepared=-1,inspections,writes;
    public long inspections(){main();return inspections;}
    public long writes(){main();return writes;}
    public PaperOneBlockEffect(JavaPlugin plugin,OneBlockEffect.Definition definition,WorldLifecycle world,String template,BiFunction<UUID,StoryStore.Snapshot,BooleanSupplier> confirmed){
        this.plugin=plugin;this.definition=definition;this.world=world;this.confirmed=confirmed;
        var m=world.mapping();if(!definition.run().equals(m.storyRunId())||!definition.world().equals(m.worldKey())||!definition.template().equals(template))throw new IllegalArgumentException("Effect mapping/version mismatch");
    }
    public void prepared(UUID run){main();if(run.equals(definition.run()))prepared=world.generation();}
    public void unloaded(UUID run){main();if(run.equals(definition.run()))prepared=-1;}
    @Override public Set<String> regions(){return definition.regions();}
    @Override public boolean required(UUID run,StoryStore.Snapshot saved){return definition.required(run,saved);}
    @Override public String notAppliedStatus(){return "PENDING";}
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Effect requires main thread");}
    private boolean available(){return prepared==world.generation()&&Set.of(WorldLifecycle.State.LOADING,WorldLifecycle.State.READY,WorldLifecycle.State.ACTIVE,WorldLifecycle.State.IDLE).contains(world.state());}
    @Override public CompletionStage<Boolean> apply(UUID run,UUID request,StoryStore.Snapshot saved){
        main();if(!run.equals(definition.run()))return CompletableFuture.failedFuture(new IllegalStateException("EFFECT_STALE"));
        if(!available())return CompletableFuture.completedFuture(false);
        var authority=confirmed.apply(run,saved);var m=world.mapping();long generation=world.generation();var result=new CompletableFuture<Boolean>();result.orTimeout(10,TimeUnit.SECONDS);
        var expected=new OneBlockEffect.Target(m.storyRunId(),m.physicalId(),m.worldKey(),m.directory().toString(),generation,definition.template(),definition.version());
        Bukkit.getScheduler().runTaskLater(plugin,new Runnable(){public void run(){
            if(result.isDone())return;try{
                if(!plugin.isEnabled()||!available()||generation!=world.generation()||!authority.getAsBoolean())throw new IllegalStateException("EFFECT_STALE");
                if(Bukkit.isTickingWorlds()){Bukkit.getScheduler().runTaskLater(plugin,this,1);return;}
                World loaded=Bukkit.getWorld(NamespacedKey.fromString(m.worldKey()));if(loaded==null)throw new IllegalStateException("EFFECT_STALE");
                var actual=new OneBlockEffect.Target(run,loaded.getUID(),loaded.getKey().toString(),loaded.getWorldFolder().toPath().toAbsolutePath().normalize().toString(),world.generation(),definition.template(),definition.version());
                var block=loaded.getBlockAt(definition.x(),definition.y(),definition.z());
                inspections++;result.complete(OneBlockEffect.reconcile(definition,run,saved,expected,actual,new OneBlockEffect.Block(){public String read(){return block.getType().name();}public void write(String type){block.setType(Material.valueOf(type),false);writes++;}},()->authority.getAsBoolean()&&available()&&generation==world.generation()));
            }catch(Exception e){result.completeExceptionally(e);}
        }},1);return result;
    }
}
