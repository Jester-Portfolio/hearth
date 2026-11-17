package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import java.util.function.BooleanSupplier;

/** owner 스레드 전용의 일회성 학습 복귀 표식. 원래 화면은 복귀 콜백에서 다시 조회합니다. */
public final class PhoneLearning {
    public static final class Origin {
        private final String label;private final BooleanSupplier current;private final Runnable back;
        private Origin(String label,BooleanSupplier current,Runnable back){this.label=label;this.current=current;this.back=back;}
        public String label(){return label;}
    }
    private final Map<Object,Origin> origins=new IdentityHashMap<>();
    public Origin begin(Object session,String label,BooleanSupplier current,Runnable back){
        Objects.requireNonNull(session);Objects.requireNonNull(current);Objects.requireNonNull(back);
        if(label==null||label.isBlank()||label.codePointCount(0,label.length())>80)throw new IllegalArgumentException("LEARNING_LABEL");
        if(!allowed(current))throw new IllegalStateException("STALE_LEARNING_ORIGIN");
        var origin=new Origin(label,current,back);origins.put(session,origin);return origin;
    }
    public Origin current(Object session){return origins.get(session);}
    public boolean back(Object session,Origin expected){
        if(expected==null||origins.get(session)!=expected)return false;
        origins.remove(session);if(!allowed(expected.current))return false;expected.back.run();return true;
    }
    private static boolean allowed(BooleanSupplier current){try{return current.getAsBoolean();}catch(RuntimeException stale){return false;}}
    public void forget(Object session){origins.remove(session);}public void clear(){origins.clear();}
}
