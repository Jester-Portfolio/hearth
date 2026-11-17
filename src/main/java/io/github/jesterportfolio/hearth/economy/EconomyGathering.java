package io.github.jesterportfolio.hearth.economy;

import io.github.jesterportfolio.hearth.gathering.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.lifecycle.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** 경제 realm 소유의 기존 채집/시설. 개인 계정이나 스토리 저장을 생성하지 않습니다. */
public final class EconomyGathering implements AutoCloseable {
    public final PaperGathering gathering;public final EconomyGatheringContent content;private final PaperEconomy economy;private final Executor owner;private final ServerProgressAccess.Context metadata;
    public EconomyGathering(JavaPlugin plugin,PostgresProgressStore db,PaperEconomy economy,EconomyGatheringContent content,Executor owner){
        this.economy=economy;this.content=content;this.owner=owner;
        // 플레이어 객체/계정이 아닌 realm 공유 메타데이터 문맥입니다. SQL 소유권에는 realm만 사용합니다.
        metadata=economy.store.context(economy.realm,new UUID(0,0));gathering=new PaperGathering(plugin,db,economy.adapter,Map.of(economy.realm,economy.world),content.gathering(),owner,System::currentTimeMillis,economy.sessions::saveRunBoundary,c->economy.store.gathering(c,content.gathering().id()),"economy-gathering");
        gathering.facilities(content.facilities(),(ctx,site)->ctx.storyRunId().equals(economy.realm),p->gathering.information(p).forEach(p::sendMessage),(p,current,back)->{if(current.getAsBoolean()){gathering.information(p).forEach(p::sendMessage);back.run();}});
    }
    public static EconomyGatheringContent load(JavaPlugin plugin,EconomyGatheringContent.File config)throws Exception{
        var base=plugin.getDataFolder().toPath().toAbsolutePath();if(!base.toRealPath().equals(base))throw new IllegalStateException("ECON_GATHERING_PATH");var file=base.resolve(config.name());if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>1024*1024)throw new IllegalStateException("ECON_GATHERING_FILE");byte[] bytes=Files.readAllBytes(file);String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));if(!hash.equals(config.sha256()))throw new IllegalStateException("ECON_GATHERING_HASH");var yaml=new YamlConfiguration();yaml.loadFromString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));return EconomyGatheringContent.parse(map(yaml));
    }
    private static Map<String,Object> map(ConfigurationSection section){var result=new LinkedHashMap<String,Object>();section.getValues(false).forEach((k,v)->result.put(k,v instanceof ConfigurationSection child?map(child):v));return result;}
    public CompletionStage<Void> restored(){return gathering.prepare(metadata).thenRunAsync(()->{gathering.ensureFacilities(economy.realm);requireHealthy();},owner);}
    private void requireHealthy(){var row=gathering.state(economy.realm).orElseThrow(()->new IllegalStateException("ECON_GATHERING_NOT_PREPARED"));if(row.phase()!=GatheringStore.Phase.OPEN)throw new IllegalStateException("ECON_GATHERING_REVIEW_REQUIRED");}
    public CompletionStage<Void> barrier(){return gathering.drain(economy.realm).thenRunAsync(()->{if(gathering.state(economy.realm).isPresent())requireHealthy();if(!gathering.settled(economy.realm))throw new IllegalStateException("ECON_GATHERING_PENDING");},owner);}
    public boolean held(){return gathering.state(economy.realm).map(s->s.phase()==GatheringStore.Phase.ERROR_HOLD).orElse(false);}
    public void unloaded(){gathering.unloaded(economy.realm);}
    @Override public void close(){gathering.close();}
}
