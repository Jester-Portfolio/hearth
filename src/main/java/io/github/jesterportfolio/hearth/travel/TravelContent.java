package io.github.jesterportfolio.hearth.travel;

import io.github.jesterportfolio.hearth.exploration.ExplorationContent;
import java.util.*;

/** 직접 방문한 거점의 시설에서만 무료·즉시·비전투 이동합니다. 좌표는 서버 전용입니다. */
public record TravelContent(Map<String,Station> stations){
    public record Point(double x,double y,double z){public Point{if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.abs(x)>29999984||Math.abs(z)>29999984||y< -64||y>320)throw new IllegalArgumentException("TRAVEL_POSITION");}public double distance(Point p){return Math.pow(x-p.x,2)+Math.pow(y-p.y,2)+Math.pow(z-p.z,2);}}
    public record Station(String id,String place,Point facility,String material,Point arrival,float yaw,float pitch){public Station{key(id);key(place);Objects.requireNonNull(facility);Objects.requireNonNull(arrival);if(material==null||!material.matches("[A-Z_]+")||!Float.isFinite(yaw)||!Float.isFinite(pitch)||Math.abs(pitch)>90)throw new IllegalArgumentException("TRAVEL_STATION");}}
    public TravelContent{stations=Map.copyOf(stations);if(stations.isEmpty()||stations.size()>128)throw new IllegalArgumentException("TRAVEL_STATIONS");if(stations.values().stream().map(s->s.facility().toString()).distinct().count()!=stations.size())throw new IllegalArgumentException("TRAVEL_DUPLICATE_FACILITY");}
    public static TravelContent parse(Map<?,?> root,ExplorationContent exploration){if(!(root.get("format") instanceof Number n)||n.doubleValue()!=1||!Boolean.TRUE.equals(root.get("isolated-unpriced-prototype"))&&!"FREE_INSTANT_NON_COMBAT".equals(root.get("policy"))||!(root.get("stations") instanceof List<?> rows))throw new IllegalArgumentException("TRAVEL_FORMAT");if(root.containsKey("policy")&&!"FREE_INSTANT_NON_COMBAT".equals(root.get("policy")))throw new IllegalArgumentException("TRAVEL_POLICY");var result=new LinkedHashMap<String,Station>();
        for(var value:rows){var r=map(value);String id=text(r,"id"),place=text(r,"place");var p=exploration.places().get(place);var facility=point(map(r.get("facility")));var arrival=point(map(r.get("arrival")));var pose=map(r.get("arrival"));var station=new Station(id,place,facility,text(map(r.get("facility")),"material"),arrival,(float)number(pose,"yaw"),(float)number(pose,"pitch"));
            if(p==null||!p.hub()||!p.bounds().contains(facility.x(),facility.y(),facility.z())||!p.bounds().contains(arrival.x(),arrival.y(),arrival.z())||Math.floor(facility.x())!=facility.x()||Math.floor(facility.y())!=facility.y()||Math.floor(facility.z())!=facility.z()||result.putIfAbsent(id,station)!=null)throw new IllegalArgumentException("TRAVEL_REFERENCE");}
        return new TravelContent(result);
    }
    public Station get(String id){var s=stations.get(id);if(s==null)throw new IllegalArgumentException("TRAVEL_UNKNOWN_STATION");return s;}
    private static void key(String s){if(s==null||!s.matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("TRAVEL_KEY");}
    private static Map<?,?> map(Object o){if(!(o instanceof Map<?,?> m))throw new IllegalArgumentException("TRAVEL_MAP");return m;}
    private static String text(Map<?,?> m,String k){if(!(m.get(k) instanceof String s)||s.isBlank())throw new IllegalArgumentException("TRAVEL_TEXT");return s;}
    private static double number(Map<?,?> m,String k){if(!(m.get(k) instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("TRAVEL_NUMBER");return n.doubleValue();}
    private static Point point(Map<?,?> m){return new Point(number(m,"x"),number(m,"y"),number(m,"z"));}
}
