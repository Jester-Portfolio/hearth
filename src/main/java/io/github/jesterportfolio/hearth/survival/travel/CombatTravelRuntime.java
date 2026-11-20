package io.github.jesterportfolio.hearth.survival.travel;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import io.github.jesterportfolio.hearth.economy.CombatTagStore;
import io.github.jesterportfolio.hearth.survival.SurvivalWorlds;
import io.github.jesterportfolio.hearth.survival.economy.SurvivalEconomy;

/** 전투와 TPA의 단일 메인 스레드 경계. PG는 worker에 맡기고 완료만 owner로 전달한다. */
public final class CombatTravelRuntime implements Listener,AutoCloseable {
    public interface Boundary {
        Object token(Player p);boolean current(Player p,Object token);boolean available(Player p);
        boolean admitted(UUID player,Location target);
        CompletionStage<Boolean> move(Player p,Object token,Location destination,BooleanSupplier guard);
        void hold();
    }
    private final JavaPlugin plugin;private final Boundary boundary;private final java.util.concurrent.Executor owner;
    public final CombatTags tags=new CombatTags(System::currentTimeMillis,true);
    public final TpaRequests requests;
    public final CombatTagStore store;
    private final boolean pve;private final BukkitTask pulse;private volatile boolean loaded,held,draining,closed;
    private CompletionStage<Void> saving;
    public CombatTravelRuntime(JavaPlugin plugin,SurvivalEconomy economy,Boundary boundary){
        this.plugin=plugin;this.boundary=boundary;owner=economy.owner;
        if(!plugin.getConfig().isBoolean("combat-travel.include-pve"))throw new IllegalArgumentException("COMBAT_PVE_CHOICE_REQUIRED");
        pve=plugin.getConfig().getBoolean("combat-travel.include-pve");long ttl=plugin.getConfig().getLong("combat-travel.request-seconds",60);
        requests=new TpaRequests(System::currentTimeMillis,Math.multiplyExact(ttl,1000));store=new CombatTagStore(economy.database(),economy.store,economy.settings.realm());
        Bukkit.getPluginManager().registerEvents(this,plugin);
        pulse=Bukkit.getScheduler().runTaskTimer(plugin,()->{if(!loaded||held||draining||closed)return;requests.expire();tags.prune();if(saving==null&&!tags.pending().isEmpty())flush();},20,20);
        for(String name:List.of("tpa","tpaccept","tpdeny","tpcancel")){
            var command=Objects.requireNonNull(plugin.getCommand(name));
            command.setExecutor((sender,c,label,args)->{if(sender instanceof Player p)command(p,name,args);else sender.sendMessage("TPA는 접속 중인 플레이어만 사용할 수 있습니다.");return true;});
            command.setTabCompleter((sender,c,label,args)->sender instanceof Player p&&name.equals("tpa")&&args.length==1?Bukkit.getOnlinePlayers().stream().filter(q->q!=p&&p.canSee(q)&&boundary.current(q,boundary.token(q))).map(Player::getName).filter(n->n.regionMatches(true,0,args[0],0,args[0].length())).sorted().toList():List.of());
        }
    }
    public CompletionStage<Void> ready(){return store.load(System.currentTimeMillis()).thenAcceptAsync(saved->{tags.restore(saved);loaded=true;},owner).whenCompleteAsync((v,error)->{if(error!=null)hold(error);},owner);}
    private void hold(Throwable error){if(closed)return;held=true;plugin.getLogger().log(java.util.logging.Level.SEVERE,"HEARTH_COMBAT_TRAVEL_HOLD",error);boundary.hold();}
    public boolean canTravel(UUID player){return loaded&&!held&&!draining&&!closed&&!tags.blocked(player);}
    public String reason(UUID player){return !loaded||held||draining||closed?"전투·이동 정보를 확인 중입니다.":"전투 종료까지 "+tags.remainingSeconds(player)+"초 · 텔레포트를 사용할 수 없습니다.";}
    public CompletionStage<Void> flush(){
        if(saving!=null)return saving;
        var batch=new HashMap<UUID,Long>();tags.pending().entrySet().stream().limit(256).forEach(e->batch.put(e.getKey(),e.getValue()));
        if(batch.isEmpty())return CompletableFuture.completedFuture(null);
        var result=new CompletableFuture<Void>();saving=result;
        store.save(batch).whenCompleteAsync((v,error)->{saving=null;if(error==null){tags.acknowledge(batch);result.complete(null);}else{hold(error);result.completeExceptionally(error);}},owner);return result;
    }
    public CompletionStage<Void> drain(){draining=true;requests.close();return flush().thenComposeAsync(v->tags.pending().isEmpty()?CompletableFuture.completedFuture(null):drain(),owner);}
    private Player actor(Entity entity){if(entity instanceof Player p)return p;if(entity instanceof Projectile projectile&&projectile.getShooter() instanceof Player p)return p;if(entity instanceof TNTPrimed tnt&&tnt.getSource() instanceof Player p)return p;if(entity instanceof Tameable tame&&tame.getOwner() instanceof Player p)return p;return null;}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void damage(EntityDamageByEntityEvent event){
        if(!loaded||held||draining||closed||event.getFinalDamage()<=0||!(event.getEntity() instanceof LivingEntity))return;
        Player attacker=actor(event.getDamager()),victim=event.getEntity() instanceof Player p?p:null;
        if(attacker==victim||!pve&&(attacker==null||victim==null))return;
        if(attacker!=null&&!boundary.current(attacker,boundary.token(attacker)))attacker=null;
        if(victim!=null&&!boundary.current(victim,boundary.token(victim)))victim=null;
        if(attacker!=null||victim!=null)tags.hit(attacker==null?null:attacker.getUniqueId(),victim==null?null:victim.getUniqueId());
    }
    private boolean usable(Player p){return p!=null&&boundary.current(p,boundary.token(p))&&boundary.available(p)&&!p.isDead()&&!p.isInsideVehicle()&&p.getPassengers().isEmpty()&&canTravel(p.getUniqueId());}
    private void say(Player p,String message){if(p!=null&&p.isOnline())p.sendMessage(Component.text(message));}
    private void command(Player p,String name,String[] args){
        if(!loaded||held||draining||closed){say(p,reason(p.getUniqueId()));return;}
        if(name.equals("tpcancel")||name.equals("tpdeny")){if(args.length>1||name.equals("tpcancel")&&args.length!=0){say(p,"/"+name);return;}var found=name.equals("tpcancel")?requests.outgoing(p.getUniqueId()):requests.incoming(p.getUniqueId());if(found.isPresent()&&args.length==1&&!found.get().id().toString().equals(args[0])){say(p,"이 요청은 만료되었거나 바뀌었습니다.");return;}if(found.isPresent()&&requests.cancel(p.getUniqueId(),found.get().id())){say(p,"TPA 요청을 취소했습니다.");say(Bukkit.getPlayer(name.equals("tpcancel")?found.get().recipient():found.get().sender()),"TPA 요청이 취소되었습니다.");}else say(p,"대기 중인 TPA 요청이 없습니다.");return;}
        if(!usable(p)){say(p,canTravel(p.getUniqueId())?"접속·거래·이동·탑승 처리가 끝난 뒤 다시 이용하세요.":reason(p.getUniqueId()));return;}
        if(name.equals("tpa")){
            if(args.length!=1){say(p,"/tpa <플레이어>");return;}var target=Bukkit.getPlayerExact(args[0]);
            if(target==null||!p.canSee(target)||!target.canSee(p)||!usable(target)||!boundary.admitted(p.getUniqueId(),target.getLocation())){say(p,"대상이 접속 중이며 전투·이동·입장 조건을 충족해야 합니다.");return;}
            try{var created=requests.request(p.getUniqueId(),target.getUniqueId(),boundary.token(p),boundary.token(target));say(p,target.getName()+"님에게 TPA를 요청했습니다. /tpcancel");target.sendMessage(Component.text(p.getName()+"님의 TPA 요청 · ").append(Component.text("[수락]").clickEvent(ClickEvent.runCommand("/tpaccept "+created.id()))).append(Component.text(" ")).append(Component.text("[거절]").clickEvent(ClickEvent.runCommand("/tpdeny "+created.id()))));}catch(IllegalStateException busy){say(p,"본인 또는 상대에게 처리 중인 TPA 요청이 있습니다.");}return;
        }
        if(args.length>1){say(p,"/tpaccept");return;}var found=requests.incoming(p.getUniqueId());if(found.isEmpty()){say(p,"유효한 TPA 요청이 없습니다.");return;}if(args.length==1&&!found.get().id().toString().equals(args[0])){say(p,"이 요청은 만료되었거나 바뀌었습니다.");return;}var sender=Bukkit.getPlayer(found.get().sender());
        if(!usable(sender)||!boundary.current(p,found.get().recipientSession())||!boundary.current(sender,found.get().senderSession())){say(p,"접속 또는 전투·이동 상태가 바뀌었습니다.");return;}
        var destination=p.getLocation().clone();
        if(!boundary.admitted(sender.getUniqueId(),destination)||!SurvivalWorlds.safe(destination)){say(p,"요청자가 입장할 수 있는 안전한 위치에서 수락해 주세요.");return;}
        var accepted=requests.accept(p.getUniqueId(),found.get().id(),boundary.token(sender),boundary.token(p),true);if(accepted.isEmpty())return;var request=accepted.get();
        BooleanSupplier guard=()->requests.current(request)&&boundary.current(sender,request.senderSession())&&boundary.current(p,request.recipientSession())&&!p.isDead()&&boundary.available(p)&&!p.isInsideVehicle()&&p.getPassengers().isEmpty()&&p.canSee(sender)&&sender.canSee(p)&&canTravel(sender.getUniqueId())&&canTravel(p.getUniqueId())&&p.getWorld()==destination.getWorld()&&p.getLocation().distanceSquared(destination)<=4&&boundary.admitted(sender.getUniqueId(),destination)&&boundary.admitted(p.getUniqueId(),p.getLocation());
        say(p,"TPA를 수락했습니다.");
        try{boundary.move(sender,request.senderSession(),destination,guard).whenCompleteAsync((ok,error)->{requests.complete(request,error!=null);say(p,error!=null?"TPA 결과 확인이 필요합니다. 다시 수락하지 마세요.":Boolean.TRUE.equals(ok)?"TPA 이동을 완료했습니다.":"상태·권한이 바뀌어 이동하지 않았습니다.");},owner);}catch(Exception error){requests.complete(request,true);say(p,"TPA 이동을 보류했습니다. 상태를 확인해 주세요.");}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void login(AsyncPlayerPreLoginEvent event){if(event.getLoginResult()==AsyncPlayerPreLoginEvent.Result.ALLOWED&&!canTravel(event.getUniqueId()))event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,Component.text("이동·재접속 보호 대기 중입니다. "+tags.remainingSeconds(event.getUniqueId())+"초 후 다시 접속해 주세요."));}
    @EventHandler public void quit(PlayerQuitEvent e){requests.invalidate(e.getPlayer().getUniqueId());if(loaded&&!held&&!closed)flush();}
    @EventHandler public void death(PlayerDeathEvent e){requests.invalidate(e.getEntity().getUniqueId());}
    public void close(){closed=true;pulse.cancel();requests.close();HandlerList.unregisterAll(this);}
}
