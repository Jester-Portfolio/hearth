package io.github.jesterportfolio.hearth.economy;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class EconomyGatheringContentTest {
    @Test void omittedFileDoesNotInventOrCopyContent(){assertTrue(EconomyGatheringContent.file(new Properties()).isEmpty());}
    @Test void fileAndHashMustBeExplicitTogether(){var p=new Properties();p.setProperty("gathering-file","mine.yml");assertThrows(IllegalArgumentException.class,()->EconomyGatheringContent.file(p));p.setProperty("gathering-sha256","a".repeat(64));assertEquals("mine.yml",EconomyGatheringContent.file(p).orElseThrow().name());}
    @Test void traversalUnknownHashAndNestedPathsAreRejected(){for(var name:List.of("../mine.yml","/mine.yml","folder/mine.yml"))assertThrows(IllegalArgumentException.class,()->new EconomyGatheringContent.File(name,"a".repeat(64)));assertThrows(IllegalArgumentException.class,()->new EconomyGatheringContent.File("mine.yml","unknown"));}
    @Test void missingDefinitionOrFacilitiesCannotSilentlyDisableProtection(){assertThrows(IllegalArgumentException.class,()->EconomyGatheringContent.parse(Map.of("format",1)));assertThrows(IllegalArgumentException.class,()->EconomyGatheringContent.parse(Map.of("format",2,"gathering",Map.of(),"facilities",Map.of())));}
}
