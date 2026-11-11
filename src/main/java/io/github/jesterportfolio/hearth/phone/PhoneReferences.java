package io.github.jesterportfolio.hearth.phone;

import java.util.*;

/** 세션 내 최신 요청의 자료만 유지합니다. 이전 요청의 늦은 조회가 새 요청을 바꾸지 못합니다. */
public final class PhoneReferences {
    public static final class Request {
        private final Object session;private PhoneGrounding.Plan plan;
        private Request(Object session,PhoneGrounding.Plan plan){this.session=session;this.plan=plan;}
        public PhoneGrounding.Plan plan(){return plan;}
    }
    private final Map<Object,Request> latest=new IdentityHashMap<>();
    public Request begin(Object session,PhoneGrounding.Plan plan){var request=new Request(Objects.requireNonNull(session),plan.sent());latest.put(session,request);return request;}
    public Request get(Object session){return latest.get(session);}
    public boolean containsKey(Object session){return latest.containsKey(session);}
    public boolean current(Request request){return request!=null&&latest.get(request.session)==request;}
    public boolean update(Request request,PhoneGrounding.Plan plan){if(!current(request))return false;if(!request.plan.scope().equals(plan.scope()))throw new IllegalStateException("STALE_AI_REFERENCE");request.plan=plan.sent();return true;}
    public void remove(Request request){if(current(request))latest.remove(request.session);}
    public void remove(Object session){latest.remove(session);}
    public void clear(){latest.clear();}
    public static String label(PhoneGrounding.Plan plan,String key){var selected=plan.selected();for(int i=0;i<selected.size();i++){var item=selected.get(i).item();if(item.key().equals(key))return "[자료 "+(i+1)+"] "+item.source().title();}throw new IllegalStateException("STALE_AI_REFERENCE");}
}
