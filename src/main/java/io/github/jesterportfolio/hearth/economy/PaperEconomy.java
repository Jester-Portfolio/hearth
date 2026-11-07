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
    public final EconomyShop shop;public final EconomyReport.Definition reportDefinition;private final org.bukkit.util.Vector merchantPoint;
    private enum Kind { CATALOG, PRODUCT, HISTORY, BUY, SELL, MARKET, REGIONS, ROUTES, EVENTS, FACILITIES, REFRESH }
    private static final Set<Kind> INFORMATION=Set.of(Kind.MARKET,Kind.REGIONS,Kind.ROUTES,Kind.EVENTS,Kind.FACILITIES);
    private record Action(Kind kind,String good,int quantity,UUID request,long cursor,int page,String region){}
    private final class Screen implements InventoryHolder {
        final UUID player;final PlayerSessions.Session session;final long generation;final EconomyStore.Offer offer;final Inventory inventory;final Map<Integer,Action> actions=new HashMap<>();boolean busy;EconomyReport.View report;Kind reportKind;int reportPage;
        Screen(PlayerSessions.Session session,EconomyStore.Offer offer,String title){this.player=session.context().playerId();this.session=session;this.offer=offer;generation=world.generation();inventory=Bukkit.createInventory(this,54,Component.text(title));}
        public Inventory getInventory(){return inventory;}
    }
    public static EconomyContent load(JavaPlugin plugin,Properties settings)throws Exception{var file=plugin.getDataFolder().toPath().resolve("economy-demo.properties");if(!Files.exists(file))plugin.saveResource("economy-demo.properties",false);try(var reader=Files.newBufferedReader(file)){settings.load(reader);}return EconomyContent.parse(settings);}
    public PaperEconomy(JavaPlugin plugin,PostgresProgressStore db,Executor owner,PaperInventoryCodec codec,Path root,Properties settings,EconomyContent content,boolean resume)throws Exception{
        main();if(!plugin.getConfig().getBoolean("economy-verification-enabled")||!Boolean.getBoolean("hearth.verification.economy")||!Bukkit.getOnlinePlayers().isEmpty())throw new IllegalStateException("ECON_ISOLATED_GATE");this.plugin=plugin;this.db=db;this.owner=owner;this.codec=codec;this.settings=settings;mappingFile=root.resolve("economy-world.properties");store=new EconomyStore(db,content);trades=new EconomyTrade(store,codec,owner);shop=EconomyShop.parse(settings,content);reportDefinition=EconomyReport.parse(settings,content,shop);
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
    private void button(Screen screen,int slot,Material material,String label,Action action,String... lore){
        var item=new ItemStack(material);item.editMeta(meta->{meta.displayName(Component.text(label));meta.lore(Arrays.stream(lore).map(Component::text).toList());});screen.inventory.setItem(slot,item);if(action!=null)screen.actions.put(slot,action);
    }
    private Action nav(Kind kind,String good,int page,long cursor){return new Action(kind,good,0,null,cursor,page,null);}
    private Screen catalog(PlayerSessions.Session session,List<EconomyStore.Offer> offers,int page){
        var screen=new Screen(session,null,"지역 상점 · "+shop.region());var byGood=new HashMap<String,EconomyStore.Offer>();for(var offer:offers)byGood.put(offer.key().good(),offer);
        int slot=0;for(String good:shop.page(page)){var offer=Objects.requireNonNull(byGood.get(good));String ask=offer.market().stock()>0?""+EconomyEngine.quote(store.content(),good,offer.market(),1,true):"품절";
            button(screen,slot++,Objects.requireNonNull(Material.matchMaterial(store.content().goods().get(good).material())),shop.name(good),nav(Kind.PRODUCT,good,0,0),"재고: "+offer.market().stock(),"1개 구매: "+ask,"1개 판매: "+EconomyEngine.quote(store.content(),good,offer.market(),1,false),"개발 화폐 잔액: "+offer.balance());}
        if(page>0)button(screen,45,Material.ARROW,"이전 상품",nav(Kind.CATALOG,null,page-1,0));if((page+1)*45<shop.goods().size())button(screen,53,Material.ARROW,"다음 상품",nav(Kind.CATALOG,null,page+1,0));
        button(screen,48,Material.COMPASS,"시장 상황",info(Kind.MARKET,shop.region(),0));button(screen,49,Material.BOOK,"본인 거래 기록",nav(Kind.HISTORY,null,0,Long.MAX_VALUE));button(screen,50,Material.CLOCK,"시세 새로고침",nav(Kind.CATALOG,null,page,0));return screen;
    }
    private Screen product(PlayerSessions.Session session,EconomyStore.Offer offer){
        var screen=new Screen(session,offer,shop.name(offer.key().good())+" · 구매/판매");button(screen,4,Objects.requireNonNull(Material.matchMaterial(store.content().goods().get(offer.key().good()).material())),shop.name(offer.key().good()),null,"재고: "+offer.market().stock(),"개발 화폐 잔액: "+offer.balance());
        int buy=10,sell=28;for(var price:shop.prices(store.content(),offer)){int slot=price.buy()?buy++:sell++;button(screen,slot,Material.PAPER,(price.buy()?"구매 ":"판매 ")+price.quantity()+"개 · "+price.total(),new Action(price.buy()?Kind.BUY:Kind.SELL,offer.key().good(),price.quantity(),UUID.randomUUID(),0,0,null));}
        button(screen,45,Material.ARROW,"상품 목록",nav(Kind.CATALOG,null,0,0));button(screen,48,Material.COMPASS,"시장 상황",info(Kind.MARKET,shop.region(),0));button(screen,49,Material.BOOK,"본인 거래 기록",nav(Kind.HISTORY,null,0,Long.MAX_VALUE));button(screen,50,Material.CLOCK,"견적 새로고침",nav(Kind.PRODUCT,offer.key().good(),0,0));return screen;
    }
    private Screen history(PlayerSessions.Session session,List<EconomyStore.Entry> rows){
        var screen=new Screen(session,null,"본인 거래 기록");int slot=0;
        for(var row:rows){String name=shop.names().getOrDefault(row.key().good(),row.key().good());button(screen,slot++,Material.PAPER,(row.buy()?"구매 · ":"판매 · ")+name,null,"수량: "+row.quantity()+" / 총액: "+row.total(),"지역: "+row.key().region(),"거래 revision: "+row.inventoryRevision(),"요청: "+row.id());}
        if(rows.isEmpty())button(screen,22,Material.PAPER,"거래 기록이 없습니다",null);
        button(screen,45,Material.ARROW,"상품 목록",nav(Kind.CATALOG,null,0,0));button(screen,49,Material.CLOCK,"최신 기록",nav(Kind.HISTORY,null,0,Long.MAX_VALUE));
        if(rows.size()==20)button(screen,53,Material.ARROW,"이전 거래",nav(Kind.HISTORY,null,0,rows.getLast().inventoryRevision()));return screen;
    }
    private Action info(Kind kind,String region,int page){return new Action(kind,null,0,null,0,page,region);}
    private String price(Long value){return value==null?"거래 불가":value.toString();}
    private String stock(EconomyEngine.StockStatus value){return switch(value){case NORMAL->"보통";case SHORTAGE->"부족";case SURPLUS->"공급 과잉";};}
    private Screen information(PlayerSessions.Session session,EconomyReport.View view,Kind kind,int page){
        if(!INFORMATION.contains(kind)||!reportDefinition.allows(view.region().id()))throw new IllegalArgumentException("ECON_REPORT_VIEW");
        var screen=new Screen(session,null,view.region().name()+" · 시장 정보");screen.report=view;screen.reportKind=kind;screen.reportPage=page;int slot=0,total;
        switch(kind){
            case MARKET->{total=view.markets().size();for(var row:EconomyReport.page(view.markets(),page)){
                Action action=view.region().id().equals(shop.region())&&shop.goods().contains(row.good())?nav(Kind.PRODUCT,row.good(),0,0):null;
                button(screen,slot++,Objects.requireNonNull(Material.matchMaterial(store.content().goods().get(row.good()).material())),row.name()+" · "+stock(row.status()),action,
                    "조회 시점: 경제 Tick "+view.tick(),"판매 가능 재고: "+row.stock()+" / 목표: "+row.target(),"품목 기준가: "+row.base()+" / 시장 기준가: "+row.anchor(),"1개 구매: "+price(row.ask())+" / 판매: "+price(row.bid()),"현재 수요 설정: "+row.demand()+" / Tick",action==null?"공개 시장 참고 정보":"선택하면 상점의 최신 견적 조회");
            }}
            case REGIONS->{total=view.regions().size();for(var row:EconomyReport.page(view.regions(),page))button(screen,slot++,Material.MAP,row.name(),info(Kind.MARKET,row.id(),0),"공개 지역 시장 조회");}
            case ROUTES->{total=view.routes().size();for(var row:EconomyReport.page(view.routes(),page))button(screen,slot++,row.blocked()?Material.BARRIER:Material.CHEST,row.name(),null,
                row.from()+" → "+row.to()+" / "+row.good(),"신규 출발: "+(row.blocked()?"차단":"경로 개방"),"기본 운송량: "+row.capacity()+" / 지연: "+row.delay()+" Tick","운송 중: "+row.inTransit()+"개 / "+row.shipments()+"건",row.nextArrival()<0?"운송 중인 물량 없음":"가장 빠른 도착까지: "+row.nextArrival()+" Tick","운송 중 물량은 시장 재고에 포함하지 않음");}
            case EVENTS->{total=view.events().size();for(var row:EconomyReport.page(view.events(),page))button(screen,slot++,Material.BOOK,row.name(),null,row.description(),row.scheduled()?"적용 대기 · 시작까지 "+row.startsIn()+" Tick":"적용 중 · 종료까지 "+row.remaining()+" Tick","운영 시간 단위는 설정한 경제 Tick");}
            case FACILITIES->{total=view.facilities().size();for(var row:EconomyReport.page(view.facilities(),page)){
                var lore=new ArrayList<String>();lore.add("생산 품목: "+row.good());lore.add("현재 생산 상한: "+row.capacity()+" / Tick");for(var input:row.inputs())lore.add(input.name()+" · 1회 소모 "+input.units()+" / 현재 재고 "+input.stock());lore.add("실제 생산량은 재료·목표 재고에 따라 제한");button(screen,slot++,Material.FURNACE,row.name(),null,lore.toArray(String[]::new));
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
    /** InventoryClickEvent 안에서 화면을 직접 바꾸지 않고 다음 틱에 현재 화면/세션을 재검사합니다. */
    public void deferReportView(java.util.function.BooleanSupplier current,Runnable change){main();Bukkit.getScheduler().runTaskLater(plugin,()->{if(!closed&&current.getAsBoolean())change.run();},1);}
    public Inventory previewReport(PlayerSessions.Session session,EconomyReport.View view,String section,int page){main();store.validate(session.context());return information(session,view,Kind.valueOf(section),page).inventory;}
    /** 기존 렌더러의 실제 Inventory 객체 검사 경계. 화면을 열거나 Player를 만들지 않습니다. */
    public Inventory preview(PlayerSessions.Session session,List<EconomyStore.Offer> offers){main();store.validate(session.context());return catalog(session,offers,0).inventory;}
    public Inventory previewProduct(PlayerSessions.Session session,EconomyStore.Offer offer){main();store.validate(session.context());return product(session,offer).inventory;}
    public void open(Player player){open(player,Kind.CATALOG,null,0,0);}
    private void open(Player player,Kind kind,String good,int page,long cursor){open(player,kind,good,page,cursor,null);}
    private void open(Player player,Kind kind,String good,int page,long cursor,String region){
        main();var session=adapter.current(player);if(session==null||!near(player)||pending.containsKey(player.getUniqueId()))return;
        if(kind==Kind.PRODUCT&&!shop.goods().contains(good))return;
        if(INFORMATION.contains(kind)&&!reportDefinition.allows(region))return;
        UUID nonce=UUID.randomUUID();pending.put(player.getUniqueId(),nonce);long generation=world.generation();
        CompletionStage<Screen> reading=switch(kind){
            case CATALOG->store.offers(session.context(),shop.region(),shop.goods()).thenApplyAsync(rows->catalog(session,rows,page),owner);
            case PRODUCT->store.offer(session.context(),new EconomyContent.Key(shop.region(),good)).thenApplyAsync(offer->product(session,offer),owner);
            case MARKET,REGIONS,ROUTES,EVENTS,FACILITIES->store.report(session.context(),reportDefinition,region).thenApplyAsync(view->information(session,view,kind,page),owner);
            case HISTORY->store.history(session.context(),cursor,20).thenApplyAsync(rows->history(session,rows),owner);
            default->throw new IllegalArgumentException("ECON_SHOP_VIEW");};
        reading.whenCompleteAsync((screen,error)->{
            if(!nonce.equals(pending.get(player.getUniqueId())))return;pending.remove(player.getUniqueId());
            if(closed||adapter.current(player)!=session||world.generation()!=generation||!near(player))return;
            if(error!=null){var old=screens.get(player.getUniqueId());if(old!=null)old.busy=false;player.sendMessage(Component.text("상점 조회 실패 · 빈 기록이나 거래 성공으로 처리하지 않았습니다."));return;}
            screens.put(player.getUniqueId(),screen);player.openInventory(screen.inventory);
        },owner);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof PaperEconomy.Screen screen))return;e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player player)||!valid(player,screen)||screen.busy||e.getClickedInventory()!=screen.inventory||e.getClick()!=ClickType.LEFT)return;
        var action=screen.actions.get(e.getSlot());if(action==null)return;screen.busy=true;
        if(action.kind()==Kind.REFRESH){open(player,screen.reportKind,null,screen.reportPage,0,screen.report.region().id());return;}
        if(INFORMATION.contains(action.kind())&&screen.report!=null&&screen.report.region().id().equals(action.region())){
            deferReportView(()->valid(player,screen)&&player.getOpenInventory().getTopInventory()==screen.inventory,()->{
                try{var next=information(screen.session,screen.report,action.kind(),action.page());screens.put(player.getUniqueId(),next);player.openInventory(next.inventory);}catch(RuntimeException error){screen.busy=false;player.sendMessage(Component.text("시장 화면 전환 실패"));}
            });return;
        }
        if(action.kind()!=Kind.BUY&&action.kind()!=Kind.SELL){open(player,action.kind(),action.good(),action.page(),action.cursor(),action.region());return;}
        trades.execute(sessions,screen.session,screen.offer,action.quantity(),action.kind()==Kind.BUY,action.request(),fence(screen.session)::get,EconomyStore.Probe.NONE).whenCompleteAsync((receipt,error)->{
            if(adapter.linked(player)!=screen.session||world.generation()!=screen.generation)return;
            player.sendMessage(Component.text(error==null?"경제 거래 DB 반영·인벤토리 적용 완료":"거래 거부 또는 보류 · 요청 "+action.request()+" · 성공으로 처리하지 않았습니다."));
            if(screens.get(player.getUniqueId())==screen&&adapter.current(player)==screen.session&&player.getOpenInventory().getTopInventory()==screen.inventory)open(player,Kind.PRODUCT,action.good(),0,0);
        },owner);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof PaperEconomy.Screen)e.setCancelled(true);}
    @EventHandler public void screenClosed(InventoryCloseEvent e){if(e.getInventory().getHolder() instanceof PaperEconomy.Screen screen&&screens.remove(e.getPlayer().getUniqueId(),screen))pending.remove(e.getPlayer().getUniqueId());}
    @EventHandler(priority=EventPriority.LOWEST) public void quit(PlayerQuitEvent e){var session=adapter.linked(e.getPlayer());if(session!=null){invalidate(session);fences.remove(session);}screens.remove(e.getPlayer().getUniqueId());pending.remove(e.getPlayer().getUniqueId());}
    public void administration(String[] args){
        main();if(closed)throw new IllegalStateException("ECON_CLOSED");var context=store.context(REALM,UUID.fromString(args[1]));CompletionStage<String> work;
        if(args[0].equals("economy-history")){long cursor=args.length==3?Long.parseLong(args[2]):Long.MAX_VALUE;work=store.history(context,cursor,20).thenApply(rows->"거래 기록 "+rows);}
        else if(args[0].equals("economy-pending")){work=CompletableFuture.completedFuture("보류 요청 "+trades.pending(context));}
        else{UUID request=UUID.fromString(args[2]);work=(args[0].equals("economy-inspect")?trades.inspect(context,request):trades.recover(sessions,context,request,Long.parseLong(args[3]),()->!closed&&Bukkit.getPlayer(context.playerId())==null)).thenApply(view->"요청 "+view.request()+" / DB revision "+view.revision()+" / "+view.decision()+" / 월드 "+world.state());}
        work.whenCompleteAsync((text,error)->plugin.getLogger().info(error==null?"HEARTH_ECONOMY_ADMIN "+text:"HEARTH_ECONOMY_ADMIN HOLD · "+InventorySaveSession.code(error)),owner);
    }
    public void fixture(String profile,boolean existing){main();if(!profile.matches("[A-Za-z0-9_]{3,16}"))throw new IllegalArgumentException("ECON_PROFILE");Bukkit.createPlayerProfile(profile).update().thenComposeAsync(p->{if(p.getUniqueId()==null||!p.isComplete())throw new IllegalStateException("ECON_AUTH_PROFILE");var ctx=store.context(REALM,p.getUniqueId());var slots=new ItemStack[41];slots[0]=new ItemStack(Material.IRON_INGOT,8);var prepared=existing?store.account(ctx).thenApply(a->(Void)null):store.createAccount(ctx,store.content().fixtureBalance(),codec.encode(slots));return prepared.thenRunAsync(()->{adapter.fixture(new PaperSessionAdapter.Link(ctx,world,this::entry));plugin.getLogger().info("HEARTH_ECONOMY_FIXTURE READY "+profile);},owner);},owner).exceptionally(error->{plugin.getLogger().warning("HEARTH_ECONOMY_FIXTURE HOLD");return null;});}
    public void startTicker(){main();if(ticker!=null)throw new IllegalStateException("ECON_TICKER_EXISTS");ticking=store.resumeClock(REALM,System.currentTimeMillis()).whenCompleteAsync((v,error)->{if(error!=null){tickHold=true;plugin.getLogger().warning("HEARTH_ECONOMY_OFFLINE_HOLD");}},owner);nextTick=System.nanoTime()+store.content().tickMillis()*1000000;ticker=Bukkit.getScheduler().runTaskTimer(plugin,()->{pulses++;if(closed||tickHold||!ticking.toCompletableFuture().isDone()||System.nanoTime()<nextTick)return;nextTick=System.nanoTime()+store.content().tickMillis()*1000000;ticking=store.snapshot(REALM).thenCompose(s->store.tick(REALM,s.tick(),System.currentTimeMillis())).whenCompleteAsync((v,error)->{if(error!=null){tickHold=true;plugin.getLogger().warning("HEARTH_ECONOMY_TICK_HOLD");}},owner);},1,1);}
    public CompletionStage<Void> stopTicker(){main();if(ticker!=null){ticker.cancel();ticker=null;}return ticking.thenApply(v->null);}
    public CompletionStage<Void> shutdown(){main();closed=true;screens.clear();pending.clear();adapter.close();HandlerList.unregisterAll(this);autosave.close();return stopTicker().thenComposeAsync(v->sessions.shutdown(),owner);}
    @Override public void close(){if(ticker!=null)ticker.cancel();adapter.close();autosave.close();HandlerList.unregisterAll(this);if(merchant!=null)merchant.remove();}
}
