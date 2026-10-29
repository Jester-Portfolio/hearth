package io.github.jesterportfolio.hearth.combat;

import java.util.*;

/** 초기 로딩 전용 공개 조우 정의. 배율과 좌표는 콘텐츠 값이며 운영 기본값이 아닙니다. */
public record FieldContent(List<Zone> zones) {
    public record Point(double x,double y,double z) {public Point{if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Invalid field point");}}
    public record Box(double minX,double maxX,double minY,double maxY,double minZ,double maxZ) {
        public Box {if(!Double.isFinite(minX+maxX+minY+maxY+minZ+maxZ)||minX>maxX||minY>maxY||minZ>maxZ)throw new IllegalArgumentException("Invalid field bounds");}
        public boolean contains(Point p){return p!=null&&p.x()>=minX&&p.x()<=maxX&&p.y()>=minY&&p.y()<=maxY&&p.z()>=minZ&&p.z()<=maxZ;}
        public boolean encloses(Box b){return contains(new Point(b.minX,b.minY,b.minZ))&&contains(new Point(b.maxX,b.maxY,b.maxZ));}
    }
    public record Tier(int players,int count,double health,double attack) {
        public Tier {if(players<1||players>32||count<1||count>16||!Double.isFinite(health)||health<1||health>256||!Double.isFinite(attack)||attack<0||attack>32)throw new IllegalArgumentException("Invalid field scaling");}
    }
    public record Zone(String id,String title,Box trigger,Box population,Point spawn,List<Tier> tiers) {
        public Zone {tiers=List.copyOf(tiers);if(id==null||!id.matches("[a-z][a-z0-9_]{0,63}")||title==null||title.isBlank()||title.length()>80||!population.encloses(trigger)||population.equals(trigger)||!trigger.contains(spawn)||tiers.isEmpty()||tiers.getFirst().players()!=1)throw new IllegalArgumentException("Invalid field zone");int prior=0;for(var t:tiers){if(t.players()<=prior)throw new IllegalArgumentException("Unordered field tiers");prior=t.players();}}
        public Tier scale(int players){if(players<1)throw new IllegalArgumentException("No eligible field player");Tier found=tiers.getFirst();for(var t:tiers)if(t.players()<=players)found=t;return found;}
    }
    public FieldContent {zones=List.copyOf(zones);var ids=new HashSet<String>();if(zones.isEmpty()||zones.size()>16)throw new IllegalArgumentException("Invalid field zone count");for(var z:zones)if(!ids.add(z.id()))throw new IllegalArgumentException("Duplicate field zone");}
    public static FieldContent parse(Map<?,?> root){if(!(root.get("format") instanceof Number n)||n.doubleValue()!=1)throw new IllegalArgumentException("Invalid field format");var zones=new ArrayList<Zone>();for(Object value:list(root.get("zones"))){var z=map(value);if(!"ZOMBIE".equals(z.get("entity")))throw new IllegalArgumentException("Unsupported field entity");var tiers=new ArrayList<Tier>();for(Object t:list(z.get("scaling"))){var r=map(t);tiers.add(new Tier(integer(r,"players"),integer(r,"count"),number(r,"health"),number(r,"attack")));}var p=map(z.get("spawn"));zones.add(new Zone(text(z,"id"),text(z,"title"),box(z.get("trigger")),box(z.get("population")),new Point(number(p,"x"),number(p,"y"),number(p,"z")),tiers));}return new FieldContent(zones);}
    private static Box box(Object value){var m=map(value);return new Box(number(m,"min-x"),number(m,"max-x"),number(m,"min-y"),number(m,"max-y"),number(m,"min-z"),number(m,"max-z"));}
    private static Map<?,?> map(Object v){if(!(v instanceof Map<?,?> m))throw new IllegalArgumentException("Invalid field mapping");return m;}
    private static List<?> list(Object v){if(!(v instanceof List<?> l))throw new IllegalArgumentException("Invalid field list");return l;}
    private static String text(Map<?,?> m,String k){if(!(m.get(k) instanceof String s))throw new IllegalArgumentException("Invalid field text");return s;}
    private static double number(Map<?,?> m,String k){if(!(m.get(k) instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Invalid field number");return n.doubleValue();}
    private static int integer(Map<?,?> m,String k){double n=number(m,k);if(n!=(int)n)throw new IllegalArgumentException("Invalid field integer");return (int)n;}
}
