package io.github.jesterportfolio.hearth.combat;

import java.util.*;

/** 개인 전투 표시만 담는 DTO. 다른 참가자의 UUID·체력·부활 사용량은 전달하지 않습니다. */
public final class CombatHud {
    private CombatHud(){}
    public record View(String title,float progress,boolean visible,String action,String notice,String stateKey){
        public View{Objects.requireNonNull(title);Objects.requireNonNull(action);Objects.requireNonNull(notice);Objects.requireNonNull(stateKey);if(!Float.isFinite(progress)||progress<0||progress>1)throw new IllegalArgumentException("Invalid combat HUD progress");}
    }
    static View build(CombatAttempt.Snapshot snapshot,CombatAttempt.View personal,UUID player,double health,double maximum){
        if(!Double.isFinite(health)||!Double.isFinite(maximum)||maximum<=0||health<0||health>maximum)throw new IllegalArgumentException("Invalid combat HUD health");
        var self=snapshot.members().stream().filter(m->m.player().equals(player)&&m.online()).findFirst().orElseThrow(()->new IllegalStateException("COMBAT_HUD_NO_MEMBER"));
        String state;
        if(snapshot.phase()==CombatAttempt.Phase.CLOSED)state="전투 종료 · "+outcome(snapshot.outcome());
        else if(snapshot.phase()==CombatAttempt.Phase.HOLD)state="전투 처리 보류 · 관리자 확인 필요";
        else if(snapshot.phase()==CombatAttempt.Phase.WIPE_WAIT)state="전멸 대기 · 자동 재시작 없음";
        else if(self.returning())state="복귀 위치 적용 대기 · 전투 개입 불가";
        else if(self.protectedReturn())state="복귀 보호 · 피해 면역 / 전투 개입 불가";
        else if(self.reviving())state="부활 적용 대기 · 전투 개입 불가";
        else if(snapshot.phase()==CombatAttempt.Phase.PAUSED)state="전투 일시정지 · 생존 동료 복귀 대기";
        else if(self.dead())state=personal.camera();
        else state="전투 진행 중";
        boolean visible=snapshot.phase()!=CombatAttempt.Phase.CLOSED;
        String key=snapshot.phase()+":"+snapshot.outcome()+":"+self.dead()+":"+self.returning()+":"+self.protectedReturn()+":"+self.reviving();
        String notice=state+(self.dead()?" · 사망 / 관전 중":"");
        String action=visible?state+" | "+personal.revival():state;
        return new View(personal.title(),(float)(health/maximum),visible,action,notice,key);
    }
    public static String outcome(CombatAttempt.Outcome outcome){return switch(outcome){case NONE->"미확정";case VICTORY->"승리";case REQUIRED_FAILURE->"필수 조건 실패";case ALL_OFFLINE->"참가자 전원 접속 종료";case RETRY->"재도전 결정";case RETIRED->"격리 시험 종료";};}
    public static List<String> help(){return List.of("/hearthcombat status · 본인 전투 상태와 남은 부활 횟수", "/hearthcombat watch · 사망 관전 대상 전환", "사망·복귀 보호·부활 적용 중 전투에 개입할 수 없습니다.", "전멸 대기는 자동 재시작되지 않습니다. 재도전 투표는 아직 연결되지 않았습니다.");}
    public static boolean command(String line){return Set.of("/hearthcombat","/hearthcombat help","/hearthcombat status","/hearthcombat watch").contains(line);}
    public record Update(boolean boss,boolean action,boolean notice){}
    /** 같은 상태 안내는 한 번, ActionBar 유지 출력은 20틱마다. 수치는 표시 갱신값일 뿐 게임 규칙이 아닙니다. */
    public static final class Delivery {
        private View previous;private long lastAction;
        public Update next(View view,long tick){
            if(tick<0||previous!=null&&tick<lastAction)throw new IllegalArgumentException("Invalid HUD clock");
            boolean first=previous==null;
            boolean boss=first||previous.visible()!=view.visible()||!previous.title().equals(view.title())||Float.compare(previous.progress(),view.progress())!=0;
            boolean action=first||!previous.action().equals(view.action())||view.visible()&&tick-lastAction>=20;
            boolean notice=first||!previous.stateKey().equals(view.stateKey());
            previous=view;if(action)lastAction=tick;
            return new Update(boss,action,notice);
        }
    }
}
