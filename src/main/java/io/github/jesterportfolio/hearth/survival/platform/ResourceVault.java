package io.github.jesterportfolio.hearth.survival.platform;

import java.nio.file.*;import java.nio.channels.FileChannel;import java.security.*;import java.util.*;import java.io.*;

/** 메타데이터를 포함한 전체 파일 seal을 검사하는 같은 파일시스템의 비파괴 cold 보관. */
public final class ResourceVault {
    private final Path level;
    public ResourceVault(Path level)throws Exception{this.level=level.toRealPath();}
    public Path source(String key)throws Exception{if(!key.matches("hearth_resource:r_(wilderness|nether|novice|end)_[a-f0-9]{32}"))throw new IOException("VAULT_SCOPE");return level.resolve("dimensions/hearth_resource").resolve(key.substring(key.indexOf(':')+1));}
    public Path cold(UUID id){return level.resolve(".hearth-resource-archive").resolve(id.toString());}
    private void safe(Path p)throws Exception{if(!p.startsWith(level)||!p.equals(p.toRealPath())||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("VAULT_PATH");}
    public String seal(Path root)throws Exception{
        safe(root);var digest=MessageDigest.getInstance("SHA-256");try(var paths=Files.walk(root)){var files=paths.limit(100001).sorted().toList();if(files.size()>100000)throw new IOException("VAULT_CAPACITY");byte[] buffer=new byte[65536];for(var p:files){if(!p.equals(p.toRealPath())||Files.isSymbolicLink(p))throw new IOException("VAULT_LINK");String rel=root.relativize(p).toString();digest.update(rel.getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update((byte)0);if(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)){digest.update((byte)'D');continue;}if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("VAULT_TYPE");digest.update((byte)'F');digest.update(java.nio.ByteBuffer.allocate(8).putLong(Files.size(p)).array());try(var in=Files.newInputStream(p)){for(int n;(n=in.read(buffer))!=-1;)digest.update(buffer,0,n);}}}return HexFormat.of().formatHex(digest.digest());
    }
    public void move(String key,UUID id,String expected,boolean restore)throws Exception{
        Path from=restore?cold(id):source(key),to=restore?source(key):cold(id);boolean a=Files.exists(from,LinkOption.NOFOLLOW_LINKS),b=Files.exists(to,LinkOption.NOFOLLOW_LINKS);
        if(a&&b||!a&&!b)throw new IOException("VAULT_AMBIGUOUS_OR_MISSING");Path actual=a?from:to;if(!seal(actual).equals(expected))throw new IOException("VAULT_CHANGED");if(!a)return;
        Files.createDirectories(to.getParent());safe(to.getParent());Files.move(from,to,StandardCopyOption.ATOMIC_MOVE);sync(from.getParent());sync(to.getParent());if(!seal(to).equals(expected))throw new IOException("VAULT_MOVE_REVIEW");
    }
    private static void sync(Path dir)throws Exception{try(var channel=FileChannel.open(dir,StandardOpenOption.READ)){channel.force(true);}}
}
