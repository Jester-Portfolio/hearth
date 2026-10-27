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
    public record Link(ServerProgressAccess.Context context,WorldLifecycle world,Supplier<Location> destination) {}
    private record Entry(Player player,PlayerSessions.Session session,Link link) {}
    private final JavaPlugin plugin;
    private final PlayerSessions sessions;
    private final Executor owner;
    private final PaperInventoryCodec codec;
    private final Location waiting;
    private final Map<UUID,Link> links=new HashMap<>();
    private final Map<UUID,Entry> entries=new HashMap<>();
    private final Map<UUID,Player> moving=new HashMap<>();
    private final Set<Entry> samples=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<UUID,String> knownNames=new HashMap<>();
    private Consumer<ServerProgressAccess.Context> onlineChanged=c->{};
    private BiPredicate<ServerProgressAccess.Context,Location> entryAccess=(c,l)->true;
    public void onlineChanged(Consumer<ServerProgressAccess.Context> listener){main();onlineChanged=listener;}
    public void entryAccess(BiPredicate<ServerProgressAccess.Context,Location> access){main();entryAccess=access;}
    public Map<UUID,Object> onlineContexts(ServerProgressAccess.Context ctx){main();var found=new HashMap<UUID,Object>();for(var e:entries.values())if(e.player().isOnline()&&e.link().context().storyRunId().equals(ctx.storyRunId()))found.put(e.player().getUniqueId(),e.session());return Map.copyOf(found);}
    private boolean closed;
    private BiPredicate<Player,String> questCommand=(p,c)->false;
    private BiPredicate<Player,Inventory> developerInventory=(p,i)->false;
    public void developerInventory(BiPredicate<Player,Inventory> handler){main();developerInventory=handler;}
    public void questCommand(BiPredicate<Player,String> handler){main();questCommand=handler;}
    public PlayerSessions.Session current(Player p){main();Entry e=entry(p);return allowed(p)?e.session():null;}
    public PaperSessionAdapter(JavaPlugin plugin,PlayerSessions sessions,Executor owner,PaperInventoryCodec codec,Location waiting) {
        this.plugin=plugin;this.sessions=sessions;this.owner=owner;this.codec=codec;this.waiting=waiting.clone();
        Bukkit.getPluginManager().registerEvents(this,plugin);
    }
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
                var result=new CompletableFuture<Boolean>();
                Bukkit.getScheduler().runTaskLater(plugin,new Runnable(){public void run(){
                    if (!connected()) {result.complete(false);return;}
                    if (Bukkit.isTickingWorlds()){Bukkit.getScheduler().runTaskLater(plugin,this,1);return;}
                    moving.put(player.getUniqueId(),player);
                    try {
                        player.teleportAsync(link.destination().get()).whenComplete((ok,error) -> owner.execute(() -> {
                            moving.remove(player.getUniqueId(),player);
                            if(error!=null)result.completeExceptionally(error);
                            else result.complete(Boolean.TRUE.equals(ok) && connected() && player.getWorld().getUID().equals(link.world().mapping().physicalId()));
                        }));
                    } catch (RuntimeException error) {moving.remove(player.getUniqueId(),player);result.completeExceptionally(error);}
                }},1);
                return result;
            }
        });
        var entry=new Entry(player,session,link);entries.put(player.getUniqueId(),entry);onlineChanged.accept(link.context());
        session.ready().whenComplete((v,error) -> owner.execute(() -> {
            if (entries.get(player.getUniqueId())!=entry || !player.isOnline()) return;
            if (error!=null) player.kick(Component.text("격리 세션 준비 실패: "+session.error()));
            else player.sendMessage(Component.text("격리 스토리 진행 복원 완료"));
        }));
    }
    private static void requireNoCursor(Player p){if(!p.getItemOnCursor().isEmpty())throw new IllegalStateException("Unsupported cursor state");}
    @EventHandler public void quit(PlayerQuitEvent e){
        Entry entry=entries.get(e.getPlayer().getUniqueId());
        if(entry==null || entry.player()!=e.getPlayer())return;
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
    private boolean allowed(Player p){Entry e=entry(p);return e!=null && sessions.playable(e.session()) && p.getWorld().getKey().toString().equals(e.link().world().mapping().worldKey());}
    private void sample(Player p){Entry e=entry(p);if(e==null || !samples.add(e))return;Bukkit.getScheduler().runTaskLater(plugin,() -> {
        samples.remove(e);if(entries.get(p.getUniqueId())==e)sessions.refresh(e.session());
    },1);}
    @EventHandler(priority=EventPriority.HIGHEST) public void move(PlayerMoveEvent e){
        if(!(e instanceof PlayerTeleportEvent)&&(!allowed(e.getPlayer())||e.getTo()!=null&&!canEnter(e.getPlayer(),e.getTo())))e.setTo(e.getFrom());
    }
    private boolean canEnter(Player p,Location target){var e=entry(p);return e!=null&&target.getWorld()!=null&&target.getWorld().getUID().equals(e.link().world().mapping().physicalId())&&entryAccess.test(e.link().context(),target);}
    @EventHandler(priority=EventPriority.HIGHEST) public void teleport(PlayerTeleportEvent e){if(moving.get(e.getPlayer().getUniqueId())!=e.getPlayer()||e.getTo()==null||!canEnter(e.getPlayer(),e.getTo()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void open(InventoryOpenEvent e){if(e.getPlayer() instanceof Player p && e.getInventory().getType()!=InventoryType.CRAFTING && e.getInventory().getType()!=InventoryType.PLAYER && (!allowed(p) || !developerInventory.test(p,e.getInventory())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e){if(e.getWhoClicked() instanceof Player)e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof Player)e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEvent e){
        if(!allowed(e.getPlayer()) || e.getClickedBlock()!=null && e.getClickedBlock().getType().isInteractable())e.setCancelled(true);
        sample(e.getPlayer());
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void place(BlockPlaceEvent e){if(!allowed(e.getPlayer()))e.setCancelled(true);sample(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void breakBlock(BlockBreakEvent e){if(!allowed(e.getPlayer()))e.setCancelled(true);sample(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void drop(PlayerDropItemEvent e){if(!allowed(e.getPlayer()))e.setCancelled(true);sample(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void pickup(EntityPickupItemEvent e){if(e.getEntity() instanceof Player p){if(!allowed(p))e.setCancelled(true);sample(p);}}
    @EventHandler(priority=EventPriority.HIGHEST) public void consume(PlayerItemConsumeEvent e){if(!allowed(e.getPlayer()))e.setCancelled(true);sample(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void swap(PlayerSwapHandItemsEvent e){if(!allowed(e.getPlayer()))e.setCancelled(true);sample(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void damageItem(PlayerItemDamageEvent e){if(!allowed(e.getPlayer()))e.setCancelled(true);sample(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void command(PlayerCommandPreprocessEvent e){boolean denied=e.isCancelled();e.setCancelled(true);if(!denied && allowed(e.getPlayer()))questCommand.test(e.getPlayer(),e.getMessage());}
    @EventHandler(priority=EventPriority.HIGHEST) public void damage(EntityDamageEvent e){if(e.getEntity() instanceof Player)e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void attack(EntityDamageByEntityEvent e){if(e.getDamager() instanceof Player)e.setCancelled(true);}
    @Override public void close(){main();closed=true;HandlerList.unregisterAll(this);}
}
