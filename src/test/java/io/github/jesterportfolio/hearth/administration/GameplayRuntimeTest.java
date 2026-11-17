package io.github.jesterportfolio.hearth.administration;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GameplayRuntimeTest {
    @Test void onlyExplicitNormalStoryCanUseNormalGameplayEntry(){assertTrue(GameplayRuntime.story("story",false));for(String mode:new String[]{null,"","disabled","economy","unknown"})assertFalse(GameplayRuntime.story(mode,false));}
    @Test void economicModeCannotEnableStoryModulesOrVerificationMasquerade(){assertTrue(GameplayRuntime.economy("economy",false));for(String mode:new String[]{null,"","story","disabled","story-prepare"})assertFalse(GameplayRuntime.economy(mode,false));assertFalse(GameplayRuntime.economy("economy",true));assertFalse(GameplayRuntime.story("economy",false));}
    @Test void verificationCannotMasqueradeAsNormalStory(){assertFalse(GameplayRuntime.story("story",true));}
}
