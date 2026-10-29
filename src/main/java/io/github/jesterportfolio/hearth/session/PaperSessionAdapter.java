package io.github.jesterportfolio.hearth.session;

import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.exploration.CommunityBoundary;
import io.github.jesterportfolio.hearth.lifecycle.WorldLifecycle;
import io.github.jesterportfolio.hearth.persistence.ServerProgressAccess;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

/** 명시적으로 활성화한 격리 fixture 전용. 실제 계정이 없으면 이벤트 경로는 미검증입니다. */
public final class PaperSessionAdapter implements Listener,AutoCloseable {
    public interface CombatControls {
        default boolean actions(Player p){return true;}default boolean movement(Player p){return true;}
        default boolean damage(Player p,EntityDamageEvent e){return false;}default boolean attack(Player p,Entity target){return false;}
        default boolean command(Player p,String line){return false;}
        default Location destination(Player p,ServerProgressAccess.Context ctx,Location original){return original;}
        default void ready(Player p,PlayerSessions.Session s){}default void leaving(Player p,PlayerSessions.Session s){}
    }
    private record Move(Player player,BooleanSupplier valid) {}
    public record Link(ServerProgressAccess.Context context,WorldLifecycle world,Supplier<Location> destination) {}
    private record Entry(Player player,PlayerSessions.Session session,Link link) {}
    private final JavaPlugin plugin;
    private final PlayerSessions sessions;
    private final Executor owner;
    private final PaperInventoryCodec codec;
    private final Location waiting;
    private final Map<UUID,Link> links=new HashMap<>();
    private final Map<UUID,Entry> entries=new HashMap<>();
    private final Map<UUID,Move> moving=new HashMap<>();
    private final Set<CompletableFuture<Boolean>> combatMoves=new HashSet<>();
    private CombatControls combat=new CombatControls(){};
    public void combat(CombatControls controls){main();combat=Objects.requireNonNull(controls);}
    private final Set<Entry> samples=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<UUID,String> knownNames=new HashMap<>();
    private final Map<UUID,Long> interactionHints=new HashMap<>();
    private Consumer<ServerProgressAccess.Context> onlineChanged=c->{};
    private BiPredicate<ServerProgressAccess.Context,Location> entryAccess=(c,l)->true;
    public void onlineChanged(Consumer<ServerProgressAccess.Context> listener){main();onlineChanged=listener;}
    public void entryAccess(BiPredicate<ServerProgressAccess.Context,Location> access){main();entryAccess=access;}
    public Map<UUID,Object> onlineContexts(ServerProgressAccess.Context ctx){main();var found=new HashMap<UUID,Object>();for(var e:entries.values())if(e.player().isOnline()&&e.link().context().storyRunId().equals(ctx.storyRunId()))found.put(e.player().getUniqueId(),e.session());return Map.copyOf(found);}
    private boolean closed;
    private BiPredicate<Player,String> questCommand=(p,c)->false;
    private BiPredicate<Player,Inventory> developerInventory=(p,i)->false;
    private BiPredicate<Player,Entity> fieldAttack=(p,e)->false;
    public void fieldAttack(BiPredicate<Player,Entity> allowed){main();fieldAttack=Objects.requireNonNull(allowed);}
    public void developerInventory(BiPredicate<Player,Inventory> handler){main();developerInventory=handler;}
    public void questCommand(BiPredicate<Player,String> handler){main();questCommand=handler;}
    public PlayerSessions.Session current(Player p){main();Entry e=entry(p);return allowed(p)?e.session():null;}
    public PlayerSessions.Session linked(Player p){main();var e=entry(p);return e==null?null:e.session();}
    public CompletionStage<Void> saveBoundary(Player player,PlayerSessions.Session session){main();if(prepared(player)!=session)return CompletableFuture.failedFuture(new IllegalStateException("STALE_SESSION"));return sessions.saveBoundary(session);}
    public CompletionStage<Void> saveRunBoundary(UUID run){main();return sessions.saveRunBoundary(run);}
    public PlayerSessions.Session prepared(Player p){main();var e=entry(p);return baseReady(p)?e.session():null;}
    public PaperSessionAdapter(JavaPlugin plugin,PlayerSessions sessions,Executor owner,PaperInventoryCodec codec,Location waiting) {
        this.plugin=plugin;this.sessions=sessions;this.owner=owner;this.codec=codec;this.waiting=waiting.clone();
        Bukkit.getPluginManager().registerEvents(this,plugin);
    }
    public CompletionStage<Void> reward(PlayerSessions.Session s,io.github.jesterportfolio.hearth.quest.QuestRewards rewards,String quest){main();return rewards.claim(sessions,s,quest);}
    public void fixture(Link link) {
        main();if (links.putIfAbsent(link.context().playerId(),link)!=null) throw new IllegalStateException("Fixture link already exists");
    }
    /** 현재 서버가 검증한 연결 범위입니다. 저장된 전체 구성원 명단으로 취급하지 않습니다. */
    public List<CommunityBoundary.Ref> community(ServerProgressAccess.Context ctx){
        main();return links.values().stream().filter(l->l.context().storyRunId().equals(ctx.storyRunId())).map(l->{
            UUID id=l.context().playerId();var e=entries.get(id);return new CommunityBoundary.Ref(l.context().storyRunId(),id,knownNames.get(id),e!=null&&e.player().isOnline());
        }).toList();
    }
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Not main thread");}
    @EventHandler public void spawn(AsyncPlayerSpawnLocationEvent e) {
        // 비동기에서는 이벤트 값만 설정합니다. 장소는 main에서 준비한 시험 전용 스냅샷입니다.
        e.setSpawnLocation(waiting.clone());
    }
    @EventHandler public void joined(PlayerJoinEvent e) {
        try { connectPlayer(e.getPlayer()); } catch (RuntimeException error) { e.getPlayer().kick(Component.text("격리 세션 연결 거부"));plugin.getLogger().warning("HEARTH_SESSION_CONNECT_REJECTED"); }
    }
    private void connectPlayer(Player player) {
        knownNames.put(player.getUniqueId(),player.getName());
        Link link=links.get(player.getUniqueId());
        if (closed || link==null || !Bukkit.getOnlineMode() || !player.isWhitelisted()) { player.kick(Component.text("준비된 격리 시험 프로필이 필요합니다."));return; }
        var session=sessions.connect(link.context(),link.world(),new PlayerSessions.Port(){
            public boolean connected(){main();return !closed && player.isOnline() && Bukkit.getPlayer(player.getUniqueId())==player;}
            public InventoryPayload capture(){main();requireNoCursor(player);return codec.capture(player.getInventory());}
            public void apply(InventoryPayload value){main();requireNoCursor(player);codec.apply(player.getInventory(),value);}
            public CompletionStage<Boolean> move(){
                long generation=link.world().generation();
                var result=new CompletableFuture<Boolean>();
                Bukkit.getScheduler().runTaskLater(plugin,new Runnable(){public void run(){
                    if (!connected()) {result.complete(false);return;}
                    if (Bukkit.isTickingWorlds()){Bukkit.getScheduler().runTaskLater(plugin,this,1);return;}
                    var permit=new Move(player,()->connected()&&link.world().generation()==generation);moving.put(player.getUniqueId(),permit);
                    try {
                        player.teleportAsync(combat.destination(player,link.context(),link.destination().get())).whenComplete((ok,error) -> owner.execute(() -> {
                            moving.remove(player.getUniqueId(),permit);
                            if(error!=null)result.completeExceptionally(error);
                            else result.complete(Boolean.TRUE.equals(ok) && connected() && player.getWorld().getUID().equals(link.world().mapping().physicalId()));
                        }));
                    } catch (RuntimeException error) {moving.remove(player.getUniqueId(),permit);result.completeExceptionally(error);}
                }},1);
                return result;
            }
        });
        var entry=new Entry(player,session,link);entries.put(player.getUniqueId(),entry);onlineChanged.accept(link.context());
        session.ready().whenComplete((v,error) -> owner.execute(() -> {
            if (entries.get(player.getUniqueId())!=entry || !player.isOnline()) return;
            if (error!=null) player.kick(Component.text("격리 세션 준비 실패: "+session.error()));
            else {try{combat.ready(player,session);}catch(RuntimeException failure){player.kick(Component.text("전투 복귀 준비 보류"));plugin.getLogger().warning("HEARTH_COMBAT_READY_HOLD");return;}player.sendMessage(Component.text("격리 스토리 진행 복원 완료"));player.sendMessage(Component.text("조작 안내: /hearthplay help"));}
        }));
    }
    private static void requireNoCursor(Player p){if(!p.getItemOnCursor().isEmpty())throw new IllegalStateException("Unsupported cursor state");}
    @EventHandler public void quit(PlayerQuitEvent e){
        interactionHints.remove(e.getPlayer().getUniqueId());
        Entry entry=entries.get(e.getPlayer().getUniqueId());
        if(entry==null || entry.player()!=e.getPlayer())return;
        try{combat.leaving(e.getPlayer(),entry.session());}catch(RuntimeException error){plugin.getLogger().warning("HEARTH_COMBAT_QUIT_HOLD");}
        entries.remove(e.getPlayer().getUniqueId(),entry);onlineChanged.accept(entry.link().context());
        sessions.disconnect(entry.session()).whenComplete((v,error) -> plugin.getLogger().info("HEARTH_SESSION_QUIT "+(error==null ? "SAVED" : "HOLD")));
    }
    public String status(UUID player){
        main();Entry e=entries.get(player);
        if(e==null)return "OFFLINE";
        var slot=e.player().getInventory().getItem(0);var save=e.session().save();
        return "state="+e.session().state()+" playable="+allowed(e.player())+" slot0="+(slot==null?0:slot.getAmount())
            +" revision="+(save==null?-1:save.revision())+" dirty="+(save!=null && save.dirty());
    }
    private Entry entry(Player p){Entry e=entries.get(p.getUniqueId());return e!=null && e.player()==p ? e : null;}
    private boolean baseReady(Player p){Entry e=entry(p);return e!=null && sessions.playable(e.session()) && p.getWorld().getKey().toString().equals(e.link().world().mapping().worldKey());}
    private boolean allowed(Player p){return baseReady(p)&&combat.actions(p);}
    private void sample(Player p){Entry e=entry(p);if(e==null || !samples.add(e))return;Bukkit.getScheduler().runTaskLater(plugin,() -> {
        samples.remove(e);if(entries.get(p.getUniqueId())==e)sessions.refresh(e.session());
    },1);}
    @EventHandler(priority=EventPriority.HIGHEST) public void move(PlayerMoveEvent e){
        if(!(e instanceof PlayerTeleportEvent)&&(!baseReady(e.getPlayer())||!combat.movement(e.getPlayer())||e.getTo()!=null&&!canEnter(e.getPlayer(),e.getTo())))e.setTo(e.getFrom());
    }
    private boolean canEnter(Player p,Location target){var e=entry(p);return e!=null&&target.getWorld()!=null&&target.getWorld().getUID().equals(e.link().world().mapping().physicalId())&&entryAccess.test(e.link().context(),target);}
    @EventHandler(priority=EventPriority.HIGHEST) public void teleport(PlayerTeleportEvent e){var permit=moving.get(e.getPlayer().getUniqueId());if(permit==null||permit.player()!=e.getPlayer()||!permit.valid().getAsBoolean()||e.getTo()==null||!canEnter(e.getPlayer(),e.getTo()))e.setCancelled(true);}
    /** 동기 DB 대기 없이 기존 허가 경로로 이동합니다. 실제 이벤트 시점에도 세션/작업을 다시 검사합니다. */
    public CompletionStage<Boolean> combatMove(Player p,PlayerSessions.Session session,Location target,BooleanSupplier valid){
        main();var result=new CompletableFuture<Boolean>();combatMoves.add(result);var permit=new Move(p,()->!result.isDone()&&prepared(p)==session&&valid.getAsBoolean());
        var timeout=Bukkit.getScheduler().runTaskLater(plugin,()->result.completeExceptionally(new IllegalStateException("COMBAT_MOVE_TIMEOUT")),200);
        result.whenComplete((v,error)->{if(Bukkit.isPrimaryThread()){moving.remove(p.getUniqueId(),permit);combatMoves.remove(result);timeout.cancel();}else owner.execute(()->{moving.remove(p.getUniqueId(),permit);combatMoves.remove(result);timeout.cancel();});});
        Bukkit.getScheduler().runTaskLater(plugin,new Runnable(){public void run(){if(result.isDone())return;if(closed||!permit.valid().getAsBoolean()){result.complete(false);return;}if(Bukkit.isTickingWorlds()){Bukkit.getScheduler().runTaskLater(plugin,this,1);return;}if(moving.putIfAbsent(p.getUniqueId(),permit)!=null){result.completeExceptionally(new IllegalStateException("COMBAT_MOVE_BUSY"));return;}try{p.teleportAsync(target.clone()).whenComplete((ok,error)->owner.execute(()->{if(result.isDone())return;if(error!=null)result.completeExceptionally(error);else result.complete(Boolean.TRUE.equals(ok)&&permit.valid().getAsBoolean());}));}catch(RuntimeException e){result.completeExceptionally(e);}}},1);return result;
    }
    public void combatCamera(Player p,PlayerSessions.Session session,BooleanSupplier valid,Runnable action){main();var permit=new Move(p,()->prepared(p)==session&&valid.getAsBoolean());if(!permit.valid().getAsBoolean()||moving.putIfAbsent(p.getUniqueId(),permit)!=null)throw new IllegalStateException("COMBAT_CAMERA_STALE_OR_BUSY");try{action.run();}finally{moving.remove(p.getUniqueId(),permit);}}
    @EventHandler(priority=EventPriority.HIGHEST) public void open(InventoryOpenEvent e){if(e.getPlayer() instanceof Player p && e.getInventory().getType()!=InventoryType.CRAFTING && e.getInventory().getType()!=InventoryType.PLAYER && (!allowed(p) || !developerInventory.test(p,e.getInventory())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p))return;
        boolean ownView=e.getView().getType()==InventoryType.CRAFTING && p.getGameMode()!=GameMode.CREATIVE;
        var request=new PlayerInteractionRules.InventoryRequest(allowed(p),ownView,e.getClickedInventory()==p.getInventory(),p.getItemOnCursor().isEmpty(),e.getSlot(),e.getClick().name(),e.getAction().name(),e.getHotbarButton());
        if(!PlayerInteractionRules.inventory(request)){
            e.setCancelled(true);if(allowed(p)&&ownView)hint(p);
        }else if(!e.isCancelled())sample(p);
    }
    private void hint(Player p){long now=System.nanoTime();Long previous=interactionHints.get(p.getUniqueId());if(previous!=null&&now-previous<5_000_000_000L)return;interactionHints.put(p.getUniqueId(),now);p.sendMessage(Component.text("격리 모드에서는 커서 없는 슬롯 조작만 지원합니다. /hearthplay help"));}
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof Player)e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEvent e){
        if(!allowed(e.getPlayer()) || e.getClickedBlock()!=null && e.getClickedBlock().getType().isInteractable() && !PlayerInteractionRules.facility(e.getClickedBlock().getType().name()))e.setCancelled(true);
        sample(e.getPlayer());
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void place(BlockPlaceEvent e){if(!allowed(e.getPlayer()))e.setCancelled(true);sample(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void breakBlock(BlockBreakEvent e){if(!allowed(e.getPlayer()))e.setCancelled(true);sample(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void drop(PlayerDropItemEvent e){if(!allowed(e.getPlayer()))e.setCancelled(true);sample(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void pickup(EntityPickupItemEvent e){if(e.getEntity() instanceof Player p){if(!allowed(p))e.setCancelled(true);sample(p);}}
    @EventHandler(priority=EventPriority.HIGHEST) public void consume(PlayerItemConsumeEvent e){if(!allowed(e.getPlayer()))e.setCancelled(true);sample(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void swap(PlayerSwapHandItemsEvent e){if(!allowed(e.getPlayer()))e.setCancelled(true);sample(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void damageItem(PlayerItemDamageEvent e){if(!allowed(e.getPlayer()))e.setCancelled(true);sample(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void command(PlayerCommandPreprocessEvent e){boolean denied=e.isCancelled();e.setCancelled(true);if(!denied&&prepared(e.getPlayer())!=null&&combat.command(e.getPlayer(),e.getMessage()))return;if(!denied && allowed(e.getPlayer())){if(Set.of("/hearthplay","/hearthplay help").contains(e.getMessage()))PlayerInteractionRules.help().forEach(line->e.getPlayer().sendMessage(Component.text(line)));else questCommand.test(e.getPlayer(),e.getMessage());}}
    @EventHandler(priority=EventPriority.HIGHEST) public void damage(EntityDamageEvent e){if(e.getEntity() instanceof Player p&&!combat.damage(p,e))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void attack(EntityDamageByEntityEvent e){if(e.getDamager() instanceof Player p&&(!allowed(p)||!fieldAttack.test(p,e.getEntity())&&!combat.attack(p,e.getEntity())))e.setCancelled(true);}
    @Override public void close(){main();closed=true;for(var move:List.copyOf(combatMoves))move.completeExceptionally(new IllegalStateException("COMBAT_MOVE_CLOSED"));moving.clear();interactionHints.clear();HandlerList.unregisterAll(this);}
}
