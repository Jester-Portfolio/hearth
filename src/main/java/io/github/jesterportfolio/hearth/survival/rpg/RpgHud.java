package io.github.jesterportfolio.hearth.survival.rpg;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;

/** 메인 스레드에서 계산한 경제 표시만 전달한다. 스토리 HUD/스마트폰 세션과 공유하지 않는다. */
public final class RpgHud implements AutoCloseable {
    interface Bridge extends AutoCloseable { boolean show(Player p,Object session,String value);void clear(Player p);void close(); }
    private final RpgRuntime r;private final String mode;private final Bridge bridge;private final Set<UUID> shown=new HashSet<>();
    RpgHud(RpgRuntime r){this.r=r;mode=r.config.getProperty("hud-mode","actionbar");if(!Set.of("actionbar","betterhud","off").contains(mode))throw new IllegalArgumentException("RPG_HUD_MODE");Bridge found=null;if(mode.equals("betterhud")&&Bukkit.getPluginManager().isPluginEnabled("BetterHud"))try{found=new RpgBetterHud(r);}catch(RuntimeException unavailable){r.plugin.getLogger().warning("HEARTH_RPG_HUD_FALLBACK "+unavailable.getMessage());}bridge=found;}
    public void publish(){for(var p:Bukkit.getOnlinePlayers()){if(mode.equals("off")||!r.available(p)){clear(p);continue;}var u=r.aura.api.getUser(p.getUniqueId());String text="마나 "+Math.round(u.getMana())+" · PvP "+(r.pvp(p.getWorld())?"ON":"OFF")+" · "+(r.policy.ready(r.role(p.getWorld()))?"/숙련도":"사망 정책 준비 중");boolean custom=bridge!=null&&bridge.show(p,r.runtime.sessionToken(p),text);if(!custom)p.sendActionBar(Component.text(text));shown.add(p.getUniqueId());}}
    public void clear(Player p){if(bridge!=null)bridge.clear(p);if(shown.remove(p.getUniqueId()))p.sendActionBar(Component.empty());}
    public void close(){for(var p:Bukkit.getOnlinePlayers())clear(p);if(bridge!=null)bridge.close();}
}
