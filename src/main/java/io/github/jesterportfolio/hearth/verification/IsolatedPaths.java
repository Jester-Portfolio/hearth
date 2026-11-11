package io.github.jesterportfolio.hearth.verification;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** 명시적으로 승인한 시험 경로만 쓰기 허용. 과거 /tmp는 런타임으로 열지 않습니다. */
public final class IsolatedPaths {
    public static final Path BASE=Path.of("/home/server/hearth-tmp");
    private static final String MARKER="Hearth isolated test base v1\n";
    private IsolatedPaths(){}
    public static Path require(Path root,String prefix)throws IOException{
        checkBase(BASE);
        if(!root.isAbsolute()||!root.equals(root.toRealPath())||!BASE.equals(root.getParent())||!Files.isDirectory(root)
                ||!root.getFileName().toString().matches(java.util.regex.Pattern.quote(prefix)+"[A-Za-z0-9_-]{1,80}")
                ||!Files.getOwner(root).equals(Files.getOwner(Path.of("/proc/self"))))throw new IOException("ISOLATED_WORKSPACE_REJECTED");
        return root;
    }
    static void checkBase(Path base)throws IOException{
        Path marker=base.resolve(".hearth-test-base");
        if(((Number)Files.getAttribute(Path.of("/proc/self"),"unix:uid")).intValue()==0||!base.equals(base.toRealPath())
                ||!Files.isDirectory(base)||!Files.getOwner(base).equals(Files.getOwner(Path.of("/proc/self")))
                ||!Files.getPosixFilePermissions(base).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE))
                ||Files.isSymbolicLink(marker)||!Files.isRegularFile(marker)||!Files.getOwner(marker).equals(Files.getOwner(base))||!Files.readString(marker).equals(MARKER))throw new IOException("ISOLATED_BASE_REJECTED");
    }
}
