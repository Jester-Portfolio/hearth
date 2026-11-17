package io.github.jesterportfolio.hearth.economy;

import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.lifecycle.WorldLifecycle;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.combat.CombatPlayers;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;

/** 기본 비활성인 정상 경제 전용 실행. 스토리 모듈/시험 fixture·AI를 초기화하지 않습니다. */
public final class EconomyRuntime implements AutoCloseable {
    public enum Phase { STARTING,READY,DRAINING,DRAINED,HOLD,CLOSED }
    private final JavaPlugin plugin;private final ConcurrentLinkedQueue<Runnable> queue=new ConcurrentLinkedQueue<>();private final Executor owner=queue::add;private final BukkitTask pump;
    private final EconomyRuntimeSettings settings;private io.github.jesterportfolio.hearth.administration.RuntimeLease.Lease lease;private final PaperInventoryCodec codec=new PaperInventoryCodec();private final NamespacedKey inventoryOwner;
    private volatile Phase phase=Phase.STARTING;private PostgresProgressStore database;private PaperEconomy economy;private EconomyAdmission admission;private CompletableFuture<Void> draining;private boolean closed,clean,started;
    public EconomyRuntime(JavaPlugin plugin)throws Exception{
        this.plugin=plugin;main();var section=Objects.requireNonNull(plugin.getConfig().getConfigurationSection("economy-runtime"));settings=EconomyRuntimeSettings.parse(section.getValues(false));
        if(!Bukkit.getOnlineMode()||!Bukkit.hasWhitelist()||!Bukkit.getOnlinePlayers().isEmpty())throw new IllegalStateException("ECON_RUNTIME_AUTH_REQUIRED");
        inventoryOwner=new NamespacedKey(plugin,"economy_inventory_realm");var directory=plugin.getDataFolder().toPath().toAbsolutePath();if(!directory.toRealPath().equals(directory))throw new IllegalStateException("ECON_RUNTIME_DIRECTORY");

        pump=Bukkit.getScheduler().runTaskTimer(plugin,this::pump,1,1);
        Objects.requireNonNull(plugin.getCommand("heartheconomy")).setExecutor((sender,command,label,args)->{console(sender instanceof org.bukkit.command.ConsoleCommandSender,args,sender::sendMessage);return true;});
        try{
            var claimed=io.github.jesterportfolio.hearth.administration.RuntimeLease.acquire(directory.resolve("economy-runtime.active"),"Hearth economy active v1\n"+settings.realm()+"\n");if(claimed.isEmpty()){hold("UNCLEAN_START");return;}lease=claimed.orElseThrow();
            var file=directory.resolve(settings.contentFile());if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("ECON_RUNTIME_CONTENT_REQUIRED");var properties=new Properties();try(var input=Files.newBufferedReader(file)){properties.load(input);}var content=EconomyContent.parse(properties);if(!content.offline().equals("PAUSE")||content.fixtureBalance()!=0)throw new IllegalStateException("ECON_RUNTIME_POLICY_CONFLICT");
            var worldBackend=new EconomyWorldBackend(settings);var source=new PGSimpleDataSource();source.setServerNames(new String[]{settings.dbHost()});source.setPortNumbers(new int[]{settings.dbPort()});source.setDatabaseName(settings.dbName());source.setUser(settings.dbUser());
            String password=System.getenv(settings.passwordEnvironment());if(password==null)throw new IllegalStateException("ECON_RUNTIME_PASSWORD_ENVIRONMENT");source.setPassword(password);source.setConnectTimeout(2);source.setSocketTimeout(5);source.setPrepareThreshold(0);
            database=PostgresProgressStore.forModules(source,f->plugin.getLogger().warning("HEARTH_ECONOMY_DB_ERROR code="+f.code()));
            economy=new PaperEconomy(plugin,database,owner,codec,directory,properties,content,new PaperEconomy.Normal(settings,worldBackend,worldBackend.placement()));
            var empty=codec.encode(new ItemStack[41]);admission=new EconomyAdmission(settings.realm(),new EconomyAdmission.Accounts(){public CompletionStage<EconomyStore.Account> existing(ServerProgressAccess.Context c){return economy.store.account(c);}public CompletionStage<EconomyStore.Account> ensureEmpty(ServerProgressAccess.Context c,InventoryPayload p){return economy.store.ensureAccount(c,p);}},empty,owner);
            economy.adapter.interactionReady(()->phase==Phase.READY);economy.adapter.provisioner(this::provision);
            economy.adapter.gathering((p,location)->location.getWorld()!=null&&location.getWorld().getUID().equals(economy.physicalWorld()),(p,block)->phase==Phase.READY&&economy.adapter.current(p)!=null&&block.getWorld().getUID().equals(economy.physicalWorld()),(p,line)->false);
            var gatheringFile=EconomyGatheringContent.file(properties);if(gatheringFile.isPresent())economy.configureGathering(EconomyGathering.load(plugin,gatheringFile.orElseThrow()));
            economy.store.migrate().thenCompose(v->economy.store.openRealm(settings.realm(),System.currentTimeMillis(),!worldBackend.previouslyOwned())).thenCompose(v->economy.verifyGatheringConfiguration()).thenComposeAsync(v->{var world=Objects.requireNonNull(Bukkit.getWorld(economy.physicalWorld()));for(var merchant:economy.merchants.all()){var point=merchant.point();var region=worldBackend.placement().directory().resolve("region/r."+(((int)Math.floor(point.x()))>>9)+"."+(((int)Math.floor(point.z()))>>9)+".mca");if(!Files.isRegularFile(region,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("ECON_MERCHANT_UNPREPARED_REGION");}return world.getChunkAtAsync(((int)Math.floor(settings.x()))>>4,((int)Math.floor(settings.z()))>>4);},owner).thenRunAsync(()->{if(closed||phase!=Phase.STARTING)throw new IllegalStateException("ECON_RUNTIME_START_STALE");safeEntry();started=true;phase=Phase.READY;economy.startTicker();plugin.getLogger().info("HEARTH_ECONOMY_RUNTIME_READY");},owner).whenCompleteAsync((v,error)->{if(error!=null)hold("STARTUP_FAILED");},owner);
        }catch(Exception error){hold("STARTUP_FAILED");}
    }
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("ECON_RUNTIME_MAIN");}
    private void pump(){main();for(int i=0;i<200;i++){var action=queue.poll();if(action==null)break;try{action.run();}catch(RuntimeException failure){hold("OWNER_DELIVERY_FAILED");}}if(phase==Phase.READY&&economy!=null&&economy.held())hold("ECONOMY_HELD");}
    private void hold(String code){if(closed)return;phase=Phase.HOLD;plugin.getLogger().warning("HEARTH_ECONOMY_RUNTIME_HOLD code="+code);}
    private void safeEntry(){var location=economy.entry();var world=location.getWorld();int x=location.getBlockX(),y=location.getBlockY(),z=location.getBlockZ();if(y<=world.getMinHeight()||y+2>=world.getMaxHeight()||!world.getWorldBorder().isInside(location))throw new IllegalStateException("ECON_ENTRY_UNSAFE");var floor=world.getBlockAt(x,y-1,z);var feet=world.getBlockAt(x,y,z);var head=world.getBlockAt(x,y+1,z);if(!CombatPlayers.safeCells(floor.getType().name(),feet.getType().name(),head.getType().name(),floor.getType().isSolid(),feet.isPassable(),head.isPassable()))throw new IllegalStateException("ECON_ENTRY_UNSAFE");}
    private CompletionStage<io.github.jesterportfolio.hearth.session.PaperSessionAdapter.Link> provision(Player player,BooleanSupplier current){
        main();if(phase!=Phase.READY||player.isDead()||(player.getGameMode()!=GameMode.SURVIVAL&&player.getGameMode()!=GameMode.ADVENTURE))return CompletableFuture.failedFuture(new IllegalStateException("ECON_NOT_READY"));var before=codec.encode(Arrays.copyOf(player.getInventory().getContents(),41));boolean empty=Arrays.stream(player.getInventory().getContents()).allMatch(i->i==null||i.isEmpty())&&player.getItemOnCursor().isEmpty();String ownerRealm=player.getPersistentDataContainer().get(inventoryOwner,PersistentDataType.STRING);var context=economy.store.context(settings.realm(),player.getUniqueId());
        BooleanSupplier valid=()->phase==Phase.READY&&current.getAsBoolean()&&Objects.equals(ownerRealm,player.getPersistentDataContainer().get(inventoryOwner,PersistentDataType.STRING))&&codec.equivalent(before,codec.encode(Arrays.copyOf(player.getInventory().getContents(),41)))&&player.getItemOnCursor().isEmpty();
        return admission.open(context,ownerRealm,empty,valid).thenApplyAsync(account->{if(!valid.getAsBoolean())throw new IllegalStateException("ECON_ADMISSION_STALE");player.getPersistentDataContainer().set(inventoryOwner,PersistentDataType.STRING,settings.realm().toString());return new io.github.jesterportfolio.hearth.session.PaperSessionAdapter.Link(context,economy.world,economy::entry);},owner);
    }

    public Phase phase(){return phase;}public PaperEconomy economy(){main();return Objects.requireNonNull(economy);}public EconomyRuntimeSettings settings(){return settings;}public Executor owner(){return owner;}public EconomyAdmission admissions(){main();return Objects.requireNonNull(admission);}
    public CompletionStage<Void> drain(){
        main();if(clean)return CompletableFuture.completedFuture(null);if(draining!=null&&!draining.isDone())return draining;if(!started||phase!=Phase.READY&&phase!=Phase.HOLD||economy==null||phase==Phase.HOLD&&economy.held())return CompletableFuture.failedFuture(new IllegalStateException("ECON_DRAIN_REVIEW_REQUIRED"));
        phase=Phase.DRAINING;draining=new CompletableFuture<>();economy.drain().thenComposeAsync(v->awaitOffline(0),owner).thenCompose(v->database.closeAsync()).whenCompleteAsync((v,error)->{if(error!=null){hold("DRAIN_FAILED");draining.completeExceptionally(error);return;}try{lease.clean();clean=true;phase=Phase.DRAINED;plugin.getLogger().info("HEARTH_ECONOMY_RUNTIME_DRAINED PASS");draining.complete(null);}catch(Exception failure){hold("DRAIN_FAILED");draining.completeExceptionally(failure);}},owner);return draining;
    }
    private CompletionStage<Void> awaitOffline(int ticks){var state=economy.world.state();if(state==WorldLifecycle.State.OFFLINE){economy.finishOffline();return CompletableFuture.completedFuture(null);}if(state==WorldLifecycle.State.ERROR_HOLD||state==WorldLifecycle.State.RECOVERY_REQUIRED||ticks>settings.idleSeconds()*20+400)return CompletableFuture.failedFuture(new IllegalStateException("ECON_WORLD_DRAIN_HOLD"));var done=new CompletableFuture<Void>();Bukkit.getScheduler().runTaskLater(plugin,()->awaitOffline(ticks+1).whenComplete((v,error)->{if(error==null)done.complete(null);else done.completeExceptionally(error);}),1);return done;}
    public void console(boolean console,String[] args,Consumer<String> output){main();if(!console){output.accept("경제 관리는 서버 콘솔 전용입니다.");return;}if(Arrays.equals(args,new String[]{"status"})){output.accept("경제 실행 상태="+phase+" world="+(economy==null?"UNBOUND":economy.world.state())+" clean="+clean);return;}if(Arrays.equals(args,new String[]{"verify"})){io.github.jesterportfolio.hearth.verification.EconomyRuntimeVerification.run(plugin,this).whenCompleteAsync((v,error)->plugin.getLogger().log(error==null?java.util.logging.Level.INFO:java.util.logging.Level.SEVERE,"HEARTH_ECONOMY_RUNTIME_PROBE "+(error==null?"PASS":"FAIL"),error),owner);return;}if(Arrays.equals(args,new String[]{"drain"})){drain().whenCompleteAsync((v,error)->output.accept(error==null?"경제 저장/종료 준비 완료":"경제 종료 준비 보류 · 원본과 오류 기록을 확인하세요."),owner);return;}if(args.length==2&&args[0].equals("peer-pending")&&economy!=null){try{var actor=economy.store.context(settings.realm(),UUID.fromString(args[1]));output.accept("직접 거래 보류 요청 "+economy.peers.exchange.pending(actor));}catch(RuntimeException refused){output.accept("계정 UUID를 확인하세요.");}return;}if(args.length==3&&args[0].equals("peer-receipt")&&economy!=null){try{var actor=economy.store.context(settings.realm(),UUID.fromString(args[1]));economy.store.peerReceipt(actor,UUID.fromString(args[2])).whenCompleteAsync((found,error)->output.accept(error!=null?"직접 거래 조회 보류":found.isEmpty()?"확정 영수증 없음 · 재제출/재지급하지 마세요.":"직접 거래 확정 영수증 "+found.get()),owner);}catch(RuntimeException refused){output.accept("직접 거래 조회 인자를 확인하세요.");}return;}if(args.length>0&&Set.of("pending","history","inspect","recover").contains(args[0])&&economy!=null){var forwarded=args.clone();forwarded[0]="economy-"+forwarded[0];try{economy.administration(forwarded);}catch(RuntimeException failure){output.accept("경제 관리 요청 보류");}return;}output.accept("heartheconomy status|drain|pending|history|inspect|recover|peer-pending <player UUID>|peer-receipt <player UUID> <request UUID>");}
    @Override public void close(){
        main();if(closed)return;RuntimeException failure=null;
        try{if(!clean){phase=Phase.HOLD;plugin.getLogger().warning("HEARTH_ECONOMY_UNDRAINED_HOLD original active marker retained");if(economy!=null){economy.sessions.shutdown();for(var player:List.copyOf(Bukkit.getOnlinePlayers()))player.kick(Component.text("경제 서버 종료 · 저장 상태 확인이 필요합니다."));}}}
        catch(RuntimeException error){failure=error;}
        finally{closed=true;phase=Phase.CLOSED;pump.cancel();try{if(economy!=null)economy.close();}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}finally{if(database!=null)database.closeAsync();}}
        plugin.getLogger().info("HEARTH_ECONOMY_DISABLE_CLOSE "+(clean&&failure==null?"PASS":"HOLD"));if(failure!=null)throw failure;
    }
}
