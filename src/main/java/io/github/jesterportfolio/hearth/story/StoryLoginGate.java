package io.github.jesterportfolio.hearth.story;

import java.util.function.Predicate;
import java.util.UUID;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import net.kyori.adventure.text.Component;

/** 초기화/종료/설정 실패 때 플러그인을 끄지 않고 접속을 닫습니다. */
public final class StoryLoginGate implements Listener {
    private final Predicate<UUID> ready;
    public StoryLoginGate(Predicate<UUID> ready){this.ready=ready;}
    @EventHandler(priority=EventPriority.HIGHEST) public void login(AsyncPlayerPreLoginEvent event){if(!ready.test(event.getUniqueId()))event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,Component.text("스토리 서버 준비 상태와 등록된 접속 권한을 확인해 주세요."));}
}
