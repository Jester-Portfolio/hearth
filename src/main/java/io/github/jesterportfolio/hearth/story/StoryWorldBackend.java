package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.world.WorldBackend;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;

/** 이미 준비한 진행 전용 차원만 엽니다. 누락된 지형/키/UUID를 자동 생성하지 않습니다. */
public final class StoryWorldBackend implements WorldBackend {
    private final StoryRuntimeSettings.Run settings;private final Placement expected;private boolean registered;
    public StoryWorldBackend(StoryRuntimeSettings.Run settings){this.settings=settings;var b=settings.binding();var key=Objects.requireNonNull(NamespacedKey.fromString(b.worldKey()));expected=new Placement(b.run(),key,b.worldId(),Bukkit.getServer().getLevelDirectory().resolve("dimensions").resolve(key.getNamespace()).resolve(key.getKey()).toAbsolutePath().normalize());}
    public Placement placement(){return expected;}
    public void verifyPrepared(){stored();}
    public String identity(){var b=settings.binding();return b.run()+"/"+b.worldKey()+"/"+b.worldId()+"\n"+b.templateVersion()+"/"+b.templateHash()+"\n";}
    private void stored(){try{var p=expected.directory();if(!p.toRealPath().equals(p)||Files.exists(p.resolve(".hearth-economy-world"),LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(p.resolve("data/paper/metadata.dat"),LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(p.resolve("region/r."+(((int)Math.floor(settings.x()))>>9)+"."+(((int)Math.floor(settings.z()))>>9)+".mca"),LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(p.resolve(".hearth-story-world"),LinkOption.NOFOLLOW_LINKS)||!Files.readString(p.resolve(".hearth-story-world")).equals(identity()))throw new IllegalStateException("STORY_WORLD_NOT_PREPARED");}catch(java.io.IOException error){throw new IllegalStateException("STORY_WORLD_NOT_PREPARED",error);}}
    private static void main(){if(!Bukkit.isPrimaryThread()||Bukkit.isTickingWorlds())throw new IllegalStateException("STORY_WORLD_SAFE_POINT");}
    public Placement create(UUID run,NamespacedKey key){throw new IllegalStateException("STORY_EXISTING_WORLD_REQUIRED");}
    public void registerExisting(Placement value){main();if(registered||!expected.equals(value))throw new IllegalStateException("STORY_WORLD_BINDING_CONFLICT");stored();registered=true;}
    private void require(Placement p){if(!registered||!expected.equals(p))throw new IllegalStateException("STORY_WORLD_BINDING_CONFLICT");stored();}
    public World load(Placement p){main();require(p);var world=Bukkit.getWorld(p.key());if(world==null)world=Bukkit.createWorld(WorldCreator.ofKey(p.key()));world=Objects.requireNonNull(world);if(!world.getUID().equals(p.physicalId())||!world.getWorldPath().toAbsolutePath().normalize().equals(p.directory()))throw new IllegalStateException("STORY_WORLD_IDENTITY_CHANGED");world.setAutoSave(false);return world;}
    private World loaded(Placement p){require(p);var w=Objects.requireNonNull(Bukkit.getWorld(p.key()));if(!w.getUID().equals(p.physicalId())||!w.getWorldPath().toAbsolutePath().normalize().equals(p.directory()))throw new IllegalStateException("STORY_WORLD_IDENTITY_CHANGED");return w;}
    public void save(Placement p){main();var w=loaded(p);if(!w.getPlayers().isEmpty())throw new IllegalStateException("STORY_WORLD_OCCUPIED");w.save(true);}
    public boolean unload(Placement p){main();var w=loaded(p);if(!w.getPlayers().isEmpty()||!w.getPluginChunkTickets().isEmpty()||!w.getForceLoadedChunks().isEmpty())throw new IllegalStateException("STORY_WORLD_UNLOAD_BLOCKED");return Bukkit.unloadWorld(w,false)&&Bukkit.getWorld(p.key())==null;}
    public void saveAndUnload(Placement p){save(p);if(!unload(p))throw new IllegalStateException("STORY_WORLD_UNLOAD_FAILED");}
}
