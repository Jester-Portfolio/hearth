package io.github.jesterportfolio.hearth.phone;

import java.util.*;

/** owner 스레드에서 현재 접속이 고른 자료 묶음 하나만 유지합니다. 권한/PG 검사 결과를 보관하지 않습니다. */
public final class PhoneFollowup {
    public static final class Choice {
        private final PhoneGrounding.Plan plan;
        private Choice(PhoneGrounding.Plan plan){this.plan=plan.sent();if(this.plan.materials().isEmpty())throw new IllegalArgumentException("FOLLOWUP_REQUIRES_MATERIAL");}
        public PhoneGrounding.Plan plan(){return plan;}
        public PhoneGrounding.Plan question(String question){return plan.followUp(question);}
    }
    private final Map<Object,Choice> choices=new IdentityHashMap<>();
    public Choice select(Object session,PhoneGrounding.Plan plan){Objects.requireNonNull(session);var choice=new Choice(Objects.requireNonNull(plan));choices.put(session,choice);return choice;}
    public Choice get(Object session){return choices.get(session);}
    public boolean matches(Object session,Choice expected){return choices.get(session)==expected;}
    public void forget(Object session,Choice expected){if(expected!=null&&matches(session,expected))choices.remove(session);}
    public void forget(Object session){choices.remove(session);}public void clear(){choices.clear();}
}
