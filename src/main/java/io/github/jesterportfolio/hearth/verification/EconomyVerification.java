package io.github.jesterportfolio.hearth.verification;

import io.github.jesterportfolio.hearth.economy.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.session.*;
import io.github.jesterportfolio.hearth.lifecycle.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.nio.file.*;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** 기존 콘솔·DB·Paper 시험 경계의 경제 시나리오. 인증 접속이나 NPC 클릭을 대체하지 않습니다. */
public final class EconomyVerification implements AutoCloseable {
    private final JavaPlugin plugin;private final PostgresProgressStore db;private final Executor owner;private final PaperInventoryCodec codec;private final Path evidence;private final boolean resume;
    public final PaperEconomy economy;private final List<Actor> actors=new ArrayList<>();private final Map<String,Long> timings=new TreeMap<>();
    private static final EconomyContent.Key KEY=new EconomyContent.Key("capital","iron_ingot");
    private final class Actor implements PlayerSessions.Port {final ServerProgressAccess.Context context;InventoryPayload value;PlayerSessions.Session session;boolean connected=true;Actor(int i){context=economy.store.context(PaperEconomy.REALM,new UUID(0x45434f,i));}public boolean connected(){return connected;}public InventoryPayload capture(){return value;}public void apply(InventoryPayload p){value=p;}public CompletionStage<Boolean> move(){return CompletableFuture.completedFuture(true);}}
    public EconomyVerification(JavaPlugin plugin,PostgresProgressStore db,Executor owner,PaperInventoryCodec codec,boolean resume)throws Exception{this.plugin=plugin;this.db=db;this.owner=owner;this.codec=codec;this.resume=resume;Path root=plugin.getDataFolder().toPath();evidence=root.resolve("economy-expected.properties");var settings=new Properties();var content=PaperEconomy.load(plugin,settings);economy=new PaperEconomy(plugin,db,owner,codec,root,settings,content,resume);economy.pinFixtureChunks();}
    private <T> CompletionStage<T> on(CompletionStage<T> task){return task.thenApplyAsync(v->v,owner);}
    private void check(boolean ok,String name){if(!ok)throw new IllegalStateException("ECON_CHECK_"+name);plugin.getLogger().info("HEARTH_SESSION_CHECK PASS economy_"+name);}
    private InventoryPayload items(int n){var slots=new ItemStack[41];slots[0]=new ItemStack(Material.IRON_INGOT,n);slots[36]=new ItemStack(Material.IRON_BOOTS);slots[40]=new ItemStack(Material.SHIELD);return codec.encode(slots);}
    private int count(InventoryPayload payload){return Arrays.stream(codec.decode(payload)).filter(Objects::nonNull).filter(i->i.getType()==Material.IRON_INGOT).mapToInt(ItemStack::getAmount).sum();}
    private CompletionStage<Void> all(List<? extends CompletionStage<?>> tasks){return on(CompletableFuture.allOf(tasks.stream().map(t->t.toCompletableFuture()).toArray(CompletableFuture[]::new)));}
    private CompletionStage<Void> rejected(CompletionStage<?> task,String code){return task.handleAsync((v,error)->{check(error!=null&&(code==null||LifecycleFailure.code(error).equals(code)),"reject_"+(code==null?"SQL_rollback":code));return null;},owner);}
    private CompletionStage<Void> waitUntil(BooleanSupplier ready){var result=new CompletableFuture<Void>();long start=System.nanoTime();Runnable[] task=new Runnable[1];task[0]=()->{try{if(ready.getAsBoolean()){result.complete(null);return;}if(System.nanoTime()-start>30_000_000_000L)throw new IllegalStateException("ECON_WAIT_TIMEOUT");Bukkit.getScheduler().runTaskLater(plugin,task[0],1);}catch(Exception e){result.completeExceptionally(e);}};task[0].run();return result;}
    public CompletionStage<Void> run(){return on(economy.store.migrate()).thenCompose(v->resume?restore():fresh());}
    private CompletionStage<Void> fresh(){return on(economy.store.createRealm(PaperEconomy.REALM,System.currentTimeMillis())).thenCompose(v->on(economy.store.createRealm(PaperEconomy.OTHER,System.currentTimeMillis()))).thenCompose(v->{var jobs=new ArrayList<CompletionStage<?>>();for(int i=1;i<=4;i++){var a=new Actor(i);actors.add(a);jobs.add(economy.store.createAccount(a.context,100000,items(8)));}jobs.add(economy.store.createAccount(economy.store.context(PaperEconomy.OTHER,actors.getFirst().context.playerId()),1234,items(3)));for(int i=5;i<=8;i++)jobs.add(economy.store.createAccount(economy.store.context(PaperEconomy.REALM,new UUID(0x45434f,i)),i==8?0:100000,items(8)));return all(jobs);}).thenCompose(v->{var jobs=new ArrayList<CompletionStage<?>>();for(var a:actors){a.session=economy.sessions.connect(a.context,economy.world,a);jobs.add(a.session.ready());}return all(jobs);}).thenRun(()->{check(economy.world.state()==WorldLifecycle.State.ACTIVE,"world_ready_4_internal_sessions");var a=actors.getFirst();a.value=items(9);economy.sessions.refresh(a.session);check(a.session.save().dirty(),"ordinary_dirty_before_trade");}).thenCompose(v->{long start=System.nanoTime();var jobs=new ArrayList<CompletionStage<?>>();for(var a:actors)jobs.add(buy(a,2,4));return all(jobs).thenRun(()->timings.put("four_trades_ns",System.nanoTime()-start));}).thenCompose(v->tradeChecks()).thenCompose(v->failures()).thenCompose(v->clockAndTransport()).thenCompose(v->sellTickRace()).thenCompose(v->offline()).thenCompose(v->capture()).thenCompose(v->cycle());}
    private CompletionStage<Void> buy(Actor a,int quantity,int retries){return on(economy.store.offer(a.context,KEY)).thenCompose(offer->economy.trades.execute(economy.sessions,a.session,offer,quantity,true,UUID.randomUUID(),economy.fence(a.session)::get,EconomyStore.Probe.NONE)).handleAsync((receipt,error)->{if(error!=null){if(retries>0&&LifecycleFailure.code(error).equals("ECON_QUOTE_CHANGED"))return buy(a,quantity,retries-1);throw new CompletionException(error);}check(count(a.value)==(a==actors.getFirst()?11:10),"trade_applied_"+a.context.playerId().getLeastSignificantBits());return CompletableFuture.<Void>completedFuture(null);},owner).thenCompose(v->v);}
    private CompletionStage<Void> tradeChecks(){var a=actors.getFirst();return on(economy.store.account(a.context)).thenCompose(row->{check(row.inventory().revision()==2&&count(row.inventory().payload())==11,"dirty_flush_before_trade_revision");return rejected(economy.store.saveInventory(a.context,0,items(1)),"CAS_CONFLICT");}).thenCompose(v->on(economy.store.account(economy.store.context(PaperEconomy.OTHER,a.context.playerId())))).thenAccept(row->check(row.balance()==1234&&count(row.inventory().payload())==3,"realm_inventory_currency_isolation")).thenCompose(v->on(db.readInventory(new ServerProgressAccess().forVerifiedServerLink(PaperEconomy.REALM,a.context.playerId()))).handleAsync((row,error)->{check(error!=null,"story_store_rejects_economy_issuer");return null;},owner)).thenCompose(v->on(economy.store.offer(a.context,KEY))).thenCompose(offer->{var id=UUID.randomUUID();var before=new InventoryStore.Stored(a.value,a.session.save().revision());var request=new EconomyStore.Request(a.context,id,KEY,1,true,offer.market().revision());var after=economy.trades.change(before.payload(),"iron_ingot",1,true);return on(economy.store.trade(request,before,after,()->true,EconomyStore.Probe.NONE)).thenCompose(first->on(economy.store.trade(request,before,after,()->true,EconomyStore.Probe.NONE)).thenAccept(replay->check(replay.replay()&&replay.account().equals(first.account()),"request_replay_no_duplicate"))).thenCompose(v->rejected(economy.store.trade(new EconomyStore.Request(a.context,id,KEY,2,true,offer.market().revision()),before,after,()->true,EconomyStore.Probe.NONE),"ECON_REQUEST_CONFLICT"));}).thenCompose(v->{// 직접 DB 멱등성 시험으로 갱신된 임대는 정상 종료 후 새 연결에서 복원합니다.
            return economy.sessions.disconnect(a.session).thenComposeAsync(x->{a.connected=true;a.session=economy.sessions.connect(a.context,economy.world,a);return a.session.ready();},owner);
        }).thenCompose(v->rejected(economy.store.account(economy.store.context(new UUID(99,99),a.context.playerId())),"ECON_ACCOUNT_MISSING"));}
    private CompletionStage<Void> failures(){var rollback=economy.store.context(PaperEconomy.REALM,new UUID(0x45434f,5));return on(economy.store.offer(rollback,KEY)).thenCompose(offer->on(economy.store.account(rollback)).thenCompose(row->{var source=row.inventory();var payload=new InventoryPayload[]{source.payload()};var save=new InventorySaveSession(economy.store,rollback,source.revision(),()->payload[0],owner,()->{},()->true,code->{});var after=economy.trades.change(source.payload(),"iron_ingot",1,true);return rejected(save.transaction(before->economy.store.trade(new EconomyStore.Request(rollback,UUID.randomUUID(),KEY,1,true,offer.market().revision()),before,after,()->true,EconomyStore.Probe.ROLLBACK).thenApply(r->r.account().inventory()),saved->payload[0]=saved.payload()),"23514").thenCompose(v->on(economy.store.account(rollback))).thenAccept(restored->check(restored.equals(row)&&save.holdCode()!=null,"rollback_original_and_HOLD_preserved"));})).thenCompose(v->{var ctx=economy.store.context(PaperEconomy.REALM,new UUID(0x45434f,6));return on(economy.store.offer(ctx,KEY)).thenCompose(offer->on(economy.store.account(ctx)).thenCompose(row->{UUID id=UUID.randomUUID();var before=row.inventory();var after=economy.trades.change(before.payload(),"iron_ingot",1,true);var save=new InventorySaveSession(economy.store,ctx,before.revision(),before::payload,owner,()->{},()->true,code->{});return rejected(save.transaction(s->economy.store.trade(new EconomyStore.Request(ctx,id,KEY,1,true,offer.market().revision()),s,after,()->true,EconomyStore.Probe.COMMIT_UNKNOWN).thenApply(r->r.account().inventory()),r->{throw new AssertionError("Unconfirmed apply");}),"COMMIT_UNKNOWN").thenCompose(x->on(economy.store.receipt(ctx,id))).thenAccept(found->check(found.isPresent()&&save.holdCode().equals("COMMIT_UNKNOWN"),"commit_unknown_receipt_found_no_auto_apply"));}));}).thenCompose(v->{var ctx=economy.store.context(PaperEconomy.REALM,new UUID(0x45434f,7));return on(economy.store.offer(ctx,KEY)).thenCompose(offer->on(economy.store.account(ctx)).thenCompose(row->rejected(economy.store.trade(new EconomyStore.Request(ctx,UUID.randomUUID(),KEY,1,true,offer.market().revision()),row.inventory(),economy.trades.change(row.inventory().payload(),"iron_ingot",1,true),()->false,EconomyStore.Probe.NONE),"STALE_CONTEXT")));}).thenCompose(v->{var ctx=economy.store.context(PaperEconomy.REALM,new UUID(0x45434f,8));return on(economy.store.offer(ctx,KEY)).thenCompose(offer->on(economy.store.account(ctx)).thenCompose(row->rejected(economy.store.trade(new EconomyStore.Request(ctx,UUID.randomUUID(),KEY,1,true,offer.market().revision()),row.inventory(),economy.trades.change(row.inventory().payload(),"iron_ingot",1,true),()->true,EconomyStore.Probe.NONE),"ECON_FUNDS")));});}
    private CompletionStage<Void> clockAndTransport(){return on(economy.store.snapshot(PaperEconomy.REALM)).thenCompose(before->{var a=economy.store.tick(PaperEconomy.REALM,before.tick(),System.currentTimeMillis());var b=economy.store.tick(PaperEconomy.REALM,before.tick(),System.currentTimeMillis());return on(a.thenCombine(b,(x,y)->x!=y)).thenAccept(one->check(one,"duplicate_tick_applied_once"));}).thenCompose(v->on(economy.store.event(PaperEconomy.REALM,new UUID(55,1),"mine_collapse"))).thenCompose(v->on(economy.store.event(PaperEconomy.REALM,new UUID(55,1),"mine_collapse"))).thenCompose(v->on(economy.store.snapshot(PaperEconomy.REALM))).thenCompose(before->{check(before.shipments().size()==1&&before.events().size()==1,"transport_reserved_and_event_idempotent");economy.startTicker();return waitUntil(()->economy.pulses>=110).thenCompose(x->on(economy.stopTicker())).thenCompose(x->on(economy.store.snapshot(PaperEconomy.REALM))).thenAccept(after->check(after.tick()>before.tick()&&economy.pulses>=110,"separate_tick_main_thread_continues"));}).thenCompose(v->on(economy.store.resumeClock(PaperEconomy.REALM,System.currentTimeMillis()))).thenAccept(n->check(n==0,"offline_pause_no_duplicate_tick"));}
    private CompletionStage<Void> sellTickRace(){
        var a=actors.get(1);return on(economy.store.offer(a.context,KEY)).thenCompose(offer->on(economy.store.snapshot(PaperEconomy.REALM)).thenCompose(snapshot->{
            long start=System.nanoTime();var ticking=economy.store.tick(PaperEconomy.REALM,snapshot.tick(),System.currentTimeMillis());
            var selling=economy.trades.execute(economy.sessions,a.session,offer,1,false,UUID.randomUUID(),economy.fence(a.session)::get,EconomyStore.Probe.NONE)
                .handleAsync((receipt,error)->{if(error==null)return CompletableFuture.completedFuture(receipt);if(!LifecycleFailure.code(error).equals("ECON_QUOTE_CHANGED"))throw new CompletionException(error);
                    return on(economy.store.offer(a.context,KEY)).thenCompose(fresh->economy.trades.execute(economy.sessions,a.session,fresh,1,false,UUID.randomUUID(),economy.fence(a.session)::get,EconomyStore.Probe.NONE));},owner).thenCompose(v->v);
            return on(ticking.thenCombine(selling,(tick,receipt)->receipt)).thenAccept(receipt->{check(count(a.value)==9&&receipt.account().balance()>offer.balance(),"sell_and_tick_concurrent_currency_items");timings.put("tick_and_sell_ns",System.nanoTime()-start);});
        }));
    }
    private CompletionStage<Void> offline(){
        var c=economy.store.content();var policy=new EconomyContent(c.regions(),c.goods(),c.demand(),c.facilities(),c.routes(),c.events(),c.policy(),c.tickMillis(),"CATCH_UP_CAPPED",8,0);
        var capped=new EconomyStore(db,policy);var realm=new UUID(0x45434f,3);long now=System.currentTimeMillis();
        return on(capped.migrate()).thenCompose(v->on(capped.createRealm(realm,now-20*c.tickMillis()))).thenCompose(v->on(capped.resumeClock(realm,now)))
            .thenAccept(n->check(n==8,"offline_capped_actual_DB_eight_ticks")).thenCompose(v->on(capped.resumeClock(realm,now))).thenAccept(n->check(n==0,"offline_same_time_no_duplicate"))
            .thenCompose(v->on(capped.snapshot(realm))).thenAccept(s->check(s.tick()==8,"offline_tick_and_clock_atomic"));
    }
    private CompletionStage<Void> capture(){var p=new Properties();p.setProperty("world",economy.physicalWorld().toString());return on(economy.store.snapshot(PaperEconomy.REALM)).thenCompose(state->{p.setProperty("tick",""+state.tick());for(var e:state.markets().entrySet())p.setProperty("market."+e.getKey(),e.getValue().toString());p.setProperty("shipments",state.shipments().toString());p.setProperty("events",state.events().toString());var chain=CompletableFuture.<Void>completedFuture(null);for(int i=1;i<=8;i++){int player=i;chain=chain.thenCompose(v->on(economy.store.account(economy.store.context(PaperEconomy.REALM,new UUID(0x45434f,player)))).thenAccept(a->{p.setProperty("account."+player,a.balance()+"/"+a.inventory().revision()+"/"+Base64.getEncoder().encodeToString(a.inventory().payload().bytes()));})).toCompletableFuture();}return chain;}).thenRun(()->{try(var out=Files.newOutputStream(evidence,StandardOpenOption.CREATE_NEW)){p.store(out,"첫 JVM 저장값");}catch(Exception e){throw new CompletionException(e);}check(true,"expected_state_saved_for_new_JVM");});}
    private CompletionStage<Void> cycle(){var jobs=new ArrayList<CompletionStage<?>>();for(var a:actors)jobs.add(economy.sessions.disconnect(a.session));return all(jobs).thenCompose(v->waitUntil(()->economy.world.state()==WorldLifecycle.State.OFFLINE)).thenRun(()->check(Bukkit.getWorld(economy.physicalWorld())==null,"world_saved_regions_unloaded"));}
    private CompletionStage<Void> restore(){var p=new Properties();try(var in=Files.newInputStream(evidence)){p.load(in);}catch(Exception e){return CompletableFuture.failedFuture(e);}check(economy.physicalWorld().toString().equals(p.getProperty("world")),"new_JVM_physical_UUID_existing");return on(economy.store.snapshot(PaperEconomy.REALM)).thenCompose(state->{check(state.tick()==Long.parseLong(p.getProperty("tick")),"new_JVM_tick_restored");for(var e:state.markets().entrySet())check(e.getValue().toString().equals(p.getProperty("market."+e.getKey())),"new_JVM_market_"+e.getKey().region()+"_"+e.getKey().good());check(state.shipments().toString().equals(p.getProperty("shipments"))&&state.events().toString().equals(p.getProperty("events")),"new_JVM_transport_events_restored");var jobs=new ArrayList<CompletionStage<?>>();for(int i=1;i<=8;i++){int player=i;jobs.add(on(economy.store.account(economy.store.context(PaperEconomy.REALM,new UUID(0x45434f,player)))).thenAccept(a->check((a.balance()+"/"+a.inventory().revision()+"/"+Base64.getEncoder().encodeToString(a.inventory().payload().bytes())).equals(p.getProperty("account."+player)),"new_JVM_account_"+player)));}return all(jobs);}).thenCompose(v->{var a=new Actor(1);actors.add(a);a.session=economy.sessions.connect(a.context,economy.world,a);return on(a.session.ready()).thenRun(()->{check(count(a.value)==12,"restored_inventory_session_applied");check(Objects.requireNonNull(Bukkit.getWorld(economy.physicalWorld())).getBlockAt(5,63,5).getType()==Material.STONE,"world_block_preserved");}).thenCompose(x->cycle());});}
    private static final UUID LEGACY=new UUID(0x45434f,90),LEGACY_PLAYER=new UUID(0x45434f,91),LEGACY_REQUEST=new UUID(0x45434f,92);
    /** 이번 변경만 검사합니다. 90일 시뮬레이션·기존 두 JVM 전체 흐름을 다시 실행하지 않습니다. */
    public CompletionStage<Void> runDesk(){
        return legacy().thenCompose(v->on(economy.store.migrate())).thenCompose(v->on(economy.store.account(economy.store.context(LEGACY,LEGACY_PLAYER))))
            .thenAccept(a->check(a.balance()==4321&&a.inventory().revision()==7&&count(a.inventory().payload())==6,"E002_legacy_account_preserved"))
            .thenCompose(v->on(economy.store.history(economy.store.context(LEGACY,LEGACY_PLAYER),Long.MAX_VALUE,20))).thenAccept(rows->check(rows.size()==1&&rows.getFirst().id().equals(LEGACY_REQUEST),"E002_legacy_receipt_preserved"))
            .thenCompose(v->on(new EconomyStore(db,economy.store.content()).migrate())).thenCompose(v->on(economy.store.createRealm(PaperEconomy.REALM,System.currentTimeMillis())))
            .thenCompose(v->on(economy.store.createRealm(PaperEconomy.OTHER,System.currentTimeMillis()))).thenCompose(v->{
                var jobs=new ArrayList<CompletionStage<?>>();for(int i=1;i<=3;i++){var a=new Actor(i);actors.add(a);jobs.add(economy.store.createAccount(a.context,100000,items(8)));}
                jobs.add(economy.store.createAccount(economy.store.context(PaperEconomy.OTHER,actors.getFirst().context.playerId()),1234,items(3)));return all(jobs);
            }).thenCompose(v->{var jobs=new ArrayList<CompletionStage<?>>();for(var a:actors){a.session=economy.sessions.connect(a.context,economy.world,a);jobs.add(a.session.ready());}return all(jobs);})
            .thenCompose(v->deskViews()).thenCompose(v->deskHistory()).thenCompose(v->deskRecovery(actors.get(1),EconomyStore.Probe.COMMIT_UNKNOWN))
            .thenCompose(v->deskRecovery(actors.get(2),EconomyStore.Probe.ROLLBACK)).thenCompose(v->newerState())
            .thenCompose(v->on(economy.store.account(economy.store.context(PaperEconomy.OTHER,actors.getFirst().context.playerId()))))
            .thenAccept(a->check(a.balance()==1234&&count(a.inventory().payload())==3,"desk_other_realm_unchanged"))
            .thenCompose(v->cycle());
    }
    private CompletionStage<Void> legacy(){
        var content=economy.store.content();var payload=items(6);return on(db.module("economy_legacy_fixture",c->{
            try(var q=c.createStatement();var r=q.executeQuery("SELECT to_regnamespace('hearth_economy')")){r.next();if(r.getString(1)!=null)throw new IllegalStateException("LEGACY_FIXTURE_REQUIRES_NEW_DB");}
            byte[] sql;try(var in=Objects.requireNonNull(getClass().getResourceAsStream("/db/economy/E001__economy.sql"))){sql=in.readAllBytes();}
            c.setAutoCommit(false);try{
                try(var st=c.createStatement()){st.execute(new String(sql,java.nio.charset.StandardCharsets.UTF_8));}
                try(var st=c.prepareStatement("INSERT INTO hearth_economy.version VALUES(1,?)")){st.setString(1,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(sql)));st.executeUpdate();}
                try(var st=c.prepareStatement("INSERT INTO hearth_economy.realm VALUES(?,?,0,0,?)")){st.setObject(1,LEGACY);st.setString(2,content.fingerprint());st.setLong(3,System.currentTimeMillis());st.executeUpdate();}
                try(var st=c.prepareStatement("INSERT INTO hearth_economy.account VALUES(?,?,?,?,7)")){st.setObject(1,LEGACY);st.setObject(2,LEGACY_PLAYER);st.setLong(3,4321);st.setBytes(4,payload.bytes());st.executeUpdate();}
                try(var st=c.prepareStatement("INSERT INTO hearth_economy.trade VALUES(?,?,?,'legacy','capital','iron_ingot',1,true,100,7)")){st.setObject(1,LEGACY);st.setObject(2,LEGACY_PLAYER);st.setObject(3,LEGACY_REQUEST);st.executeUpdate();}c.commit();
            }catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}return null;
        }));
    }
    private CompletionStage<Void> deskViews(){
        var a=actors.getFirst();var before=a.value;return on(economy.store.offers(a.context,economy.shop.region(),economy.shop.goods())).thenAccept(offers->{
            check(offers.size()==2,"multi_product_one_query_boundary");var view=economy.preview(a.session,offers);check(view.getSize()==54&&view.getItem(0).getType()==Material.IRON_INGOT&&view.getItem(1).getType()==Material.IRON_ORE,"actual_inventory_catalog_two_goods");
            check(view.getItem(1).getItemMeta().displayName().equals(net.kyori.adventure.text.Component.text("철광석")),"content_product_name_not_hardcoded_ingot");
            var product=economy.previewProduct(a.session,offers.get(1));check(product.getItem(4).getType()==Material.IRON_ORE&&product.getItem(10)!=null&&product.getItem(28)!=null,"actual_inventory_selected_good_buy_sell");check(a.value.equals(before),"shop_render_preserves_41_slots");
        }).thenCompose(v->on(economy.store.history(a.context,Long.MAX_VALUE,20))).thenAccept(rows->check(rows.isEmpty(),"history_empty_is_successful_query"))
            .thenCompose(v->rejected(economy.store.history(economy.store.context(PaperEconomy.REALM,new UUID(0x45434f,999)),Long.MAX_VALUE,20),"ECON_ACCOUNT_MISSING"));
    }
    private CompletionStage<Void> deskHistory(){
        var a=actors.getFirst();var ore=new EconomyContent.Key("capital","iron_ore");
        return on(economy.store.offer(a.context,ore)).thenCompose(offer->economy.trades.execute(economy.sessions,a.session,offer,1,true,UUID.randomUUID(),economy.fence(a.session)::get,EconomyStore.Probe.NONE)).thenCompose(receipt->{
            check(Arrays.stream(codec.decode(a.value)).filter(Objects::nonNull).anyMatch(i->i.getType()==Material.IRON_ORE&&i.getAmount()==1),"selected_ore_trade_applied");
            CompletionStage<Void> chain=CompletableFuture.completedFuture(null);for(int i=0;i<22;i++){boolean buy=i%2==0;chain=chain.thenComposeAsync(v->on(economy.store.offer(a.context,KEY)).thenCompose(offer->economy.trades.execute(economy.sessions,a.session,offer,1,buy,UUID.randomUUID(),economy.fence(a.session)::get,EconomyStore.Probe.NONE)).thenAccept(row->{}),owner);}return chain;
        }).thenCompose(v->on(economy.store.account(a.context))).thenCompose(before->on(economy.store.history(a.context,Long.MAX_VALUE,20)).thenCompose(first->{
            check(first.size()==20,"history_first_page_twenty");for(int i=1;i<first.size();i++)check(first.get(i-1).inventoryRevision()>first.get(i).inventoryRevision(),"history_revision_order_"+i);
            return on(economy.store.history(a.context,first.getLast().inventoryRevision(),20)).thenCompose(second->{
                check(second.size()==3&&Collections.disjoint(first.stream().map(EconomyStore.Entry::id).toList(),second.stream().map(EconomyStore.Entry::id).toList()),"history_keyset_no_duplicate");
                var rows=new ArrayList<>(first);rows.addAll(second);long balance=100000;for(var row:rows)balance+=row.buy()?-row.total():row.total();check(balance==before.balance()&&count(before.inventory().payload())==8,"all_trade_amounts_currency_items_conserved");
                return on(economy.store.account(a.context)).thenAccept(after->check(before.equals(after),"history_read_no_account_revision_change"));
            });
        })).thenCompose(v->on(economy.store.snapshot(PaperEconomy.REALM))).thenAccept(s->check(s.markets().get(KEY).stock()==100&&s.markets().get(ore).stock()==199,"two_product_market_stock_conservation"))
            .thenCompose(v->on(economy.store.history(actors.get(1).context,Long.MAX_VALUE,20))).thenAccept(rows->check(rows.isEmpty(),"other_player_history_isolated"))
            .thenCompose(v->on(economy.store.history(economy.store.context(PaperEconomy.OTHER,a.context.playerId()),Long.MAX_VALUE,20))).thenAccept(rows->check(rows.isEmpty(),"other_realm_history_isolated"));
    }
    private CompletionStage<Void> rejectedCall(Supplier<CompletionStage<?>> action,String code){try{return rejected(action.get(),code);}catch(RuntimeException e){return rejected(CompletableFuture.failedFuture(e),code);}}
    private CompletionStage<Void> deskRecovery(Actor a,EconomyStore.Probe probe){
        UUID request=UUID.randomUUID();var old=a.session;var decision=probe==EconomyStore.Probe.COMMIT_UNKNOWN?EconomyRecovery.Decision.COMMITTED_RELOAD:EconomyRecovery.Decision.NOT_COMMITTED_RELOAD;
        String code=probe==EconomyStore.Probe.COMMIT_UNKNOWN?"COMMIT_UNKNOWN":"23514";long revision=probe==EconomyStore.Probe.COMMIT_UNKNOWN?1:0;
        return on(economy.store.offer(a.context,KEY)).thenCompose(offer->rejected(economy.trades.execute(economy.sessions,old,offer,1,true,request,economy.fence(old)::get,probe),code))
            .thenRun(()->check(old.state()==PlayerSessions.State.HOLD&&economy.trades.pending(a.context).contains(request),"request_retained_HOLD_"+code))
            .thenCompose(v->rejectedCall(()->economy.trades.inspect(actors.getFirst().context,request),"ECON_NO_SETTLED_REQUEST"))
            .thenCompose(v->on(economy.trades.inspect(a.context,request))).thenAccept(review->check(review.decision()==decision&&review.revision()==revision,"inspection_"+decision))
            .thenCompose(v->rejectedCall(()->economy.trades.recover(economy.sessions,a.context,request,revision+1,()->true),"ECON_RECOVERY_REVIEW_REQUIRED"))
            .thenCompose(v->rejectedCall(()->economy.trades.recover(economy.sessions,a.context,request,revision,()->true),"RECOVERY_NOT_SETTLED"))
            .thenRun(()->a.connected=false).thenCompose(v->rejectedCall(()->economy.trades.recover(economy.sessions,a.context,request,revision,()->false),"ECON_RECOVERY_REVIEW_REQUIRED"))
            .thenCompose(v->on(economy.trades.recover(economy.sessions,a.context,request,revision,()->true)))
            .thenRun(()->check(old.state()==PlayerSessions.State.CLOSED&&old.closed().toCompletableFuture().isCompletedExceptionally(),"explicit_retirement_preserves_failure_evidence_"+code))
            .thenCompose(v->rejectedCall(()->economy.trades.recover(economy.sessions,a.context,request,revision,()->true),"ECON_NO_SETTLED_REQUEST"))
            .thenCompose(v->{a.connected=true;a.session=economy.sessions.connect(a.context,economy.world,a);return on(a.session.ready());})
            .thenRun(()->check(count(a.value)==(probe==EconomyStore.Probe.COMMIT_UNKNOWN?9:8)&&a.session.save().revision()==revision,"fresh_session_DB_reload_no_duplicate_"+code))
            .thenCompose(v->on(economy.store.history(a.context,Long.MAX_VALUE,20))).thenAccept(rows->check(rows.size()==(probe==EconomyStore.Probe.COMMIT_UNKNOWN?1:0),"recovery_never_reexecutes_trade_"+code));
    }
    private CompletionStage<Void> newerState(){
        var ctx=economy.store.context(PaperEconomy.REALM,new UUID(0x45434f,95));var before=new InventoryStore.Stored(items(8),0);var after=economy.trades.change(before.payload(),"iron_ingot",1,true);var id=UUID.randomUUID();
        return on(economy.store.createAccount(ctx,100000,before.payload())).thenCompose(v->on(economy.store.offer(ctx,KEY))).thenCompose(offer->{
            var request=new EconomyStore.Request(ctx,id,KEY,1,true,offer.market().revision());return on(economy.store.trade(request,before,after,()->true,EconomyStore.Probe.NONE))
                .thenCompose(receipt->on(economy.store.saveInventory(ctx,1,items(10)))).thenCompose(saved->on(economy.store.inspect(request)))
                .thenAccept(found->check(EconomyRecovery.decide(request,before,after,found)==EconomyRecovery.Decision.REVIEW_REQUIRED,"newer_account_cannot_be_overwritten_by_old_receipt"));
        });
    }
    public CompletionStage<Void> runReport(){
        return on(economy.store.migrate()).thenCompose(v->on(economy.store.createRealm(PaperEconomy.REALM,System.currentTimeMillis())))
            .thenCompose(v->on(economy.store.createRealm(PaperEconomy.OTHER,System.currentTimeMillis()))).thenCompose(v->{
                var jobs=new ArrayList<CompletionStage<?>>();for(int i=1;i<=2;i++){var a=new Actor(i);actors.add(a);jobs.add(economy.store.createAccount(a.context,100000,items(8)));}
                jobs.add(economy.store.createAccount(economy.store.context(PaperEconomy.OTHER,actors.getFirst().context.playerId()),1234,items(3)));return all(jobs);
            }).thenCompose(v->{var jobs=new ArrayList<CompletionStage<?>>();for(var a:actors){a.session=economy.sessions.connect(a.context,economy.world,a);jobs.add(a.session.ready());}return all(jobs);})
            .thenCompose(v->reportDeferred()).thenCompose(v->reportInitial()).thenCompose(v->reportShippingAndEvents()).thenCompose(v->reportPrivate())
            .thenCompose(v->cycle());
    }
    private CompletionStage<Void> reportDeferred(){
        var calls=new int[1];int tick=Bukkit.getCurrentTick();var current=new java.util.concurrent.atomic.AtomicBoolean(true);
        economy.deferReportView(()->true,()->calls[0]+=Bukkit.getCurrentTick()>tick?1:100);economy.deferReportView(current::get,()->calls[0]+=10);current.set(false);
        check(calls[0]==0,"report_view_not_changed_inside_click_path");return waitUntil(()->Bukkit.getCurrentTick()>=tick+2).thenRun(()->{plugin.getLogger().info("HEARTH_REPORT_DEFER original_tick="+tick+" current_tick="+Bukkit.getCurrentTick()+" calls="+calls[0]);check(calls[0]==1,"report_next_tick_stale_view_rejected");});
    }
    private CompletionStage<EconomyReport.View> report(String region){return on(economy.store.report(actors.getFirst().context,economy.reportDefinition,region));}
    private void reportViews(EconomyReport.View view){
        var a=actors.getFirst();var before=a.value;for(String section:List.of("MARKET","REGIONS","ROUTES","EVENTS","FACILITIES")){
            var inventory=economy.previewReport(a.session,view,section,0);check(inventory.getSize()==54&&inventory.getItem(45)!=null&&inventory.getItem(51)!=null,"report_actual_inventory_"+section);
            if(section.equals("MARKET"))check(inventory.getItem(0).getType()==Material.IRON_INGOT,"report_market_goods_not_placeholder");
        }check(before.equals(a.value)&&!a.session.save().dirty(),"report_render_no_inventory_dirty");
    }
    private CompletionStage<Void> reportInitial(){
        var a=actors.getFirst();return on(economy.store.snapshot(PaperEconomy.REALM)).thenCompose(before->report("capital").thenCompose(view->{
            check(view.tick()==0&&view.markets().size()==2&&view.routes().getFirst().inTransit()==0&&view.events().isEmpty(),"report_initial_actual_values");reportViews(view);
            return on(economy.store.report(actors.get(1).context,economy.reportDefinition,"capital")).thenAccept(other->check(view.equals(other),"report_shared_market_not_personal_inventory"));
        }).thenCompose(v->on(economy.store.snapshot(PaperEconomy.REALM))).thenAccept(after->check(before.equals(after),"report_query_no_tick_or_market_change")))
            .thenCompose(v->rejectedCall(()->economy.store.report(a.context,economy.reportDefinition,"missing"),null))
            .thenCompose(v->rejected(economy.store.report(economy.store.context(PaperEconomy.REALM,new UUID(0x45434f,999)),economy.reportDefinition,"capital"),"ECON_ACCOUNT_MISSING"))
            .thenCompose(v->rejected(economy.store.report(new ServerProgressAccess().forVerifiedServerLink(PaperEconomy.REALM,a.context.playerId()),economy.reportDefinition,"capital"),null));
    }
    private CompletionStage<Void> reportShippingAndEvents(){
        return on(economy.store.tick(PaperEconomy.REALM,0,System.currentTimeMillis())).thenCompose(v->report("capital")).thenCompose(cached->{
            check(cached.tick()==1&&cached.routes().getFirst().inTransit()==10&&cached.routes().getFirst().nextArrival()==3,"report_actual_departure_delay");
            var work=economy.store.event(PaperEconomy.REALM,new UUID(89,1),"road_block");return on(work).thenCompose(v->on(economy.store.event(PaperEconomy.REALM,new UUID(89,2),"mine_collapse")))
                .thenCompose(v->on(economy.store.event(PaperEconomy.REALM,new UUID(89,3),"demand_surge"))).thenCompose(v->report("capital"))
                .thenAccept(view->check(view.events().size()==3&&view.events().stream().allMatch(EconomyReport.EventRow::scheduled)&&!view.routes().getFirst().blocked(),"report_scheduled_event_not_early_apply"))
                .thenCompose(v->{var ticking=economy.store.tick(PaperEconomy.REALM,1,System.currentTimeMillis());var reading=economy.store.report(actors.getFirst().context,economy.reportDefinition,"capital");return on(ticking.thenCombine(reading,(applied,view)->{if(!applied)throw new AssertionError("Tick rejected");return view;}));})
                .thenAccept(view->{check(view.tick()==1||view.tick()==2,"report_tick_concurrent_atomic_read");check(view.routes().getFirst().blocked()==(view.tick()==2)&&view.routes().getFirst().nextArrival()==4-view.tick(),"report_route_and_event_same_clock");})
                .thenCompose(v->report("capital")).thenAccept(view->{check(view.tick()==2&&view.events().size()==3&&view.events().stream().noneMatch(EconomyReport.EventRow::scheduled),"report_refresh_observes_active_events");check(view.markets().getFirst().demand()==6&&view.routes().getFirst().blocked()&&view.routes().getFirst().inTransit()==10,"report_current_demand_route_state");check(cached.tick()==1&&!cached.routes().getFirst().blocked(),"report_old_snapshot_stays_immutable");reportViews(view);})
                .thenCompose(v->report("mine")).thenAccept(view->check(view.facilities().getFirst().capacity()==0&&view.events().size()==2,"report_public_mine_production_stop"))
                .thenCompose(v->on(economy.store.tick(PaperEconomy.REALM,2,System.currentTimeMillis()))).thenCompose(v->on(economy.store.tick(PaperEconomy.REALM,3,System.currentTimeMillis())))
                .thenCompose(v->report("capital")).thenAccept(view->check(view.tick()==4&&view.routes().getFirst().blocked()&&view.routes().getFirst().inTransit()==0&&view.routes().getFirst().shipments()==0,"report_arrival_processed_while_new_departures_blocked"));
        });
    }
    private CompletionStage<Void> reportPrivate(){
        var settings=new Properties();try{PaperEconomy.load(plugin,settings);}catch(Exception e){return CompletableFuture.failedFuture(e);}settings.setProperty("report-events","");settings.setProperty("report-routes","");settings.setProperty("report-facilities","");
        var hidden=EconomyReport.parse(settings,economy.store.content(),economy.shop);var a=actors.getFirst();
        return on(economy.store.report(a.context,hidden,"capital")).thenAccept(view->check(view.events().isEmpty()&&view.routes().isEmpty()&&view.facilities().isEmpty()&&!view.toString().contains("mine_collapse"),"report_unlisted_details_not_in_public_DTO"))
            .thenCompose(v->on(economy.store.report(economy.store.context(PaperEconomy.OTHER,a.context.playerId()),economy.reportDefinition,"capital")))
            .thenAccept(view->check(view.tick()==0&&view.events().isEmpty()&&view.routes().getFirst().inTransit()==0&&view.markets().getFirst().stock()==100,"report_other_realm_unchanged"))
            .thenCompose(v->on(economy.store.account(a.context))).thenAccept(account->check(account.balance()==100000&&account.inventory().revision()==0&&account.inventory().payload().equals(items(8)),"report_owner_account_inventory_unchanged"));
    }
    private int quantity(InventoryPayload payload,Material material){return Arrays.stream(codec.decode(payload)).filter(Objects::nonNull).filter(item->item.getType()==material).mapToInt(ItemStack::getAmount).sum();}
    public CompletionStage<Void> runSupply(){
        return on(economy.store.migrate()).thenCompose(v->on(economy.store.createRealm(PaperEconomy.REALM,System.currentTimeMillis()))).thenCompose(v->on(economy.store.createRealm(PaperEconomy.OTHER,System.currentTimeMillis())))
            .thenCompose(v->{var jobs=new ArrayList<CompletionStage<?>>();for(int i=1;i<=2;i++){var a=new Actor(i);actors.add(a);jobs.add(economy.store.createAccount(a.context,100000,items(8)));}jobs.add(economy.store.createAccount(economy.store.context(PaperEconomy.OTHER,actors.getFirst().context.playerId()),1234,items(3)));return all(jobs);})
            .thenCompose(v->openMerchantActors()).thenCompose(v->supplyViews()).thenCompose(v->supplyChanges())
            .thenCompose(v->rejectedCall(()->economy.supply(economy.store.context(PaperEconomy.REALM,new UUID(0x45434f,999)),"capital_trader","iron_ore"),"ECON_ACCOUNT_MISSING"))
            .thenCompose(v->rejectedCall(()->economy.supply(actors.getFirst().context,"capital_trader","missing"),"IllegalArgumentException"))
            .thenCompose(v->on(economy.store.account(economy.store.context(PaperEconomy.OTHER,actors.getFirst().context.playerId())))).thenAccept(row->check(row.balance()==1234&&row.inventory().revision()==0&&count(row.inventory().payload())==3,"supply_other_realm_owner_preserved"))
            .thenCompose(v->cycle());
    }
    private CompletionStage<Void> supplyViews(){var a=actors.getFirst();return on(economy.store.account(a.context)).thenCompose(before->on(economy.supply(a.context,"capital_trader","iron_ore")).thenCompose(view->{
        check(view.nodes().size()==5&&view.steps().size()==4&&view.tick()==0,"supply_ore_chain_and_content_guide");
        check(economy.previewSupplyAt("capital_trader",a.session,view,"SUPPLY",0).getItem(4)!=null,"supply_native_chain_screen");
        var guide=economy.previewSupplyAt("capital_trader",a.session,view,"GUIDE",0);check(guide.getItem(0).getItemMeta().displayName().equals(net.kyori.adventure.text.Component.text("1. 광산 지역의 철광석 시장 확인"))&&guide.getItem(3)!=null,"supply_native_ordered_guide_screen");
        var goods=economy.previewSupplyAt("capital_trader",a.session,view,"SUPPLY_GOODS",0);check(goods.getItem(0).getType()==Material.IRON_INGOT&&goods.getItem(1).getType()==Material.IRON_ORE,"supply_native_public_goods_screen");
        var remote=view.nodes().stream().filter(n->n.section()==EconomySupply.Section.MARKET&&n.region().equals("mine")).findFirst().orElseThrow();check(!EconomySupply.localTrade(remote,economy.merchants.get("capital_trader"))&&EconomySupply.localTrade(remote,economy.merchants.get("mine_trader")),"supply_cross_region_no_remote_trade");
        var mine=economy.previewSupplyAt("mine_trader",a.session,view,"SUPPLY",0);check(mine.getItem(0)!=null,"supply_mine_merchant_view_reuses_public_data");
        return on(economy.store.account(a.context)).thenAccept(after->check(after.equals(before)&&a.value.equals(items(8))&&!a.session.save().dirty(),"supply_readonly_account_inventory_revision"));
    })).thenCompose(v->on(economy.supply(actors.get(1).context,"capital_trader","iron_ore"))).thenAccept(view->check(view.nodes().size()==5&&view.tick()==0,"supply_same_realm_public_information_shared"))
        .thenCompose(v->{var selected=new EconomyMerchants.Visit[]{economy.merchantVisit("capital_trader")};var old=selected[0];int[] calls={0};int tick=Bukkit.getCurrentTick();
            economy.deferReportView(()->old.current(selected[0],economy.merchantEntities().get("capital_trader"),economy.world.generation()),()->calls[0]++);selected[0]=economy.merchantVisit("mine_trader");
            economy.deferReportView(()->true,()->{check(Bukkit.getCurrentTick()>tick,"supply_cached_transition_next_tick");calls[0]+=2;});return waitUntil(()->Bukkit.getCurrentTick()>=tick+2).thenRun(()->check(calls[0]==2,"supply_late_different_merchant_callback_rejected"));
        });}
    private CompletionStage<Void> supplyChanges(){var a=actors.getFirst();EconomySupply.View[] cached={null};return on(economy.supply(a.context,"capital_trader","iron_ore")).thenAccept(v->cached[0]=v)
        .thenCompose(v->merchantTrade(a,"mine_trader","iron_ore",1,true)).thenCompose(v->merchantTrade(a,"capital_trader","iron_ingot",1,true))
        .thenRun(()->check(count(a.value)==9&&quantity(a.value,Material.IRON_ORE)==1&&a.session.save().revision()==2,"supply_navigation_keeps_existing_trade_path"))
        .thenCompose(v->advanceFlows(4)).thenCompose(v->on(economy.applyEvent("road_block",new UUID(0x53555050,1)))).thenCompose(v->advanceFlows(1))
        .thenCompose(v->on(economy.supply(a.context,"capital_trader","iron_ore"))).thenAccept(view->{check(view.tick()==5&&cached[0].tick()==0,"supply_refresh_replaces_but_does_not_mutate_cache");var route=view.nodes().stream().filter(n->n.section()==EconomySupply.Section.ROUTE).findFirst().orElseThrow();check(route.lines().stream().anyMatch(x->x.contains("차단"))&&route.lines().stream().anyMatch(x->x.startsWith("운송 중: ")),"supply_actual_route_block_and_in_transit");var market=view.nodes().stream().filter(n->n.section()==EconomySupply.Section.MARKET&&n.region().equals("capital")).findFirst().orElseThrow();check(market.lines().stream().anyMatch(x->x.contains("완료 Tick 5")),"supply_completed_flow_in_market_node");
            var pub=economy.previewSupplyAt("capital_trader",a.session,view,"GUIDE",0);check(pub.getItem(1).getItemMeta().lore().stream().anyMatch(x->x.equals(net.kyori.adventure.text.Component.text("신규 출발: 차단"))),"supply_guide_uses_same_live_snapshot");})
        .thenCompose(v->on(economy.store.report(a.context,economy.reportDefinition,"capital"))).thenAccept(view->{var inventory=economy.previewReportAt("capital_trader",a.session,view,"MARKET",0);check(inventory.getItem(0).getItemMeta().lore().contains(net.kyori.adventure.text.Component.text(EconomySupply.flowLines(view.markets().getFirst().flow()).getFirst())),"supply_existing_market_multiline_flow_preserved");})
        .thenCompose(v->on(economy.supply(economy.store.context(PaperEconomy.OTHER,a.context.playerId()),"capital_trader","iron_ore"))).thenAccept(view->check(view.tick()==0&&view.nodes().stream().noneMatch(n->n.lines().contains("신규 출발: 차단")),"supply_other_realm_state_isolated"))
        .thenCompose(v->on(economy.store.account(actors.get(1).context))).thenAccept(row->check(row.balance()==100000&&row.inventory().revision()==0&&count(row.inventory().payload())==8,"supply_other_player_inventory_preserved"));}
    private Path flowEvidence(){return evidence.resolveSibling("economy-flow-expected.properties");}
    private static String flowSignature(Map<EconomyContent.Key,EconomyEngine.Flow> flows){return new TreeMap<>(flows).toString();}
    public CompletionStage<Void> runFlows(){return resume?flowRestore():flowPrepare();}
    private CompletionStage<Void> flowPrepare(){
        return legacy().thenCompose(v->on(db.module("flow_E002_fixture",c->{
            byte[] sql;try(var in=Objects.requireNonNull(getClass().getResourceAsStream("/db/economy/E002__trade_history.sql"))){sql=in.readAllBytes();}
            c.setAutoCommit(false);try{try(var st=c.createStatement()){st.execute(new String(sql,java.nio.charset.StandardCharsets.UTF_8));}try(var st=c.prepareStatement("INSERT INTO hearth_economy.version VALUES(2,?)")){st.setString(1,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(sql)));st.executeUpdate();}c.commit();}catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}return null;
        }))).thenCompose(v->on(economy.store.migrate())).thenCompose(v->on(new EconomyStore(db,economy.store.content()).migrate()))
            .thenCompose(v->on(economy.store.account(economy.store.context(LEGACY,LEGACY_PLAYER)))).thenAccept(row->check(row.balance()==4321&&row.inventory().revision()==7&&count(row.inventory().payload())==6,"flow_E003_preserves_E002_account"))
            .thenCompose(v->on(economy.store.history(economy.store.context(LEGACY,LEGACY_PLAYER),Long.MAX_VALUE,20))).thenAccept(rows->check(rows.size()==1&&rows.getFirst().id().equals(LEGACY_REQUEST),"flow_E003_preserves_old_receipt"))
            .thenCompose(v->on(economy.store.createRealm(PaperEconomy.REALM,System.currentTimeMillis()))).thenCompose(v->on(economy.store.createRealm(PaperEconomy.OTHER,System.currentTimeMillis())))
            .thenCompose(v->{var jobs=new ArrayList<CompletionStage<?>>();for(int i=1;i<=2;i++){var a=new Actor(i);actors.add(a);jobs.add(economy.store.createAccount(a.context,100000,items(8)));}jobs.add(economy.store.createAccount(economy.store.context(PaperEconomy.OTHER,actors.getFirst().context.playerId()),1234,items(3)));return all(jobs);})
            .thenCompose(v->openMerchantActors()).thenCompose(v->on(economy.store.report(actors.getFirst().context,economy.reportDefinition,"capital"))).thenAccept(view->check(view.markets().getFirst().flow()==null,"flow_before_first_tick_is_unregistered"))
            .thenCompose(v->advanceFlows(1)).thenCompose(v->on(economy.store.flows(PaperEconomy.REALM))).thenAccept(flows->{var ingot=flows.get(KEY);var ore=flows.get(new EconomyContent.Key("capital","iron_ore"));check(ingot.produced()==3&&ingot.consumed()==3&&ore.inputs()==6,"flow_actual_manufacture_and_consumption");check(flows.get(new EconomyContent.Key("mine","iron_ore")).departed()==10,"flow_departure_reserved_not_arrived");})
            .thenCompose(v->advanceFlows(3)).thenCompose(v->on(economy.store.flows(PaperEconomy.REALM))).thenAccept(flows->check(flows.get(new EconomyContent.Key("capital","iron_ore")).arrived()==10,"flow_delayed_arrival_not_production"))
            .thenCompose(v->flowEvents()).thenCompose(v->flowViews()).thenCompose(v->flowFailure()).thenCompose(v->advanceFlows(24))
            .thenCompose(v->on(economy.store.flows(PaperEconomy.REALM))).thenAccept(flows->check(flows.get(new EconomyContent.Key("mine","iron_ore")).produced()>0&&flows.get(new EconomyContent.Key("mine","iron_ore")).departed()==10,"flow_supply_resumes_after_events"))
            .thenCompose(v->flowCorrupt()).thenCompose(v->flowCapture()).thenCompose(v->cycle()).thenCompose(v->openMerchantActors()).thenCompose(v->flowVerifyEvidence("same_JVM_reload"))
            .thenCompose(v->cycle());
    }
    private CompletionStage<Void> advanceFlows(int count){CompletionStage<Void> chain=CompletableFuture.completedFuture(null);for(int i=0;i<count;i++)chain=chain.thenComposeAsync(v->on(economy.store.snapshot(PaperEconomy.REALM)).thenCompose(state->on(economy.store.tick(PaperEconomy.REALM,state.tick(),System.currentTimeMillis()))).thenAccept(ok->{if(!ok)throw new IllegalStateException("FLOW_TICK_REJECTED");}),owner);return chain;}
    private CompletionStage<Void> flowEvents(){
        var road=new UUID(0x464c4f57,2);return on(economy.applyEvent("mine_collapse",new UUID(0x464c4f57,1))).thenCompose(v->on(economy.applyEvent("road_block",road))).thenCompose(v->on(economy.applyEvent("road_block",road)))
            .thenCompose(v->on(economy.applyEvent("demand_surge",new UUID(0x464c4f57,3))))
            .thenCompose(v->rejectedCall(()->economy.applyEvent("missing",UUID.randomUUID()),null))
            .thenCompose(v->rejected(economy.applyEvent("mine_collapse",road),"ECON_REQUEST_CONFLICT"))
            .thenCompose(v->on(economy.store.snapshot(PaperEconomy.REALM))).thenAccept(state->check(state.events().size()==3,"flow_event_duplicate_is_same_request"))
            .thenCompose(v->advanceFlows(1)).thenCompose(v->on(economy.store.flows(PaperEconomy.REALM))).thenAccept(flows->{var mine=flows.get(new EconomyContent.Key("mine","iron_ore"));check(mine.produced()==0&&mine.departed()==0,"flow_production_and_route_events_applied");check(flows.get(KEY).consumed()==6&&flows.get(KEY).produced()==3,"flow_surge_actual_demand_and_target_limited_output");});
    }
    private CompletionStage<Void> flowViews(){var a=actors.getFirst();return on(economy.store.account(a.context)).thenCompose(before->on(economy.store.report(a.context,economy.reportDefinition,"capital")).thenCompose(view->{
        var f=view.markets().getFirst().flow();check(f!=null&&f.tick()==5&&f.consumed()==6,"flow_public_report_actual_tick");var nativeView=economy.previewReportAt("capital_trader",a.session,view,"MARKET",0);check(nativeView.getItem(0).getItemMeta().lore().contains(net.kyori.adventure.text.Component.text(EconomySupply.flowLines(f).getFirst())),"flow_native_market_lore");
        check(economy.previewReportAt("capital_trader",a.session,view,"FACILITIES",0).getItem(0)!=null,"flow_native_facility_diagnosis");
        return on(economy.store.account(a.context)).thenAccept(after->check(before.equals(after)&&count(a.value)==8&&!a.session.save().dirty(),"flow_readonly_owner_inventory_revision_preserved"));
    })).thenCompose(v->on(economy.store.report(actors.get(1).context,economy.reportDefinition,"capital"))).thenAccept(view->check(view.markets().getFirst().flow().consumed()==6,"flow_other_player_same_realm_public_values"))
        .thenCompose(v->on(economy.store.flows(PaperEconomy.OTHER))).thenAccept(flows->check(flows.isEmpty(),"flow_other_realm_not_initialized"));}
    private CompletionStage<Void> flowFailure(){
        EconomyEngine.State[] before={null};var previous=new java.util.concurrent.atomic.AtomicReference<Map<EconomyContent.Key,EconomyEngine.Flow>>();return on(economy.store.snapshot(PaperEconomy.REALM)).thenAccept(s->before[0]=s)
            .thenCompose(v->on(economy.store.flows(PaperEconomy.REALM))).thenAccept(previous::set)
            .thenCompose(v->on(db.module("flow_failure_trigger",c->{try(var st=c.createStatement()){st.execute("CREATE FUNCTION hearth_economy.verification_flow_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.realm_id = '"+PaperEconomy.REALM+"'::uuid THEN RAISE EXCEPTION 'isolated flow failure' USING ERRCODE = '23514'; END IF; RETURN NEW; END $$");st.execute("CREATE TRIGGER verification_flow_fail BEFORE INSERT OR UPDATE ON hearth_economy.tick_flow FOR EACH ROW EXECUTE FUNCTION hearth_economy.verification_flow_fail()");}return null;})))
            .thenCompose(v->rejected(economy.store.tick(PaperEconomy.REALM,5,System.currentTimeMillis()),"23514"))
            .thenCompose(v->on(economy.store.snapshot(PaperEconomy.REALM))).thenAccept(s->check(s.equals(before[0]),"flow_failed_write_rolls_back_markets_transport_tick"))
            .thenCompose(v->on(economy.store.flows(PaperEconomy.REALM))).thenAccept(f->check(f.equals(previous.get()),"flow_failed_write_preserves_previous_metrics"))
            .thenCompose(v->on(economy.store.tick(PaperEconomy.OTHER,0,System.currentTimeMillis()))).thenAccept(ok->check(ok,"flow_A_failure_does_not_stop_B"))
            .thenCompose(v->on(db.module("flow_remove_failure_trigger",c->{try(var st=c.createStatement()){st.execute("DROP TRIGGER verification_flow_fail ON hearth_economy.tick_flow");st.execute("DROP FUNCTION hearth_economy.verification_flow_fail()");}return null;})))
            .thenCompose(v->on(economy.store.tick(PaperEconomy.REALM,4,System.currentTimeMillis()))).thenAccept(ok->check(!ok,"flow_duplicate_tick_does_not_overwrite_metrics"));
    }
    private CompletionStage<Void> flowCorrupt(){var id=new UUID(0x464c4f57,99);return on(economy.store.createRealm(id,System.currentTimeMillis())).thenCompose(v->on(economy.store.tick(id,0,System.currentTimeMillis())))
        .thenCompose(v->on(db.module("flow_isolated_missing_rows",c->{try(var st=c.prepareStatement("DELETE FROM hearth_economy.tick_flow WHERE realm_id=?")){st.setObject(1,id);st.executeUpdate();}return null;})))
        .thenCompose(v->rejected(economy.store.flows(id),"ECON_FLOW_MISMATCH"))
        .thenCompose(v->rejected(economy.store.tick(id,1,System.currentTimeMillis()),"ECON_FLOW_MISMATCH"));}
    private CompletionStage<Void> flowCapture(){var p=new Properties();var w=Objects.requireNonNull(Bukkit.getWorld(economy.physicalWorld()));w.getBlockAt(5,63,5).setType(Material.GOLD_BLOCK);p.setProperty("world",w.getUID().toString());p.setProperty("key",w.getKey().toString());
        return on(economy.store.flows(PaperEconomy.REALM)).thenAccept(f->p.setProperty("flows",flowSignature(f))).thenRun(()->{try(var out=Files.newOutputStream(flowEvidence(),StandardOpenOption.CREATE_NEW)){p.store(out,"첫 JVM 실제 Tick 실적");}catch(Exception e){throw new CompletionException(e);}});}
    private CompletionStage<Void> flowVerifyEvidence(String tag){var p=new Properties();try(var in=Files.newInputStream(flowEvidence())){p.load(in);}catch(Exception e){return CompletableFuture.failedFuture(e);}var w=Objects.requireNonNull(Bukkit.getWorld(economy.physicalWorld()));
        check(w.getUID().toString().equals(p.getProperty("world"))&&w.getKey().toString().equals(p.getProperty("key"))&&w.getBlockAt(5,63,5).getType()==Material.GOLD_BLOCK,"flow_"+tag+"_UUID_key_block");
        var manager=com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer().get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(w));check(manager!=null&&manager.getRegion("capital")!=null,"flow_"+tag+"_protection_restored");
        return on(economy.store.flows(PaperEconomy.REALM)).thenAccept(f->check(flowSignature(f).equals(p.getProperty("flows")),"flow_"+tag+"_metrics_restored"))
            .thenCompose(v->on(economy.store.account(actors.getFirst().context))).thenAccept(row->check(row.balance()==100000&&row.inventory().revision()==0&&count(row.inventory().payload())==8&&count(actors.getFirst().value)==8,"flow_"+tag+"_account_inventory_preserved"));}
    private CompletionStage<Void> flowRestore(){return on(economy.store.migrate()).thenRun(()->{actors.add(new Actor(1));actors.add(new Actor(2));}).thenCompose(v->openMerchantActors()).thenCompose(v->flowVerifyEvidence("new_JVM"))
        .thenCompose(v->on(economy.store.flows(PaperEconomy.OTHER))).thenAccept(f->check(f.values().stream().allMatch(row->row.tick()==1),"flow_new_JVM_other_realm_separate"))
        .thenCompose(v->on(economy.store.report(actors.getFirst().context,economy.reportDefinition,"capital"))).thenAccept(view->check(view.markets().getFirst().flow().tick()==29&&economy.previewReportAt("capital_trader",actors.getFirst().session,view,"MARKET",0).getItem(0)!=null,"flow_new_JVM_public_UI_restored"))
        .thenCompose(v->cycle());}
    public CompletionStage<Void> runQuote(){
        return on(economy.store.migrate()).thenCompose(v->on(economy.store.createRealm(PaperEconomy.REALM,System.currentTimeMillis())))
            .thenCompose(v->on(economy.store.createRealm(PaperEconomy.OTHER,System.currentTimeMillis()))).thenCompose(v->{
                var jobs=new ArrayList<CompletionStage<?>>();for(int i=1;i<=2;i++){var a=new Actor(i);actors.add(a);jobs.add(economy.store.createAccount(a.context,100000,items(8)));}
                jobs.add(economy.store.createAccount(economy.store.context(PaperEconomy.OTHER,actors.getFirst().context.playerId()),1234,items(3)));return all(jobs);
            }).thenCompose(v->openMerchantActors()).thenCompose(v->quoteViews()).thenCompose(v->quoteTransactions())
            .thenCompose(v->deskRecovery(actors.get(1),EconomyStore.Probe.ROLLBACK))
            .thenCompose(v->on(economy.store.account(economy.store.context(PaperEconomy.OTHER,actors.getFirst().context.playerId())))).thenAccept(row->check(row.balance()==1234&&count(row.inventory().payload())==3,"quote_other_realm_preserved"))
            .thenCompose(v->cycle());
    }
    private CompletionStage<Void> quoteViews(){
        var a=actors.getFirst();var before=a.value;
        var ordinary=economy.trades.capacity(before,"iron_ingot");check(ordinary.sell()==8&&ordinary.buy()==36*64-8,"quote_storage_capacity_ignores_equipment");
        var slots=codec.decode(before);slots[1]=new ItemStack(Material.IRON_INGOT,3);slots[1].editMeta(meta->meta.displayName(net.kyori.adventure.text.Component.text("시험 이름")));
        check(economy.trades.capacity(codec.encode(slots),"iron_ingot").sell()==8,"quote_named_items_not_sellable");
        var full=new ItemStack[41];for(int i=0;i<36;i++)full[i]=new ItemStack(Material.DIRT,64);
        check(economy.trades.capacity(codec.encode(full),"iron_ingot").buy()==0,"quote_full_inventory_has_no_capacity");
        return on(economy.store.offer(a.context,KEY)).thenCompose(offer->{
            var product=economy.previewQuantity("capital_trader",a.session,offer,13,a.value);check(product.getItem(4).getType()==Material.IRON_INGOT&&product.getItem(10).getType()==Material.LIME_DYE&&product.getItem(28).getType()==Material.GRAY_DYE,"quote_quantity_native_screen_buy_and_insufficient_sell");
            var confirm=economy.previewConfirmation("capital_trader",a.session,offer,13,true,a.value);check(confirm.getItem(13)!=null&&confirm.getItem(31).getType()==Material.LIME_DYE,"quote_confirmation_native_inventory");
            var impossible=economy.previewConfirmation("capital_trader",a.session,offer,13,true,codec.encode(full));check(impossible.getItem(31).getType()==Material.BARRIER,"quote_confirmation_full_inventory_disabled");
            var poor=new EconomyStore.Offer(KEY,offer.market(),0);check(economy.previewConfirmation("capital_trader",a.session,poor,1,true,a.value).getItem(31).getType()==Material.BARRIER,"quote_confirmation_poor_disabled");
            check(a.value.equals(before)&&!a.session.save().dirty()&&a.session.save().revision()==0,"quote_render_no_inventory_mutation");
            return on(economy.store.account(a.context)).thenAccept(row->check(row.inventory().revision()==0&&row.balance()==100000&&row.inventory().payload().equals(before),"quote_views_no_DB_write"));
        }).thenCompose(v->{int tick=Bukkit.getCurrentTick();int[] applied={0};boolean[] valid={true};
            economy.deferReportView(()->valid[0],()->applied[0]++);valid[0]=false;
            economy.deferReportView(()->true,()->{check(Bukkit.getCurrentTick()>tick,"quote_local_screen_change_next_tick");applied[0]+=2;});
            return waitUntil(()->Bukkit.getCurrentTick()>=tick+2).thenRun(()->check(applied[0]==2,"quote_stale_local_screen_change_rejected"));
        });
    }
    private CompletionStage<Void> quoteTransactions(){
        var a=actors.getFirst();long[] cost={0};EconomyStore.Offer[] stale=new EconomyStore.Offer[1];
        return on(economy.store.offer(a.context,KEY)).thenCompose(offer->{
            stale[0]=offer;cost[0]=EconomyQuote.view(economy.store.content(),offer,economy.trades.capacity(a.value,"iron_ingot"),13,true).total();var id=UUID.randomUUID();
            var first=economy.trades.execute(economy.sessions,a.session,offer,13,true,id,economy.fence(a.session)::get,EconomyStore.Probe.NONE);
            var second=rejected(economy.trades.execute(economy.sessions,a.session,offer,13,true,id,economy.fence(a.session)::get,EconomyStore.Probe.NONE),null);
            return on(first).thenCompose(receipt->{check(receipt.total()==cost[0]&&receipt.account().balance()==100000-cost[0]&&count(a.value)==21&&a.session.save().revision()==1,"quote_confirm_exact_total_quantity_revision");
                var result=economy.previewResult("capital_trader",a.session,"iron_ingot",13,true,receipt);check(result.getItem(13).getType()==Material.EMERALD&&result.getItem(13).getItemMeta().lore().contains(net.kyori.adventure.text.Component.text("실제 총액: "+receipt.total())),"quote_result_uses_committed_receipt");return second;
            });
        }).thenCompose(v->rejected(economy.trades.execute(economy.sessions,a.session,stale[0],1,true,UUID.randomUUID(),economy.fence(a.session)::get,EconomyStore.Probe.NONE),"ECON_QUOTE_CHANGED"))
            .thenRun(()->check(a.session.state()==PlayerSessions.State.ACTIVE&&a.session.save().revision()==1&&count(a.value)==21,"quote_changed_rejected_without_HOLD_or_items"))
            .thenCompose(v->on(economy.store.offer(a.context,KEY))).thenCompose(offer->{long expected=EconomyQuote.view(economy.store.content(),offer,economy.trades.capacity(a.value,"iron_ingot"),5,false).total();
                return economy.trades.execute(economy.sessions,a.session,offer,5,false,UUID.randomUUID(),economy.fence(a.session)::get,EconomyStore.Probe.NONE).thenAccept(receipt->{check(receipt.total()==expected&&receipt.account().balance()==100000-cost[0]+expected&&count(a.value)==16&&a.session.save().revision()==2,"quote_sell_confirm_exact_balance_quantity");var after=codec.decode(a.value);var original=codec.decode(items(8));check(after[36].equals(original[36])&&after[40].equals(original[40]),"quote_equipment_offhand_preserved");});
            }).thenCompose(v->on(economy.store.history(a.context,Long.MAX_VALUE,20))).thenAccept(rows->check(rows.size()==2&&rows.get(0).quantity()==5&&rows.get(1).quantity()==13,"quote_duplicate_and_stale_no_receipt"))
            .thenCompose(v->on(economy.store.snapshot(PaperEconomy.REALM))).thenAccept(state->check(state.markets().get(KEY).stock()==92,"quote_stock_conserved"))
            .thenRun(()->{check(PaperEconomy.tradeFailure(new StorageFailure("economy","ECON_QUOTE_CHANGED")).contains("시세"),"quote_known_refusal_clear_guidance");check(PaperEconomy.tradeFailure(new StorageFailure("economy","COMMIT_UNKNOWN")).contains("보류"),"quote_unknown_is_not_success");});
    }
    public CompletionStage<Void> runMerchants(){
        return on(economy.store.migrate()).thenCompose(v->on(economy.store.createRealm(PaperEconomy.REALM,System.currentTimeMillis())))
            .thenCompose(v->on(economy.store.createRealm(PaperEconomy.OTHER,System.currentTimeMillis()))).thenCompose(v->{
                var jobs=new ArrayList<CompletionStage<?>>();for(int i=1;i<=2;i++){var a=new Actor(i);actors.add(a);jobs.add(economy.store.createAccount(a.context,100000,items(8)));}
                jobs.add(economy.store.createAccount(economy.store.context(PaperEconomy.OTHER,actors.getFirst().context.playerId()),1234,items(3)));return all(jobs);
            }).thenCompose(v->openMerchantActors()).thenCompose(v->{int tick=Bukkit.getCurrentTick();return waitUntil(()->Bukkit.getCurrentTick()>=tick+2);}).thenCompose(v->merchantViews()).thenCompose(v->merchantTransactions()).thenCompose(v->merchantProduction()).thenCompose(v->merchantReload());
    }
    private CompletionStage<Void> openMerchantActors(){var jobs=new ArrayList<CompletionStage<?>>();for(var a:actors){a.connected=true;a.session=economy.sessions.connect(a.context,economy.world,a);jobs.add(a.session.ready());}return all(jobs);}
    private CompletionStage<Void> merchantViews(){
        var a=actors.getFirst();var ids=economy.merchantEntities();plugin.getLogger().info("HEARTH_MERCHANT_ENTITIES ids="+ids+" spawns="+economy.merchantSpawns());check(ids.keySet().equals(Set.of("capital_trader","mine_trader"))&&new HashSet<>(ids.values()).size()==2,"merchant_two_native_entities_distinct_ids");
        check(economy.merchantSpawns()==2,"merchant_spawn_once_per_definition");
        return on(economy.store.offers(a.context,"capital",economy.merchants.get("capital_trader").shop().goods())).thenCompose(city->{
            var view=economy.previewAt("capital_trader",a.session,city);check(view.getItem(0).getType()==Material.IRON_INGOT,"capital_catalog_bound_to_city_merchant");
            return on(economy.store.offers(a.context,"mine",economy.merchants.get("mine_trader").shop().goods())).thenCompose(mine->{
                var inventory=economy.previewAt("mine_trader",a.session,mine);check(inventory.getItem(0).getType()==Material.IRON_ORE,"mine_catalog_only_ore");
                var product=economy.previewProductAt("mine_trader",a.session,mine.getFirst());check(product.getItem(4).getType()==Material.IRON_ORE,"mine_product_correct_material");
                return rejectedCall(()->{economy.previewProductAt("mine_trader",a.session,city.get(1));return CompletableFuture.completedFuture(null);},null)
                    .thenCompose(v->rejectedCall(()->{economy.previewAt("mine_trader",a.session,city);return CompletableFuture.completedFuture(null);},null));
            });
        }).thenCompose(v->on(economy.store.report(a.context,economy.reportDefinition,"mine"))).thenAccept(view->{
            check(economy.previewReportAt("mine_trader",a.session,view,"MARKET",0).getItem(0)!=null,"mine_report_same_merchant_scope");check(a.session.save().revision()==0&&!a.session.save().dirty()&&a.value.equals(items(8)),"merchant_views_preserve_inventory");
        }).thenCompose(v->{
            var old=economy.merchantVisit("capital_trader");var selected=new java.util.concurrent.atomic.AtomicReference<>(old);var applied=new int[1];int tick=Bukkit.getCurrentTick();
            economy.deferReportView(()->old.current(selected.get(),economy.merchantEntities().get(old.merchant()),economy.world.generation()),()->applied[0]++);selected.set(economy.merchantVisit("mine_trader"));
            return waitUntil(()->Bukkit.getCurrentTick()>=tick+2).thenRun(()->check(applied[0]==0,"late_city_callback_rejected_after_mine_selection"));
        });
    }
    private CompletionStage<EconomyStore.Receipt> merchantTrade(Actor a,String id,String good,int quantity,boolean buy){var merchant=economy.merchants.get(id);return on(economy.store.offer(a.context,new EconomyContent.Key(merchant.shop().region(),good))).thenCompose(offer->{merchant.require(offer);return economy.trades.execute(economy.sessions,a.session,offer,quantity,buy,UUID.randomUUID(),economy.fence(a.session)::get,EconomyStore.Probe.NONE);});}
    private CompletionStage<Void> merchantTransactions(){
        var a=actors.getFirst();long[] totals=new long[3];return merchantTrade(a,"mine_trader","iron_ore",2,true).thenAccept(receipt->totals[0]=receipt.total())
            .thenCompose(v->merchantTrade(a,"capital_trader","iron_ore",1,false)).thenAccept(receipt->totals[1]=receipt.total())
            .thenCompose(v->merchantTrade(actors.get(1),"capital_trader","iron_ingot",1,true)).thenAccept(receipt->totals[2]=receipt.total())
            .thenCompose(v->on(economy.store.account(a.context))).thenAccept(row->check(row.balance()==100000-totals[0]+totals[1]&&row.inventory().revision()==2&&quantity(row.inventory().payload(),Material.IRON_ORE)==1&&count(row.inventory().payload())==8,"regional_trade_same_owner_wallet_inventory_conserved"))
            .thenCompose(v->on(economy.store.account(actors.get(1).context))).thenAccept(row->check(row.balance()==100000-totals[2]&&row.inventory().revision()==1&&count(row.inventory().payload())==9,"regional_other_player_independent"))
            .thenCompose(v->on(economy.store.snapshot(PaperEconomy.REALM))).thenAccept(state->check(state.markets().get(new EconomyContent.Key("mine","iron_ore")).stock()==198&&state.markets().get(new EconomyContent.Key("capital","iron_ore")).stock()==201&&state.markets().get(KEY).stock()==99,"regional_market_stock_conservation"))
            .thenCompose(v->on(economy.store.history(a.context,Long.MAX_VALUE,20))).thenAccept(rows->check(rows.size()==2&&rows.get(0).key().region().equals("capital")&&rows.get(1).key().region().equals("mine"),"regional_history_records_source_markets"))
            .thenCompose(v->on(economy.store.offer(a.context,new EconomyContent.Key("mine","iron_ore")))).thenCompose(mine->on(economy.store.offer(a.context,new EconomyContent.Key("capital","iron_ore"))).thenAccept(city->check(economy.merchants.get("mine_trader").shop().prices(economy.store.content(),mine).getFirst().total()!=economy.merchants.get("capital_trader").shop().prices(economy.store.content(),city).getFirst().total(),"regional_quotes_follow_distinct_stock")))
            .thenCompose(v->on(economy.store.account(economy.store.context(PaperEconomy.OTHER,a.context.playerId())))).thenAccept(row->check(row.balance()==1234&&quantity(row.inventory().payload(),Material.IRON_ORE)==0&&count(row.inventory().payload())==3,"regional_other_realm_unchanged"));
    }
    private CompletionStage<Void> merchantProduction(){
        var a=actors.getFirst();return on(economy.store.tick(PaperEconomy.REALM,0,System.currentTimeMillis())).thenCompose(applied->{check(applied,"regional_trade_feeds_economy_tick");return on(economy.store.snapshot(PaperEconomy.REALM));})
            .thenAccept(state->check(state.markets().get(new EconomyContent.Key("capital","iron_ore")).stock()==193&&state.markets().get(KEY).stock()==100&&state.shipments().size()==1&&state.shipments().getFirst().quantity()==10,"regional_ore_supply_manufacture_and_route_reservation"))
            .thenCompose(v->merchantTrade(a,"capital_trader","iron_ingot",1,true)).thenAccept(receipt->{
                check(count(a.value)==9&&quantity(a.value,Material.IRON_ORE)==1&&a.session.save().revision()==3,"regional_capital_output_purchase_after_manufacturing");
                var slots=codec.decode(a.value);var original=codec.decode(items(8));check(slots[36].equals(original[36])&&slots[40].equals(original[40]),"regional_trade_equipment_offhand_preserved");
            });
    }
    private CompletionStage<Void> merchantReload(){
        var original=economy.merchantEntities();var visit=economy.merchantVisit("capital_trader");var physical=economy.physicalWorld();var before=new EconomyEngine.State[1];
        return on(economy.store.snapshot(PaperEconomy.REALM)).thenAccept(state->before[0]=state).thenCompose(v->cycle()).thenRun(()->check(economy.merchantEntities().isEmpty(),"merchant_registry_released_after_unload"))
            .thenCompose(v->openMerchantActors()).thenCompose(v->{int tick=Bukkit.getCurrentTick();return waitUntil(()->Bukkit.getCurrentTick()>=tick+3);})
            .thenRun(()->{var restored=economy.merchantEntities();check(restored.size()==2&&Collections.disjoint(original.values(),restored.values())&&economy.merchantSpawns()==4,"merchant_reload_no_duplicate_or_old_entity_reference");
                check(economy.physicalWorld().equals(physical)&&Bukkit.getWorld(physical).getEntitiesByClass(org.bukkit.entity.Villager.class).size()==2,"merchant_reload_original_world_two_native_villagers");
                check(!visit.current(economy.merchantVisit("capital_trader"),restored.get("capital_trader"),economy.world.generation()),"merchant_old_generation_visit_rejected");
                for(UUID id:original.values())check(Bukkit.getEntity(id)==null,"merchant_old_entity_removed_"+id);
                check(quantity(actors.getFirst().value,Material.IRON_ORE)==1&&actors.getFirst().session.save().revision()==3&&count(actors.getFirst().value)==9&&count(actors.get(1).value)==9,"regional_inventories_restored_after_world_reload");
            }).thenCompose(v->on(economy.store.history(actors.getFirst().context,Long.MAX_VALUE,20))).thenAccept(rows->check(rows.size()==3,"regional_trade_history_retained_after_reload"))
            .thenCompose(v->on(economy.store.snapshot(PaperEconomy.REALM))).thenAccept(state->check(state.equals(before[0]),"regional_market_transport_state_preserved_after_reload"))
            .thenCompose(v->cycle());
    }
    public CompletionStage<Void> shutdown(){return economy.shutdown().thenComposeAsync(v->waitUntil(()->economy.world.state()==WorldLifecycle.State.OFFLINE),owner).thenRun(()->plugin.getLogger().info("HEARTH_ECONOMY_TIMING "+timings));}
    public void close(){economy.close();}
}
