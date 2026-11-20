package io.github.jesterportfolio.hearth.survival.rpg;

import java.util.*;import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.*;import org.bukkit.entity.Player;import org.bukkit.event.*;import org.bukkit.event.player.*;
import kr.toxicity.hud.api.*;import kr.toxicity.hud.api.placeholder.HudPlaceholder;import kr.toxicity.hud.api.player.HudPlayer;

/** 선택형 경제 HUD 공개 API. 기존 팩·HUD 파일을 덮어쓰거나 자동 재로드하지 않는다. */
final class RpgBetterHud implements RpgHud.Bridge,Listener {
    private record Frame(HudPlayer actor,Object session,String text,long expires){}
    private final RpgRuntime r;private final BetterHud api;private final Map<UUID,Frame> frames=new ConcurrentHashMap<>();private final Set<UUID> packs=new HashSet<>();private volatile boolean closed;
    RpgBetterHud(RpgRuntime r){this.r=r;var p=Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("BetterHud"));if(!p.getPluginMeta().getVersion().equals("2.1.0-SNAPSHOT-448"))throw new IllegalStateException("BETTERHUD_UNSUPPORTED_VERSION");api=BetterHudAPI.inst();if(api.isMergeBossBar())throw new IllegalStateException("BETTERHUD_MERGE_BOSS_BAR_MUST_BE_FALSE");var registry=api.getPlaceholderManager().getStringContainer();if(registry.getAllPlaceholders().containsKey("hearth_rpg"))throw new IllegalStateException("RPG_HUD_PLACEHOLDER_OWNER");registry.addPlaceholder("hearth_rpg",HudPlaceholder.<String>builder().requiredArgsLength(0).function((args,event)->actor->{var f=frames.get(actor.uuid());return !closed&&f!=null&&f.actor()==actor&&System.nanoTime()<f.expires()?f.text():"";}).build());Bukkit.getPluginManager().registerEvents(this,r.plugin);}
    public boolean show(Player p,Object session,String text){var actor=api.getPlayerManager().getHudPlayer(p.getUniqueId());var hud=api.getHudManager().getHud("hearth_rpg");if(closed||api.isOnReload()||!packs.contains(p.getUniqueId())||actor==null||actor.handle()!=p||hud==null||hud.isDefault()){clear(p);return false;}var old=frames.get(p.getUniqueId());if(old!=null&&(old.actor()!=actor||old.session()!=session))clear(p);frames.put(p.getUniqueId(),new Frame(actor,session,text,System.nanoTime()+1_500_000_000L));hud.add(actor);return true;}
    public void clear(Player p){var old=frames.remove(p.getUniqueId());var hud=api.getHudManager().getHud("hearth_rpg");if(old!=null&&hud!=null)hud.remove(old.actor());}
    @EventHandler public void pack(PlayerResourcePackStatusEvent e){if(e.getStatus()==PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED)packs.add(e.getPlayer().getUniqueId());else{packs.remove(e.getPlayer().getUniqueId());clear(e.getPlayer());}}
    @EventHandler public void quit(PlayerQuitEvent e){packs.remove(e.getPlayer().getUniqueId());clear(e.getPlayer());}
    public void close(){closed=true;HandlerList.unregisterAll(this);frames.clear();packs.clear();}
}
