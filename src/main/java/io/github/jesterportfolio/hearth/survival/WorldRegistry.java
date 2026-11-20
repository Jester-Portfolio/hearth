package io.github.jesterportfolio.hearth.survival;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.io.*;

/** 새 묶음의 준비 결과만 게시합니다. 기존 파일의 덮어쓰기/누락 월드 재생성은 없습니다. */
public final class WorldRegistry {
    public record Entry(String key,UUID uuid,long seed,String terrain,double x,double y,double z) {
        public Entry {if(key==null||!key.matches("[a-z0-9_]+:[a-z0-9_]+")||uuid==null||!Set.of("NORMAL","FLAT").contains(terrain)||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.abs(x)>29999000||Math.abs(z)>29999000||y< -2032||y>2032)throw new IllegalArgumentException("WORLD_REGISTRY_ENTRY");}
    }
    private WorldRegistry(){}
    public static Map<SurvivalWorld,Entry> read(Path file,SurvivalSettings settings)throws IOException {
        if(!file.equals(file.toRealPath())||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>16384)throw new IOException("WORLD_REGISTRY_PATH");
        var p=new Properties();try(var reader=Files.newBufferedReader(file)){p.load(reader);}
        if(!"1".equals(p.getProperty("format"))||!settings.group().toString().equals(p.getProperty("group")))throw new IOException("WORLD_REGISTRY_GROUP");
        var result=new EnumMap<SurvivalWorld,Entry>(SurvivalWorld.class);
        try{for(var role:SurvivalWorld.base()){String k=role.id()+".";var entry=new Entry(p.getProperty(k+"key"),UUID.fromString(p.getProperty(k+"uuid")),Long.parseLong(p.getProperty(k+"seed")),p.getProperty(k+"terrain"),Double.parseDouble(p.getProperty(k+"x")),Double.parseDouble(p.getProperty(k+"y")),Double.parseDouble(p.getProperty(k+"z")));var def=settings.worlds().get(role);if(!def.key().equals(entry.key())||!def.terrain().equals(entry.terrain())||def.seed()!=null&&def.seed()!=entry.seed())throw new IOException("WORLD_REGISTRY_CONFIG_CHANGED");result.put(role,entry);}
            if(result.values().stream().map(Entry::uuid).distinct().count()!=3)throw new IOException("WORLD_REGISTRY_DUPLICATE");
        }catch(IllegalArgumentException|NullPointerException invalid){throw new IOException("WORLD_REGISTRY_INVALID",invalid);}
        return Map.copyOf(result);
    }
    public static void publish(Path file,SurvivalSettings settings,Map<SurvivalWorld,Entry> entries)throws IOException {
        if(!entries.keySet().equals(EnumSet.copyOf(SurvivalWorld.base()))||!file.getParent().equals(file.getParent().toRealPath())||Files.exists(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("WORLD_REGISTRY_NEW_ONLY");
        var p=new Properties();p.setProperty("format","1");p.setProperty("group",settings.group().toString());for(var role:SurvivalWorld.base()){var e=entries.get(role);String k=role.id()+".";p.setProperty(k+"key",e.key());p.setProperty(k+"uuid",e.uuid().toString());p.setProperty(k+"seed",Long.toString(e.seed()));p.setProperty(k+"terrain",e.terrain());p.setProperty(k+"x",Double.toString(e.x()));p.setProperty(k+"y",Double.toString(e.y()));p.setProperty(k+"z",Double.toString(e.z()));}
        // 준비 lease가 모든 쓰기 작업을 직렬화합니다. 충돌 시 CREATE_NEW 실패를 그대로 보존합니다.
        try(var writer=Files.newBufferedWriter(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){p.store(writer,"Hearth vanilla world group v1");}
        try(var channel=FileChannel.open(file,StandardOpenOption.WRITE)){channel.force(true);}read(file,settings);
    }
}
