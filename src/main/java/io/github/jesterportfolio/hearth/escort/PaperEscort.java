package io.github.jesterportfolio.hearth.escort;

import io.github.jesterportfolio.hearth.quest.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.session.*;
import io.github.jesterportfolio.hearth.lifecycle.WorldLifecycle;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** 기존 NPC/개인 의뢰를 재사용하는 공개 호위. 플레이어 이동·일반 공격 권한은 바꾸지 않습니다. */
public final class PaperEscort implements Listener,AutoCloseable {
    private final JavaPlugin plugin;private final PaperSessionAdapter players;private final QuestJournal journal;private final QuestCatalog catalog;private final Map<UUID,WorldLifecycle> worlds;private final Executor owner;
    private final Map<UUID,Object> frames=new HashMap<>();private final Map<Player,Object> owners=new IdentityHashMap<>();private final Map<Chunk,Integer> tickets=new HashMap<>();private final Set<Chunk> ownedTickets=new HashSet<>();private final BukkitTask task;private boolean closed;
    public final EscortContent content;public final EscortPlay play;
    public PaperEscort(JavaPlugin plugin,PaperSessionAdapter players,QuestJournal journal,PersonalQuest quests,QuestCatalog catalog,Map<UUID,WorldLifecycle> worlds,EscortContent content,Executor owner){
        this.plugin=plugin;this.players=players;this.journal=journal;this.catalog=catalog;this.worlds=worlds;this.content=content;this.owner=owner;play=createPlay(quests);Bukkit.getPluginManager().registerEvents(this,plugin);task=Bukkit.getScheduler().runTaskTimer(plugin,play::tick,5,5);
    }
    public EscortPlay createPlay(PersonalQuest progress){return new EscortPlay(content,catalog,progress,this::spawn,owner,PaperEscort::main);}
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("ESCORT_OWNER_THREAD");}
    private final class Actor implements EscortPlay.Actor{
        final Player player;final PlayerSessions.Session session;final UUID world;final long generation;
        Actor(Player p){player=p;session=Objects.requireNonNull(players.current(p));world=p.getWorld().getUID();generation=worlds.get(session.context().storyRunId()).generation();owners.put(p,session);}
        public ServerProgressAccess.Context context(){return session.context();}public Object token(){return session;}public UUID world(){return world;}public long generation(){return generation;}
        public boolean current(){return !closed&&players.current(player)==session&&world.equals(player.getWorld().getUID())&&worlds.get(context().storyRunId()).generation()==generation;}public boolean allowed(String q){return current()&&journal.questAllowed(context(),q);}public EscortContent.Point position(){return new EscortContent.Point(player.getX(),player.getY(),player.getZ());}
    }
    private EscortPlay.Body spawn(EscortPlay.Actor actor,EscortContent.Definition definition,EscortContent.Leg leg){
        main();var world=Objects.requireNonNull(Bukkit.getWorld(actor.world()));var point=leg.start();var chunk=world.getChunkAt(((int)Math.floor(point.x()))>>4,((int)Math.floor(point.z()))>>4);tickets.compute(chunk,(c,n)->{if(n==null&&c.addPluginChunkTicket(plugin))ownedTickets.add(c);return n==null?1:n+1;});
        Villager mob=null;try{mob=world.spawn(new Location(world,point.x(),point.y(),point.z()),Villager.class,v->{v.setPersistent(false);v.setVisibleByDefault(false);v.setSilent(true);v.setCollidable(false);v.setCanPickupItems(false);v.setRemoveWhenFarAway(false);v.customName(Component.text(definition.name()));});Bukkit.getMobGoals().removeAllGoals(mob);if(actor instanceof Actor a&&a.current())a.player.showEntity(plugin,mob);}
        catch(RuntimeException error){if(mob!=null)mob.remove();release(chunk);throw error;}var body=mob;
        return new EscortPlay.Body(){boolean removed;public UUID id(){return body.getUniqueId();}public boolean alive(){return body.isValid()&&!body.isDead();}public EscortContent.Point position(){var p=body.getLocation();return new EscortContent.Point(p.getX(),p.getY(),p.getZ());}public void move(EscortContent.Point p,double speed){if(alive())body.getPathfinder().moveTo(new Location(world,p.x(),p.y(),p.z()),speed);}public void stop(){body.getPathfinder().stopPathfinding();}public void remove(){if(removed)return;removed=true;body.remove();release(chunk);}};
    }
    private void release(Chunk chunk){tickets.computeIfPresent(chunk,(c,n)->{if(n<=1){if(ownedTickets.remove(c))c.removePluginChunkTicket(plugin);return null;}return n-1;});}
    public CompletionStage<EscortPlay.Attempt> start(EscortPlay.Actor actor,String quest,QuestStore.Saved expected){
        main();var definition=content.quests().get(quest);if(definition==null)return CompletableFuture.failedFuture(new IllegalArgumentException("ESCORT_UNAVAILABLE"));var path=definition.legs().stream().flatMap(l->l.path().stream()).toList();CompletionStage<Void> work=CompletableFuture.completedFuture(null);var chunks=new HashSet<String>();
        for(var p:path){int x=((int)Math.floor(p.x()))>>4,z=((int)Math.floor(p.z()))>>4;if(chunks.add(x+":"+z))work=work.thenComposeAsync(v->{if(!actor.current())return CompletableFuture.failedFuture(new IllegalStateException("ESCORT_STALE_PREPARE"));return Objects.requireNonNull(Bukkit.getWorld(actor.world())).getChunkAtAsync(x,z).thenApply(c->null);},owner);}
        return work.thenComposeAsync(v->play.start(actor,quest,expected),owner);
    }
    public boolean command(Player player,String command){if(!command.equals("/hearthescort")&&!command.startsWith("/hearthescort "))return false;String[] args=command.split(" ",-1);if(args.length!=2||!content.quests().containsKey(args[1])){player.sendMessage(Component.text("현재 호위 의뢰 상세의 현장 안내를 선택하세요. /hearthescort <의뢰>"));return true;}show(player,args[1]);return true;}
    public void show(Player player,String quest){
        main();if(closed||players.current(player)==null||!content.quests().containsKey(quest))return;var actor=new Actor(player);Object frame=new Object();frames.put(player.getUniqueId(),frame);
        journal.load(actor.context(),actor.token(),actor::current).whenCompleteAsync((view,error)->{
            if(!actor.current()||frames.get(player.getUniqueId())!=frame)return;if(error!=null){player.sendMessage(Component.text("호위 상태를 확인하지 못했습니다. 저장 상태를 확인하세요."));return;}
            try{journal.requireView(actor.context(),view);var q=catalog.quest(quest);var saved=view.states().get(quest);if(!actor.allowed(quest)||saved.phase()==QuestStore.Phase.NOT_ACCEPTED||saved.phase()==QuestStore.Phase.SKIPPED)throw new IllegalStateException("ESCORT_QUEST_REQUIRED");var lines=new ArrayList<String>();lines.add(q.detailGuidance(saved));lines.addAll(play.lines(actor,quest));lines.add("대상이 쓰러지거나 지정 호위 구역을 벗어나면 개인 의뢰가 실패합니다. 멀어지면 이동을 기다립니다.");lines.add("보고·재시작·보상은 기존 개인 의뢰 상세에서 확인합니다. 접속 종료/월드 변경은 대상만 정리하며 저장된 목표를 초기화하지 않습니다.");var actions=new ArrayList<Component>();
                if(saved.phase()==QuestStore.Phase.ACTIVE&&content.quests().get(quest).leg(saved.objectiveIndex()).isPresent())actions.add(Component.text("현재 목표의 출발 지점에서 호위 시작").clickEvent(ClickEvent.callback(audience->{if(audience==player)Bukkit.getScheduler().runTask(plugin,()->{if(!actor.current()||frames.get(player.getUniqueId())!=frame)return;start(actor,quest,saved).whenCompleteAsync((attempt,failure)->{if(actor.current())player.sendMessage(Component.text(failure==null?"현재 개인 목표의 호위를 확인했습니다. 대상을 따라 지정 도착 지점으로 이동하세요.":"현재 출발 위치·개인 목표·저장 상태를 확인한 뒤 다시 선택하세요."));},owner);});},ClickCallback.Options.builder().uses(1).lifetime(java.time.Duration.ofMinutes(2)).build())));
                actions.add(Component.text("호위 상태 새로고침").clickEvent(ClickEvent.runCommand("/hearthescort "+quest)));actions.add(Component.text("개인 의뢰 상세·재시작·보상").clickEvent(ClickEvent.runCommand("/hearthquest info "+quest)));actions.add(Component.text("이어 하기").clickEvent(ClickEvent.runCommand("/hearthresume")));player.openBook(PaperQuestUI.book(new QuestBookView("개인 호위 현장",io.github.jesterportfolio.hearth.phone.PhonePages.text("개인 호위 현장",lines).pages()),actions));
            }catch(RuntimeException stale){player.sendMessage(Component.text("호위 목표나 공개 조건이 바뀌었습니다. 현재 개인 의뢰를 다시 조회하세요."));}
        },owner);
    }
    @EventHandler public void died(EntityDeathEvent e){var id=e.getEntity().getUniqueId();boolean owned=play.ownsBody(id);if(owned){e.getDrops().clear();e.setDroppedExp(0);play.died(id);}}
    public CompletionStage<Void> barrier(UUID run){return play.barrier(run);}
    @EventHandler public void quit(PlayerQuitEvent e){Object session=owners.remove(e.getPlayer());if(session!=null)play.left(session);frames.remove(e.getPlayer().getUniqueId());}
    @Override public void close(){main();if(closed)return;closed=true;task.cancel();HandlerList.unregisterAll(this);play.close();for(var chunk:List.copyOf(ownedTickets))chunk.removePluginChunkTicket(plugin);ownedTickets.clear();tickets.clear();frames.clear();owners.clear();}
}
