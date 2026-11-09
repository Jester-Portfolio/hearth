package io.github.jesterportfolio.hearth.verification;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ManualClientEnvironmentTest {
    @Test void naturalSpawnsCannotDisruptUiAcceptance(){for(var reason:new String[]{"NATURAL","CHUNK_GEN","PATROL","REINFORCEMENTS","RAID","DEFAULT"})assertTrue(ManualClientEnvironment.blocksSpawn(reason),reason);}
    @Test void hearthCustomEntitiesAndOtherExplicitSpawnsArePreserved(){for(var reason:new String[]{"CUSTOM","COMMAND","SPAWNER_EGG","BUILD_IRONGOLEM"})assertFalse(ManualClientEnvironment.blocksSpawn(reason));assertFalse(ManualClientEnvironment.blocksSpawn(null));}
}
