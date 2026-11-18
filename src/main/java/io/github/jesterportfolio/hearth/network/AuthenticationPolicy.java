package io.github.jesterportfolio.hearth.network;

/** 직결 인증과 프록시 인증을 명시적으로 구분합니다. offline-mode만으로 신뢰하지 않습니다. */
public record AuthenticationPolicy(boolean online,boolean whitelist,String bind,boolean legacy,boolean velocity,boolean forwardedOnline,boolean proxyProtocol) {
    public boolean permits(boolean network,boolean secretMatches){return whitelist&&(network?!online&&"127.0.0.1".equals(bind)&&!legacy&&velocity&&forwardedOnline&&!proxyProtocol&&secretMatches:online);}
}
