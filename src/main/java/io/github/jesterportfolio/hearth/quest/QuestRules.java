package io.github.jesterportfolio.hearth.quest;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import io.github.jesterportfolio.hearth.story.StoryContent;

/** 초기 로딩한 개인 실패·보상·장 정의. 실제 서사/밸런스나 스킵 보상 정책을 만들지 않습니다. */
public record QuestRules(Map<String,Rule> rules,Map<String,Chapter> chapters){
    public enum Restart{KEEP,CHECKPOINT,BEGINNING}
    public record Item(String material,int amount){}
    public record Reward(List<Item> items,String fingerprint){public Reward{items=List.copyOf(items);}}
    public record Rule(Restart restart,Set<String> failures,List<Integer> checkpoints,long timeoutMillis,Reward reward){public Rule{failures=Set.copyOf(failures);checkpoints=List.copyOf(checkpoints);}}
    public record Chapter(String id,String title,String sharedStage,List<String> required,String fingerprint){public Chapter{required=List.copyOf(required);}}
    public static final Rule DEFAULT=new Rule(Restart.KEEP,Set.of(),List.of(),0,null);
    public QuestRules{rules=Map.copyOf(rules);chapters=Map.copyOf(chapters);}
    public static QuestRules empty(){return new QuestRules(Map.of(),Map.of());}
    public Rule rule(String id){return rules.getOrDefault(id,DEFAULT);}
    public static QuestRules parse(Map<?,?> root,QuestCatalog catalog,StoryContent story){
        if(!(root.get("format") instanceof Number n)||n.doubleValue()!=1)throw new IllegalArgumentException("Invalid quest rules format");
        var rules=new LinkedHashMap<String,Rule>();
        for(var r:rows(root,"quests")){String id=id(r,"id");if(!catalog.questIds().contains(id))throw new IllegalArgumentException("Unknown quest rules");
            var mode=Restart.valueOf(text(r,"restart"));var failures=new HashSet<String>();for(Object f:list(r,"failures"))if(!(f instanceof String s)||!Set.of("DEATH","TIMEOUT","OBJECTIVE").contains(s)||!failures.add(s))throw new IllegalArgumentException("Invalid failure condition");
            long timeout=integer(r.get("timeout-seconds"),0,86400)*1000L;if((timeout>0)!=failures.contains("TIMEOUT"))throw new IllegalArgumentException("Invalid quest timeout");
            var checkpoints=new ArrayList<Integer>();for(Object value:list(r,"checkpoints")){int i=integer(value,0,catalog.quest(id).goals().size()-1);if(!checkpoints.isEmpty()&&i<=checkpoints.getLast())throw new IllegalArgumentException("Unordered checkpoints");checkpoints.add(i);}
            if(mode==Restart.CHECKPOINT&&checkpoints.isEmpty()||mode!=Restart.CHECKPOINT&&!checkpoints.isEmpty())throw new IllegalArgumentException("Invalid checkpoint policy");
            Reward reward=null;if(r.containsKey("reward")){var items=new ArrayList<Item>();for(var item:rows(map(r.get("reward")),"items")){String material=text(item,"material");if(!material.matches("[A-Z][A-Z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid reward material");items.add(new Item(material,integer(item.get("amount"),1,2304)));}if(items.isEmpty()||items.size()>16)throw new IllegalArgumentException("Invalid reward items");reward=new Reward(items,hash(items.toString()));}
            if(rules.putIfAbsent(id,new Rule(mode,failures,checkpoints,timeout,reward))!=null)throw new IllegalArgumentException("Duplicate quest rules");
        }
        var chapters=new LinkedHashMap<String,Chapter>();var assigned=new HashSet<String>();
        for(var r:rows(root,"chapters")){String id=id(r,"id"),stage=id(r,"shared-stage");if(story==null||!story.stages().containsKey(stage))throw new IllegalArgumentException("Unknown chapter shared stage");var required=new ArrayList<String>();for(Object v:list(r,"required-quests")){if(!(v instanceof String q)||!catalog.questIds().contains(q)||!assigned.add(q))throw new IllegalArgumentException("Invalid chapter required quest");required.add(q);}if(required.isEmpty())throw new IllegalArgumentException("Empty chapter");var chapter=new Chapter(id,text(r,"title"),stage,required,hash(stage+required));if(chapters.putIfAbsent(id,chapter)!=null)throw new IllegalArgumentException("Duplicate chapter");}
        return new QuestRules(rules,chapters);
    }
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static int integer(Object value,int min,int max){if(!(value instanceof Number n)||n.doubleValue()!=n.intValue()||n.intValue()<min||n.intValue()>max)throw new IllegalArgumentException("Invalid quest rule number");return n.intValue();}
    private static Map<?,?> map(Object v){if(!(v instanceof Map<?,?> m))throw new IllegalArgumentException("Expected rules mapping");return m;}
    private static List<?> list(Map<?,?> r,String k){if(!(r.get(k) instanceof List<?> l))throw new IllegalArgumentException("Expected rules list");return l;}
    private static List<Map<?,?>> rows(Map<?,?> r,String k){return list(r,k).stream().map(QuestRules::map).toList();}
    private static String text(Map<?,?> r,String k){if(!(r.get(k) instanceof String s)||s.isBlank()||s.length()>300)throw new IllegalArgumentException("Invalid rules text");return s;}
    private static String id(Map<?,?> r,String k){String s=text(r,k);if(!s.matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid rules id");return s;}
}
