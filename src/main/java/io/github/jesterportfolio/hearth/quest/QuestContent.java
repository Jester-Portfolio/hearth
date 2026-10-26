package io.github.jesterportfolio.hearth.quest;

import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** 격리 시험 한 개의 콘텐츠. 실제 월드 식별자/진행 소유권을 콘텐츠가 선택하지 않습니다. */
public record QuestContent(String id,String name,double x,double y,double z,
        double minX,double maxX,double minY,double maxY,double minZ,double maxZ,Map<String,String> text) {
    public static QuestContent load(YamlConfiguration yaml) {
        String id=yaml.getString("id","");
        if(!id.matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid quest content id");
        var values=new HashMap<String,String>();
        for(String key:List.of("offer","accept","active","report-ready","report","completed","failure")) {
            String value=yaml.getString("text."+key);if(value==null || value.isBlank())throw new IllegalArgumentException("Missing dialogue");values.put(key,value);
        }
        String name=yaml.getString("npc.name");if(name==null || name.isBlank())throw new IllegalArgumentException("Missing NPC name");
        double[] pos=new double[9];int i=0;
        for(String key:List.of("npc.x","npc.y","npc.z","objective.min-x","objective.max-x","objective.min-y","objective.max-y","objective.min-z","objective.max-z")) {
            if(!yaml.isSet(key))throw new IllegalArgumentException("Missing position");
            pos[i]=yaml.getDouble(key);if(!Double.isFinite(pos[i]))throw new IllegalArgumentException("Invalid position");i++;
        }
        if(pos[3]>pos[4] || pos[5]>pos[6] || pos[7]>pos[8])throw new IllegalArgumentException("Invalid bounds");
        return new QuestContent(id,name,pos[0],pos[1],pos[2],pos[3],pos[4],pos[5],pos[6],pos[7],pos[8],Map.copyOf(values));
    }
    public boolean contains(double x,double y,double z){return x>=minX && x<=maxX && y>=minY && y<=maxY && z>=minZ && z<=maxZ;}
}
