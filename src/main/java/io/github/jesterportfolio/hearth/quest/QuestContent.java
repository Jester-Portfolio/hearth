package io.github.jesterportfolio.hearth.quest;

import java.util.Map;

/** 초기 로딩 시 검증한 NPC-개인 방문 퀘스트 연결 한 개입니다. */
public record QuestContent(String id,String title,String npcId,String name,double x,double y,double z,
        double minX,double maxX,double minY,double maxY,double minZ,double maxZ,CompletionMode completionMode,StartMode startMode,Area startArea,String requiresCompleted,Map<String,String> text) {
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
