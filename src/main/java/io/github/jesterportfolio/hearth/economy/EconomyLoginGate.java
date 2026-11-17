package io.github.jesterportfolio.hearth.economy;

import java.util.function.BooleanSupplier;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import net.kyori.adventure.text.Component;

/** 설정/초기화 실패도 플러그인을 비활성화해 순정 접속을 열지 않고 접속 보류로 유지합니다. */
public final class EconomyLoginGate implements Listener {
    private final BooleanSupplier ready;
    public EconomyLoginGate(BooleanSupplier ready){this.ready=ready;}
    @EventHandler(priority=EventPriority.HIGHEST) public void login(AsyncPlayerPreLoginEvent event){if(!ready.getAsBoolean())event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,Component.text("경제 서버 접속 준비 중이거나 점검 중입니다."));}
}
