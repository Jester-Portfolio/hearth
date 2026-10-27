package io.github.jesterportfolio.hearth.quest;

import java.util.Map;

/** 초기 로딩 시 검증한 NPC-개인 방문 퀘스트 연결 한 개입니다. */
public record QuestContent(String id,String npcId,String name,double x,double y,double z,
        double minX,double maxX,double minY,double maxY,double minZ,double maxZ,Map<String,String> text) {
    public boolean contains(double x,double y,double z){return x>=minX && x<=maxX && y>=minY && y<=maxY && z>=minZ && z<=maxZ;}
}
