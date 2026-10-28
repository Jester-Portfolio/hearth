package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.quest.*;
import io.github.jesterportfolio.hearth.exploration.*;
import java.util.*;

/** 서버 전용 공유 단계 정의. 개인 조건의 ID/미선택 분기 정의는 공개 DTO에 전달하지 않습니다. */
public record StoryContent(String first,Map<String,Stage> stages,List<RegionRule> regions,Set<String> guideNpcs){
    public record Choice(String id,String label,String next,ExplorationContent.Fact fact){}
    public record Stage(String id,String title,String goal,String next,Set<String> entry,Set<String> complete,boolean opens,List<Choice> choices,ExplorationContent.Fact fact){
        public Stage{entry=entry==null?null:Set.copyOf(entry);complete=complete==null?null:Set.copyOf(complete);choices=List.copyOf(choices);}
    }
    public enum Policy{OPEN,ENTRY,CONTENT}
    public record RegionRule(String region,Policy policy,String reached,QuestContent.Area bounds){}
    public StoryContent(String first,Map<String,Stage> stages,List<RegionRule> regions){this(first,stages,regions,Set.of());}
    public StoryContent{stages=Map.copyOf(stages);regions=List.copyOf(regions);guideNpcs=Set.copyOf(guideNpcs);}
    public Stage stage(String id){var s=stages.get(id);if(s==null)throw new IllegalStateException("UNREGISTERED_STAGE");return s;}
    public Set<String> conditions(Stage current,Stage target){if(target.entry()==null || current!=null&&current.complete()==null)throw new IllegalStateException("UNREGISTERED_CONDITIONS");var ids=new HashSet<>(target.entry());if(current!=null)ids.addAll(current.complete());return Set.copyOf(ids);}
    public Stage target(Stage current,String choice){if(current==null)return stage(first);if(current.choices().isEmpty())return current.next()==null?current:stage(current.next());return stage(current.choices().stream().filter(c->c.id().equals(choice)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown choice")).next());}
    public static StoryContent parse(Map<?,?> root,QuestCatalog quests,ExplorationContent exploration){
        if(!(root.get("format") instanceof Number n)||n.doubleValue()!=1)throw new IllegalArgumentException("Invalid story format");
        String first=id(root,"first");var stages=new LinkedHashMap<String,Stage>();
        for(var row:rows(root,"stages")){
            String key=id(row,"id");var choices=new ArrayList<Choice>();var seen=new HashSet<String>();
            if(row.containsKey("choices"))for(var c:rows(row,"choices")){String cid=id(c,"id");if(!seen.add(cid))throw new IllegalArgumentException("Duplicate choice");choices.add(new Choice(cid,text(c,"label"),id(c,"next"),fact(c,exploration)));}
            if(!choices.isEmpty()&&row.containsKey("next")||choices.size()==1)throw new IllegalArgumentException("Ambiguous stage transitions");
            var stage=new Stage(key,text(row,"title"),text(row,"next-goal"),row.containsKey("next")?id(row,"next"):null,condition(row,"entry",quests),condition(row,"complete",quests),Boolean.TRUE.equals(row.get("opens-exploration")),choices,fact(row,exploration));
            if(stages.putIfAbsent(key,stage)!=null)throw new IllegalArgumentException("Duplicate stage");
        }
        if(!stages.containsKey(first))throw new IllegalArgumentException("Missing first stage");
        var visited=new HashSet<String>();walk(first,stages,new HashSet<>(),visited);if(visited.size()!=stages.size())throw new IllegalArgumentException("Unreachable stage");
        for(var stage:stages.values())if(!stage.choices().isEmpty()){
            var expected=stages.get(stage.choices().getFirst().next()).entry();
            if(stage.choices().stream().anyMatch(c->!Objects.equals(expected,stages.get(c.next()).entry())))throw new IllegalArgumentException("Branch target entry conditions must match in this prototype");
        }
        var rules=new ArrayList<RegionRule>();var seen=new HashSet<String>();
        if(root.containsKey("regions"))for(var row:rows(root,"regions")){
            String region=id(row,"id");if(!seen.add(region)||!exploration.regions().containsKey(region))throw new IllegalArgumentException("Invalid region link");
            var p=Policy.valueOf(text(row,"policy"));String reached=p==Policy.OPEN?null:id(row,"requires-stage");if(reached!=null&&!stages.containsKey(reached))throw new IllegalArgumentException("Unknown region condition");
            var b=map(row.get("bounds"));var area=new QuestContent.Area(number(b,"min-x"),number(b,"max-x"),number(b,"min-y"),number(b,"max-y"),number(b,"min-z"),number(b,"max-z"));
            if(area.minX()>area.maxX()||area.minY()>area.maxY()||area.minZ()>area.maxZ()||area.minY()< -64||area.maxY()>320)throw new IllegalArgumentException("Invalid region bounds");rules.add(new RegionRule(region,p,reached,area));
        }
        var guides=new HashSet<String>();
        if(root.containsKey("guide-npcs")){if(!(root.get("guide-npcs") instanceof List<?> list))throw new IllegalArgumentException("Invalid story guides");
            var known=new HashSet<String>();quests.npcs().forEach(npc->known.add(npc.npcId()));
            for(Object value:list)if(!(value instanceof String guide)||!known.contains(guide)||!guides.add(guide))throw new IllegalArgumentException("Invalid story guide link");}
        return new StoryContent(first,stages,rules,guides);
    }
    static void walk(String id,Map<String,Stage> all,Set<String> path,Set<String> visited){if(path.contains(id))throw new IllegalArgumentException("Cyclic story");if(visited.contains(id))return;var s=all.get(id);if(s==null)throw new IllegalArgumentException("Missing next stage");path.add(id);if(s.next()!=null)walk(s.next(),all,path,visited);for(var c:s.choices())walk(c.next(),all,path,visited);path.remove(id);visited.add(id);}
    static Set<String> condition(Map<?,?> m,String key,QuestCatalog quests){if(!m.containsKey(key))return null;if(!(m.get(key) instanceof List<?> list)||list.isEmpty())throw new IllegalArgumentException("Unregistered conditions must be omitted");var ids=new HashSet<String>();for(Object v:list){if(!(v instanceof String s)||!quests.questIds().contains(s)||!ids.add(s))throw new IllegalArgumentException("Invalid personal condition");}return ids;}
    static ExplorationContent.Fact fact(Map<?,?> m,ExplorationContent e){if(!m.containsKey("journal"))return null;var f=e.facts().get(id(m,"journal"));if(f==null||!f.important())throw new IllegalArgumentException("Unknown important diary");return f;}
    static String text(Map<?,?> m,String key){if(!(m.get(key) instanceof String s)||s.isBlank()||s.length()>4096)throw new IllegalArgumentException("Missing story text");return s;}
    static String id(Map<?,?> m,String key){var s=text(m,key);if(!s.matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid story id");return s;}
    static double number(Map<?,?> m,String key){if(!(m.get(key) instanceof Number n)||!Double.isFinite(n.doubleValue())||Math.abs(n.doubleValue())>29999984)throw new IllegalArgumentException("Invalid region coordinate");return n.doubleValue();}
    static Map<?,?> map(Object x){if(!(x instanceof Map<?,?> m))throw new IllegalArgumentException("Expected story mapping");return m;}
    static List<Map<?,?>> rows(Map<?,?> m,String key){if(!(m.get(key) instanceof List<?> list))throw new IllegalArgumentException("Expected story list");return list.stream().map(StoryContent::map).toList();}
}
