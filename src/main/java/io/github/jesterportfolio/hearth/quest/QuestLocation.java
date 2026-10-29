package io.github.jesterportfolio.hearth.quest;

import java.util.*;

/** 콘텐츠가 명시적으로 허용한 목표 위치만 표시합니다. 실제 목표 경계를 자동 공개하지 않습니다. */
public record QuestLocation(String kind,String text){
    public static QuestLocation parse(Object raw,QuestContent.Area goal){
        if(!(raw instanceof Map<?,?> m)||!(m.get("kind") instanceof String kind))throw new IllegalArgumentException("Invalid objective location");
        if(kind.equals("HIDDEN")){if(m.size()!=1)throw new IllegalArgumentException("Hidden location must not include coordinates");return new QuestLocation(kind,"위치 비공개");}
        if(kind.equals("EXACT")){
            if(!m.keySet().equals(Set.of("kind","x","y","z")))throw new IllegalArgumentException("Invalid exact location fields");
            double x=n(m,"x"),y=n(m,"y"),z=n(m,"z");if(!goal.contains(x,y,z))throw new IllegalArgumentException("Exact location outside objective");
            return new QuestLocation(kind,"정확한 위치: "+x+", "+y+", "+z);
        }
        if(!kind.equals("AREA")||!m.keySet().equals(Set.of("kind","min-x","max-x","min-y","max-y","min-z","max-z")))throw new IllegalArgumentException("Invalid area location fields");
        double ax=n(m,"min-x"),bx=n(m,"max-x"),ay=n(m,"min-y"),by=n(m,"max-y"),az=n(m,"min-z"),bz=n(m,"max-z");
        if(ax>goal.minX()||bx<goal.maxX()||ay>goal.minY()||by<goal.maxY()||az>goal.minZ()||bz<goal.maxZ())throw new IllegalArgumentException("Public area must cover objective");
        return new QuestLocation(kind,"탐색 구역: "+ax+"~"+bx+", "+ay+"~"+by+", "+az+"~"+bz);
    }
    private static double n(Map<?,?> m,String key){if(!(m.get(key) instanceof Number value)||!Double.isFinite(value.doubleValue())||Math.abs(value.doubleValue())>29999984)throw new IllegalArgumentException("Invalid public coordinate");double n=value.doubleValue();if(key.endsWith("y")&&(n< -64||n>320))throw new IllegalArgumentException("Invalid public height");return n;}
}
