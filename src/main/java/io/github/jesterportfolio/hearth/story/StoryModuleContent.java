package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.combat.*;
import io.github.jesterportfolio.hearth.crafting.*;
import io.github.jesterportfolio.hearth.gathering.*;
import io.github.jesterportfolio.hearth.escort.*;
import io.github.jesterportfolio.hearth.travel.*;
import io.github.jesterportfolio.hearth.quest.*;
import io.github.jesterportfolio.hearth.exploration.*;
import java.util.*;

/** 하나의 검토된 콘텐츠 묶음에서 연결할 게임 모듈과 상호 참조를 먼저 검증합니다. */
public record StoryModuleContent(CraftContent crafting,GatheringContent gathering,GatheringFacilities facilities,TravelContent travel,EscortContent escort,
        CombatContent combat,FieldContent field,DungeonPlay.Definition dungeon,Playthrough playthrough,PreparationGuide preparation,CourseRoute route,Set<String> gatheringQuests){
    public StoryModuleContent{gatheringQuests=Set.copyOf(gatheringQuests);}
    public Set<String> integrations(){var result=new HashSet<String>();if(crafting!=null)result.add("craft");if(dungeon!=null)result.add("dungeon");if(escort!=null)result.add("escort");return Set.copyOf(result);}
    public static StoryModuleContent parse(Map<String,?> root,QuestCatalog quests,ExplorationContent exploration,StoryContent shared,QuestRules rules){
        var m=root.containsKey("modules")?StoryContentBundle.section(root,"modules"):Map.<String,Object>of();
        if(!Set.of("crafting","gathering","gathering-facilities","travel","escort","combat","field","dungeon","playthrough","preparation","course-route","gathering-quests").containsAll(m.keySet()))throw new IllegalArgumentException("STORY_UNKNOWN_MODULE");
        var craft=m.containsKey("crafting")?CraftContent.parse(StoryContentBundle.section(m,"crafting")):null;
        var gather=m.containsKey("gathering")?GatheringContent.parse(StoryContentBundle.section(m,"gathering")):null;
        if(m.containsKey("gathering-facilities")&&gather==null)throw new IllegalArgumentException("STORY_GATHERING_DEPENDENCY");
        var facilities=m.containsKey("gathering-facilities")?GatheringFacilities.parse(StoryContentBundle.section(m,"gathering-facilities"),gather):null;
        var travel=m.containsKey("travel")?TravelContent.parse(StoryContentBundle.section(m,"travel"),exploration):null;
        var escort=m.containsKey("escort")?EscortContent.parse(StoryContentBundle.section(m,"escort"),quests,rules):null;
        var combat=m.containsKey("combat")?CombatContent.parse(StoryContentBundle.section(m,"combat")):null;
        var field=m.containsKey("field")?FieldContent.parse(StoryContentBundle.section(m,"field")):null;
        if(field!=null&&(combat==null||combat.exit()==null))throw new IllegalArgumentException("STORY_FIELD_COMBAT_DEPENDENCY");
        if(m.containsKey("dungeon")&&combat==null)throw new IllegalArgumentException("STORY_DUNGEON_COMBAT_DEPENDENCY");
        var dungeon=m.containsKey("dungeon")?DungeonPlay.Definition.parse(StoryContentBundle.section(m,"dungeon")):null;if(dungeon!=null)dungeon.validate(quests.quest(dungeon.quest()),combat);
        for(var quest:quests.all())for(var goal:quest.goals())if(goal.interaction()!=null){String key=goal.interaction().split(":",2)[0];if(key.equals("craft")&&craft==null||key.equals("dungeon")&&dungeon==null||key.equals("escort")&&escort==null)throw new IllegalArgumentException("STORY_GOAL_MODULE_MISSING");}
        var playthrough=m.containsKey("playthrough")?Playthrough.parse(StoryContentBundle.section(m,"playthrough"),quests,shared,exploration):null;
        if(m.containsKey("preparation")&&(craft==null||gather==null||dungeon==null))throw new IllegalArgumentException("STORY_PREPARATION_DEPENDENCY");
        var preparation=m.containsKey("preparation")?PreparationGuide.parse(StoryContentBundle.section(m,"preparation"),quests,craft,exploration,gather,dungeon):null;
        if(m.containsKey("course-route")&&(playthrough==null||preparation==null))throw new IllegalArgumentException("STORY_COURSE_ROUTE_DEPENDENCY");
        var route=m.containsKey("course-route")?CourseRoute.parse(StoryContentBundle.section(m,"course-route"),playthrough,quests,preparation,exploration):null;
        var gatheringQuests=new HashSet<String>();if(m.containsKey("gathering-quests")){if(!(m.get("gathering-quests") instanceof List<?> list)||gather==null)throw new IllegalArgumentException("STORY_GATHERING_QUESTS");for(Object value:list){if(!(value instanceof String id)||!quests.questIds().contains(id)||!gatheringQuests.add(id))throw new IllegalArgumentException("STORY_GATHERING_QUESTS");}}
        if(craft!=null&&facilities!=null)for(var facility:craft.facilities().values())for(var site:facilities.sites())if(facility.x()==site.x()&&facility.y()==site.y()&&facility.z()==site.z())throw new IllegalArgumentException("STORY_FACILITY_CONFLICT");
        return new StoryModuleContent(craft,gather,facilities,travel,escort,combat,field,dungeon,playthrough,preparation,route,gatheringQuests);
    }
}
