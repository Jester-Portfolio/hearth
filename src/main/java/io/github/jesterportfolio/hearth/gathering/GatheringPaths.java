package io.github.jesterportfolio.hearth.gathering;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.IOException;
import java.util.*;

/** 스토리/경제 증거를 나누고 기존 symlink/불명 파일을 채택하지 않습니다. */
public final class GatheringPaths {
    private GatheringPaths(){}
    public static Path directory(Path plugin,String namespace,UUID owner,String dungeon)throws IOException{
        Objects.requireNonNull(owner);GatheringContent.key(dungeon);if(!Set.of("gathering","economy-gathering").contains(namespace))throw new IllegalArgumentException("GATHERING_NAMESPACE");plugin=plugin.toAbsolutePath().normalize();if(!plugin.toRealPath().equals(plugin))throw new IOException("GATHERING_PATH");
        Path base=plugin.resolve(namespace);directory(base);if(!base.toRealPath().equals(base))throw new IOException("GATHERING_PATH");Path folder=base.resolve(owner+"_"+dungeon);directory(folder);if(!folder.toRealPath().equals(folder))throw new IOException("GATHERING_PATH");return folder;
    }
    private static void directory(Path path)throws IOException{if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path))throw new IOException("GATHERING_PATH");}
}
