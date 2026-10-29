package io.github.jesterportfolio.hearth.combat;

import io.github.jesterportfolio.hearth.session.PlayerSessions;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** 전투 전용 BossBar와 ActionBar의 단일 출력 경로. 퀘스트 BossBar나 기본 HUD는 건드리지 않습니다. */
public final class PaperCombatHud implements AutoCloseable {
    private static final class Entry {
        final Player player;final PlayerSessions.Session session;final UUID attempt;final BossBar bar;final CombatHud.Delivery delivery=new CombatHud.Delivery();boolean shown;
        Entry(Player p,PlayerSessions.Session session,UUID attempt,CombatHud.View view){player=p;this.session=session;this.attempt=attempt;bar=create(view);}
    }
    private final Map<UUID,Entry> entries=new HashMap<>();private boolean closed;
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Not main thread");}
    public static BossBar create(CombatHud.View view){return BossBar.bossBar(Component.text(view.title()),view.progress(),BossBar.Color.RED,BossBar.Overlay.PROGRESS);}
    public void present(Player p,PlayerSessions.Session session,UUID attempt,CombatHud.View view,long tick,BooleanSupplier current){
        main();if(closed||!current.getAsBoolean())return;
        var entry=entries.get(p.getUniqueId());
        if(entry!=null&&(entry.player!=p||entry.session!=session||!entry.attempt.equals(attempt))){clear(entry.player,entry.session,false);entry=null;}
        if(entry==null){entry=new Entry(p,session,attempt,view);entries.put(p.getUniqueId(),entry);}
        var update=entry.delivery.next(view,tick);
        if(update.boss()){
            entry.bar.name(Component.text(view.title()));entry.bar.progress(view.progress());
            if(view.visible()&&!entry.shown){p.showBossBar(entry.bar);entry.shown=true;}
            else if(!view.visible()&&entry.shown){p.hideBossBar(entry.bar);entry.shown=false;}
        }
        if(update.action())p.sendActionBar(Component.text(view.visible()?view.action():""));
        if(update.notice()){
            p.sendMessage(Component.text(view.notice()));
            if(view.visible())p.sendMessage(Component.text("[본인 전투 상태]").clickEvent(ClickEvent.runCommand("/hearthcombat status"))
                .append(Component.text(" [관전 대상 전환]").clickEvent(ClickEvent.runCommand("/hearthcombat watch")))
                .append(Component.text(" [재도전 투표]").clickEvent(ClickEvent.runCommand("/hearthcombat retry"))));
        }
    }
    public void clear(Player p,PlayerSessions.Session session,boolean clearAction){
        main();var entry=entries.get(p.getUniqueId());if(entry==null||entry.player!=p||entry.session!=session)return;
        entries.remove(p.getUniqueId(),entry);if(entry.shown)p.hideBossBar(entry.bar);
        if(clearAction&&p.isOnline())p.sendActionBar(Component.empty());
    }
    @Override public void close(){main();closed=true;RuntimeException failure=null;for(var e:List.copyOf(entries.values()))try{clear(e.player,e.session,false);}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}if(failure!=null)throw failure;}
}
