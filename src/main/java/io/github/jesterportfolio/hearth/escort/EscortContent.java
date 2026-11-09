package io.github.jesterportfolio.hearth.escort;

import io.github.jesterportfolio.hearth.quest.*;
import java.util.*;

/** 기존 개인 목표/실패 규칙에 연결한 제한 구역 호위 정의입니다. */
public record EscortContent(Map<String,Definition> quests){
    public record Point(double x,double y,double z){public double distance(Point p){return Math.sqrt(Math.pow(x-p.x,2)+Math.pow(y-p.y,2)+Math.pow(z-p.z,2));}}
    public record Leg(int objective,List<Point> path,QuestContent.Area area){public Leg{path=List.copyOf(path);}public Point start(){return path.getFirst();}public Point end(){return path.getLast();}public boolean contains(Point p){return area.contains(p.x,p.y,p.z);}}
    public record Definition(String id,String quest,String name,double speed,double followDistance,List<Leg> legs){
        public Definition{legs=List.copyOf(legs);}public String reference(){return "escort:"+id;}public Optional<Leg> leg(int objective){return legs.stream().filter(l->l.objective()==objective).findFirst();}
    }
    public EscortContent{quests=Map.copyOf(quests);}
    public static EscortContent parse(Map<?,?> root,QuestCatalog catalog,QuestRules rules){
        if(!(root.get("format") instanceof Number n)||n.doubleValue()!=1||!(root.get("escorts") instanceof List<?> rows)||rows.isEmpty()||rows.size()>8)throw new IllegalArgumentException("ESCORT_FORMAT");
        var result=new LinkedHashMap<String,Definition>();var ids=new HashSet<String>();
        for(Object raw:rows){var row=map(raw);String id=id(row,"id"),quest=id(row,"quest");var q=catalog.quest(quest);if(!ids.add(id)||result.containsKey(quest)||!rules.rule(quest).failures().contains("OBJECTIVE"))throw new IllegalArgumentException("ESCORT_LINK");
            double speed=number(row.get("speed")),follow=number(row.get("follow-distance"));if(speed<.2||speed>1.5||follow<4||follow>16)throw new IllegalArgumentException("ESCORT_MOVEMENT_LIMIT");
            if(!(row.get("legs") instanceof List<?> list)||list.isEmpty()||list.size()>16)throw new IllegalArgumentException("ESCORT_LEGS");var legs=new ArrayList<Leg>();var used=new HashSet<Integer>();
            for(Object value:list){var leg=map(value);double index=number(leg.get("objective"));if(index!=(int)index||index<0||index>=q.goals().size()||!used.add((int)index)||!Objects.equals(q.goals().get((int)index).interaction(),"escort:"+id))throw new IllegalArgumentException("ESCORT_OBJECTIVE");
                if(!(leg.get("path") instanceof List<?> path)||path.size()<2||path.size()>16)throw new IllegalArgumentException("ESCORT_PATH");var points=new ArrayList<Point>();var area=q.goals().get((int)index).area();
                for(Object p:path){if(!(p instanceof List<?> xyz)||xyz.size()!=3)throw new IllegalArgumentException("ESCORT_POINT");var point=new Point(number(xyz.get(0)),number(xyz.get(1)),number(xyz.get(2)));if(!area.contains(point.x,point.y,point.z)||!points.isEmpty()&&points.getLast().distance(point)>16)throw new IllegalArgumentException("ESCORT_POINT_BOUNDS");points.add(point);}if(points.getFirst().distance(points.getLast())<2)throw new IllegalArgumentException("ESCORT_PATH_LENGTH");legs.add(new Leg((int)index,points,area));
            }
            for(int i=0;i<q.goals().size();i++)if(Objects.equals(q.goals().get(i).interaction(),"escort:"+id)&&!used.contains(i))throw new IllegalArgumentException("ESCORT_MISSING_LEG");
            if(legs.stream().flatMap(l->l.path().stream()).map(p->(((int)Math.floor(p.x()))>>4)+":"+(((int)Math.floor(p.z()))>>4)).distinct().count()>4)throw new IllegalArgumentException("ESCORT_CHUNK_LIMIT");
            result.put(quest,new Definition(id,quest,text(row,"name"),speed,follow,legs));
        }
        for(var q:catalog.all())for(var goal:q.goals())if(goal.interaction()!=null&&goal.interaction().startsWith("escort:")&&(!result.containsKey(q.id())||!result.get(q.id()).reference().equals(goal.interaction())))throw new IllegalArgumentException("ESCORT_UNBOUND_GOAL");
        return new EscortContent(result);
    }
    private static Map<?,?> map(Object value){if(!(value instanceof Map<?,?> m))throw new IllegalArgumentException("ESCORT_MAPPING");return m;}
    private static double number(Object value){if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("ESCORT_NUMBER");return n.doubleValue();}
    private static String text(Map<?,?> row,String key){if(!(row.get(key) instanceof String s)||s.isBlank()||s.length()>100)throw new IllegalArgumentException("ESCORT_TEXT");return s;}
    private static String id(Map<?,?> row,String key){String s=text(row,key);if(!s.matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("ESCORT_ID");return s;}
}
