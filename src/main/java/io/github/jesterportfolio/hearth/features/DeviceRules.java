package io.github.jesterportfolio.hearth.features;
/** 발급 표시가 있으면 명시적 재발급만 허용합니다. 정상 이동은 개인 슬롯 안으로 한정합니다. */
public final class DeviceRules {
    private DeviceRules(){}public static boolean issue(boolean issued,boolean explicit){return !issued||explicit;}
}
