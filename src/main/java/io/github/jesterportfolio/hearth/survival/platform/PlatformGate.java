package io.github.jesterportfolio.hearth.survival.platform;

/** 조회·복구와 일반 효과 실행의 준비 조건을 분리한다. 권한·세션·ACL은 기존 경계를 추가로 적용한다. */
public record PlatformGate(boolean opened,boolean closed,boolean maintenance,boolean configuring,boolean resourcesBusy,boolean jobsBusy,boolean transactionsSettled,boolean rpgReady,boolean serverReady,boolean economyReady) {
    public boolean operational(){return opened&&!closed&&!maintenance;}
    public boolean configuration(){return opened&&!closed&&!configuring&&!resourcesBusy&&!jobsBusy&&transactionsSettled;}
    public boolean dispatch(){return operational()&&!configuring&&!resourcesBusy&&rpgReady&&serverReady&&economyReady;}
}
