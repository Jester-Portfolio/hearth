package io.github.jesterportfolio.hearth.quest;

import java.util.*;

/** 유효한 현재 세션의 첫 확인에만 안내합니다. 조회 실패·HOLD·이전 콜백은 안내한 것으로 처리하지 않습니다. */
public final class ResumeReminder {
    private final Map<UUID,Object> announced=new HashMap<>();
    public Optional<String> claim(UUID player,Object session,boolean current,CourseResume.Board board){
        if(!current||!board.available()||session==null||announced.get(player)==session)return Optional.empty();
        announced.put(player,session);return Optional.of("현재 안내: "+board.course().guide().title()+" · 확인할 개인 의뢰 "+board.rows().size()+"건");
    }
    public boolean current(UUID player,Object session){return session!=null&&announced.get(player)==session;}
    public void forget(UUID player,Object session){if(current(player,session))announced.remove(player);}
    public void clear(){announced.clear();}
}
