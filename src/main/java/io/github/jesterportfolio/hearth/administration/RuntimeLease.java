package io.github.jesterportfolio.hearth.administration;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** 종료 확인 전 실행 표식을 지우거나 기존 표식을 덮어쓰지 않는 작은 파일 경계. */
public final class RuntimeLease {
    private RuntimeLease(){}
    public static final class Lease {
        private final Path path;private final byte[] content;private final Object key;private boolean cleaned;
        private Lease(Path path,byte[] content,Object key){this.path=path;this.content=content;this.key=key;}
        /** Publish a cleanup intent without replacing an existing record. Both links survive a partial retirement. */
        public void retire(Path destination)throws java.io.IOException{
            if(cleaned)throw new java.io.IOException("RUNTIME_ALREADY_CLEANED");
            if(!destination.getParent().equals(path.getParent())||Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new java.io.IOException("RUNTIME_RETIRE_PATH");
            var attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attributes.isRegularFile()||!Objects.equals(key,attributes.fileKey())||!Arrays.equals(content,Files.readAllBytes(path)))throw new java.io.IOException("RUNTIME_MARKER_CHANGED");
            Files.createLink(destination,path);Files.delete(path);cleaned=true;
        }
        public void clean()throws java.io.IOException{
            if(cleaned)return;
            var attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attributes.isRegularFile()||!Objects.equals(key,attributes.fileKey())||!Arrays.equals(content,Files.readAllBytes(path)))throw new java.io.IOException("RUNTIME_MARKER_CHANGED");
            Files.delete(path);cleaned=true;
        }
    }
    public static Optional<Lease> acquire(Path path,String header)throws java.io.IOException{
        path=path.toAbsolutePath();if(!path.getParent().toRealPath().equals(path.getParent())||header==null||header.length()>512)throw new java.io.IOException("RUNTIME_MARKER_PATH");
        var content=(header+UUID.randomUUID()+"\n").getBytes(StandardCharsets.UTF_8);
        try(var channel=FileChannel.open(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))){
            var buffer=ByteBuffer.wrap(content);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }catch(FileAlreadyExistsException exists){return Optional.empty();}
        return Optional.of(new Lease(path,content,Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey()));
    }
}
