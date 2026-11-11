package io.github.jesterportfolio.hearth.verification;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class IsolatedPathsTest {
    @TempDir Path temp;
    @Test void approvedRootOnlyAndExactPrefixAreRequired()throws Exception{
        for(String prefix:java.util.List.of("hearth-s4-1.","hearth-s4-2.","hearth-s4-4.","hearth-s4-5.","hearth-s4-6.","hearth-s4-11.")){
            Path root=Files.createTempDirectory(IsolatedPaths.BASE,prefix);
            try{assertEquals(root,IsolatedPaths.require(root,prefix));assertThrows(IOException.class,()->IsolatedPaths.require(root,"hearth-forbidden."));assertThrows(IOException.class,()->IsolatedPaths.require(temp,prefix));assertThrows(IOException.class,()->IsolatedPaths.require(Path.of("/tmp"),prefix));}
            finally{Files.delete(root);}
        }
    }
    @Test void symlinkAliasesAreRejectedAndTargetRemainsUntouched()throws Exception{
        Path root=Files.createTempDirectory(IsolatedPaths.BASE,"hearth-s4-11."),link=IsolatedPaths.BASE.resolve("hearth-s4-11.alias_"+root.getFileName().toString().substring("hearth-s4-11.".length()));
        try{Files.createSymbolicLink(link,root);assertThrows(IOException.class,()->IsolatedPaths.require(link,"hearth-s4-11."));assertTrue(Files.isDirectory(root));}
        finally{Files.deleteIfExists(link);Files.delete(root);}
    }
    @Test void missingOrSymlinkedMarkerAndLooseBasePermissionsAreRejected()throws Exception{
        Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rwx------"));
        assertThrows(IOException.class,()->IsolatedPaths.checkBase(temp));Path marker=temp.resolve(".hearth-test-base");Files.writeString(marker,"Hearth isolated test base v1\n");IsolatedPaths.checkBase(temp);
        Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rwxr-xr-x"));assertThrows(IOException.class,()->IsolatedPaths.checkBase(temp));Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rwx------"));
        Files.delete(marker);Files.createSymbolicLink(marker,IsolatedPaths.BASE.resolve(".hearth-test-base"));assertThrows(IOException.class,()->IsolatedPaths.checkBase(temp));
    }
}
