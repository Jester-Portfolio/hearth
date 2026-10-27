package io.github.jesterportfolio.hearth.quest;

import java.util.*;

/** 목록을 끝까지 검증한 뒤에만 공개합니다. 초기 로딩 전용이며 게임 정책/자동 이관 엔진이 아닙니다. */
public final class QuestCatalog {
    private final Map<String,QuestContent> quests,npcs;
    private QuestCatalog(Map<String,QuestContent> quests,Map<String,QuestContent> npcs){this.quests=Map.copyOf(quests);this.npcs=Map.copyOf(npcs);}
    public Collection<QuestContent> all(){return quests.values();}
    public Set<String> questIds(){return quests.keySet();}
    public QuestContent quest(String id){return Objects.requireNonNull(quests.get(id),"Unknown quest");}
    public QuestContent npc(String id){return Objects.requireNonNull(npcs.get(id),"Unknown NPC");}
    public boolean linked(String npc,String quest){var c=npcs.get(npc);return c!=null && c.id().equals(quest);}
    public static QuestCatalog parse(List<?> npcRows,List<?> questRows){
        if(npcRows.isEmpty() || questRows.isEmpty())throw new IllegalArgumentException("Empty quest/NPC content");
        var rawNpcs=new LinkedHashMap<String,Map<?,?>>();
        for(Object row:npcRows){var npc=map(row);String id=id(npc,"id");if(rawNpcs.putIfAbsent(id,npc)!=null)throw new IllegalArgumentException("Duplicate NPC id");}
        var quests=new LinkedHashMap<String,QuestContent>();var npcs=new LinkedHashMap<String,QuestContent>();
        for(Object row:questRows){
            var quest=map(row);String id=id(quest,"id"),npcId=id(quest,"npc-id");var npc=rawNpcs.get(npcId);
            if(npc==null)throw new IllegalArgumentException("Unknown NPC link");
            var goal=map(quest.get("objective"));var text=map(quest.get("text"));var messages=new HashMap<String,String>();
            for(String key:List.of("offer","accept","active","report-ready","report","completed","failure"))messages.put(key,string(text,key));
            double x=number(npc,"x"),y=number(npc,"y"),z=number(npc,"z");
            double minX=number(goal,"min-x"),maxX=number(goal,"max-x"),minY=number(goal,"min-y"),maxY=number(goal,"max-y"),minZ=number(goal,"min-z"),maxZ=number(goal,"max-z");
            if(minX>maxX || minY>maxY || minZ>maxZ)throw new IllegalArgumentException("Invalid visit bounds");
            if(y< -64 || y>318 || minY< -64 || maxY>320 || Math.abs(x)>29999984 || Math.abs(z)>29999984
                || Math.abs(minX)>29999984 || Math.abs(maxX)>29999984 || Math.abs(minZ)>29999984 || Math.abs(maxZ)>29999984)
                throw new IllegalArgumentException("Position outside supported test world bounds");
            var content=new QuestContent(id,string(quest,"title"),npcId,string(npc,"name"),x,y,z,minX,maxX,minY,maxY,minZ,maxZ,quest.containsKey("completion-mode")?QuestContent.CompletionMode.parse(quest.get("completion-mode")):QuestContent.CompletionMode.REPORT,Map.copyOf(messages));
            if(quests.putIfAbsent(id,content)!=null)throw new IllegalArgumentException("Duplicate quest id");
            if(npcs.putIfAbsent(npcId,content)!=null)throw new IllegalArgumentException("This prototype supports one visit quest per NPC");
        }
        if(npcs.size()!=rawNpcs.size())throw new IllegalArgumentException("NPC without quest link");
        var positions=new HashSet<List<Double>>();
        for(var c:quests.values())if(!positions.add(List.of(Math.floor(c.x()),Math.floor(c.y()),Math.floor(c.z()))))throw new IllegalArgumentException("Overlapping NPC positions");
        return new QuestCatalog(quests,npcs);
    }
    private static Map<?,?> map(Object o){if(!(o instanceof Map<?,?> m))throw new IllegalArgumentException("Expected content mapping");return m;}
    private static String string(Map<?,?> m,String key){if(!(m.get(key) instanceof String s) || s.isBlank())throw new IllegalArgumentException("Missing content text: "+key);return s;}
    private static String id(Map<?,?> m,String key){String id=string(m,key);if(!id.matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid content id");return id;}
    private static double number(Map<?,?> m,String key){if(!(m.get(key) instanceof Number n) || !Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Invalid numeric position: "+key);return n.doubleValue();}
}
