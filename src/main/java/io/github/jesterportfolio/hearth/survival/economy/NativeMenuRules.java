package io.github.jesterportfolio.hearth.survival.economy;
import java.util.Set;
/** GUI 클릭은 현재 연결·화면·취소·커서·상단 슬롯을 모두 확인한 뒤 처리합니다. */
public final class NativeMenuRules{
 private NativeMenuRules(){}
 public static boolean peerRefusal(String code){return code!=null&&(io.github.jesterportfolio.hearth.economy.PeerExchange.REFUSALS.contains(code)||code.equals("ECON_QUOTE_CHANGED"));}
 public static boolean allow(boolean current,boolean cancelled,boolean top,boolean cursorEmpty,String click,boolean peer){return current&&!cancelled&&top&&cursorEmpty&&(peer?Set.of("LEFT","RIGHT","SHIFT_LEFT","SHIFT_RIGHT").contains(click):"LEFT".equals(click));}
}
