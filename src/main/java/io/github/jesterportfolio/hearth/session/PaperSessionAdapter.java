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
import org.bukkit.inventory.*;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import org.bukkit.plugin.java.JavaPlugin;

/** 명시적으로 활성화한 격리 fixture 전용. 실제 계정이 없으면 이벤트 경로는 미검증입니다. */
public final class PaperSessionAdapter implements Listener,AutoCloseable {
    public interface CombatControls {
        default boolean actions(Player p){return true;}default boolean movement(Player p){return true;}
        default boolean damage(Player p,EntityDamageEvent e){return false;}default boolean attack(Player p,Entity target){return false;}
        default boolean command(Player p,String line){return false;}default boolean travel(Player p){return actions(p);}
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
    private final TemporaryInventory temporary;
    private record Window(PlayerSessions.Session session,Inventory inventory,UUID world,long generation,InventoryWindowRules.Kind kind){}
    private final Map<Player,Window> windows=new IdentityHashMap<>();

    private final Map<UUID,Link> links=new HashMap<>();
    private final Map<UUID,Entry> entries=new HashMap<>();
    private final Map<UUID,Move> moving=new HashMap<>();
    private final Set<PlayerSessions.Session> travelFrozen=Collections.newSetFromMap(new IdentityHashMap<>());
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
    private BiPredicate<Player,Location> gatheringEntry=(p,l)->true;private BiPredicate<Player,org.bukkit.block.Block> gatheringChange=(p,b)->true;private BiPredicate<Player,String> gatheringCommand=(p,c)->false;
    public void gathering(BiPredicate<Player,Location> entry,BiPredicate<Player,org.bukkit.block.Block> change,BiPredicate<Player,String> command){main();gatheringEntry=entry;gatheringChange=change;gatheringCommand=command;}
    private BiPredicate<Player,Inventory> gatheringInventory=(p,i)->false;
    public void gatheringInventory(BiPredicate<Player,Inventory> handler){main();gatheringInventory=handler;}
    public void gatheringHold(PlayerSessions.Session session){main();sessions.inventoryHold(session,"GATHERING_VISIT_HOLD");}
    private boolean closed;
    private BiPredicate<Player,String> questCommand=(p,c)->false;
    private BiPredicate<Player,Inventory> developerInventory=(p,i)->false;
    private BiPredicate<Player,Inventory> craftingInventory=(p,i)->false;
    private BiPredicate<Player,org.bukkit.block.Block> craftingFacility=(p,b)->false;
    public void crafting(BiPredicate<Player,Inventory> inventory,BiPredicate<Player,org.bukkit.block.Block> facility){main();craftingInventory=inventory;craftingFacility=facility;}
    public void crafted(Player p,PlayerSessions.Session s){main();if(current(p)!=s)throw new IllegalStateException("CRAFT_STALE");sessions.refresh(s);if(current(p)!=s)throw new IllegalStateException("CRAFT_HOLD");}
    public void craftingHold(PlayerSessions.Session s){main();sessions.inventoryHold(s,"CRAFT_APPLY_HOLD");}
    private BiPredicate<Player,Inventory> travelInventory=(p,i)->false;
    public void travelInventory(BiPredicate<Player,Inventory> handler){main();travelInventory=handler;}
    private BiPredicate<Player,Entity> fieldAttack=(p,e)->false;
    public void fieldAttack(BiPredicate<Player,Entity> allowed){main();fieldAttack=Objects.requireNonNull(allowed);}
    public void developerInventory(BiPredicate<Player,Inventory> handler){main();developerInventory=handler;}
    public void questCommand(BiPredicate<Player,String> handler){main();questCommand=handler;}
    public PlayerSessions.Session current(Player p){main();Entry e=entry(p);return allowed(p)?e.session():null;}
    public PlayerSessions.Session linked(Player p){main();var e=entry(p);return e==null?null:e.session();}
    public boolean travelEligible(Player p,PlayerSessions.Session session){main();return prepared(p)==session&&combat.travel(p);}
    public void freezeTravel(Player p,PlayerSessions.Session session,boolean frozen){main();if(!frozen){travelFrozen.remove(session);return;}if(!travelEligible(p,session)||moving.containsKey(p.getUniqueId())||!travelFrozen.add(session))throw new IllegalStateException("TRAVEL_LOCK_BUSY");}
    public CompletionStage<Void> saveBoundary(Player player,PlayerSessions.Session session){main();if(prepared(player)!=session)return CompletableFuture.failedFuture(new IllegalStateException("STALE_SESSION"));return sessions.saveBoundary(session);}
    public CompletionStage<Void> saveRunBoundary(UUID run){main();return sessions.saveRunBoundary(run);}
    public PlayerSessions.Session prepared(Player p){main();var e=entry(p);return baseReady(p)?e.session():null;}
    public PaperSessionAdapter(JavaPlugin plugin,PlayerSessions sessions,Executor owner,PaperInventoryCodec codec,Location waiting) {
        this.plugin=plugin;this.sessions=sessions;this.owner=owner;this.codec=codec;this.temporary=new TemporaryInventory(codec);this.waiting=waiting.clone();
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
            public InventoryPayload capture(){main();return temporary.capture(temporaryPort(player,player.getOpenInventory().getTopInventory()));}
            public void settle(){settleBoundary(player);}
            public void apply(InventoryPayload value){main();requireNoCursor(player);if(Arrays.stream(PaperInventoryWindow.matrix(player.getOpenInventory().getTopInventory())).anyMatch(i->i!=null&&!i.isEmpty()))throw new IllegalStateException("UNSETTLED_CRAFTING_MATRIX");codec.apply(player.getInventory(),value);}
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
    private TemporaryInventory.Port temporaryPort(Player p,Inventory top){return new TemporaryInventory.Port(){
        public ItemStack[] slots(){return PaperInventoryWindow.slots(p.getInventory());}public ItemStack cursor(){return p.getItemOnCursor();}public ItemStack[] matrix(){return PaperInventoryWindow.matrix(top);}public void apply(InventoryPayload value){codec.apply(p.getInventory(),value);}public void cursor(ItemStack value){p.setItemOnCursor(value);}public void clearMatrix(){if(top instanceof CraftingInventory c){c.setMatrix(new ItemStack[c.getMatrix().length]);c.setResult(null);}}
    };}
    private void settleCursor(Player player){main();temporary.settle(temporaryPort(player,player.getOpenInventory().getTopInventory()));}
    private void settleBoundary(Player player){
        settleCursor(player);var top=player.getOpenInventory().getTopInventory();
        if(top instanceof CraftingInventory&&top.getType()==InventoryType.WORKBENCH||!WorldInventoryAccess.blocks(top).isEmpty())player.closeInventory();
    }
    private void temporaryHold(Player p){var e=entry(p);if(e!=null)sessions.inventoryHold(e.session(),"TEMPORARY_RETURN_HOLD");}
    private boolean nativeAccess(Player p,Location location,boolean container){
        var e=entry(p);if(e==null||location.getWorld()==null||!location.getWorld().getUID().equals(e.link().world().mapping().physicalId())||location.distanceSquared(p.getLocation())>64||!entryAccess.test(e.link().context(),location)||!gatheringEntry.test(p,location))return false;
        var platform=WorldGuard.getInstance().getPlatform();var local=WorldGuardPlugin.inst().wrapPlayer(p);
        var manager=platform.getRegionContainer().get(BukkitAdapter.adapt(location.getWorld()));
        return platform.getSessionManager().hasBypass(local,BukkitAdapter.adapt(location.getWorld()))||manager!=null&&manager.getApplicableRegions(com.sk89q.worldedit.math.BlockVector3.at(location.getBlockX(),location.getBlockY(),location.getBlockZ())).testState(local,container?Flags.CHEST_ACCESS:Flags.USE,Flags.INTERACT);
    }
    private InventoryWindowRules.Kind nativeKind(Player p,Inventory top){
        if(p.getGameMode()==GameMode.CREATIVE)return InventoryWindowRules.Kind.OTHER;
        if(top instanceof CraftingInventory&&top.getType()==InventoryType.CRAFTING)return InventoryWindowRules.Kind.CRAFTING;
        if(top instanceof CraftingInventory&&top.getType()==InventoryType.WORKBENCH){var location=top.getLocation();if(location!=null&&location.getBlock().getType()==Material.CRAFTING_TABLE&&nativeAccess(p,location,false))return InventoryWindowRules.Kind.WORKBENCH;}
        var e=entry(p);if(e!=null&&WorldInventoryAccess.allowed(e.link().world().mapping().physicalId(),top,location->nativeAccess(p,location,true)))return InventoryWindowRules.Kind.CONTAINER;
        return InventoryWindowRules.Kind.OTHER;
    }
    private InventoryWindowRules.Kind window(Player p,InventoryView view){
        if(!allowed(p)||!view.getBottomInventory().equals(p.getInventory())||!view.getTopInventory().equals(p.getOpenInventory().getTopInventory()))return InventoryWindowRules.Kind.OTHER;var kind=nativeKind(p,view.getTopInventory());
        if(kind==InventoryWindowRules.Kind.CRAFTING)return kind;
        var w=windows.get(p);var e=entry(p);return w!=null&&w.session()==e.session()&&w.inventory().equals(view.getTopInventory())&&w.world().equals(p.getWorld().getUID())&&w.generation()==e.link().world().generation()&&w.kind()==kind?kind:InventoryWindowRules.Kind.OTHER;
    }
    private static void requireNoCursor(Player p){if(!p.getItemOnCursor().isEmpty())throw new IllegalStateException("Unsupported cursor state");}
    @EventHandler public void quit(PlayerQuitEvent e){travelFrozen.remove(linked(e.getPlayer()));
        interactionHints.remove(e.getPlayer().getUniqueId());windows.remove(e.getPlayer());
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
    private boolean allowed(Player p){var e=entry(p);return baseReady(p)&&!travelFrozen.contains(e.session())&&combat.actions(p);}
    private void sample(Player p){Entry e=entry(p);if(e==null || !samples.add(e))return;Bukkit.getScheduler().runTaskLater(plugin,() -> {
        samples.remove(e);if(entries.get(p.getUniqueId())==e)sessions.refresh(e.session());
    },1);}
    @EventHandler(priority=EventPriority.HIGHEST) public void move(PlayerMoveEvent e){
        if(!(e instanceof PlayerTeleportEvent)&&(!baseReady(e.getPlayer())||travelFrozen.contains(linked(e.getPlayer()))||!combat.movement(e.getPlayer())||e.getTo()!=null&&!canEnter(e.getPlayer(),e.getTo())))e.setTo(e.getFrom());
    }
    private boolean canEnter(Player p,Location target){var e=entry(p);return e!=null&&target.getWorld()!=null&&target.getWorld().getUID().equals(e.link().world().mapping().physicalId())&&entryAccess.test(e.link().context(),target)&&gatheringEntry.test(p,target);}
    @EventHandler(priority=EventPriority.HIGHEST) public void teleport(PlayerTeleportEvent e){var permit=moving.get(e.getPlayer().getUniqueId());if(permit==null||permit.player()!=e.getPlayer()||!permit.valid().getAsBoolean()||e.getTo()==null||!canEnter(e.getPlayer(),e.getTo()))e.setCancelled(true);}
    /** 동기 DB 대기 없이 기존 허가 경로로 이동합니다. 실제 이벤트 시점에도 세션/작업을 다시 검사합니다. */
    public CompletionStage<Boolean> combatMove(Player p,PlayerSessions.Session session,Location target,BooleanSupplier valid){
        main();var result=new CompletableFuture<Boolean>();combatMoves.add(result);var permit=new Move(p,()->!result.isDone()&&prepared(p)==session&&valid.getAsBoolean());
        var timeout=Bukkit.getScheduler().runTaskLater(plugin,()->result.completeExceptionally(new IllegalStateException("COMBAT_MOVE_TIMEOUT")),200);
        result.whenComplete((v,error)->{if(Bukkit.isPrimaryThread()){moving.remove(p.getUniqueId(),permit);combatMoves.remove(result);timeout.cancel();}else owner.execute(()->{moving.remove(p.getUniqueId(),permit);combatMoves.remove(result);timeout.cancel();});});
        Bukkit.getScheduler().runTaskLater(plugin,new Runnable(){public void run(){if(result.isDone())return;if(closed||!permit.valid().getAsBoolean()){result.complete(false);return;}if(Bukkit.isTickingWorlds()){Bukkit.getScheduler().runTaskLater(plugin,this,1);return;}if(moving.putIfAbsent(p.getUniqueId(),permit)!=null){result.completeExceptionally(new IllegalStateException("COMBAT_MOVE_BUSY"));return;}try{p.teleportAsync(target.clone()).whenComplete((ok,error)->owner.execute(()->{if(result.isDone())return;if(error!=null)result.completeExceptionally(error);else result.complete(Boolean.TRUE.equals(ok)&&permit.valid().getAsBoolean());}));}catch(RuntimeException e){result.completeExceptionally(e);}}},1);return result;
    }
    public void combatCamera(Player p,PlayerSessions.Session session,BooleanSupplier valid,Runnable action){main();var permit=new Move(p,()->prepared(p)==session&&valid.getAsBoolean());if(!permit.valid().getAsBoolean()||moving.putIfAbsent(p.getUniqueId(),permit)!=null)throw new IllegalStateException("COMBAT_CAMERA_STALE_OR_BUSY");try{action.run();}finally{moving.remove(p.getUniqueId(),permit);}}
    @EventHandler(priority=EventPriority.HIGHEST) public void open(InventoryOpenEvent e){
        if(!(e.getPlayer() instanceof Player p))return;
        if(e.getInventory().getType()!=InventoryType.CRAFTING&&e.getInventory().getType()!=InventoryType.PLAYER){
            if(!allowed(p)||!(nativeKind(p,e.getInventory())!=InventoryWindowRules.Kind.OTHER||developerInventory.test(p,e.getInventory())||travelInventory.test(p,e.getInventory())||craftingInventory.test(p,e.getInventory())||gatheringInventory.test(p,e.getInventory())))e.setCancelled(true);
            else if(!e.isCancelled())try{settleCursor(p);}catch(RuntimeException failure){e.setCancelled(true);temporaryHold(p);}
        }else if(!allowed(p))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void opened(InventoryOpenEvent e){
        if(!(e.getPlayer() instanceof Player p)||!allowed(p))return;var kind=nativeKind(p,e.getInventory());windows.remove(p);
        if(kind!=InventoryWindowRules.Kind.OTHER){var source=entry(p);windows.put(p,new Window(source.session(),e.getInventory(),p.getWorld().getUID(),source.link().world().generation(),kind));}
    }
    @EventHandler(priority=EventPriority.MONITOR) public void inventoryClosed(InventoryCloseEvent e){
        if(e.getPlayer() instanceof Player p){var previous=windows.get(p);if(previous!=null&&previous.inventory().equals(e.getInventory()))windows.remove(p,previous);
            if(allowed(p))try{temporary.settle(temporaryPort(p,e.getInventory()));}catch(RuntimeException failure){temporaryHold(p);}sample(p);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p))return;var kind=window(p,e.getView());var request=new InventoryWindowRules.Request(allowed(p),kind!=InventoryWindowRules.Kind.OTHER,p.getGameMode()==GameMode.CREATIVE,kind,PaperInventoryWindow.slot(e.getView(),e.getClickedInventory(),e.getSlot()),e.getClick().name(),e.getAction().name(),e.getHotbarButton(),p.getItemOnCursor().isEmpty());
        if(!InventoryWindowRules.inventory(request)){e.setCancelled(true);if(allowed(p)&&kind!=InventoryWindowRules.Kind.OTHER)hint(p);return;}
        if(!e.isCancelled())try{PaperInventoryWindow.preview(codec,p,e);sample(p);}catch(RuntimeException full){e.setCancelled(true);hint(p);}
    }
    private void hint(Player p){long now=System.nanoTime();Long previous=interactionHints.get(p.getUniqueId());if(previous!=null&&now-previous<5_000_000_000L)return;interactionHints.put(p.getUniqueId(),now);p.sendMessage(Component.text("현재 화면·권한·보관 공간을 확인하세요. 제작 결과는 한 번씩 받습니다. /hearthplay help"));}
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e){
        if(!(e.getWhoClicked() instanceof Player p))return;var kind=window(p,e.getView());var slots=new ArrayList<InventoryWindowRules.Slot>();
        for(int raw:e.getRawSlots()){if(raw<0||raw>=e.getView().countSlots()){slots.add(InventoryWindowRules.Slot.OUTSIDE);continue;}slots.add(PaperInventoryWindow.slot(e.getView(),e.getView().getInventory(raw),e.getView().convertSlot(raw)));}
        if(!InventoryWindowRules.drag(allowed(p),kind!=InventoryWindowRules.Kind.OTHER,p.getGameMode()==GameMode.CREATIVE,kind,slots))e.setCancelled(true);
        else if(!e.isCancelled())try{PaperInventoryWindow.previewDrag(codec,p,e);sample(p);}catch(RuntimeException full){e.setCancelled(true);hint(p);}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEvent e){
        if(!allowed(e.getPlayer()) || e.getClickedBlock()!=null && e.getClickedBlock().getType().isInteractable() && !PlayerInteractionRules.facility(e.getClickedBlock().getType().name()) && !(InventoryWindowRules.container(e.getClickedBlock().getType().name())&&nativeAccess(e.getPlayer(),e.getClickedBlock().getLocation(),true)) && !(e.getClickedBlock().getType()==Material.CRAFTING_TABLE&&nativeAccess(e.getPlayer(),e.getClickedBlock().getLocation(),false)) && !craftingFacility.test(e.getPlayer(),e.getClickedBlock()))e.setCancelled(true);
        sample(e.getPlayer());
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void place(BlockPlaceEvent e){if(!allowed(e.getPlayer())||!gatheringChange.test(e.getPlayer(),e.getBlock()))e.setCancelled(true);sample(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void breakBlock(BlockBreakEvent e){if(!allowed(e.getPlayer())||!gatheringChange.test(e.getPlayer(),e.getBlock()))e.setCancelled(true);sample(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void drop(PlayerDropItemEvent e){if(!allowed(e.getPlayer()))e.setCancelled(true);sample(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void pickup(EntityPickupItemEvent e){if(e.getEntity() instanceof Player p){if(!allowed(p)||!p.getItemOnCursor().isEmpty())e.setCancelled(true);else if(!e.isCancelled())try{codec.withTemporary(PaperInventoryWindow.slots(p.getInventory()),e.getItem().getItemStack(),PaperInventoryWindow.matrix(p.getOpenInventory().getTopInventory()));}catch(RuntimeException full){e.setCancelled(true);}sample(p);}}
    @EventHandler(priority=EventPriority.HIGHEST) public void consume(PlayerItemConsumeEvent e){if(!allowed(e.getPlayer()))e.setCancelled(true);sample(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void swap(PlayerSwapHandItemsEvent e){if(!allowed(e.getPlayer()))e.setCancelled(true);sample(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void damageItem(PlayerItemDamageEvent e){if(!allowed(e.getPlayer()))e.setCancelled(true);sample(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void command(PlayerCommandPreprocessEvent e){boolean denied=e.isCancelled();e.setCancelled(true);if(!denied&&prepared(e.getPlayer())!=null&&combat.command(e.getPlayer(),e.getMessage()))return;if(!denied && allowed(e.getPlayer())){try{settleBoundary(e.getPlayer());}catch(RuntimeException failure){temporaryHold(e.getPlayer());return;}if(Set.of("/hearthplay","/hearthplay help").contains(e.getMessage()))PlayerInteractionRules.help().forEach(line->e.getPlayer().sendMessage(Component.text(line)));else if(!gatheringCommand.test(e.getPlayer(),e.getMessage()))questCommand.test(e.getPlayer(),e.getMessage());}}
    @EventHandler(priority=EventPriority.HIGHEST) public void damage(EntityDamageEvent e){if(e.getEntity() instanceof Player p&&!combat.damage(p,e))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void attack(EntityDamageByEntityEvent e){if(e.getDamager() instanceof Player p&&(!allowed(p)||!fieldAttack.test(p,e.getEntity())&&!combat.attack(p,e.getEntity())))e.setCancelled(true);}
    @Override public void close(){main();closed=true;for(var move:List.copyOf(combatMoves))move.completeExceptionally(new IllegalStateException("COMBAT_MOVE_CLOSED"));moving.clear();travelFrozen.clear();interactionHints.clear();windows.clear();HandlerList.unregisterAll(this);}
}
