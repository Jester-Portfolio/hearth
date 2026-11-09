package io.github.jesterportfolio.hearth.phone;

import java.util.*;

/** 책과 스마트폰이 공유하는 세션 내 요청/결과 표식입니다. DB 작업의 성공을 추정하지 않습니다. */
public final class CompletionFlow {
    public enum Phase { PENDING,SUCCEEDED,FAILED }
    public static final class Request {
        private final PhoneCompletion.Choice choice;private Phase phase=Phase.PENDING;
        private Request(PhoneCompletion.Choice choice){this.choice=Objects.requireNonNull(choice);}
        public PhoneCompletion.Choice choice(){return choice;}public Phase phase(){return phase;}
        public String title(){return switch(phase){case PENDING->"처리 중";case SUCCEEDED->"처리 완료";case FAILED->"처리 보류/실패";};}
        public List<String> lines(){return List.of(choice.title(),switch(phase){case PENDING->"기존 저장 작업의 완료를 기다립니다. 아직 성공으로 확정하지 않았습니다.";case SUCCEEDED->"기존 저장 경로의 완료를 확인했습니다. 현재 의뢰와 원래 안내를 다시 조회하세요.";case FAILED->"성공으로 기록하지 않습니다. 저장/HOLD·인벤토리 공간·최신 진행 상태를 확인하세요.";});}
    }
    private final Map<Object,Request> requests=new IdentityHashMap<>();
    public Request begin(Object session,PhoneCompletion.Choice choice){
        Objects.requireNonNull(session);var previous=requests.get(session);if(previous!=null&&previous.phase()==Phase.PENDING)throw new IllegalStateException("COMPLETION_ACTION_BUSY");
        var next=new Request(choice);requests.put(session,next);return next;
    }
    public Request current(Object session){return requests.get(session);}
    public boolean complete(Object session,Request request,boolean success){
        if(request==null||requests.get(session)!=request||request.phase()!=Phase.PENDING)return false;
        request.phase=success?Phase.SUCCEEDED:Phase.FAILED;return true;
    }
    public boolean current(Object session,Request request){return request!=null&&requests.get(session)==request;}
    public void forget(Object session){requests.remove(session);}
    public void clear(){requests.clear();}
}
