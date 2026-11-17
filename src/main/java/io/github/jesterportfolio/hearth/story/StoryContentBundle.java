package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.quest.*;
import io.github.jesterportfolio.hearth.exploration.*;
import io.github.jesterportfolio.hearth.features.SceneContent;
import java.util.*;

/** 배포자가 검토한 공개 콘텐츠 묶음. 파일 생성/기존 콘텐츠 갱신은 하지 않습니다. */
public record StoryContentBundle(QuestCatalog quests,ExplorationContent exploration,StoryContent shared,QuestRules rules,SceneContent scenes,StoryModuleContent modules){
    public static StoryContentBundle parse(Map<String,?> root){
        var q=section(root,"quests");if(!(q.get("format") instanceof Number n)||n.doubleValue()!=2||!(q.get("npcs") instanceof List<?> actors)||!(q.get("quests") instanceof List<?> rows))throw new IllegalArgumentException("STORY_CONTENT_QUEST_FORMAT");
        var catalog=QuestCatalog.parse(actors,rows);var exploration=ExplorationContent.parse(section(root,"exploration"),catalog);var shared=StoryContent.parse(section(root,"shared"),catalog,exploration);
        var goals=new HashMap<>(exploration.sharedGoals());shared.stages().values().forEach(stage->{if(goals.putIfAbsent(stage.id(),stage.goal())!=null&&!goals.get(stage.id()).equals(stage.goal()))throw new IllegalArgumentException("STORY_CONTENT_GOAL_CONFLICT");});
        exploration=new ExplorationContent(exploration.regions(),exploration.places(),exploration.facts(),exploration.questFacts(),goals);
        var rules=QuestRules.parse(section(root,"rules"),catalog,shared);
        return new StoryContentBundle(catalog,exploration,shared,rules,SceneContent.parse(section(root,"scenes"),catalog),StoryModuleContent.parse(root,catalog,exploration,shared,rules));
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> section(Map<String,?> root,String key){if(!(root.get(key) instanceof Map<?,?> map)||map.keySet().stream().anyMatch(k->!(k instanceof String)))throw new IllegalArgumentException("STORY_CONTENT_SECTION");return (Map<String,Object>)map;}
}
