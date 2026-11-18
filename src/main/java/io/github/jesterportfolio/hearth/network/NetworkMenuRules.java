package io.github.jesterportfolio.hearth.network;

/** 서버 선택의 가상 버튼 입력만 허용합니다. */
public final class NetworkMenuRules {
    private NetworkMenuRules(){}
    public static boolean activate(boolean current,boolean cancelled,int slot,String click){return current&&!cancelled&&slot==4&&"LEFT".equals(click);}
}
