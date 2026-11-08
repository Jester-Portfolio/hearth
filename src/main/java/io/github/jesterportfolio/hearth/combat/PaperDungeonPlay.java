package io.github.jesterportfolio.hearth.combat;

import io.github.jesterportfolio.hearth.quest.*;
import io.github.jesterportfolio.hearth.session.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.lifecycle.WorldLifecycle;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 실제 인증 Player를 받는 격리 연결기. 자동 시험은 별도 제어 포트를 사용합니다. */
public final class PaperDungeonPlay implements AutoCloseable {
    public final DungeonPlay play;public final DungeonPlay.Definition definition;
    private final PaperSessionAdapter sessions;private final PaperCombatPlayers combat;private final Map<UUID,WorldLifecycle> worlds;private final QuestJournal journal;private final Executor owner;private boolean closed;
    public PaperDungeonPlay(JavaPlugin plugin,DungeonPlay.Definition definition,QuestCatalog catalog,PersonalQuest quests,QuestJournal journal,PaperSessionAdapter sessions,PaperCombatPlayers combat,CombatContent content,Map<UUID,WorldLifecycle> worlds,Executor owner,Function<UUID,CompletionStage<Void>> saving){
        if(!plugin.getConfig().getBoolean("dungeon-play-verification-enabled")||!Boolean.getBoolean("hearth.verification.dungeonPlay"))throw new IllegalStateException("DUNGEON_GATE");definition.validate(catalog.quest(definition.quest()),content);if(catalog.all().stream().anyMatch(q->q.goals().stream().anyMatch(g->g.interaction()!=null&&g.interaction().startsWith("dungeon:")&&(!q.id().equals(definition.quest())||!g.interaction().equals(definition.reference())))))throw new IllegalArgumentException("DUNGEON_UNREGISTERED_GOAL");this.definition=definition;this.sessions=sessions;this.combat=combat;this.worlds=worlds;this.journal=journal;this.owner=owner;
        play=new DungeonPlay(definition,catalog.quest(definition.quest()),quests,new DungeonPlay.Boundary(){
            public List<DungeonPlay.Actor> nearby(UUID run){return Bukkit.getOnlinePlayers().stream().filter(p->{var s=sessions.prepared(p);return s!=null&&s.context().storyRunId().equals(run)&&p.isWhitelisted()&&(p.getGameMode()==GameMode.SURVIVAL||p.getGameMode()==GameMode.ADVENTURE);}).map(Live::new).filter(Live::atEntry).map(p->(DungeonPlay.Actor)p).toList();}
            public boolean available(UUID run){return !closed&&combat.available(run);}public boolean allowed(DungeonPlay.Actor a){return journal.questAllowed(a.context(),definition.quest());}public CompletionStage<Void> save(UUID run){return saving.apply(run);}
            public CombatAttempt start(List<DungeonPlay.Actor> selected){var roster=new HashMap<UUID,PlayerSessions.Session>();selected.forEach(a->roster.put(a.context().playerId(),((Live)a).session));return combat.startExact(selected.getFirst().context().storyRunId(),roster);}
        },owner,PaperDungeonPlay::main);
        combat.gameplay(new PaperCombatPlayers.Gameplay(){public void changed(CombatAttempt a,CombatAttempt b,boolean path){play.changed(a,b,path);}public CompletionStage<Void> victory(CombatAttempt battle){if(!play.registered(battle))return CompletableFuture.completedFuture(null);var actors=new ArrayList<DungeonPlay.Actor>();for(UUID id:battle.onlineSessions().keySet()){var p=Bukkit.getPlayer(id);if(p==null||sessions.prepared(p)==null)return CompletableFuture.failedFuture(new IllegalStateException("DUNGEON_MISSING_PLAYER"));actors.add(new Live(p));}return play.victory(battle,actors).thenRunAsync(()->actors.forEach(a->{if(a.current())((Live)a).player.sendMessage("던전 승리 목표 저장 완료 · 개인 퀘스트를 확인하고 담당 NPC에게 보고하세요.");}),owner);}});
    }
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("DUNGEON_MAIN_THREAD");}
    private final class Live implements DungeonPlay.Actor {final Player player;final PlayerSessions.Session session;final UUID world;final long generation;Live(Player p){player=p;session=Objects.requireNonNull(sessions.prepared(p));var w=Objects.requireNonNull(worlds.get(session.context().storyRunId()));world=w.mapping().physicalId();generation=w.generation();}public ServerProgressAccess.Context context(){return session.context();}public Object token(){return session;}public UUID world(){return world;}public long generation(){return generation;}public boolean current(){return !closed&&sessions.prepared(player)==session&&player.getWorld().getUID().equals(world)&&worlds.get(context().storyRunId()).generation()==generation;}public boolean atEntry(){return current()&&definition.entry().contains(player.getX(),player.getY(),player.getZ());}public void freeze(boolean v){sessions.freezeTravel(player,session,v);}}
    public void enter(Player p,BooleanSupplier dialogue){main();var actor=new Live(p);play.enter(actor,definition.npc(),dialogue).whenCompleteAsync((battle,error)->{if(actor.current())p.sendMessage(error==null?"공개 던전 입장 완료 · 활성 의뢰자 명단과 저장 확인 완료":"던전 입장 거부 또는 보류 · 의뢰/집결/저장 상태를 확인하세요.");},owner);}
    public List<String> information(Player p){main();return play.lines(new Live(p));}
    public void guide(Player p,String quest){main();if(!definition.quest().equals(quest))return;var actor=new Live(p);play.lines(actor).forEach(p::sendMessage);combat.command(p,"/hearthcombat status");}
    public CompletionStage<Void> barrier(UUID run){return play.barrier(run);}
    public void unloaded(UUID run){play.unloaded(run);}
    public void close(){main();closed=true;play.close();combat.gameplay(new PaperCombatPlayers.Gameplay(){});}
}
