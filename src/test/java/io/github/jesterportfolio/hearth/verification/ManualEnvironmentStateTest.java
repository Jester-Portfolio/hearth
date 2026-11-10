package io.github.jesterportfolio.hearth.verification;

import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class ManualEnvironmentStateTest {
    @TempDir Path root;final UUID run=UUID.randomUUID(),world=UUID.randomUUID();
    @Test void originalDifficultySurvivesAnotherInstanceAndUiSavedPeaceful()throws Exception{var file=root.resolve("state.properties");assertEquals("HARD",ManualEnvironmentState.load(file,run,world,"HARD"));var original=Files.readAllBytes(file);assertEquals("HARD",ManualEnvironmentState.load(file,run,world,"PEACEFUL"));assertArrayEquals(original,Files.readAllBytes(file));}
    @Test void foreignWorldOrRunCannotOverwriteOriginal()throws Exception{var file=root.resolve("state.properties");ManualEnvironmentState.load(file,run,world,"EASY");var original=Files.readAllBytes(file);assertThrows(java.io.IOException.class,()->ManualEnvironmentState.load(file,UUID.randomUUID(),world,"HARD"));assertThrows(java.io.IOException.class,()->ManualEnvironmentState.load(file,run,UUID.randomUUID(),"HARD"));assertArrayEquals(original,Files.readAllBytes(file));}
    @Test void malformedOrLinkedStateIsPreservedInsteadOfDefaulting()throws Exception{var file=root.resolve("bad.properties");Files.writeString(file,"difficulty=HARD\n");assertThrows(java.io.IOException.class,()->ManualEnvironmentState.load(file,run,world,"EASY"));assertEquals("difficulty=HARD\n",Files.readString(file));var link=root.resolve("link");Files.createSymbolicLink(link,file);assertThrows(java.io.IOException.class,()->ManualEnvironmentState.load(link,run,world,"EASY"));}
}
