package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.quest.*;
import io.github.jesterportfolio.hearth.exploration.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StoryModuleContentTest {
    QuestCatalog catalog(String interaction){var text=new HashMap<String,Object>();for(String key:List.of("offer","accept","active","report-ready","report","completed","failure"))text.put(key,"공개 시험 문구");var goal=new HashMap<String,Object>(Map.of("min-x",1,"max-x",14,"min-y",64,"max-y",67,"min-z",1,"max-z",14));if(interaction!=null)goal.put("interaction",interaction);return QuestCatalog.parse(List.of(Map.of("id","guide","name","안내원","x",3,"y",64,"z",3)),List.of(Map.of("id","survey","title","개인 조사","npc-id","guide","objective",goal,"text",text)));}
    StoryModuleContent parse(Map<String,?> modules,String interaction){return StoryModuleContent.parse(Map.of("modules",modules),catalog(interaction),new ExplorationContent(Map.of(),Map.of(),Map.of(),Map.of(),Map.of()),new StoryContent("start",Map.of(),List.of()),QuestRules.empty());}
    @Test void omittedModulesDoNotInventFacilitiesOrCombat(){var c=parse(Map.of(),null);assertNull(c.crafting());assertNull(c.gathering());assertNull(c.combat());assertTrue(c.integrations().isEmpty());}
    @Test void unknownModulesAreNotSilentlyIgnored(){assertThrows(IllegalArgumentException.class,()->parse(Map.of("typo-module",Map.of()),null));assertThrows(IllegalArgumentException.class,()->parse(Map.of("world-effects",Map.of()),null));}
    @Test void objectivesCannotBypassMissingRuntimeBindings(){for(String interaction:List.of("craft:workbench:stick","dungeon:trial","escort:survey"))assertThrows(IllegalArgumentException.class,()->parse(Map.of(),interaction),interaction);}
    @Test void facilitiesDungeonPreparationAndRouteRequireTheirRealDependencies(){for(String key:List.of("gathering-facilities","dungeon","preparation","course-route"))assertThrows(IllegalArgumentException.class,()->parse(Map.of(key,Map.of()),null),key);}
    @Test void gatheringQuestRoutingCannotExistWithoutGathering(){assertThrows(IllegalArgumentException.class,()->parse(Map.of("gathering-quests",List.of("survey")),null));}
}
