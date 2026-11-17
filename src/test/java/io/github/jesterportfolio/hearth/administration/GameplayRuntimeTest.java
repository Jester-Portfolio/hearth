package io.github.jesterportfolio.hearth.administration;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GameplayRuntimeTest {
    @Test void onlyExplicitNormalStoryCanUseNormalGameplayEntry(){assertTrue(GameplayRuntime.story("story",false));for(String mode:new String[]{null,"","disabled","economy","unknown"})assertFalse(GameplayRuntime.story(mode,false));}
    @Test void verificationCannotMasqueradeAsNormalStory(){assertFalse(GameplayRuntime.story("story",true));}
}
