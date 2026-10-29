package io.github.jesterportfolio.hearth.exploration;

import io.github.jesterportfolio.hearth.quest.*;
import java.util.*;

/** 서버 전용 정의입니다. 이 객체/좌표를 화면이나 AI DTO로 전달하지 않습니다. */
public record ExplorationContent(Map<String,Region> regions,Map<String,Place> places,Map<String,Fact> facts,Map<String,String> questFacts,Map<String,String> sharedGoals){
    public record Region(String id,String name,boolean published){}
    public enum Visibility{PUBLIC,HIDDEN,QUESTION}
    public enum Discovery{ENTRY,INTERACTION}
    public record Location(String kind,String text){}
    public record Place(String id,String marker,String name,String region,String category,Visibility visibility,Discovery discovery,
            QuestContent.Area bounds,Location location,Location unknownLocation,boolean hub,String interaction,String fact){}
    public record Fact(String id,String category,String title,String summary,String detail,int version,boolean important){}
    public ExplorationContent{regions=Map.copyOf(regions);places=Map.copyOf(places);facts=Map.copyOf(facts);questFacts=Map.copyOf(questFacts);sharedGoals=Map.copyOf(sharedGoals);}
    public static ExplorationContent parse(Map<?,?> root,QuestCatalog quests){
        if(!(root.get("format") instanceof Number n) || n.doubleValue()!=1)throw new IllegalArgumentException("Unsupported exploration format");
        var regions=new LinkedHashMap<String,Region>();var facts=new LinkedHashMap<String,Fact>();var places=new LinkedHashMap<String,Place>();var bindings=new HashMap<String,String>();
        for(var row:list(root,"regions")){String id=id(row,"id");if(regions.putIfAbsent(id,new Region(id,text(row,"name"),bool(row,"public")))!=null)throw new IllegalArgumentException("Duplicate region");}
        for(var row:list(root,"journal")){
            String id=id(row,"id"),category=text(row,"category");if(!Set.of("STORY","PERSON","EXPLORATION").contains(category))throw new IllegalArgumentException("Invalid diary category");
            if(!(row.get("version") instanceof Number value))throw new IllegalArgumentException("Invalid fact version");int version=value.intValue();if(version<1 || value.doubleValue()!=version)throw new IllegalArgumentException("Invalid fact version");
            var fact=new Fact(id,category,text(row,"title"),text(row,"summary"),text(row,"detail"),version,bool(row,"important"));
            if(facts.putIfAbsent(id,fact)!=null)throw new IllegalArgumentException("Duplicate fact");
            if(row.containsKey("quest-completed")){String quest=id(row,"quest-completed");if(!quests.questIds().contains(quest))throw new IllegalArgumentException("Unknown quest diary binding");if(bindings.putIfAbsent(quest,id)!=null)throw new IllegalArgumentException("Duplicate quest diary binding");}
        }
        for(var npc:quests.npcs())for(var topic:quests.topics(npc.npcId()))if(topic.journal()!=null){var fact=facts.get(topic.journal());if(fact==null||!fact.important())throw new IllegalArgumentException("Unknown or unimportant NPC topic journal");}
        var sharedGoals=new HashMap<String,String>();
        if(root.containsKey("shared-stages"))for(var row:list(root,"shared-stages")){
            String stage=id(row,"id");if(sharedGoals.putIfAbsent(stage,text(row,"next-goal"))!=null)throw new IllegalArgumentException("Duplicate shared stage");
        }
        var markers=new HashSet<String>();
        for(var row:list(root,"places")){
            String id=id(row,"id"),region=id(row,"region"),marker=id(row,"marker-key");if(!regions.containsKey(region) || !markers.add(marker))throw new IllegalArgumentException("Invalid region/marker");
            String fact=row.containsKey("journal")?id(row,"journal"):null;if(fact!=null && !facts.containsKey(fact))throw new IllegalArgumentException("Unknown diary fact");
            var mode=Discovery.valueOf(text(row,"discovery"));String interaction=row.containsKey("interaction")?text(row,"interaction"):null;
            if(mode==Discovery.INTERACTION && (interaction==null || !(interaction.startsWith("npc:") || interaction.matches("block:-?[0-9]+:-?[0-9]+:-?[0-9]+:[A-Z_]+"))))throw new IllegalArgumentException("Missing interaction reference");
            if(interaction!=null && interaction.startsWith("npc:") && quests.npcs().stream().noneMatch(c->c.npcId().equals(interaction.substring(4))))throw new IllegalArgumentException("Unknown NPC interaction");
            var place=new Place(id,marker,text(row,"name"),region,text(row,"category"),Visibility.valueOf(text(row,"visibility")),mode,area(map(row.get("bounds"))),location(row.get("location")),location(row.get("unknown-location")),bool(row,"hub"),interaction,fact);
            if(places.putIfAbsent(id,place)!=null)throw new IllegalArgumentException("Duplicate place");
        }
        if(markers.stream().anyMatch(places::containsKey))throw new IllegalArgumentException("Marker shadows place id");
        if(regions.isEmpty())throw new IllegalArgumentException("No regions");return new ExplorationContent(regions,places,facts,bindings,sharedGoals);
    }
    private static Location location(Object value){
        if(value==null)return new Location("NONE","위치 비공개");var m=map(value);String kind=text(m,"kind");
        return switch(kind){case "NONE"->new Location(kind,"위치 비공개");case "EXACT"->new Location(kind,"좌표: "+number(m,"x")+", "+number(m,"y")+", "+number(m,"z"));
            case "AREA"->{var a=area(m);yield new Location(kind,"탐색 구역: X "+a.minX()+"~"+a.maxX()+", Y "+a.minY()+"~"+a.maxY()+", Z "+a.minZ()+"~"+a.maxZ());}default->throw new IllegalArgumentException("Unknown location disclosure");};
    }
    static QuestContent.Area area(Map<?,?> m){var a=new QuestContent.Area(number(m,"min-x"),number(m,"max-x"),number(m,"min-y"),number(m,"max-y"),number(m,"min-z"),number(m,"max-z"));if(a.minX()>a.maxX()||a.minY()>a.maxY()||a.minZ()>a.maxZ()||a.minY()< -64||a.maxY()>320)throw new IllegalArgumentException("Invalid bounds");return a;}
    static double number(Map<?,?> m,String key){if(!(m.get(key) instanceof Number n)||!Double.isFinite(n.doubleValue())||Math.abs(n.doubleValue())>29999984)throw new IllegalArgumentException("Invalid coordinate");return n.doubleValue();}
    static String text(Map<?,?> m,String key){if(!(m.get(key) instanceof String s)||s.isBlank()||s.length()>4096)throw new IllegalArgumentException("Invalid content text");return s;}
    static String id(Map<?,?> m,String key){String s=text(m,key);if(!s.matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid id");return s;}
    static boolean bool(Map<?,?> m,String key){if(!(m.get(key) instanceof Boolean b))throw new IllegalArgumentException("Invalid boolean");return b;}
    static Map<?,?> map(Object o){if(!(o instanceof Map<?,?> m))throw new IllegalArgumentException("Expected mapping");return m;}
    static List<Map<?,?>> list(Map<?,?> m,String key){if(!(m.get(key) instanceof List<?> rows))throw new IllegalArgumentException("Expected list: "+key);return rows.stream().map(ExplorationContent::map).toList();}
}
