package io.github.jesterportfolio.hearth.gathering;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class GatheringPathsTest {
    @TempDir Path root;
    @Test void sameOwnerAndDungeonUseDifferentStoryAndEconomyEvidenceAndPreserveFiles()throws Exception{var id=UUID.randomUUID();var story=GatheringPaths.directory(root,"gathering",id,"mine");Files.writeString(story.resolve("original"),"preserve");var economy=GatheringPaths.directory(root,"economy-gathering",id,"mine");assertNotEquals(story,economy);assertEquals("preserve",Files.readString(story.resolve("original")));assertEquals(story,GatheringPaths.directory(root,"gathering",id,"mine"));}
    @Test void symlinkNamespaceIsRefusedBeforeCreatingChildrenOutsidePlugin()throws Exception{var outside=Files.createDirectory(root.resolve("outside"));var plugin=Files.createDirectory(root.resolve("plugin"));Files.createSymbolicLink(plugin.resolve("economy-gathering"),outside);assertThrows(java.io.IOException.class,()->GatheringPaths.directory(plugin,"economy-gathering",UUID.randomUUID(),"mine"));try(var files=Files.list(outside)){assertEquals(0,files.count());}}
    @Test void symlinkOwnerDirectoryAndUnknownFilesArePreservedAndRefused()throws Exception{var id=UUID.randomUUID();var path=Files.createDirectory(root.resolve("economy-gathering"));var original=Files.createDirectory(root.resolve("original"));Files.createSymbolicLink(path.resolve(id+"_mine"),original);assertThrows(java.io.IOException.class,()->GatheringPaths.directory(root,"economy-gathering",id,"mine"));assertTrue(Files.isSymbolicLink(path.resolve(id+"_mine")));}
    @Test void unsupportedNamespacesAndTraversalNeverCreateDirectories(){for(String namespace:List.of("../gathering","unknown","economy-gathering/child"))assertThrows(IllegalArgumentException.class,()->GatheringPaths.directory(root,namespace,UUID.randomUUID(),"mine"));assertThrows(IllegalArgumentException.class,()->GatheringPaths.directory(root,"gathering",UUID.randomUUID(),"../mine"));}
}
