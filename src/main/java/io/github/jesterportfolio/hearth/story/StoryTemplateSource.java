package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.world.TemplateStore;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 명시한 템플릿의 공간 파일과 WorldGuard 정의만 읽습니다. 원본에는 쓰지 않습니다. */
public final class StoryTemplateSource {
    private final Path directory,regions;private final String spatialHash,regionHash;private final long bytes;
    private StoryTemplateSource(Path directory,Path regions,String spatialHash,String regionHash,long bytes){this.directory=directory;this.regions=regions;this.spatialHash=spatialHash;this.regionHash=regionHash;this.bytes=bytes;}
    public Path directory(){return directory;}public Path regions(){return regions;}public long bytes(){return bytes;}
    /** 아직 없는 새 경로도 실제 가장 가까운 저장소의 여유를 확인합니다. 파일/별칭은 채택하지 않습니다. */
    public static void requireSpace(Path target,long bytes,long reserve)throws Exception{
        if(bytes<0||reserve<0)throw new IllegalArgumentException("STORY_PREPARATION_SPACE_BUDGET");long required=Math.addExact(bytes,reserve);var parent=target.toAbsolutePath().normalize();while(!Files.exists(parent,LinkOption.NOFOLLOW_LINKS)){parent=parent.getParent();if(parent==null)throw new IllegalStateException("STORY_PREPARATION_TARGET_PARENT_MISSING");}
        if(!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS)||!parent.toRealPath().equals(parent))throw new IllegalStateException("STORY_PREPARATION_TARGET_PATH_ALIAS");
        if(Files.getFileStore(parent).getUsableSpace()<required)throw new IllegalStateException("STORY_PREPARATION_DISK_RESERVE");
    }
    public static String hash(Path path)throws Exception{var hash=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){var buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)hash.update(buffer,0,n);}return HexFormat.of().formatHex(hash.digest());}
    public static StoryTemplateSource inspect(Path root,StoryPreparationSettings.Job job,int maxFiles,long maxBytes)throws Exception{
        root=root.toAbsolutePath().normalize();if(!root.toRealPath().equals(root))throw new IllegalStateException("STORY_TEMPLATE_PATH_ALIAS");var dir=root.resolve(job.template());if(!dir.toRealPath().equals(dir))throw new IllegalStateException("STORY_TEMPLATE_PATH_ALIAS");
        long size=0;int count=0;
        for(String folder:List.of("region","entities","poi")){
            var path=dir.resolve(folder);if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))continue;
            try(var walk=Files.walk(path)){var files=walk.iterator();while(files.hasNext()){var f=files.next();if(Files.isDirectory(f,LinkOption.NOFOLLOW_LINKS)&&!f.equals(path))throw new IllegalStateException("STORY_TEMPLATE_SPATIAL_LAYOUT");if(Files.isSymbolicLink(f)||!Files.isDirectory(f,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("STORY_TEMPLATE_SPECIAL_FILE");if(Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS)){if(++count>maxFiles)throw new IllegalStateException("STORY_TEMPLATE_FILE_LIMIT");size=Math.addExact(size,Files.size(f));if(size>maxBytes)throw new IllegalStateException("STORY_TEMPLATE_BYTE_LIMIT");}}}
        }
        var regions=dir.resolve("worldguard-regions.yml");if(!Files.isRegularFile(regions,LinkOption.NOFOLLOW_LINKS)||Files.size(regions)>1024*1024)throw new IllegalStateException("STORY_TEMPLATE_REGIONS_REQUIRED");size=Math.addExact(size,Files.size(regions));if(++count>maxFiles||size>maxBytes)throw new IllegalStateException("STORY_TEMPLATE_LIMIT");
        if(!new TemplateStore().digest(dir).equals(job.spatialHash())||!hash(regions).equals(job.regionHash()))throw new IllegalStateException("STORY_TEMPLATE_HASH_CONFLICT");
        var entry=dir.resolve("region/r."+(((int)Math.floor(job.x()))>>9)+"."+(((int)Math.floor(job.z()))>>9)+".mca");if(!Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("STORY_TEMPLATE_ENTRY_REGION_MISSING");
        return new StoryTemplateSource(dir,regions,job.spatialHash(),job.regionHash(),size);
    }
    public void copy(Path destination,Path targetRegions,String originalTargetHash)throws Exception{
        if(!hash(regions).equals(regionHash))throw new IllegalStateException("STORY_TEMPLATE_HASH_CONFLICT");
        if(Files.exists(targetRegions,LinkOption.NOFOLLOW_LINKS)&&(!Files.isRegularFile(targetRegions,LinkOption.NOFOLLOW_LINKS)||originalTargetHash==null||!hash(targetRegions).equals(originalTargetHash)))throw new IllegalStateException("STORY_REGIONS_TARGET_CHANGED");
        if(!targetRegions.getParent().toRealPath().equals(targetRegions.getParent()))throw new IllegalStateException("STORY_REGIONS_PATH_ALIAS");
        new TemplateStore().copySpatial(directory,destination,spatialHash);
        if(originalTargetHash==null)Files.copy(regions,targetRegions);else Files.copy(regions,targetRegions,StandardCopyOption.REPLACE_EXISTING);
        if(!hash(regions).equals(regionHash)||!hash(targetRegions).equals(regionHash))throw new IllegalStateException("STORY_REGIONS_HASH_CONFLICT");
    }
}
