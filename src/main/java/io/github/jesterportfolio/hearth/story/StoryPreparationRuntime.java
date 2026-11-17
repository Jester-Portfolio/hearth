package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.administration.RuntimeLease;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.verification.StoryPreparationVerification;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.postgresql.ds.PGSimpleDataSource;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** 게임 접속을 열지 않는 명시적 새 진행 준비 모드. 운영 모드/서비스를 자동 전환하지 않습니다. */
public final class StoryPreparationRuntime implements AutoCloseable {
    public enum Phase{STARTING,READY,DRAINING,DRAINED,HOLD,CLOSED}
    private final JavaPlugin plugin;private final StoryPreparationSettings settings;private final ConcurrentLinkedQueue<Runnable> queue=new ConcurrentLinkedQueue<>();private final java.util.concurrent.Executor owner=queue::add;
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"hearth-story-preparation-io");t.setDaemon(true);return t;});private final BukkitTask pump;
    private final ServerProgressAccess access=new ServerProgressAccess();private volatile Phase phase=Phase.STARTING;private PostgresProgressStore database;private StoryRegistry registry;private StoryRunPreparation preparation;private RuntimeLease.Lease lease;private CompletableFuture<Void> draining;private CompletionStage<Void> batch;private boolean closed,clean;
    public StoryPreparationRuntime(JavaPlugin plugin)throws Exception{
        this.plugin=plugin;main();if(!Bukkit.getOnlineMode()||!Bukkit.hasWhitelist()||!Bukkit.getOnlinePlayers().isEmpty())throw new IllegalStateException("STORY_PREPARATION_AUTH_REQUIRED");settings=StoryPreparationSettings.parse(Objects.requireNonNull(plugin.getConfig().getConfigurationSection("story-preparation")).getValues(false));var folder=plugin.getDataFolder().toPath().toAbsolutePath();if(!folder.toRealPath().equals(folder))throw new IllegalStateException("STORY_PREPARATION_DIRECTORY");
        var file=folder.resolve(settings.contentFile());if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>1024*1024||!StoryTemplateSource.hash(file).equals(settings.contentHash()))throw new IllegalStateException("STORY_PREPARATION_CONTENT_HASH_CONFLICT");var yaml=YamlConfiguration.loadConfiguration(file.toFile());StoryContentBundle.parse(StoryRuntime.map(yaml));
        pump=Bukkit.getScheduler().runTaskTimer(plugin,this::pump,1,1);Objects.requireNonNull(plugin.getCommand("hearthstoryadmin")).setExecutor((sender,command,label,args)->{console(sender instanceof org.bukkit.command.ConsoleCommandSender,args,sender::sendMessage);return true;});
        try{
            var claimed=RuntimeLease.acquire(folder.resolve("story-preparation.active"),"Hearth story preparation active v1\n"+settings.contentHash()+"\n");if(claimed.isEmpty()){hold("UNCLEAN_START");return;}lease=claimed.orElseThrow();
            var source=new PGSimpleDataSource();source.setServerNames(new String[]{settings.dbHost()});source.setPortNumbers(new int[]{settings.dbPort()});source.setDatabaseName(settings.dbName());source.setUser(settings.dbUser());var password=System.getenv(settings.passwordEnvironment());if(password==null)throw new IllegalStateException("STORY_PREPARATION_PASSWORD_ENVIRONMENT");source.setPassword(password);source.setConnectTimeout(2);source.setSocketTimeout(5);source.setPrepareThreshold(0);
            database=new PostgresProgressStore(source,access,f->plugin.getLogger().warning("HEARTH_STORY_PREPARATION_DB_ERROR code="+f.code()));registry=new StoryRegistry(database,access);preparation=new StoryRunPreparation(plugin,settings,registry,owner,io);
            database.migrate().thenCompose(v->registry.migrate()).thenRunAsync(()->{if(closed||phase!=Phase.STARTING)throw new IllegalStateException("STORY_PREPARATION_START_STALE");phase=Phase.READY;plugin.getLogger().info("HEARTH_STORY_PREPARATION_READY; gameplay admissions remain closed");},owner).whenCompleteAsync((v,error)->{if(error!=null)hold("STARTUP_FAILED");},owner);
        }catch(Exception error){hold(StoryRunPreparation.code(error));}
    }
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("STORY_PREPARATION_OWNER");}
    private void pump(){main();for(int i=0;i<200;i++){var work=queue.poll();if(work==null)break;try{work.run();}catch(RuntimeException error){hold("OWNER_DELIVERY_FAILED");}}}
    private void hold(String code){if(closed)return;phase=Phase.HOLD;plugin.getLogger().warning("HEARTH_STORY_PREPARATION_HOLD code="+code);}
    public Phase phase(){return phase;}public StoryPreparationSettings settings(){return settings;}public StoryRunPreparation preparation(){main();return Objects.requireNonNull(preparation);}public PostgresProgressStore database(){return Objects.requireNonNull(database);}public StoryRegistry registry(){return Objects.requireNonNull(registry);}public ServerProgressAccess access(){return access;}public java.util.concurrent.Executor owner(){return owner;}
    public CompletionStage<Void> drain(){
        main();if(clean)return CompletableFuture.completedFuture(null);if(draining!=null)return draining;if(phase!=Phase.READY||preparation==null||preparation.busy()||batch!=null&&!batch.toCompletableFuture().isDone()||preparation.held())return CompletableFuture.failedFuture(new IllegalStateException("STORY_PREPARATION_DRAIN_REVIEW_REQUIRED"));phase=Phase.DRAINING;draining=new CompletableFuture<>();preparation.close();io.shutdown();database.closeAsync().whenCompleteAsync((v,error)->{if(error!=null){hold("DRAIN_FAILED");draining.completeExceptionally(error);return;}try{lease.clean();clean=true;phase=Phase.DRAINED;plugin.getLogger().info("HEARTH_STORY_PREPARATION_DRAINED PASS");draining.complete(null);}catch(Exception failure){hold("DRAIN_FAILED");draining.completeExceptionally(failure);}},owner);return draining;
    }
    public void console(boolean console,String[] args,Consumer<String> reply){
        main();if(!console){reply.accept("새 진행 준비는 서버 콘솔 전용입니다.");return;}
        if(Arrays.equals(args,new String[]{"status"})){reply.accept("스토리 준비 상태="+phase+" 작업="+(preparation==null?Map.of():preparation.status()));return;}
        if(Arrays.equals(args,new String[]{"verify"})){StoryPreparationVerification.prepare(plugin,this).whenCompleteAsync((v,error)->plugin.getLogger().log(error==null?java.util.logging.Level.INFO:java.util.logging.Level.SEVERE,"HEARTH_STORY_PREPARATION_PROBE "+(error==null?"PASS":"FAIL"),error),owner);return;}
        if(Arrays.equals(args,new String[]{"drain"})){drain().whenCompleteAsync((v,error)->reply.accept(error==null?"스토리 준비 저장/종료 확인 완료":"스토리 준비 종료 보류 · 실패 산출물과 원본을 보존합니다."),owner);return;}
        if(args.length==2&&args[0].equals("prepare")&&phase==Phase.READY&&settings.jobs().containsKey(args[1])){preparation.prepare(args[1]).whenCompleteAsync((prepared,error)->reply.accept(error==null?"새 진행 준비 완료 · 연결 자료="+prepared.report():"새 진행 준비 보류 code="+StoryRunPreparation.code(error)),owner);return;}
        if(Arrays.equals(args,new String[]{"prepare"})&&phase==Phase.READY&&!preparation.busy()&&(batch==null||batch.toCompletableFuture().isDone())){
            CompletionStage<Void> work=CompletableFuture.completedFuture(null);for(String id:new TreeSet<>(settings.jobs().keySet()))work=work.thenComposeAsync(v->preparation.prepare(id).handleAsync((prepared,error)->{reply.accept(error==null?"새 진행 준비 완료 job="+id+" 자료="+prepared.report():"새 진행 준비 보류 job="+id+" code="+StoryRunPreparation.code(error));return (Void)null;},owner),owner);batch=work;return;
        }
        reply.accept("hearthstoryadmin status | prepare [설정한 작업 ID] | drain");
    }
    @Override public void close(){main();if(closed)return;closed=true;phase=Phase.CLOSED;pump.cancel();try{if(preparation!=null)preparation.close();}finally{io.shutdownNow();if(database!=null)database.closeAsync();}plugin.getLogger().info("HEARTH_STORY_PREPARATION_DISABLE_CLOSE "+(clean?"PASS":"HOLD"));}
}
