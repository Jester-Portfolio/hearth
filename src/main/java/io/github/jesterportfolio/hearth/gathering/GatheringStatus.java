package io.github.jesterportfolio.hearth.gathering;

import java.util.*;

/** 현재 등록 구역의 공개 이용 안내만 만듭니다. 다른 참가자 개인 정보는 표시하지 않습니다. */
public final class GatheringStatus {
    private GatheringStatus(){}
    private static long remaining(long next,long now){long left=next>now?next-now:0;return left/1000+(left%1000==0?0:1);}
    public static List<String> lines(GatheringStore.State state,int occupants,boolean participating,long now){
        if(state==null||occupants<0||now<0)throw new IllegalArgumentException("GATHERING_STATUS");
        String status=switch(state.phase()){case OPEN->"이용 가능";case RESETTING->"초기화 중 · 입장/채집 대기";case ERROR_HOLD->"보류 · 관리자 확인 필요";};
        var result=new ArrayList<String>();result.add("채집 구역: "+status);result.add("본인 입장 등록: "+(participating?"예":"아니오")+" · 이용 중인 인원: "+occupants);
        if(state.phase()==GatheringStore.Phase.OPEN){if(state.emptyReset())result.add("전원 퇴장 상태입니다. 다음 입장 전에 초기화합니다.");result.add("다음 정기 초기화까지 약 "+(remaining(state.nextReset(),now))+"초");}
        result.add("/hearthgather enter · /hearthgather leave · /hearthgather info");result.add("필요한 제작 재료의 보유·부족 수량은 작업대에서 확인하세요.");return List.copyOf(result);
    }
}
