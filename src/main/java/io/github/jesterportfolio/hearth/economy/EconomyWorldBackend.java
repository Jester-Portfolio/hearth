package io.github.jesterportfolio.hearth.economy;

import io.github.jesterportfolio.hearth.world.WorldBackend;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;

/** 명시한 기존 경제 차원만 연결합니다. 지형·보호 구역·UUID를 새로 생성/교체하지 않습니다. */
public final class EconomyWorldBackend implements WorldBackend {
    private final Placement expected;private final EconomyRuntimeSettings settings;private boolean registered;
    public EconomyWorldBackend(EconomyRuntimeSettings settings){
        this.settings=settings;var key=Objects.requireNonNull(NamespacedKey.fromString(settings.worldKey()));
        var directory=Bukkit.getServer().getLevelDirectory().resolve("dimensions").resolve(key.getNamespace()).resolve(key.getKey()).toAbsolutePath().normalize();
        expected=new Placement(settings.realm(),key,settings.worldId(),directory);
    }
    public Placement placement(){return expected;}
    private void main(){if(!Bukkit.isPrimaryThread()||Bukkit.isTickingWorlds())throw new IllegalStateException("ECON_WORLD_SAFE_POINT");}
    private void stored(){try{
        var dir=expected.directory();if(!dir.toRealPath().equals(dir)||!Files.isRegularFile(dir.resolve("data/paper/metadata.dat"),LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(dir.resolve("region/r."+(((int)Math.floor(settings.x()))>>9)+"."+(((int)Math.floor(settings.z()))>>9)+".mca"),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("ECON_WORLD_NOT_PREPARED");
        String identity=expected.storyRunId()+"/"+expected.key()+"/"+expected.physicalId();
        for(String name:List.of(".hearth-s4-spatial-ready",".hearth-economy-world")){var marker=dir.resolve(name);if(Files.exists(marker,LinkOption.NOFOLLOW_LINKS)&&(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)||!Files.readString(marker).trim().equals(identity)))throw new IllegalStateException("ECON_WORLD_OWNER_CONFLICT");}
    }catch(java.io.IOException error){throw new IllegalStateException("ECON_WORLD_NOT_PREPARED");}}
    public Placement create(UUID realm,NamespacedKey key){throw new IllegalStateException("ECON_EXISTING_WORLD_REQUIRED");}
    public void registerExisting(Placement value){main();if(registered||!expected.equals(value))throw new IllegalStateException("ECON_WORLD_BINDING_CONFLICT");stored();registered=true;}
    private void require(Placement value){if(!registered||!expected.equals(value))throw new IllegalStateException("ECON_WORLD_BINDING_CONFLICT");stored();}
    public World load(Placement value){main();require(value);var loaded=Bukkit.getWorld(value.key());if(loaded==null)loaded=Bukkit.createWorld(WorldCreator.ofKey(value.key()));var world=Objects.requireNonNull(loaded);if(!world.getUID().equals(value.physicalId())||!world.getWorldPath().toAbsolutePath().normalize().equals(value.directory()))throw new IllegalStateException("ECON_WORLD_IDENTITY_CHANGED");try{var marker=value.directory().resolve(".hearth-economy-world");if(!Files.exists(marker,LinkOption.NOFOLLOW_LINKS))Files.writeString(marker,value.storyRunId()+"/"+value.key()+"/"+value.physicalId(),StandardOpenOption.CREATE_NEW);}catch(java.io.IOException error){throw new IllegalStateException("ECON_WORLD_BINDING_WRITE_FAILED");}world.setAutoSave(false);return world;}
    public void save(Placement value){main();require(value);var world=Objects.requireNonNull(Bukkit.getWorld(value.key()));if(!world.getUID().equals(value.physicalId())||!world.getPlayers().isEmpty())throw new IllegalStateException("ECON_WORLD_SAVE_BLOCKED");world.save(true);}
    public boolean unload(Placement value){main();require(value);var world=Objects.requireNonNull(Bukkit.getWorld(value.key()));if(!world.getUID().equals(value.physicalId())||!world.getPlayers().isEmpty()||!world.getPluginChunkTickets().isEmpty()||!world.getForceLoadedChunks().isEmpty())throw new IllegalStateException("ECON_WORLD_UNLOAD_BLOCKED");return Bukkit.unloadWorld(world,false)&&Bukkit.getWorld(value.key())==null;}
    public void saveAndUnload(Placement value){save(value);if(!unload(value))throw new IllegalStateException("ECON_WORLD_UNLOAD_FAILED");}
}
