package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.lifecycle.WorldLifecycle;
import io.github.jesterportfolio.hearth.persistence.*;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;

/** 정상 스토리의 불변 연결·로드 세대·DB 확정·WorldGuard 영역을 모두 검사합니다. */
public final class PaperStoryEffects implements SharedStory.Effects {
    private final JavaPlugin plugin;private final StoryWorldEffects content;private final Map<UUID,StoryRuntimeSettings.Run> runs;private final Map<UUID,WorldLifecycle> worlds;private final SharedStory shared;private final Executor owner;
    private final Map<UUID,Long> prepared=new HashMap<>();
    public PaperStoryEffects(JavaPlugin plugin,StoryWorldEffects content,Map<UUID,StoryRuntimeSettings.Run> runs,Map<UUID,WorldLifecycle> worlds,SharedStory shared,Executor owner){this.plugin=plugin;this.content=content;this.runs=Map.copyOf(runs);this.worlds=Map.copyOf(worlds);this.shared=shared;this.owner=owner;}
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("STORY_EFFECT_MAIN");}
    public void prepared(UUID run){main();prepared.put(run,worlds.get(run).generation());}
    public void unloaded(UUID run){main();prepared.remove(run);}
    @Override public Set<String> regions(){return content.regions();}
    @Override public boolean required(UUID run,StoryStore.Snapshot saved){return runs.containsKey(run)&&content.required(saved);}
    @Override public String notAppliedStatus(){return "PENDING";}
    private boolean available(UUID run,long generation){var w=worlds.get(run);return plugin.isEnabled()&&w!=null&&Objects.equals(prepared.get(run),generation)&&w.generation()==generation&&Set.of(WorldLifecycle.State.LOADING,WorldLifecycle.State.READY,WorldLifecycle.State.ACTIVE,WorldLifecycle.State.IDLE).contains(w.state());}
    @Override public CompletionStage<Boolean> apply(UUID run,UUID request,StoryStore.Snapshot saved){
        main();var lifecycle=worlds.get(run);if(lifecycle==null)return CompletableFuture.failedFuture(new IllegalStateException("EFFECT_STALE"));long generation=lifecycle.generation();if(!available(run,generation))return CompletableFuture.completedFuture(false);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);var fence=shared.effectPermit(run,saved);BooleanSupplier permit=()->System.nanoTime()<deadline&&fence.getAsBoolean();var binding=runs.get(run).binding();var mapping=lifecycle.mapping();var expected=new StoryWorldEffects.Target(binding,mapping.directory().toString(),generation);
        CompletionStage<Void> work=CompletableFuture.completedFuture(null);
        for(var change:content.changes())if(change.required(saved))work=work.thenComposeAsync(v->{
            if(!available(run,generation)||!permit.getAsBoolean())throw new IllegalStateException("EFFECT_STALE");
            var world=Objects.requireNonNull(Bukkit.getWorld(binding.worldId()));return world.getChunkAtAsync(change.position().x()>>4,change.position().z()>>4,false).copy().orTimeout(10,TimeUnit.SECONDS).thenComposeAsync(chunk->{if(chunk==null)throw new IllegalStateException("EFFECT_CHUNK_UNAVAILABLE");return applyWhenSafe(run,generation,saved,change,expected,permit);},owner);
        },owner);
        return work.thenApply(v->true);
    }
    private CompletionStage<Void> applyWhenSafe(UUID run,long generation,StoryStore.Snapshot saved,StoryWorldEffects.Change change,StoryWorldEffects.Target expected,BooleanSupplier permit){
        var result=new CompletableFuture<Void>();result.orTimeout(10,TimeUnit.SECONDS);
        Bukkit.getScheduler().runTaskLater(plugin,new Runnable(){public void run(){if(result.isDone())return;try{
            if(!available(run,generation)||!permit.getAsBoolean())throw new IllegalStateException("EFFECT_STALE");if(Bukkit.isTickingWorlds()){Bukkit.getScheduler().runTaskLater(plugin,this,1);return;}
            var binding=runs.get(run).binding();var world=Objects.requireNonNull(Bukkit.getWorld(binding.worldId()));var p=change.position();
            if(!world.getKey().toString().equals(binding.worldKey())||!world.getWorldFolder().toPath().toRealPath().equals(worlds.get(run).mapping().directory())||p.y()<world.getMinHeight()||p.y()>=world.getMaxHeight()||!world.getWorldBorder().isInside(new Location(world,p.x(),p.y(),p.z())))throw new IllegalStateException("EFFECT_STALE");
            var manager=WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));if(manager==null||change.regions().stream().anyMatch(id->manager.getRegion(id)==null||!manager.getRegion(id).contains(BlockVector3.at(p.x(),p.y(),p.z()))))throw new IllegalStateException("EFFECT_REGION_MISMATCH");
            var actual=new StoryWorldEffects.Target(binding,world.getWorldFolder().toPath().toRealPath().toString(),worlds.get(run).generation());var block=world.getBlockAt(p.x(),p.y(),p.z());
            StoryWorldEffects.reconcile(change,saved,expected,actual,new StoryWorldEffects.Block(){public String read(){return block.getType().name();}public void write(String material){block.setType(Material.valueOf(material),false);}},()->permit.getAsBoolean()&&available(run,generation));result.complete(null);
        }catch(Exception error){result.completeExceptionally(error);}}},1);return result;
    }
}
