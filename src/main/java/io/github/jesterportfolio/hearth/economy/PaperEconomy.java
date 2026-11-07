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
import org.bukkit.event.world.ChunkLoadEvent;
import io.github.jesterportfolio.hearth.economy.EconomyMerchants.Visit;
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
    private final PaperWorldBackend backend=new PaperWorldBackend();private WorldBackend.Placement placement;private final Map<String,Villager> merchantEntities=new HashMap<>();private final Map<UUID,String> entityMerchants=new HashMap<>();private final Map<UUID,Visit> visits=new HashMap<>();private int merchantSpawns;private boolean fixtureChunks;private final Set<Long> pinnedChunks=new HashSet<>();private final PaperInventoryAutosave autosave;
    private final Map<UUID,UUID> pending=new HashMap<>();private final Map<UUID,Screen> screens=new HashMap<>();private final Map<PlayerSessions.Session,AtomicBoolean> fences=new IdentityHashMap<>();private BukkitTask ticker;private CompletionStage<?> ticking=CompletableFuture.completedFuture(null);private long nextTick;private boolean closed,tickHold;public long pulses;
    public final EconomyMerchants merchants;public final EconomyShop shop;public final EconomyReport.Definition reportDefinition;private final Map<String,EconomyReport.Definition> merchantReports=new HashMap<>();private final Map<String,EconomySupply.Plan> merchantGuides=new HashMap<>();
    private enum Kind { CATALOG, PRODUCT, QUANTITY, MAX_BUY, MAX_SELL, CONFIRM_BUY, CONFIRM_SELL, RESULT, SUPPLY, SUPPLY_GOODS, GUIDE, SUPPLY_REFRESH, HISTORY, BUY, SELL, MARKET, REGIONS, ROUTES, EVENTS, FACILITIES, REFRESH }
    private static final Set<Kind> SUPPLY_VIEWS=Set.of(Kind.SUPPLY,Kind.SUPPLY_GOODS,Kind.GUIDE);
    private static final Set<Kind> INFORMATION=Set.of(Kind.MARKET,Kind.REGIONS,Kind.ROUTES,Kind.EVENTS,Kind.FACILITIES);
    private record Action(Kind kind,String good,int quantity,UUID request,long cursor,int page,String region){}
    private final class Screen implements InventoryHolder {
        final UUID player;final Visit visit;final PlayerSessions.Session session;final long generation;final EconomyStore.Offer offer;final Inventory inventory;final Map<Integer,Action> actions=new HashMap<>();boolean busy;int quantity=1;EconomyQuote.View quote;EconomyReport.View report;Kind reportKind;int reportPage;EconomySupply.View supply;Kind supplyKind;int supplyPage;
        Screen(PlayerSessions.Session session,EconomyStore.Offer offer,String title,Visit visit){this.visit=visit;this.player=session.context().playerId();this.session=session;this.offer=offer;generation=world.generation();inventory=Bukkit.createInventory(this,54,Component.text(merchants.get(visit.merchant()).name()+" · "+title));}
        public Inventory getInventory(){return inventory;}
    }
    public static EconomyContent load(JavaPlugin plugin,Properties settings)throws Exception{var file=plugin.getDataFolder().toPath().resolve("economy-demo.properties");if(!Files.exists(file))plugin.saveResource("economy-demo.properties",false);try(var reader=Files.newBufferedReader(file)){settings.load(reader);}return EconomyContent.parse(settings);}
    public PaperEconomy(JavaPlugin plugin,PostgresProgressStore db,Executor owner,PaperInventoryCodec codec,Path root,Properties settings,EconomyContent content,boolean resume)throws Exception{
        main();if(!plugin.getConfig().getBoolean("economy-verification-enabled")||!Boolean.getBoolean("hearth.verification.economy")||!Bukkit.getOnlinePlayers().isEmpty())throw new IllegalStateException("ECON_ISOLATED_GATE");this.plugin=plugin;this.db=db;this.owner=owner;this.codec=codec;this.settings=settings;mappingFile=root.resolve("economy-world.properties");store=new EconomyStore(db,content);trades=new EconomyTrade(store,codec,owner);merchants=EconomyMerchants.parse(settings,content);shop=merchants.first().shop();for(var merchant:merchants.all())merchantReports.put(merchant.id(),EconomyReport.parse(settings,content,merchant.shop()));reportDefinition=merchantReports.get(merchants.first().id());for(var merchant:merchants.all())merchantGuides.put(merchant.id(),EconomySupply.parse(settings,content,merchantReports.get(merchant.id())));
        if(resume){var p=new Properties();try(var in=Files.newInputStream(mappingFile)){p.load(in);}if(!REALM.toString().equals(p.getProperty("realm")))throw new IllegalStateException("ECON_MAPPING_OWNER");var key=Objects.requireNonNull(NamespacedKey.fromString(p.getProperty("key")));placement=new WorldBackend.Placement(REALM,key,UUID.fromString(p.getProperty("uuid")),Path.of(p.getProperty("directory")));backend.registerExisting(placement);backend.load(placement);}
        else{if(Files.exists(mappingFile))throw new IllegalStateException("ECON_MAPPING_EXISTS");placement=backend.create(REALM,new NamespacedKey("hearth_verify","economy_fixture"));var w=Objects.requireNonNull(Bukkit.getWorld(placement.physicalId()));for(int x=0;x<16;x++)for(int z=0;z<16;z++)w.getBlockAt(x,63,z).setType(Material.STONE,false);w.save(true);Files.writeString(placement.directory().resolve(".hearth-s4-spatial-ready"),REALM+"/"+placement.key()+"/"+placement.physicalId());var p=new Properties();p.setProperty("realm",REALM.toString());p.setProperty("key",placement.key().toString());p.setProperty("uuid",placement.physicalId().toString());p.setProperty("directory",placement.directory().toString());try(var out=Files.newOutputStream(mappingFile,StandardOpenOption.CREATE_NEW)){p.store(out,"격리 경제 월드 매핑");}}
        var physical=Objects.requireNonNull(Bukkit.getWorld(placement.physicalId()));for(var definition:merchants.all())if(definition.point().y()<physical.getMinHeight()||definition.point().y()>=physical.getMaxHeight())throw new IllegalArgumentException("ECON_MERCHANT_HEIGHT");
        final PlayerSessions[] coordinator=new PlayerSessions[1];var mapping=new WorldLifecycle.Mapping(REALM,placement.physicalId(),placement.key().toString(),placement.directory());
        world=new WorldLifecycle(mapping,new WorldLifecycle.Operations(){
            public CompletionStage<Void> loadAndPrepare(){main();var w=Bukkit.getWorld(placement.physicalId());if(w==null)w=backend.load(placement);if(!w.getUID().equals(placement.physicalId()))throw new IllegalStateException("ECON_WORLD_ID");var manager=Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(w)));if(manager.getRegion("capital") == null){if(resume)throw new IllegalStateException("ECON_REGION_MISSING");var protectedRegion=new ProtectedCuboidRegion("capital",BlockVector3.at(0,60,0),BlockVector3.at(15,75,15));protectedRegion.setFlag(Flags.BUILD,StateFlag.State.DENY);manager.addRegion(protectedRegion);}pinFixtureChunks(w);spawnMerchants(w);return store.snapshot(REALM).thenApply(v->null);}
            public CompletionStage<Void> quiesce(){main();removeMerchants();releaseFixtureChunks();return coordinator[0].saveRunBoundary(REALM);}
            public CompletionStage<Void> save(){main();var manager=Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(Objects.requireNonNull(Bukkit.getWorld(placement.physicalId())))));return db.module("economy_region_save",c->{manager.save();return null;}).thenRunAsync(()->backend.save(placement),owner);}
            public CompletionStage<Boolean> unload(){main();return CompletableFuture.completedFuture(backend.unload(placement));}
        },owner,PaperEconomy::main,(delay,action)->{var task=Bukkit.getScheduler().runTaskLater(plugin,action,Math.max(1,delay.toMillis()/50));return task::cancel;},Duration.ofSeconds(1),16,state->{if(state==WorldLifecycle.State.RECOVERY_REQUIRED||state==WorldLifecycle.State.ERROR_HOLD)fences.values().forEach(f->f.set(false));});
        autosave=new PaperInventoryAutosave(plugin,List.of(),()->{if(coordinator[0]!=null)coordinator[0].refreshAll();},()->{});
        sessions=new PlayerSessions(store.progressAdapter(),store,owner,PaperEconomy::main,p->codec.decode(p),codec::equivalent,autosave::add,autosave::remove,code->plugin.getLogger().warning("HEARTH_ECONOMY_HOLD "+code));coordinator[0]=sessions;
        adapter=new PaperSessionAdapter(plugin,sessions,owner,codec,new Location(Bukkit.getWorlds().getFirst(),0.5,64,0.5));adapter.developerInventory((player,inventory)->inventory.getHolder() instanceof PaperEconomy.Screen screen&&valid(player,screen));Bukkit.getPluginManager().registerEvents(this,plugin);
    }
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("ECON_MAIN_SAFE_POINT");}
    /** 실제 접속자가 없는 자동 시험에서만 청크를 유지합니다. 일반 NPC는 플레이어 주변 청크 로드를 따릅니다. */
    public void pinFixtureChunks(){main();if(!Bukkit.getOnlinePlayers().isEmpty())throw new IllegalStateException("ECON_FIXTURE_NO_CLIENTS");fixtureChunks=true;var current=Bukkit.getWorld(placement.physicalId());if(current!=null)pinFixtureChunks(current);}
    private void pinFixtureChunks(World current){if(!fixtureChunks)return;for(var merchant:merchants.all()){
        int x=((int)Math.floor(merchant.point().x()))>>4,z=((int)Math.floor(merchant.point().z()))>>4;long key=((long)x<<32)|(z&0xffffffffL);
        if(!pinnedChunks.contains(key)&&current.addPluginChunkTicket(x,z,plugin))pinnedChunks.add(key);
    }}
    private void releaseFixtureChunks(){var current=Bukkit.getWorld(placement.physicalId());if(current!=null)for(long key:pinnedChunks)current.removePluginChunkTicket((int)(key>>32),(int)key,plugin);pinnedChunks.clear();}
    private void spawnMerchants(World world){main();for(var merchant:merchants.all())spawnMerchant(world,merchant);}
    private void spawnMerchant(World current,EconomyMerchants.Merchant definition){
        var old=merchantEntities.get(definition.id());if(old!=null&&old.isValid()&&old.getWorld()==current)return;
        if(old!=null){entityMerchants.remove(old.getUniqueId());old.remove();}var point=definition.point();
        if(point.y()<current.getMinHeight()||point.y()>=current.getMaxHeight())throw new IllegalArgumentException("ECON_MERCHANT_HEIGHT");
        var mob=current.spawn(new Location(current,point.x(),point.y(),point.z()),Villager.class,entity->{entity.setAI(false);entity.setInvulnerable(true);entity.setPersistent(false);entity.setCollidable(false);entity.customName(Component.text(definition.name()));entity.setCustomNameVisible(true);entity.setRecipes(List.of());});
        merchantEntities.put(definition.id(),mob);entityMerchants.put(mob.getUniqueId(),definition.id());merchantSpawns++;
    }
    private void removeMerchants(){main();for(var mob:merchantEntities.values())mob.remove();merchantEntities.clear();entityMerchants.clear();visits.clear();screens.clear();pending.clear();}
    @EventHandler public void chunkLoaded(ChunkLoadEvent event){
        if(!event.getWorld().getUID().equals(placement.physicalId()))return;long generation=world.generation();int x=event.getChunk().getX(),z=event.getChunk().getZ();if(merchants.inChunk(x,z).isEmpty())return;
        Bukkit.getScheduler().runTaskLater(plugin,()->{
            if(closed||world.generation()!=generation||!(world.state()==WorldLifecycle.State.READY||world.state()==WorldLifecycle.State.ACTIVE))return;
            var current=Bukkit.getWorld(placement.physicalId());if(current==null||!current.isChunkLoaded(x,z))return;
            for(var merchant:merchants.inChunk(x,z))spawnMerchant(current,merchant);
        },1);
    }
    public Map<String,UUID> merchantEntities(){main();var result=new HashMap<String,UUID>();merchantEntities.forEach((id,mob)->{if(mob.isValid())result.put(id,mob.getUniqueId());});return Map.copyOf(result);}
    public int merchantSpawns(){main();return merchantSpawns;}
    public Visit merchantVisit(String id){main();var live=merchantEntities.get(merchants.get(id).id());if(live==null||!live.isValid())throw new IllegalStateException("ECON_MERCHANT_UNAVAILABLE");return new Visit(id,live.getUniqueId(),world.generation());}
    private EconomyShop shop(Visit visit){return merchants.get(visit.merchant()).shop();}
    private EconomyReport.Definition report(Visit visit){return Objects.requireNonNull(merchantReports.get(visit.merchant()));}
    public AtomicBoolean fence(PlayerSessions.Session session){main();return fences.computeIfAbsent(session,s->new AtomicBoolean(true));}
    public void invalidate(PlayerSessions.Session session){main();fence(session).set(false);}
    public Location entry(){return new Location(Objects.requireNonNull(Bukkit.getWorld(placement.physicalId())),5.5,64,5.5);}
    public UUID physicalWorld(){return placement.physicalId();}
    private boolean near(Player player,Visit visit){var mob=merchantEntities.get(visit.merchant());return mob!=null&&mob.isValid()&&mob.getUniqueId().equals(visit.entity())&&mob.getWorld().getUID().equals(placement.physicalId())&&player.getWorld()==mob.getWorld()&&player.getLocation().distanceSquared(mob.getLocation())<=25;}
    private boolean current(Player player,Visit visit){var mob=merchantEntities.get(visit.merchant());return mob!=null&&mob.isValid()&&visit.current(visits.get(player.getUniqueId()),mob.getUniqueId(),world.generation())&&near(player,visit);}
    private boolean valid(Player player,Screen screen){return !closed&&player.getUniqueId().equals(screen.player)&&screens.get(screen.player)==screen&&adapter.current(player)==screen.session&&world.generation()==screen.generation&&current(player,screen.visit);}
    @EventHandler(priority=EventPriority.HIGHEST) public void npc(PlayerInteractEntityEvent e){String id=entityMerchants.get(e.getRightClicked().getUniqueId());if(id==null)return;e.setCancelled(true);if(e.getHand()==EquipmentSlot.HAND&&merchantEntities.get(id)==e.getRightClicked())openAt(e.getPlayer(),id);}
    public void openAt(Player player,String id){
        main();if(closed||adapter.current(player)==null)return;Visit visit=merchantVisit(id);if(!near(player,visit))return;
        var old=visits.get(player.getUniqueId());if(old!=null&&old.merchant().equals(id)&&old.entity().equals(visit.entity())&&pending.containsKey(player.getUniqueId()))return;
        visits.put(player.getUniqueId(),visit);pending.remove(player.getUniqueId());screens.remove(player.getUniqueId());open(player,Kind.CATALOG,null,0,0,null);
    }
    private void button(Screen screen,int slot,Material material,String label,Action action,String... lore){
        var item=new ItemStack(material);item.editMeta(meta->{meta.displayName(Component.text(label));meta.lore(Arrays.stream(lore).map(Component::text).toList());});screen.inventory.setItem(slot,item);if(action!=null)screen.actions.put(slot,action);
    }
    private Action nav(Kind kind,String good,int page,long cursor){return new Action(kind,good,0,null,cursor,page,null);}
    private Screen catalog(PlayerSessions.Session session,List<EconomyStore.Offer> offers,int page,Visit visit){
        var shop=shop(visit);
        var screen=new Screen(session,null,"지역 상점 · "+shop.region(),visit);var byGood=shop.index(offers);
        int slot=0;for(String good:shop.page(page)){var offer=Objects.requireNonNull(byGood.get(good));String ask=offer.market().stock()>0?""+EconomyEngine.quote(store.content(),good,offer.market(),1,true):"품절";
            button(screen,slot++,Objects.requireNonNull(Material.matchMaterial(store.content().goods().get(good).material())),shop.name(good),nav(Kind.PRODUCT,good,0,0),"재고: "+offer.market().stock(),"1개 구매: "+ask,"1개 판매: "+EconomyEngine.quote(store.content(),good,offer.market(),1,false),"개발 화폐 잔액: "+offer.balance());}
        if(page>0)button(screen,45,Material.ARROW,"이전 상품",nav(Kind.CATALOG,null,page-1,0));if((page+1)*45<shop.goods().size())button(screen,53,Material.ARROW,"다음 상품",nav(Kind.CATALOG,null,page+1,0));
        button(screen,46,Material.MAP,"상품 공급망",nav(Kind.SUPPLY_GOODS,report(visit).goods().getFirst(),0,0));button(screen,47,Material.WRITABLE_BOOK,"경제 플레이 안내",nav(Kind.GUIDE,report(visit).goods().getFirst(),0,0));button(screen,48,Material.COMPASS,"시장 상황",info(Kind.MARKET,shop.region(),0));button(screen,49,Material.BOOK,"본인 거래 기록",nav(Kind.HISTORY,null,0,Long.MAX_VALUE));button(screen,50,Material.CLOCK,"시세 새로고침",nav(Kind.CATALOG,null,page,0));return screen;
    }
    private Screen product(PlayerSessions.Session session,EconomyStore.Offer offer,Visit visit){return product(session,offer,visit,1,null);}
    private Screen product(PlayerSessions.Session session,EconomyStore.Offer offer,Visit visit,int quantity,InventoryPayload payload){
        var shop=shop(visit);merchants.get(visit.merchant()).require(offer);
        var screen=new Screen(session,offer,shop.name(offer.key().good())+" · 수량 선택",visit);screen.quantity=quantity;
        button(screen,4,Objects.requireNonNull(Material.matchMaterial(store.content().goods().get(offer.key().good()).material())),shop.name(offer.key().good()),null,"선택 수량: "+quantity,"재고: "+offer.market().stock(),"개발 화폐 잔액: "+offer.balance(),"이 화면의 수량 변경은 저장하지 않습니다");
        var capacity=payload==null?new EconomyQuote.Capacity(4096,4096):trades.capacity(payload,offer.key().good());
        var buy=EconomyQuote.view(store.content(),offer,capacity,quantity,true);var sell=EconomyQuote.view(store.content(),offer,capacity,quantity,false);
        button(screen,10,buy.available()?Material.LIME_DYE:Material.GRAY_DYE,"구매 견적 · "+quantity+"개",buy.available()?new Action(Kind.BUY,offer.key().good(),quantity,null,0,0,null):null,"총액: "+(buy.total()==null?"거래 불가":buy.total()),buy.guidance(),"다음 화면에서 최종 확인");
        button(screen,28,sell.available()?Material.ORANGE_DYE:Material.GRAY_DYE,"판매 견적 · "+quantity+"개",sell.available()?new Action(Kind.SELL,offer.key().good(),quantity,null,0,0,null):null,"총액: "+(sell.total()==null?"거래 불가":sell.total()),sell.guidance(),"다음 화면에서 최종 확인");
        int slot=19;for(int delta:new int[]{-64,-8,-1,1,8,64}){int next=EconomyQuote.adjust(quantity,delta);button(screen,slot++,Material.PAPER,(delta>0?"+":"")+delta+"개",new Action(Kind.QUANTITY,offer.key().good(),next,null,0,0,null));}
        button(screen,16,Material.CHEST,"최대 구매 가능 수량",nav(Kind.MAX_BUY,offer.key().good(),0,0));button(screen,34,Material.HOPPER,"최대 판매 가능 수량",nav(Kind.MAX_SELL,offer.key().good(),0,0));
        button(screen,45,Material.ARROW,"상품 목록",nav(Kind.CATALOG,null,0,0));button(screen,48,Material.COMPASS,"시장 상황",info(Kind.MARKET,shop.region(),0));button(screen,49,Material.BOOK,"본인 거래 기록",nav(Kind.HISTORY,null,0,Long.MAX_VALUE));button(screen,50,Material.CLOCK,"견적 새로고침",nav(Kind.PRODUCT,offer.key().good(),0,0));if(report(visit).allowsGood(offer.key().good())){button(screen,51,Material.MAP,"이 상품 공급망",nav(Kind.SUPPLY,offer.key().good(),0,0));button(screen,52,Material.WRITABLE_BOOK,"경제 플레이 안내",nav(Kind.GUIDE,offer.key().good(),0,0));}return screen;
    }
    private Screen confirmation(PlayerSessions.Session session,EconomyStore.Offer offer,Visit visit,int quantity,boolean buy,InventoryPayload payload){
        merchants.get(visit.merchant()).require(offer);var shop=shop(visit);var quote=EconomyQuote.view(store.content(),offer,trades.capacity(payload,offer.key().good()),quantity,buy);
        var screen=new Screen(session,offer,"거래 확인",visit);screen.quote=quote;screen.quantity=quantity;
        button(screen,13,Material.PAPER,(buy?"구매 · ":"판매 · ")+shop.name(offer.key().good()),null,"지역: "+shop.region(),"수량: "+quantity,"총액: "+(quote.total()==null?"거래 불가":quote.total()),"개발 화폐 잔액: "+quote.balance()+" → "+quote.projectedBalance(),"현재 시장 재고: "+quote.stock(),quote.guidance());
        button(screen,31,quote.available()?Material.LIME_DYE:Material.BARRIER,quote.available()?"거래 확정":"거래할 수 없습니다",quote.available()?new Action(buy?Kind.CONFIRM_BUY:Kind.CONFIRM_SELL,offer.key().good(),quantity,UUID.randomUUID(),0,0,null):null,"시세가 바뀌면 거래를 거부하고 다시 확인합니다");
        button(screen,45,Material.ARROW,"수량 선택으로",new Action(Kind.QUANTITY,offer.key().good(),quantity,null,0,0,null));button(screen,50,Material.CLOCK,"최신 견적 다시 조회",nav(Kind.PRODUCT,offer.key().good(),0,0));return screen;
    }
    private Screen result(PlayerSessions.Session session,Visit visit,String good,int quantity,boolean buy,EconomyStore.Receipt receipt){
        var shop=shop(visit);var screen=new Screen(session,null,"거래 완료",visit);
        button(screen,13,Material.EMERALD,(buy?"구매 완료 · ":"판매 완료 · ")+shop.name(good),null,"수량: "+quantity,"실제 총액: "+receipt.total(),"거래 후 개발 화폐: "+receipt.account().balance(),"저장 revision: "+receipt.account().inventory().revision(),"요청: "+receipt.id(),"DB 반영 및 인벤토리 적용 완료");
        button(screen,45,Material.ARROW,"상품 목록",nav(Kind.CATALOG,null,0,0));button(screen,49,Material.BOOK,"본인 거래 기록",nav(Kind.HISTORY,null,0,Long.MAX_VALUE));button(screen,50,Material.PAPER,"같은 상품 다시 조회",nav(Kind.PRODUCT,good,0,0));return screen;
    }
    public static String tradeFailure(Throwable error){return switch(InventorySaveSession.code(error)){
        case "ECON_QUOTE_CHANGED"->"시세가 변경되어 거래하지 않았습니다. 최신 견적을 다시 확인해 주세요.";
        case "ECON_STOCK"->"시장 재고가 부족하여 거래하지 않았습니다.";
        case "ECON_FUNDS"->"개발 화폐 잔액이 부족하여 거래하지 않았습니다.";
        case "ECON_ITEMS"->"판매 가능한 일반 아이템이 부족하여 거래하지 않았습니다.";
        case "ECON_INVENTORY_FULL"->"인벤토리 공간이 부족하여 거래하지 않았습니다.";
        default->"거래 결과를 확정할 수 없어 보류합니다. 성공으로 처리하지 않았습니다. 관리자 확인이 필요합니다.";};}
    private Screen history(PlayerSessions.Session session,List<EconomyStore.Entry> rows,Visit visit){
        var screen=new Screen(session,null,"본인 거래 기록",visit);int slot=0;
        for(var row:rows){String name=settings.getProperty("good."+row.key().good()+".name",row.key().good());button(screen,slot++,Material.PAPER,(row.buy()?"구매 · ":"판매 · ")+name,null,"수량: "+row.quantity()+" / 총액: "+row.total(),"지역: "+row.key().region(),"거래 revision: "+row.inventoryRevision(),"요청: "+row.id());}
        if(rows.isEmpty())button(screen,22,Material.PAPER,"거래 기록이 없습니다",null);
        button(screen,45,Material.ARROW,"상품 목록",nav(Kind.CATALOG,null,0,0));button(screen,49,Material.CLOCK,"최신 기록",nav(Kind.HISTORY,null,0,Long.MAX_VALUE));
        if(rows.size()==20)button(screen,53,Material.ARROW,"이전 거래",nav(Kind.HISTORY,null,0,rows.getLast().inventoryRevision()));return screen;
    }
    private Action supplyTarget(EconomySupply.Node node,Visit visit){
        if(!report(visit).allows(node.region()))throw new IllegalArgumentException("ECON_REPORT_REGION");
        if(node.section()==EconomySupply.Section.MARKET){return EconomySupply.localTrade(node,merchants.get(visit.merchant()))?nav(Kind.PRODUCT,node.reference(),0,0):info(Kind.MARKET,node.region(),0);}
        return info(node.section()==EconomySupply.Section.ROUTE?Kind.ROUTES:Kind.FACILITIES,node.region(),0);
    }
    private Screen supplyScreen(PlayerSessions.Session session,EconomySupply.View view,Kind kind,int page,Visit visit){
        if(!SUPPLY_VIEWS.contains(kind)||!report(visit).allowsGood(view.good()))throw new IllegalArgumentException("ECON_SUPPLY_VIEW");
        var screen=new Screen(session,null,kind==Kind.GUIDE?"경제 플레이 안내":kind==Kind.SUPPLY_GOODS?"공급망 상품 선택":view.name()+" · 공급망",visit);screen.supply=view;screen.supplyKind=kind;screen.supplyPage=page;int total,slot=0;
        if(kind==Kind.SUPPLY_GOODS){total=view.goods().size();for(var row:EconomyReport.page(view.goods(),page))button(screen,slot++,Objects.requireNonNull(Material.matchMaterial(store.content().goods().get(row.id()).material())),row.name(),nav(Kind.SUPPLY,row.id(),0,0),"공개 지역의 생산·사용처·운송·시장 조회");}
        else if(kind==Kind.GUIDE){total=view.steps().size();for(var row:EconomyReport.page(view.steps(),page)){var lines=new ArrayList<String>();lines.add(row.text());lines.addAll(row.target().lines());lines.add("조회 시점: 경제 Tick "+view.tick());lines.add("읽기 전용 안내 · 완료/보상 판정 없음");button(screen,slot++,Material.WRITABLE_BOOK,row.title(),supplyTarget(row.target(),visit),lines.toArray(String[]::new));}}
        else{total=view.nodes().size();for(var row:EconomyReport.page(view.nodes(),page)){var lines=new ArrayList<>(row.lines());lines.add("조회 시점: 경제 Tick "+view.tick());String label=switch(row.section()){case MARKET->"시장";case PRODUCER->"생산시설";case USE->"재료 사용처";case ROUTE->"운송로";};button(screen,slot++,switch(row.section()){case MARKET->Material.EMERALD;case PRODUCER,USE->Material.FURNACE;case ROUTE->Material.CHEST;},label+" · "+row.title(),supplyTarget(row,visit),lines.toArray(String[]::new));}}
        if(total==0)button(screen,22,Material.PAPER,kind==Kind.GUIDE?"등록된 경제 플레이 안내가 없습니다":"공개 공급망 정보가 없습니다",null);
        button(screen,45,Material.ARROW,"상점으로",nav(Kind.CATALOG,null,0,0));button(screen,46,Material.MAP,"상품 선택",nav(Kind.SUPPLY_GOODS,view.good(),0,0));button(screen,47,Material.WRITABLE_BOOK,"경제 플레이 안내",nav(Kind.GUIDE,view.good(),0,0));button(screen,48,Material.CHEST,"상품 공급망",nav(Kind.SUPPLY,view.good(),0,0));button(screen,49,Material.BOOK,"본인 거래 기록",nav(Kind.HISTORY,null,0,Long.MAX_VALUE));button(screen,50,Material.CLOCK,"공급망 새로고침",nav(Kind.SUPPLY_REFRESH,view.good(),page,0));
        if(page>0)button(screen,52,Material.ARROW,"이전 페이지",nav(kind,view.good(),page-1,0));if((page+1)*45<total)button(screen,53,Material.ARROW,"다음 페이지",nav(kind,view.good(),page+1,0));return screen;
    }
    public Inventory previewSupplyAt(String id,PlayerSessions.Session session,EconomySupply.View view,String kind,int page){main();store.validate(session.context());return supplyScreen(session,view,Kind.valueOf(kind),page,merchantVisit(id)).inventory;}
    public CompletionStage<EconomySupply.View> supply(ServerProgressAccess.Context context,String merchant,String good){main();var visit=merchants.get(merchant);return store.supply(context,merchantReports.get(visit.id()),merchants,merchantGuides.get(visit.id()),good);}
    private Action info(Kind kind,String region,int page){return new Action(kind,null,0,null,0,page,region);}
    private String price(Long value){return value==null?"거래 불가":value.toString();}
    private String stock(EconomyEngine.StockStatus value){return switch(value){case NORMAL->"보통";case SHORTAGE->"부족";case SURPLUS->"공급 과잉";};}
    private Screen information(PlayerSessions.Session session,EconomyReport.View view,Kind kind,int page,Visit visit){
        var shop=shop(visit);
        if(!INFORMATION.contains(kind)||!report(visit).allows(view.region().id()))throw new IllegalArgumentException("ECON_REPORT_VIEW");
        var screen=new Screen(session,null,view.region().name()+" · 시장 정보",visit);screen.report=view;screen.reportKind=kind;screen.reportPage=page;int slot=0,total;
        switch(kind){
            case MARKET->{total=view.markets().size();for(var row:EconomyReport.page(view.markets(),page)){
                Action action=view.region().id().equals(shop.region())&&shop.goods().contains(row.good())?nav(Kind.PRODUCT,row.good(),0,0):null;
                var lore=new ArrayList<String>(List.of("조회 시점: 경제 Tick "+view.tick(),"판매 가능 재고: "+row.stock()+" / 목표: "+row.target(),"품목 기준가: "+row.base()+" / 시장 기준가: "+row.anchor(),"1개 구매: "+price(row.ask())+" / 판매: "+price(row.bid()),"현재 수요 설정: "+row.demand()+" / Tick"));lore.addAll(EconomySupply.flowLines(row.flow()));lore.add(action==null?"공개 시장 참고 정보":"선택하면 상점의 최신 견적 조회");
                button(screen,slot++,Objects.requireNonNull(Material.matchMaterial(store.content().goods().get(row.good()).material())),row.name()+" · "+stock(row.status()),action,lore.toArray(String[]::new));
            }}
            case REGIONS->{total=view.regions().size();for(var row:EconomyReport.page(view.regions(),page))button(screen,slot++,Material.MAP,row.name(),info(Kind.MARKET,row.id(),0),"공개 지역 시장 조회");}
            case ROUTES->{total=view.routes().size();for(var row:EconomyReport.page(view.routes(),page))button(screen,slot++,row.blocked()?Material.BARRIER:Material.CHEST,row.name(),null,
                row.from()+" → "+row.to()+" / "+row.good(),"신규 출발: "+(row.blocked()?"차단":"경로 개방"),"기본 운송량: "+row.capacity()+" / 지연: "+row.delay()+" Tick","운송 중: "+row.inTransit()+"개 / "+row.shipments()+"건",row.nextArrival()<0?"운송 중인 물량 없음":"가장 빠른 도착까지: "+row.nextArrival()+" Tick","운송 중 물량은 시장 재고에 포함하지 않음");}
            case EVENTS->{total=view.events().size();for(var row:EconomyReport.page(view.events(),page))button(screen,slot++,Material.BOOK,row.name(),null,row.description(),row.scheduled()?"적용 대기 · 시작까지 "+row.startsIn()+" Tick":"적용 중 · 종료까지 "+row.remaining()+" Tick","운영 시간 단위는 설정한 경제 Tick");}
            case FACILITIES->{total=view.facilities().size();for(var row:EconomyReport.page(view.facilities(),page)){
                var lore=new ArrayList<String>();lore.add("생산 품목: "+row.good());lore.add("현재 생산 상한: "+row.capacity()+" / Tick");lore.add("현재 재고 단독 계산: "+row.possible()+" / "+row.restriction());for(var input:row.inputs())lore.add(input.name()+" · 1회 소모 "+input.units()+" / 현재 재고 "+input.stock());lore.add("단독 참고값이며 다음 Tick 실제 실적은 도착·시설 순서에 따라 다릅니다");button(screen,slot++,Material.FURNACE,row.name(),null,lore.toArray(String[]::new));
            }}
            default->throw new IllegalArgumentException("ECON_REPORT_VIEW");
        }
        if(total==0)button(screen,22,Material.PAPER,switch(kind){case EVENTS->"현재 공개된 경제 사건이 없습니다";case ROUTES->"공개된 관련 운송로가 없습니다";case FACILITIES->"공개된 생산시설이 없습니다";default->"공개 정보가 없습니다";},null);
        button(screen,45,Material.ARROW,"상품 목록",nav(Kind.CATALOG,null,0,0));button(screen,46,Material.MAP,"지역 선택",info(Kind.REGIONS,view.region().id(),0));
        button(screen,47,Material.COMPASS,"재고·가격",info(Kind.MARKET,view.region().id(),0));button(screen,48,Material.CHEST,"운송",info(Kind.ROUTES,view.region().id(),0));
        button(screen,49,Material.BOOK,"경제 사건",info(Kind.EVENTS,view.region().id(),0));button(screen,50,Material.FURNACE,"생산시설",info(Kind.FACILITIES,view.region().id(),0));
        button(screen,51,Material.CLOCK,"시장 정보 새로고침",info(Kind.REFRESH,view.region().id(),page),"조회 시점: 경제 Tick "+view.tick());
        if(page>0)button(screen,52,Material.ARROW,"이전 페이지",info(kind,view.region().id(),page-1));if((page+1)*45<total)button(screen,53,Material.ARROW,"다음 페이지",info(kind,view.region().id(),page+1));return screen;
    }
    public static String flowText(EconomyEngine.Flow flow){return flow==null?"완료 Tick 실적 미등록":"완료 Tick "+flow.tick()+" · 생산 "+flow.produced()+" / 제조 투입 "+flow.inputs()+" / 실제 소비 "+flow.consumed()+" / 미충족 "+flow.shortage()+" / 도착 "+flow.arrived()+" / 출발 "+flow.departed()+" / 재고 "+flow.before()+" → "+flow.after();}
    /** InventoryClickEvent 안에서 화면을 직접 바꾸지 않고 다음 틱에 현재 화면/세션을 재검사합니다. */
    public void deferReportView(java.util.function.BooleanSupplier current,Runnable change){main();Bukkit.getScheduler().runTaskLater(plugin,()->{if(!closed&&current.getAsBoolean())change.run();},1);}
    public Inventory previewReport(PlayerSessions.Session session,EconomyReport.View view,String section,int page){main();store.validate(session.context());return information(session,view,Kind.valueOf(section),page,merchantVisit(merchants.first().id())).inventory;}
    /** 기존 렌더러의 실제 Inventory 객체 검사 경계. 화면을 열거나 Player를 만들지 않습니다. */
    public Inventory preview(PlayerSessions.Session session,List<EconomyStore.Offer> offers){main();store.validate(session.context());return catalog(session,offers,0,merchantVisit(merchants.first().id())).inventory;}
    public Inventory previewProduct(PlayerSessions.Session session,EconomyStore.Offer offer){main();store.validate(session.context());return product(session,offer,merchantVisit(merchants.first().id())).inventory;}
    public Inventory previewAt(String id,PlayerSessions.Session session,List<EconomyStore.Offer> offers){main();store.validate(session.context());return catalog(session,offers,0,merchantVisit(id)).inventory;}
    public Inventory previewProductAt(String id,PlayerSessions.Session session,EconomyStore.Offer offer){main();store.validate(session.context());return product(session,offer,merchantVisit(id)).inventory;}
    public Inventory previewReportAt(String id,PlayerSessions.Session session,EconomyReport.View view,String section,int page){main();store.validate(session.context());return information(session,view,Kind.valueOf(section),page,merchantVisit(id)).inventory;}
    public Inventory previewQuantity(String id,PlayerSessions.Session session,EconomyStore.Offer offer,int quantity,InventoryPayload payload){main();store.validate(session.context());return product(session,offer,merchantVisit(id),quantity,payload).inventory;}
    public Inventory previewConfirmation(String id,PlayerSessions.Session session,EconomyStore.Offer offer,int quantity,boolean buy,InventoryPayload payload){main();store.validate(session.context());return confirmation(session,offer,merchantVisit(id),quantity,buy,payload).inventory;}
    public Inventory previewResult(String id,PlayerSessions.Session session,String good,int quantity,boolean buy,EconomyStore.Receipt receipt){main();store.validate(session.context());return result(session,merchantVisit(id),good,quantity,buy,receipt).inventory;}
    public void open(Player player){openAt(player,merchants.first().id());}
    private void open(Player player,Kind kind,String good,int page,long cursor){open(player,kind,good,page,cursor,null);}
    private void open(Player player,Kind kind,String good,int page,long cursor,String region){
        main();var visit=visits.get(player.getUniqueId());var session=adapter.current(player);if(visit==null||session==null||!current(player,visit)||pending.containsKey(player.getUniqueId()))return;var shop=shop(visit);
        if(kind==Kind.PRODUCT&&!shop.goods().contains(good))return;
        if(INFORMATION.contains(kind)&&!report(visit).allows(region)||SUPPLY_VIEWS.contains(kind)&&!report(visit).allowsGood(good))return;
        UUID nonce=UUID.randomUUID();pending.put(player.getUniqueId(),nonce);long generation=world.generation();
        CompletionStage<Screen> reading=switch(kind){
            case CATALOG->store.offers(session.context(),shop.region(),shop.goods()).thenApplyAsync(rows->catalog(session,rows,page,visit),owner);
            case PRODUCT->store.offer(session.context(),new EconomyContent.Key(shop.region(),good)).thenApplyAsync(offer->product(session,offer,visit,1,codec.capture(player.getInventory())),owner);
            case MARKET,REGIONS,ROUTES,EVENTS,FACILITIES->store.report(session.context(),report(visit),region).thenApplyAsync(view->information(session,view,kind,page,visit),owner);
            case SUPPLY,SUPPLY_GOODS,GUIDE->supply(session.context(),visit.merchant(),good).thenApplyAsync(view->supplyScreen(session,view,kind,page,visit),owner);
            case HISTORY->store.history(session.context(),cursor,20).thenApplyAsync(rows->history(session,rows,visit),owner);
            default->throw new IllegalArgumentException("ECON_SHOP_VIEW");};
        reading.whenCompleteAsync((screen,error)->{
            if(!nonce.equals(pending.get(player.getUniqueId())))return;pending.remove(player.getUniqueId());
            if(closed||adapter.current(player)!=session||world.generation()!=generation||!current(player,visit))return;
            if(error!=null){var old=screens.get(player.getUniqueId());if(old!=null)old.busy=false;player.sendMessage(Component.text("상점 조회 실패 · 빈 기록이나 거래 성공으로 처리하지 않았습니다."));return;}
            screens.put(player.getUniqueId(),screen);player.openInventory(screen.inventory);
        },owner);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof PaperEconomy.Screen screen))return;e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player player)||!valid(player,screen)||screen.busy||e.getClickedInventory()!=screen.inventory||e.getClick()!=ClickType.LEFT)return;
        var action=screen.actions.get(e.getSlot());if(action==null)return;screen.busy=true;
        if(action.kind()==Kind.SUPPLY_REFRESH){open(player,screen.supplyKind,screen.supply.good(),screen.supplyPage,0);return;}
        if(SUPPLY_VIEWS.contains(action.kind())&&screen.supply!=null&&screen.supply.good().equals(action.good())){
            deferReportView(()->valid(player,screen)&&player.getOpenInventory().getTopInventory()==screen.inventory,()->{try{var next=supplyScreen(screen.session,screen.supply,action.kind(),action.page(),screen.visit);screens.put(player.getUniqueId(),next);player.openInventory(next.inventory);}catch(RuntimeException error){screen.busy=false;player.sendMessage(Component.text("공급망 화면 전환 실패"));}});return;
        }
        if(action.kind()==Kind.REFRESH){open(player,screen.reportKind,null,screen.reportPage,0,screen.report.region().id());return;}
        if(INFORMATION.contains(action.kind())&&screen.report!=null&&screen.report.region().id().equals(action.region())){
            deferReportView(()->valid(player,screen)&&player.getOpenInventory().getTopInventory()==screen.inventory,()->{
                try{var next=information(screen.session,screen.report,action.kind(),action.page(),screen.visit);screens.put(player.getUniqueId(),next);player.openInventory(next.inventory);}catch(RuntimeException error){screen.busy=false;player.sendMessage(Component.text("시장 화면 전환 실패"));}
            });return;
        }
        if(Set.of(Kind.QUANTITY,Kind.MAX_BUY,Kind.MAX_SELL,Kind.BUY,Kind.SELL).contains(action.kind())){
            deferReportView(()->valid(player,screen)&&player.getOpenInventory().getTopInventory()==screen.inventory,()->{
                try{
                    var payload=codec.capture(player.getInventory());int quantity=action.quantity();Screen next;
                    if(action.kind()==Kind.MAX_BUY||action.kind()==Kind.MAX_SELL){quantity=EconomyQuote.maximum(store.content(),screen.offer,trades.capacity(payload,action.good()),action.kind()==Kind.MAX_BUY);if(quantity==0){screen.busy=false;player.sendMessage(Component.text("현재 선택한 상품으로 거래 가능한 수량이 없습니다."));return;}}
                    next=action.kind()==Kind.BUY||action.kind()==Kind.SELL?confirmation(screen.session,screen.offer,screen.visit,quantity,action.kind()==Kind.BUY,payload):product(screen.session,screen.offer,screen.visit,quantity,payload);
                    screens.put(player.getUniqueId(),next);player.openInventory(next.inventory);
                }catch(RuntimeException error){screen.busy=false;player.sendMessage(Component.text("견적 생성 실패 · 거래하지 않았습니다."));}
            });return;
        }
        if(action.kind()!=Kind.CONFIRM_BUY&&action.kind()!=Kind.CONFIRM_SELL){open(player,action.kind(),action.good(),action.page(),action.cursor(),action.region());return;}
        if(screen.offer==null||screen.quote==null||!screen.quote.available()||screen.quote.quantity()!=action.quantity()||screen.quote.buy()!=(action.kind()==Kind.CONFIRM_BUY)||!screen.offer.key().good().equals(action.good())){screen.busy=false;return;}
        merchants.get(screen.visit.merchant()).require(screen.offer);boolean buy=action.kind()==Kind.CONFIRM_BUY;
        trades.execute(sessions,screen.session,screen.offer,action.quantity(),buy,action.request(),fence(screen.session)::get,EconomyStore.Probe.NONE).whenCompleteAsync((receipt,error)->{
            if(adapter.linked(player)!=screen.session||world.generation()!=screen.generation)return;
            player.sendMessage(Component.text(error==null?(buy?"구매":"판매")+" 완료 · "+action.quantity()+"개 / 실제 총액 "+receipt.total()+" / 잔액 "+receipt.account().balance():tradeFailure(error)+" · 요청 "+action.request()));
            if(screens.get(player.getUniqueId())==screen&&adapter.current(player)==screen.session&&player.getOpenInventory().getTopInventory()==screen.inventory){
                if(error==null){var next=result(screen.session,screen.visit,action.good(),action.quantity(),buy,receipt);screens.put(player.getUniqueId(),next);player.openInventory(next.inventory);}else open(player,Kind.PRODUCT,action.good(),0,0);
            }
        },owner);
    }

    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof PaperEconomy.Screen)e.setCancelled(true);}
    @EventHandler public void screenClosed(InventoryCloseEvent e){if(e.getInventory().getHolder() instanceof PaperEconomy.Screen screen&&screens.remove(e.getPlayer().getUniqueId(),screen))pending.remove(e.getPlayer().getUniqueId());}
    @EventHandler(priority=EventPriority.LOWEST) public void quit(PlayerQuitEvent e){var session=adapter.linked(e.getPlayer());if(session!=null){invalidate(session);fences.remove(session);}screens.remove(e.getPlayer().getUniqueId());pending.remove(e.getPlayer().getUniqueId());visits.remove(e.getPlayer().getUniqueId());}
    public CompletionStage<Void> applyEvent(String event,UUID request){main();if(closed)throw new IllegalStateException("ECON_CLOSED");if(!store.content().events().containsKey(event))throw new IllegalArgumentException("ECON_EVENT_MISSING");return store.event(REALM,Objects.requireNonNull(request),event);}
    public void administration(String[] args){
        main();if(closed)throw new IllegalStateException("ECON_CLOSED");
        if(args[0].equals("economy-event")){applyEvent(args[1],UUID.fromString(args[2])).whenCompleteAsync((v,error)->plugin.getLogger().info(error==null?"HEARTH_ECONOMY_EVENT 예약 확인 · "+args[1]+" / 요청 "+args[2]:"HEARTH_ECONOMY_EVENT 보류 · "+InventorySaveSession.code(error)),owner);return;}
        var context=store.context(REALM,UUID.fromString(args[1]));CompletionStage<String> work;
        if(args[0].equals("economy-history")){long cursor=args.length==3?Long.parseLong(args[2]):Long.MAX_VALUE;work=store.history(context,cursor,20).thenApply(rows->"거래 기록 "+rows);}
        else if(args[0].equals("economy-pending")){work=CompletableFuture.completedFuture("보류 요청 "+trades.pending(context));}
        else{UUID request=UUID.fromString(args[2]);work=(args[0].equals("economy-inspect")?trades.inspect(context,request):trades.recover(sessions,context,request,Long.parseLong(args[3]),()->!closed&&Bukkit.getPlayer(context.playerId())==null)).thenApply(view->"요청 "+view.request()+" / DB revision "+view.revision()+" / "+view.decision()+" / 월드 "+world.state());}
        work.whenCompleteAsync((text,error)->plugin.getLogger().info(error==null?"HEARTH_ECONOMY_ADMIN "+text:"HEARTH_ECONOMY_ADMIN HOLD · "+InventorySaveSession.code(error)),owner);
    }
    public void fixture(String profile,boolean existing){main();if(!profile.matches("[A-Za-z0-9_]{3,16}"))throw new IllegalArgumentException("ECON_PROFILE");Bukkit.createPlayerProfile(profile).update().thenComposeAsync(p->{if(p.getUniqueId()==null||!p.isComplete())throw new IllegalStateException("ECON_AUTH_PROFILE");var ctx=store.context(REALM,p.getUniqueId());var slots=new ItemStack[41];slots[0]=new ItemStack(Material.IRON_INGOT,8);var prepared=existing?store.account(ctx).thenApply(a->(Void)null):store.createAccount(ctx,store.content().fixtureBalance(),codec.encode(slots));return prepared.thenRunAsync(()->{adapter.fixture(new PaperSessionAdapter.Link(ctx,world,this::entry));plugin.getLogger().info("HEARTH_ECONOMY_FIXTURE READY "+profile);},owner);},owner).exceptionally(error->{plugin.getLogger().warning("HEARTH_ECONOMY_FIXTURE HOLD");return null;});}
    public void startTicker(){main();if(ticker!=null)throw new IllegalStateException("ECON_TICKER_EXISTS");ticking=store.resumeClock(REALM,System.currentTimeMillis()).whenCompleteAsync((v,error)->{if(error!=null){tickHold=true;plugin.getLogger().warning("HEARTH_ECONOMY_OFFLINE_HOLD");}},owner);nextTick=System.nanoTime()+store.content().tickMillis()*1000000;ticker=Bukkit.getScheduler().runTaskTimer(plugin,()->{pulses++;if(closed||tickHold||!ticking.toCompletableFuture().isDone()||System.nanoTime()<nextTick)return;nextTick=System.nanoTime()+store.content().tickMillis()*1000000;ticking=store.snapshot(REALM).thenCompose(s->store.tick(REALM,s.tick(),System.currentTimeMillis())).whenCompleteAsync((v,error)->{if(error!=null){tickHold=true;plugin.getLogger().warning("HEARTH_ECONOMY_TICK_HOLD");}},owner);},1,1);}
    public CompletionStage<Void> stopTicker(){main();if(ticker!=null){ticker.cancel();ticker=null;}return ticking.thenApply(v->null);}
    public CompletionStage<Void> shutdown(){main();closed=true;screens.clear();pending.clear();adapter.close();HandlerList.unregisterAll(this);autosave.close();return stopTicker().thenComposeAsync(v->sessions.shutdown(),owner);}
    @Override public void close(){if(ticker!=null)ticker.cancel();adapter.close();autosave.close();HandlerList.unregisterAll(this);removeMerchants();releaseFixtureChunks();}
}
