package io.github.jesterportfolio.hearth.economy;

import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.session.*;
import io.github.jesterportfolio.hearth.lifecycle.*;
import io.github.jesterportfolio.hearth.world.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.*;
import java.time.Duration;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.flags.*;

/** 격리 경제 전용 실행. 운영 서버·일반 로그인·스토리 인벤토리로 자동 활성화하지 않습니다. */
public final class PaperEconomy implements Listener,AutoCloseable {
    public static final UUID REALM=new UUID(0x45434f,1),OTHER=new UUID(0x45434f,2);
    public final EconomyStore store;public final PlayerSessions sessions;public final PaperSessionAdapter adapter;public final EconomyTrade trades;public final WorldLifecycle world;
    private final JavaPlugin plugin;private final PostgresProgressStore db;private final Executor owner;private final PaperInventoryCodec codec;private final Path mappingFile;private final Properties settings;
    private final PaperWorldBackend backend=new PaperWorldBackend();private WorldBackend.Placement placement;private Villager merchant;private final PaperInventoryAutosave autosave;
    private final Map<UUID,UUID> pending=new HashMap<>();private final Map<UUID,Screen> screens=new HashMap<>();private final Map<PlayerSessions.Session,AtomicBoolean> fences=new IdentityHashMap<>();private BukkitTask ticker;private CompletionStage<?> ticking=CompletableFuture.completedFuture(null);private long nextTick;private boolean closed,tickHold;public long pulses;
    private final String region,good;private final org.bukkit.util.Vector merchantPoint;
    private record Action(int quantity,boolean buy,UUID request){}
    private final class Screen implements InventoryHolder {
        final UUID player,nonce=UUID.randomUUID();final PlayerSessions.Session session;final long generation;final EconomyStore.Offer offer;final Inventory inventory;final Map<Integer,Action> actions=new HashMap<>();boolean busy;
        Screen(Player player,PlayerSessions.Session session,EconomyStore.Offer offer){this.player=player.getUniqueId();this.session=session;this.offer=offer;generation=world.generation();inventory=Bukkit.createInventory(this,54,Component.text("수도 개발 상점"));}
        public Inventory getInventory(){return inventory;}
    }
    public static EconomyContent load(JavaPlugin plugin,Properties settings)throws Exception{var file=plugin.getDataFolder().toPath().resolve("economy-demo.properties");if(!Files.exists(file))plugin.saveResource("economy-demo.properties",false);try(var reader=Files.newBufferedReader(file)){settings.load(reader);}return EconomyContent.parse(settings);}
    public PaperEconomy(JavaPlugin plugin,PostgresProgressStore db,Executor owner,PaperInventoryCodec codec,Path root,Properties settings,EconomyContent content,boolean resume)throws Exception{
        main();if(!plugin.getConfig().getBoolean("economy-verification-enabled")||!Boolean.getBoolean("hearth.verification.economy")||!Bukkit.getOnlinePlayers().isEmpty())throw new IllegalStateException("ECON_ISOLATED_GATE");this.plugin=plugin;this.db=db;this.owner=owner;this.codec=codec;this.settings=settings;mappingFile=root.resolve("economy-world.properties");store=new EconomyStore(db,content);trades=new EconomyTrade(store,codec,owner);region=settings.getProperty("merchant-region");good=settings.getProperty("merchant-good");if(!content.regions().contains(region)||!content.goods().containsKey(good))throw new IllegalArgumentException("ECON_MERCHANT_REF");
        if(resume){var p=new Properties();try(var in=Files.newInputStream(mappingFile)){p.load(in);}if(!REALM.toString().equals(p.getProperty("realm")))throw new IllegalStateException("ECON_MAPPING_OWNER");var key=Objects.requireNonNull(NamespacedKey.fromString(p.getProperty("key")));placement=new WorldBackend.Placement(REALM,key,UUID.fromString(p.getProperty("uuid")),Path.of(p.getProperty("directory")));backend.registerExisting(placement);backend.load(placement);}
        else{if(Files.exists(mappingFile))throw new IllegalStateException("ECON_MAPPING_EXISTS");placement=backend.create(REALM,new NamespacedKey("hearth_verify","economy_fixture"));var w=Objects.requireNonNull(Bukkit.getWorld(placement.physicalId()));for(int x=0;x<16;x++)for(int z=0;z<16;z++)w.getBlockAt(x,63,z).setType(Material.STONE,false);w.save(true);Files.writeString(placement.directory().resolve(".hearth-s4-spatial-ready"),REALM+"/"+placement.key()+"/"+placement.physicalId());var p=new Properties();p.setProperty("realm",REALM.toString());p.setProperty("key",placement.key().toString());p.setProperty("uuid",placement.physicalId().toString());p.setProperty("directory",placement.directory().toString());try(var out=Files.newOutputStream(mappingFile,StandardOpenOption.CREATE_NEW)){p.store(out,"격리 경제 월드 매핑");}}
        var initial=Objects.requireNonNull(Bukkit.getWorld(placement.physicalId()));merchantPoint=new org.bukkit.util.Vector(Double.parseDouble(settings.getProperty("merchant-x")),Double.parseDouble(settings.getProperty("merchant-y")),Double.parseDouble(settings.getProperty("merchant-z")));
        final PlayerSessions[] coordinator=new PlayerSessions[1];var mapping=new WorldLifecycle.Mapping(REALM,placement.physicalId(),placement.key().toString(),placement.directory());
        world=new WorldLifecycle(mapping,new WorldLifecycle.Operations(){
            public CompletionStage<Void> loadAndPrepare(){main();var w=Bukkit.getWorld(placement.physicalId());if(w==null)w=backend.load(placement);if(!w.getUID().equals(placement.physicalId()))throw new IllegalStateException("ECON_WORLD_ID");var manager=Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(w)));if(manager.getRegion("capital") == null){if(resume)throw new IllegalStateException("ECON_REGION_MISSING");var protectedRegion=new ProtectedCuboidRegion("capital",BlockVector3.at(0,60,0),BlockVector3.at(15,75,15));protectedRegion.setFlag(Flags.BUILD,StateFlag.State.DENY);manager.addRegion(protectedRegion);}spawnMerchant(w);return store.snapshot(REALM).thenApply(v->null);}
            public CompletionStage<Void> quiesce(){main();if(merchant!=null){merchant.remove();merchant=null;}return coordinator[0].saveRunBoundary(REALM);}
            public CompletionStage<Void> save(){main();var manager=Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(Objects.requireNonNull(Bukkit.getWorld(placement.physicalId())))));return db.module("economy_region_save",c->{manager.save();return null;}).thenRunAsync(()->backend.save(placement),owner);}
            public CompletionStage<Boolean> unload(){main();return CompletableFuture.completedFuture(backend.unload(placement));}
        },owner,PaperEconomy::main,(delay,action)->{var task=Bukkit.getScheduler().runTaskLater(plugin,action,Math.max(1,delay.toMillis()/50));return task::cancel;},Duration.ofSeconds(1),16,state->{if(state==WorldLifecycle.State.RECOVERY_REQUIRED||state==WorldLifecycle.State.ERROR_HOLD)fences.values().forEach(f->f.set(false));});
        autosave=new PaperInventoryAutosave(plugin,List.of(),()->{if(coordinator[0]!=null)coordinator[0].refreshAll();},()->{});
        sessions=new PlayerSessions(store.progressAdapter(),store,owner,PaperEconomy::main,p->codec.decode(p),codec::equivalent,autosave::add,autosave::remove,code->plugin.getLogger().warning("HEARTH_ECONOMY_HOLD "+code));coordinator[0]=sessions;
        adapter=new PaperSessionAdapter(plugin,sessions,owner,codec,new Location(Bukkit.getWorlds().getFirst(),0.5,64,0.5));adapter.developerInventory((player,inventory)->inventory.getHolder() instanceof PaperEconomy.Screen screen&&valid(player,screen));Bukkit.getPluginManager().registerEvents(this,plugin);
    }
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("ECON_MAIN_SAFE_POINT");}
    private void spawnMerchant(World w){if(merchant!=null&&merchant.isValid())return;merchant=w.spawn(new Location(w,merchantPoint.getX(),merchantPoint.getY(),merchantPoint.getZ()),Villager.class,mob->{mob.setAI(false);mob.setInvulnerable(true);mob.setPersistent(false);mob.setCollidable(false);mob.customName(Component.text(settings.getProperty("merchant-name")));mob.setCustomNameVisible(true);mob.setRecipes(List.of());});}
    public AtomicBoolean fence(PlayerSessions.Session session){main();return fences.computeIfAbsent(session,s->new AtomicBoolean(true));}
    public void invalidate(PlayerSessions.Session session){main();fence(session).set(false);}
    public Location entry(){return new Location(Objects.requireNonNull(Bukkit.getWorld(placement.physicalId())),5.5,64,5.5);}
    public UUID physicalWorld(){return placement.physicalId();}
    private boolean near(Player player){return merchant!=null&&merchant.isValid()&&player.getWorld()==merchant.getWorld()&&player.getLocation().distanceSquared(merchant.getLocation())<=25;}
    private boolean valid(Player player,Screen screen){return !closed&&player.getUniqueId().equals(screen.player)&&screens.get(screen.player)==screen&&adapter.current(player)==screen.session&&world.generation()==screen.generation&&near(player);}
    @EventHandler(priority=EventPriority.HIGHEST) public void npc(PlayerInteractEntityEvent e){if(e.getRightClicked()!=merchant)return;e.setCancelled(true);if(e.getHand()==EquipmentSlot.HAND)open(e.getPlayer());}
    public void open(Player player){main();var session=adapter.current(player);if(session==null||!near(player)||pending.containsKey(player.getUniqueId()))return;UUID nonce=UUID.randomUUID();pending.put(player.getUniqueId(),nonce);screens.remove(player.getUniqueId());long generation=world.generation();store.offer(session.context(),new EconomyContent.Key(region,good)).whenCompleteAsync((offer,error)->{if(!nonce.equals(pending.get(player.getUniqueId()))||closed||adapter.current(player)!=session||world.generation()!=generation||!near(player))return;pending.remove(player.getUniqueId());if(error!=null){player.sendMessage(Component.text("시장 조회 실패 · 거래하지 않았습니다."));return;}var screen=new Screen(player,session,offer);var item=new ItemStack(Objects.requireNonNull(Material.matchMaterial(store.content().goods().get(good).material())));item.editMeta(meta->{meta.displayName(Component.text("철괴 · 재고 "+offer.market().stock()));meta.lore(List.of(Component.text("개발 화폐 잔액: "+offer.balance())));});screen.inventory.setItem(4,item);
            int index=0;for(boolean buy:List.of(true,false))for(int quantity:new int[]{1,8,32}){int slot=(buy?10:28)+index%3;index++;try{long total=EconomyEngine.quote(store.content(),good,offer.market(),quantity,buy);var button=item.clone();button.editMeta(meta->meta.displayName(Component.text((buy?"구매 ":"판매 ")+quantity+"개 · "+total)));screen.inventory.setItem(slot,button);screen.actions.put(slot,new Action(quantity,buy,UUID.randomUUID()));}catch(IllegalArgumentException unavailable){screen.inventory.setItem(slot,new ItemStack(Material.BARRIER));}}screens.put(player.getUniqueId(),screen);player.openInventory(screen.inventory);},owner);}
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e){if(!(e.getView().getTopInventory().getHolder() instanceof PaperEconomy.Screen screen))return;e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player player)||!valid(player,screen)||screen.busy||e.getClickedInventory()!=screen.inventory||e.getClick()!=ClickType.LEFT)return;var action=screen.actions.get(e.getSlot());if(action==null)return;screen.busy=true;
        trades.execute(sessions,screen.session,screen.offer,action.quantity(),action.buy(),action.request(),fence(screen.session)::get,EconomyStore.Probe.NONE).whenCompleteAsync((receipt,error)->{if(adapter.linked(player)!=screen.session)return;player.sendMessage(Component.text(error==null?"경제 거래 DB 반영·인벤토리 적용 완료":"거래 거부 또는 보류 · 성공으로 처리하지 않았습니다."));if(screens.get(player.getUniqueId())==screen&&adapter.current(player)==screen.session)open(player);},owner);}
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof PaperEconomy.Screen)e.setCancelled(true);}
    @EventHandler public void screenClosed(InventoryCloseEvent e){if(e.getInventory().getHolder() instanceof PaperEconomy.Screen screen)screens.remove(e.getPlayer().getUniqueId(),screen);}
    @EventHandler(priority=EventPriority.LOWEST) public void quit(PlayerQuitEvent e){var session=adapter.linked(e.getPlayer());if(session!=null){invalidate(session);fences.remove(session);}screens.remove(e.getPlayer().getUniqueId());pending.remove(e.getPlayer().getUniqueId());}
    public void fixture(String profile,boolean existing){main();if(!profile.matches("[A-Za-z0-9_]{3,16}"))throw new IllegalArgumentException("ECON_PROFILE");Bukkit.createPlayerProfile(profile).update().thenComposeAsync(p->{if(p.getUniqueId()==null||!p.isComplete())throw new IllegalStateException("ECON_AUTH_PROFILE");var ctx=store.context(REALM,p.getUniqueId());var slots=new ItemStack[41];slots[0]=new ItemStack(Material.IRON_INGOT,8);var prepared=existing?store.account(ctx).thenApply(a->(Void)null):store.createAccount(ctx,store.content().fixtureBalance(),codec.encode(slots));return prepared.thenRunAsync(()->{adapter.fixture(new PaperSessionAdapter.Link(ctx,world,this::entry));plugin.getLogger().info("HEARTH_ECONOMY_FIXTURE READY "+profile);},owner);},owner).exceptionally(error->{plugin.getLogger().warning("HEARTH_ECONOMY_FIXTURE HOLD");return null;});}
    public void startTicker(){main();if(ticker!=null)throw new IllegalStateException("ECON_TICKER_EXISTS");ticking=store.resumeClock(REALM,System.currentTimeMillis()).whenCompleteAsync((v,error)->{if(error!=null){tickHold=true;plugin.getLogger().warning("HEARTH_ECONOMY_OFFLINE_HOLD");}},owner);nextTick=System.nanoTime()+store.content().tickMillis()*1000000;ticker=Bukkit.getScheduler().runTaskTimer(plugin,()->{pulses++;if(closed||tickHold||!ticking.toCompletableFuture().isDone()||System.nanoTime()<nextTick)return;nextTick=System.nanoTime()+store.content().tickMillis()*1000000;ticking=store.snapshot(REALM).thenCompose(s->store.tick(REALM,s.tick(),System.currentTimeMillis())).whenCompleteAsync((v,error)->{if(error!=null){tickHold=true;plugin.getLogger().warning("HEARTH_ECONOMY_TICK_HOLD");}},owner);},1,1);}
    public CompletionStage<Void> stopTicker(){main();if(ticker!=null){ticker.cancel();ticker=null;}return ticking.thenApply(v->null);}
    public CompletionStage<Void> shutdown(){main();closed=true;screens.clear();pending.clear();adapter.close();HandlerList.unregisterAll(this);autosave.close();return stopTicker().thenComposeAsync(v->sessions.shutdown(),owner);}
    @Override public void close(){if(ticker!=null)ticker.cancel();adapter.close();autosave.close();HandlerList.unregisterAll(this);if(merchant!=null)merchant.remove();}
}
