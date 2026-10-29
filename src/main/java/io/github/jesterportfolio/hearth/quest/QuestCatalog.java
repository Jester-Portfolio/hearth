package io.github.jesterportfolio.hearth.quest;

import java.util.*;

/** 목록을 끝까지 검증한 뒤에만 공개합니다. 초기 로딩 전용이며 게임 정책/자동 이관 엔진이 아닙니다. */
public final class QuestCatalog {
    private final Map<String,QuestContent> quests;
    private final Map<String,List<QuestContent>> npcs;
    private final Map<String,List<String>> pages;
    public record Topic(String id,String label,String text,String requiresCompleted,String journal){
        public boolean available(Map<String,io.github.jesterportfolio.hearth.persistence.QuestStore.Saved> rows,java.util.function.Predicate<String> allowed){return requiresCompleted==null||allowed.test(requiresCompleted)&&rows.containsKey(requiresCompleted)&&rows.get(requiresCompleted).phase()==io.github.jesterportfolio.hearth.persistence.QuestStore.Phase.COMPLETED;}
    }
    private final Map<String,List<Topic>> topics;
    public List<Topic> topics(String npc){return Objects.requireNonNull(topics.get(npc),"Unknown NPC");}
    public Topic topic(String npc,String id){return topics(npc).stream().filter(t->t.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown topic"));}
    public List<Topic> availableTopics(String npc,Map<String,io.github.jesterportfolio.hearth.persistence.QuestStore.Saved> rows,java.util.function.Predicate<String> allowed){return topics(npc).stream().filter(t->t.available(rows,allowed)).toList();}
    public List<String> pages(String npc){return Objects.requireNonNull(pages.get(npc),"Unknown NPC");}
    private QuestCatalog(Map<String,QuestContent> quests,Map<String,List<QuestContent>> npcs,Map<String,List<String>> pages,Map<String,List<Topic>> topics){
        this.topics=Map.copyOf(topics);this.pages=Map.copyOf(pages);this.quests=Collections.unmodifiableMap(new LinkedHashMap<>(quests));
        var groups=new LinkedHashMap<String,List<QuestContent>>();npcs.forEach((id,rows)->groups.put(id,List.copyOf(rows)));this.npcs=Collections.unmodifiableMap(groups);
    }
    public Collection<QuestContent> all(){return quests.values();}
    public Set<String> questIds(){return quests.keySet();}
    public QuestContent quest(String id){return Objects.requireNonNull(quests.get(id),"Unknown quest");}
    public List<QuestContent> forNpc(String id){return Objects.requireNonNull(npcs.get(id),"Unknown NPC");}
    /** NPC 위치·표시는 동일 정의에서 온 대표값만 사용합니다. */
    public Collection<QuestContent> npcs(){return npcs.values().stream().map(List::getFirst).toList();}
    public boolean linked(String npc,String quest){var c=quests.get(quest);return c!=null && c.npcId().equals(npc);}
    public static QuestCatalog parse(List<?> npcRows,List<?> questRows){
        if(npcRows.isEmpty() || questRows.isEmpty())throw new IllegalArgumentException("Empty quest/NPC content");
        var rawNpcs=new LinkedHashMap<String,Map<?,?>>();
        for(Object row:npcRows){var npc=map(row);String id=id(npc,"id");if(rawNpcs.putIfAbsent(id,npc)!=null)throw new IllegalArgumentException("Duplicate NPC id");}
        var quests=new LinkedHashMap<String,QuestContent>();var npcs=new LinkedHashMap<String,List<QuestContent>>();
        for(Object row:questRows){
            var quest=map(row);String id=id(quest,"id"),npcId=id(quest,"npc-id");var npc=rawNpcs.get(npcId);
            if(npc==null)throw new IllegalArgumentException("Unknown NPC link");
            var goals=new ArrayList<QuestContent.Goal>();Map<?,?> goal;
            if(quest.containsKey("objectives")){
                if(quest.containsKey("objective") || !(quest.get("objectives") instanceof List<?> list) || list.isEmpty())throw new IllegalArgumentException("Invalid objectives");
                for(Object raw:list){var g=map(raw);var bounds=area(g);goals.add(new QuestContent.Goal(string(g,"description"),bounds,g.containsKey("location")?QuestLocation.parse(g.get("location"),bounds):null,interaction(g,bounds,rawNpcs)));}
                goal=map(((List<?>)quest.get("objectives")).getFirst());
            }else goal=map(quest.get("objective"));
            var text=map(quest.get("text"));var messages=new HashMap<String,String>();
            for(String key:List.of("offer","accept","active","report-ready","report","completed","failure"))messages.put(key,string(text,key));
            double x=number(npc,"x"),y=number(npc,"y"),z=number(npc,"z");
            double minX=number(goal,"min-x"),maxX=number(goal,"max-x"),minY=number(goal,"min-y"),maxY=number(goal,"max-y"),minZ=number(goal,"min-z"),maxZ=number(goal,"max-z");
            if(minX>maxX || minY>maxY || minZ>maxZ)throw new IllegalArgumentException("Invalid visit bounds");
            if(y< -64 || y>318 || minY< -64 || maxY>320 || Math.abs(x)>29999984 || Math.abs(z)>29999984
                || Math.abs(minX)>29999984 || Math.abs(maxX)>29999984 || Math.abs(minZ)>29999984 || Math.abs(maxZ)>29999984)
                throw new IllegalArgumentException("Position outside supported test world bounds");
            var start=quest.containsKey("start-mode")?QuestContent.StartMode.parse(quest.get("start-mode")):QuestContent.StartMode.NPC;
            QuestContent.Area startArea=null;
            if(start==QuestContent.StartMode.AUTOMATIC){var area=map(quest.get("start-area"));startArea=area(area);}
            else if(quest.containsKey("start-area"))throw new IllegalArgumentException("NPC start must not define start-area");
            if(goals.isEmpty())goals.add(new QuestContent.Goal(messages.get("active"),area(goal),goal.containsKey("location")?QuestLocation.parse(goal.get("location"),area(goal)):null,interaction(goal,area(goal),rawNpcs)));
            var content=new QuestContent(id,string(quest,"title"),npcId,string(npc,"name"),x,y,z,minX,maxX,minY,maxY,minZ,maxZ,quest.containsKey("completion-mode")?QuestContent.CompletionMode.parse(quest.get("completion-mode")):QuestContent.CompletionMode.REPORT,start,startArea,quest.containsKey("requires-completed")?id(quest,"requires-completed"):null,Map.copyOf(messages),goals);
            if(quests.putIfAbsent(id,content)!=null)throw new IllegalArgumentException("Duplicate quest id");
            npcs.computeIfAbsent(npcId,key->new ArrayList<>()).add(content);
        }
        for(var c:quests.values())if(c.requiresCompleted()!=null && !quests.containsKey(c.requiresCompleted()))throw new IllegalArgumentException("Unknown prerequisite quest");
        var done=new HashSet<String>();
        for(String first:quests.keySet()){
            var path=new HashSet<String>();String next=first;
            while(next!=null && !done.contains(next)){if(!path.add(next))throw new IllegalArgumentException("Cyclic prerequisite quest");next=quests.get(next).requiresCompleted();}
            done.addAll(path);
        }
        if(npcs.size()!=rawNpcs.size())throw new IllegalArgumentException("NPC without quest link");
        var positions=new HashSet<List<Double>>();
        for(var rows:npcs.values()){var c=rows.getFirst();if(!positions.add(List.of(Math.floor(c.x()),Math.floor(c.y()),Math.floor(c.z()))))throw new IllegalArgumentException("Overlapping NPC positions");}
        var pages=new HashMap<String,List<String>>();
        for(var entry:rawNpcs.entrySet()){var texts=new ArrayList<String>();Object raw=entry.getValue().get("dialogue-pages");
            if(raw!=null){if(!(raw instanceof List<?> list)||list.isEmpty()||list.size()>16)throw new IllegalArgumentException("Invalid dialogue pages");for(Object item:list){if(!(item instanceof String text)||text.isBlank()||text.length()>600)throw new IllegalArgumentException("Invalid dialogue text");texts.add(text);}}
            pages.put(entry.getKey(),List.copyOf(texts));}
        var topics=new HashMap<String,List<Topic>>();
        for(var entry:rawNpcs.entrySet()){
            var list=new ArrayList<Topic>();var seen=new HashSet<String>();Object raw=entry.getValue().get("topics");
            if(raw!=null){if(!(raw instanceof List<?> rows)||rows.isEmpty()||rows.size()>16)throw new IllegalArgumentException("Invalid NPC topics");
                for(Object value:rows){var row=map(value);String key=id(row,"id"),label=string(row,"label"),text=string(row,"text"),requires=row.containsKey("requires-completed")?id(row,"requires-completed"):null,journal=row.containsKey("journal")?id(row,"journal"):null;
                    if(!seen.add(key)||label.length()>100||text.length()>1200||requires!=null&&!quests.containsKey(requires))throw new IllegalArgumentException("Invalid NPC topic");
                    list.add(new Topic(key,label,text,requires,journal));}}
            topics.put(entry.getKey(),List.copyOf(list));}
        return new QuestCatalog(quests,npcs,pages,topics);
    }
    private static String interaction(Map<?,?> row,QuestContent.Area bounds,Map<String,Map<?,?>> npcs){
        if(!row.containsKey("interaction"))return null;String value=string(row,"interaction");String[] parts=value.split(":",-1);
        if(parts.length==2&&parts[0].equals("npc")){var npc=npcs.get(parts[1]);if(npc==null||!bounds.contains(number(npc,"x"),number(npc,"y"),number(npc,"z")))throw new IllegalArgumentException("Invalid objective NPC");return value;}
        if(parts.length!=5||!parts[0].equals("block")||!parts[4].matches("[A-Z][A-Z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid block objective");
        try{int x=Integer.parseInt(parts[1]),y=Integer.parseInt(parts[2]),z=Integer.parseInt(parts[3]);if(!bounds.contains(x,y,z)||!value.equals("block:"+x+":"+y+":"+z+":"+parts[4]))throw new IllegalArgumentException("Block objective outside bounds");}catch(NumberFormatException error){throw new IllegalArgumentException("Invalid block objective coordinate");}return value;
    }
    private static QuestContent.Area area(Map<?,?> m){
        var a=new QuestContent.Area(number(m,"min-x"),number(m,"max-x"),number(m,"min-y"),number(m,"max-y"),number(m,"min-z"),number(m,"max-z"));
        if(a.minX()>a.maxX() || a.minY()>a.maxY() || a.minZ()>a.maxZ() || a.minY()< -64 || a.maxY()>320
            || Math.abs(a.minX())>29999984 || Math.abs(a.maxX())>29999984 || Math.abs(a.minZ())>29999984 || Math.abs(a.maxZ())>29999984)throw new IllegalArgumentException("Invalid start-area bounds");
        return a;
    }
    private static Map<?,?> map(Object o){if(!(o instanceof Map<?,?> m))throw new IllegalArgumentException("Expected content mapping");return m;}
    private static String string(Map<?,?> m,String key){if(!(m.get(key) instanceof String s) || s.isBlank())throw new IllegalArgumentException("Missing content text: "+key);return s;}
    private static String id(Map<?,?> m,String key){String id=string(m,key);if(!id.matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid content id");return id;}
    private static double number(Map<?,?> m,String key){if(!(m.get(key) instanceof Number n) || !Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Invalid numeric position: "+key);return n.doubleValue();}
}
