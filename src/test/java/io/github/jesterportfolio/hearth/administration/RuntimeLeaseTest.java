package io.github.jesterportfolio.hearth.administration;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class RuntimeLeaseTest {
    @TempDir Path root;
    @Test void uncleanRestartCannotAcquireOrOverwriteExistingMarker()throws Exception{var file=root.resolve("active");var first=RuntimeLease.acquire(file,"active\n").orElseThrow();byte[] before=Files.readAllBytes(file);assertTrue(RuntimeLease.acquire(file,"new\n").isEmpty());assertArrayEquals(before,Files.readAllBytes(file));assertEquals(0,((Number)Files.getAttribute(file,"unix:mode")).intValue()&0077);first.clean();assertFalse(Files.exists(file));assertTrue(RuntimeLease.acquire(file,"next\n").isPresent());}
    @Test void changedMarkerRemainsForManualReview()throws Exception{var file=root.resolve("active");var lease=RuntimeLease.acquire(file,"active\n").orElseThrow();Files.writeString(file,"changed evidence");assertThrows(java.io.IOException.class,lease::clean);assertEquals("changed evidence",Files.readString(file));assertTrue(RuntimeLease.acquire(file,"new\n").isEmpty());}
    @Test void replacedFileEvenWithSameContentCannotBeRemovedByOldLease()throws Exception{var file=root.resolve("active");var lease=RuntimeLease.acquire(file,"active\n").orElseThrow();var bytes=Files.readAllBytes(file);Files.move(file,root.resolve("original"));Files.write(file,bytes);assertThrows(java.io.IOException.class,lease::clean);assertArrayEquals(bytes,Files.readAllBytes(file));assertArrayEquals(bytes,Files.readAllBytes(root.resolve("original")));}
    @Test void existingSymlinkAndNoncanonicalParentDoNotFollowOrOverwriteOriginal()throws Exception{var original=root.resolve("original");Files.writeString(original,"keep");var link=root.resolve("active");Files.createSymbolicLink(link,original);assertTrue(RuntimeLease.acquire(link,"new\n").isEmpty());assertEquals("keep",Files.readString(original));var parent=root.resolve("alias");Files.createSymbolicLink(parent,root);assertThrows(java.io.IOException.class,()->RuntimeLease.acquire(parent.resolve("new"),"active\n"));assertFalse(Files.exists(root.resolve("new")));}
    @Test void successfulCleanupIsIdempotentAndCannotDeleteNextInstanceMarker()throws Exception{var file=root.resolve("active");var lease=RuntimeLease.acquire(file,"active\n").orElseThrow();lease.clean();RuntimeLease.acquire(file,"next\n").orElseThrow();byte[] next=Files.readAllBytes(file);lease.clean();assertArrayEquals(next,Files.readAllBytes(file));}
    @Test void retirementCannotReplaceExistingCompletionEvidence()throws Exception{var file=root.resolve("pending");var lease=RuntimeLease.acquire(file,"active\n").orElseThrow();var done=root.resolve("done");Files.writeString(done,"original completion");assertThrows(java.io.IOException.class,()->lease.retire(done));assertTrue(Files.exists(file));assertEquals("original completion",Files.readString(done));Files.delete(done);lease.retire(done);assertFalse(Files.exists(file));assertTrue(Files.readString(done).startsWith("active"));}
}
