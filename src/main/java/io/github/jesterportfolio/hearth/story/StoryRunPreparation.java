package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.administration.RuntimeLease;
import io.github.jesterportfolio.hearth.combat.CombatPlayers;
import io.github.jesterportfolio.hearth.world.*;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** 새 진행 전용 복제/검증/등록. 기존 경로 덮어쓰기·실패 산출물 삭제/자동 채택은 없습니다. */
public final class StoryRunPreparation implements AutoCloseable {
    public enum State{PREFLIGHT,ALLOCATED,COPIED,VERIFIED,REGISTERED,READY,REFUSED,HOLD}
    public record Prepared(StoryRuntimeSettings.Run run,Path report){}
    public record Status(State state,String code){}
    private final class Operation{
        final StoryPreparationSettings.Job job;final long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);final CompletableFuture<Prepared> result=new CompletableFuture<>();final CompletionStage<Prepared> view=result.minimalCompletionStage();
        State state=State.PREFLIGHT;boolean mutationStarted;StoryTemplateSource source;WorldBackend.Placement placement;Path regions,report;String originalRegionsHash,metadataHash;RuntimeLease.Lease pending;StoryRuntimeSettings.Run run;
        Operation(StoryPreparationSettings.Job job){this.job=job;}
        void live(){if(closed||result.isDone()||System.nanoTime()>deadline)throw new IllegalStateException("STORY_PREPARATION_EXPIRED_OR_CLOSED");}
    }
    private final JavaPlugin plugin;private final StoryPreparationSettings settings;private final StoryRegistry registry;private final Executor owner,io;private final Path folder,templates,outputs,guardWorlds,level;
    private final PaperWorldBackend allocator;private final Map<String,Operation> operations=new LinkedHashMap<>();private final Map<String,Status> status=new LinkedHashMap<>();private Operation active;private boolean closed;
    public StoryRunPreparation(JavaPlugin plugin,StoryPreparationSettings settings,StoryRegistry registry,Executor owner,Executor io)throws Exception{
        this.plugin=plugin;this.settings=settings;this.registry=registry;this.owner=owner;this.io=io;main();folder=plugin.getDataFolder().toPath().toAbsolutePath();level=Bukkit.getServer().getLevelDirectory().toAbsolutePath().normalize();if(!folder.toRealPath().equals(folder)||!level.toRealPath().equals(level))throw new IllegalStateException("STORY_PREPARATION_DIRECTORY");templates=folder.resolve("templates");outputs=folder.resolve("prepared");
        if(!Files.exists(outputs,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(outputs,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));if(!outputs.toRealPath().equals(outputs))throw new IllegalStateException("STORY_PREPARATION_OUTPUT_PATH");
        var guard=Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("WorldGuard"));guardWorlds=guard.getDataFolder().toPath().toAbsolutePath().resolve("worlds");if(!guardWorlds.toRealPath().equals(guardWorlds))throw new IllegalStateException("STORY_PREPARATION_GUARD_PATH");
        allocator=new PaperWorldBackend(settings.jobs().values().stream().map(j->Objects.requireNonNull(NamespacedKey.fromString(j.key()))).collect(java.util.stream.Collectors.toSet()));
    }
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("STORY_PREPARATION_OWNER");}
    public Map<String,Status> status(){main();return Map.copyOf(status);}
    public boolean busy(){main();return active!=null;}
    public boolean held(){main();return status.values().stream().anyMatch(s->s.state()==State.HOLD);}
    public CompletionStage<Prepared> prepare(String id){
        main();if(closed)return CompletableFuture.failedFuture(new IllegalStateException("STORY_PREPARATION_CLOSED"));var prior=operations.get(id);if(prior!=null)return prior.view;var job=settings.jobs().get(id);if(job==null)return CompletableFuture.failedFuture(new IllegalArgumentException("STORY_PREPARATION_UNKNOWN_JOB"));if(active!=null)return CompletableFuture.failedFuture(new IllegalStateException("STORY_PREPARATION_BUSY"));
        var op=new Operation(job);operations.put(id,op);active=op;status.put(id,new Status(State.PREFLIGHT,"NONE"));
        file(op,()->{var source=StoryTemplateSource.inspect(templates,job,settings.maxFiles(),settings.maxBytes());StoryTemplateSource.requireSpace(level.resolve("dimensions/hearth_story").resolve(job.key().substring(job.key().indexOf(':')+1)),source.bytes(),settings.reserveBytes());StoryTemplateSource.requireSpace(guardWorlds,Files.size(source.regions()),settings.reserveBytes());StoryTemplateSource.requireSpace(outputs,65536,settings.reserveBytes());return source;})
            .thenComposeAsync(source->{op.live();op.source=source;return registry.requireNew(job.run(),job.key());},owner)
            .thenComposeAsync(v->safe(op,()->allocate(op)),owner)
            .thenComposeAsync(v->file(op,()->{op.pending=RuntimeLease.acquire(op.placement.directory().resolve(".hearth-story-provisioning"),"Hearth story preparation v1\n"+job.run()+"\n"+job.key()+"\n").orElseThrow(()->new IllegalStateException("STORY_PREPARATION_PENDING_EXISTS"));op.metadataHash=StoryTemplateSource.hash(op.placement.directory().resolve("data/paper/metadata.dat"));op.source.copy(op.placement.directory(),op.regions,op.originalRegionsHash);if(!op.metadataHash.equals(StoryTemplateSource.hash(op.placement.directory().resolve("data/paper/metadata.dat"))))throw new IllegalStateException("STORY_PREPARATION_UUID_METADATA_CHANGED");return null;}),owner)
            .thenComposeAsync(v->{op.live();step(op,State.COPIED);return safe(op,()->allocator.loadAllocated(op.placement));},owner)
            .thenComposeAsync(world->{op.live();var manager=Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world)));return file(op,()->{manager.load();return manager;});},owner)
            .thenComposeAsync(manager->safe(op,()->{for(String region:job.regions())if(manager.getRegion(region)==null)throw new IllegalStateException("STORY_PREPARATION_REQUIRED_REGION_MISSING");return Objects.requireNonNull(Bukkit.getWorld(op.placement.physicalId()));}),owner)
            .thenComposeAsync(world->world.getChunkAtAsync(((int)Math.floor(job.x()))>>4,((int)Math.floor(job.z()))>>4),owner)
            .thenComposeAsync(chunk->safe(op,()->{verifyEntry(op);allocator.saveAndUnload(op.placement);op.run=job.run(op.placement.physicalId(),settings.contentHash());step(op,State.VERIFIED);return null;}),owner)
            .thenComposeAsync(v->file(op,()->{var marker=op.placement.directory().resolve(".hearth-story-world");Files.writeString(marker,job.run()+"/"+job.key()+"/"+op.placement.physicalId()+"\n"+job.version()+"/"+job.spatialHash()+"\n",StandardOpenOption.CREATE_NEW);writeReport(op);return null;}),owner)
            .thenComposeAsync(v->{op.live();return registry.createPrepared(op.run.binding());},owner)
            .thenComposeAsync(v->{op.live();step(op,State.REGISTERED);return file(op,()->{op.pending.clean();return null;});},owner)
            .toCompletableFuture().orTimeout(90,TimeUnit.SECONDS).whenCompleteAsync((v,error)->{
                main();active=null;if(op.result.isDone())return;if(error!=null){op.state=!op.mutationStarted?State.REFUSED:State.HOLD;status.put(id,new Status(op.state,code(error)));op.result.completeExceptionally(error);return;}
                step(op,State.READY);op.result.complete(new Prepared(op.run,op.report));
            },owner);
        return op.view;
    }
    private Void allocate(Operation op)throws Exception{
        var key=Objects.requireNonNull(NamespacedKey.fromString(op.job.key()));var predicted=key.getNamespace()+"_"+key.getKey();var guardDirectory=guardWorlds.resolve(predicted);if(Files.exists(guardDirectory,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("STORY_PREPARATION_EXISTING_GUARD_DATA");
        var directory=Bukkit.getServer().getLevelDirectory().resolve("dimensions").resolve(key.getNamespace()).resolve(key.getKey()).toAbsolutePath().normalize();if(Bukkit.getWorld(key)!=null||Files.exists(directory,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("STORY_PREPARATION_EXISTING_WORLD");op.mutationStarted=true;
        op.placement=allocator.create(op.job.run(),key);step(op,State.ALLOCATED);var world=Objects.requireNonNull(Bukkit.getWorld(op.placement.physicalId()));if(!world.getName().equals(predicted))throw new IllegalStateException("STORY_PREPARATION_GUARD_NAME_MISMATCH");
        var manager=Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world)));if(!manager.getRegions().isEmpty())throw new IllegalStateException("STORY_PREPARATION_NONEMPTY_NEW_GUARD_DATA");
        allocator.saveAndUnload(op.placement);if(!Files.exists(guardDirectory,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(guardDirectory,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));op.regions=guardDirectory.resolve("regions.yml");if(!guardDirectory.toRealPath().equals(guardDirectory))throw new IllegalStateException("STORY_PREPARATION_GUARD_PATH");
        if(Files.exists(op.regions,LinkOption.NOFOLLOW_LINKS)){if(!Files.isRegularFile(op.regions,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("STORY_PREPARATION_GUARD_PATH");op.originalRegionsHash=StoryTemplateSource.hash(op.regions);}return null;
    }
    private void verifyEntry(Operation op){
        var world=Objects.requireNonNull(Bukkit.getWorld(op.placement.physicalId()));var job=op.job;var at=new Location(world,job.x(),job.y(),job.z());if(at.getY()<=world.getMinHeight()||at.getY()+2>=world.getMaxHeight()||!world.getWorldBorder().isInside(at))throw new IllegalStateException("STORY_PREPARATION_ENTRY_UNSAFE");
        var floor=world.getBlockAt(at.getBlockX(),at.getBlockY()-1,at.getBlockZ());var feet=world.getBlockAt(at.getBlockX(),at.getBlockY(),at.getBlockZ());var head=world.getBlockAt(at.getBlockX(),at.getBlockY()+1,at.getBlockZ());if(!CombatPlayers.safeCells(floor.getType().name(),feet.getType().name(),head.getType().name(),floor.getType().isSolid(),feet.isPassable(),head.isPassable()))throw new IllegalStateException("STORY_PREPARATION_ENTRY_UNSAFE");
    }
    private void writeReport(Operation op)throws Exception{
        var config=new YamlConfiguration();config.set("template-regions-sha256",op.job.regionHash());config.set("content-file",settings.contentFile());config.set("content-sha256",settings.contentHash());var b=op.run.binding();var run=new LinkedHashMap<String,Object>();run.put("run",b.run().toString());run.put("world-key",b.worldKey());run.put("world-uuid",b.worldId().toString());run.put("template-version",b.templateVersion());run.put("template-sha256",b.templateHash());run.put("entry-x",op.run.x());run.put("entry-y",op.run.y());run.put("entry-z",op.run.z());run.put("entry-yaw",op.run.yaw());run.put("entry-pitch",op.run.pitch());run.put("required-regions",List.copyOf(op.run.regions()));config.set("runs",List.of(run));
        op.report=outputs.resolve(op.job.id()+"-"+UUID.randomUUID()+".yml");Files.writeString(op.report,config.saveToString(),StandardOpenOption.CREATE_NEW);
    }
    private void step(Operation op,State next){main();op.state=next;status.put(op.job.id(),new Status(next,"NONE"));}
    private <T> CompletionStage<T> file(Operation op,Callable<T> work){return CompletableFuture.supplyAsync(()->{op.live();try{return work.call();}catch(Exception error){throw new CompletionException(error);}},io);}
    private <T> CompletionStage<T> safe(Operation op,Callable<T> work){main();var result=new CompletableFuture<T>();result.orTimeout(10,TimeUnit.SECONDS);Bukkit.getScheduler().runTask(plugin,new Runnable(){public void run(){if(result.isDone())return;try{op.live();if(!plugin.isEnabled())throw new IllegalStateException("STORY_PREPARATION_CLOSED");if(Bukkit.isTickingWorlds()){Bukkit.getScheduler().runTaskLater(plugin,this,1);return;}result.complete(work.call());}catch(Exception error){result.completeExceptionally(error);}}});return result;}
    public static String code(Throwable error){while((error instanceof CompletionException||error instanceof ExecutionException)&&error.getCause()!=null)error=error.getCause();if(error instanceof io.github.jesterportfolio.hearth.persistence.StorageFailure storage)return storage.code();if(error.getMessage()!=null&&error.getMessage().matches("[A-Z0-9_]{1,80}"))return error.getMessage();return error.getClass().getSimpleName();}
    @Override public void close(){main();closed=true;for(var op:operations.values())if(!op.result.isDone()){op.state=!op.mutationStarted?State.REFUSED:State.HOLD;status.put(op.job.id(),new Status(op.state,"CLOSED"));op.result.completeExceptionally(new IllegalStateException("STORY_PREPARATION_CLOSED"));}}
}
