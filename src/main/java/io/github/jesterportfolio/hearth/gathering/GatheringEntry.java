package io.github.jesterportfolio.hearth.gathering;

import io.github.jesterportfolio.hearth.session.PlayerSessions;
import java.util.UUID;

/** 초기 입장 이동은 자기 월드의 채집장 밖으로만 허용합니다. 채집장 내부는 활성 참가 권한이 필요합니다. */
public final class GatheringEntry {
    public enum Gate { DENY, OUTSIDE, MEMBERSHIP }
    public static Gate gate(PlayerSessions.State phase,UUID expectedWorld,UUID targetWorld,boolean inside){
        if(expectedWorld==null||!expectedWorld.equals(targetWorld)||phase!=PlayerSessions.State.MOVING&&phase!=PlayerSessions.State.ACTIVE)return Gate.DENY;
        if(!inside)return Gate.OUTSIDE;
        return phase==PlayerSessions.State.ACTIVE?Gate.MEMBERSHIP:Gate.DENY;
    }
}
