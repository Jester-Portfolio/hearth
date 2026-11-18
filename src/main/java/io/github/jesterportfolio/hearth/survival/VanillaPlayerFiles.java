package io.github.jesterportfolio.hearth.survival;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.channels.FileChannel;
import java.util.*;
import java.security.MessageDigest;

/** 네트워크 전환에서만 기본 저장 API 호출 뒤 새 파일을 확인합니다. DB/인벤토리 재적용은 없습니다. */
public final class VanillaPlayerFiles {
    private VanillaPlayerFiles(){}
    private record Before(Object key,java.nio.file.attribute.FileTime modified,String hash){}
    private static Before snapshot(Path path)throws Exception{if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return null;if(!path.equals(path.toRealPath())||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>4*1024*1024)throw new IllegalStateException("PLAYER_DATA_PATH");var attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return new Before(attributes.fileKey(),attributes.lastModifiedTime(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));}
    public static void save(Path levelDirectory,UUID player,Runnable save){try{var root=levelDirectory.toAbsolutePath().normalize();if(!root.equals(root.toRealPath()))throw new IllegalStateException("PLAYER_DATA_PATH");var directory=root.resolve("playerdata");if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||!directory.equals(directory.toRealPath()))throw new IllegalStateException("PLAYER_DATA_PATH");var file=directory.resolve(player+".dat");var before=snapshot(file);save.run();var after=snapshot(file);if(after==null||Files.size(file)==0||Objects.equals(before,after))throw new IllegalStateException("PLAYER_SAVE_NOT_CONFIRMED");try(var channel=FileChannel.open(file,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}}catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException("PLAYER_SAVE_NOT_CONFIRMED");}}
}
