package io.github.jesterportfolio.hearth.quest;

import java.util.*;
import io.github.jesterportfolio.hearth.persistence.QuestStore;

/** 초기 로딩 시 검증한 NPC-개인 방문 퀘스트 연결 한 개입니다. */
public record QuestContent(String id,String title,String npcId,String name,double x,double y,double z,
        double minX,double maxX,double minY,double maxY,double minZ,double maxZ,CompletionMode completionMode,StartMode startMode,Area startArea,String requiresCompleted,Map<String,String> text,List<Goal> goals) {
    public QuestContent {goals=List.copyOf(goals);if(goals.isEmpty())throw new IllegalArgumentException("Empty objectives");}
    public QuestContent(String id,String title,String npcId,String name,double x,double y,double z,double minX,double maxX,double minY,double maxY,double minZ,double maxZ,CompletionMode mode,StartMode start,Area area,String prerequisite,Map<String,String> text){this(id,title,npcId,name,x,y,z,minX,maxX,minY,maxY,minZ,maxZ,mode,start,area,prerequisite,text,List.of(new Goal(text.get("active"),new Area(minX,maxX,minY,maxY,minZ,maxZ))));}
    public record Goal(String description,Area area,QuestLocation location) {public Goal(String description,Area area){this(description,area,null);}}
    public String detailGuidance(QuestStore.Saved saved){String text=guidance(saved);return saved.phase()==QuestStore.Phase.ACTIVE&&goal(saved).location()!=null?text+" · "+goal(saved).location().text():text;}
    public Goal goal(QuestStore.Saved saved){if(saved.objectiveIndex()>=goals.size())throw new IllegalStateException("Content objective mismatch; preserve progress");return goals.get(saved.objectiveIndex());}
    public String guidance(QuestStore.Saved saved){return saved.phase()==QuestStore.Phase.ACTIVE?(goals.size()>1?"["+(saved.objectiveIndex()+1)+"/"+goals.size()+"] ":"")+goal(saved).description():text.get(saved.phase()==QuestStore.Phase.REPORT_READY?"report-ready":"completed");}
    public enum StartMode {
        NPC, AUTOMATIC;
        static StartMode parse(Object value){if("npc".equals(value))return NPC;if("automatic".equals(value))return AUTOMATIC;throw new IllegalArgumentException("Invalid start-mode");}
    }
    public record Area(double minX,double maxX,double minY,double maxY,double minZ,double maxZ){
        public boolean contains(double x,double y,double z){return x>=minX && x<=maxX && y>=minY && y<=maxY && z>=minZ && z<=maxZ;}
        public boolean entered(double fx,double fy,double fz,double tx,double ty,double tz){return !contains(fx,fy,fz) && contains(tx,ty,tz);}
    }
    public boolean startsAt(double x,double y,double z){return startMode==StartMode.AUTOMATIC && startArea!=null && startArea.contains(x,y,z);}
    public enum CompletionMode {
        REPORT, AUTOMATIC;
        static CompletionMode parse(Object value){
            if("report".equals(value))return REPORT;
            if("automatic".equals(value))return AUTOMATIC;
            throw new IllegalArgumentException("Invalid completion-mode");
        }
    }
    public boolean contains(double x,double y,double z){return x>=minX && x<=maxX && y>=minY && y<=maxY && z>=minZ && z<=maxZ;}
}
