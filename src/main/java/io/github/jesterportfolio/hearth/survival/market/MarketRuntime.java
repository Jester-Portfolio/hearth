package io.github.jesterportfolio.hearth.survival.market;

import io.github.jesterportfolio.hearth.economy.*;
import io.github.jesterportfolio.hearth.economy.MarketAutomation.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 생존 경제와 같은 PG Tick/종료 경계를 사용하며 별도 거래/플레이어 쓰기 경로는 없습니다. */
public final class MarketRuntime implements AutoCloseable {
 private final EconomyStore store;private final UUID realm;private final Path binding;private final MarketSettings settings;private final Executor owner;private final MarketDirector director;private Cursor cursor,opened;
 public MarketRuntime(EconomyStore store,UUID realm,Path data,MarketSettings settings,Executor owner,BooleanSupplier active){this.store=store;this.realm=realm;this.binding=data.resolve("market-automation.properties");this.settings=settings;this.owner=owner;director=new MarketDirector(settings,settings.aiMode().equals("ollama")?MarketAdviser.ollama(settings.aiAddress()):null,owner,active);}
 public CompletionStage<Void> open(){try{boolean existing=Files.exists(binding,LinkOption.NOFOLLOW_LINKS);if(existing){if(!binding.equals(binding.toRealPath())||!Files.isRegularFile(binding,LinkOption.NOFOLLOW_LINKS)||Files.size(binding)>4096)throw new IllegalStateException("MARKET_BINDING_PATH");var p=new Properties();try(var r=Files.newBufferedReader(binding)){p.load(r);}if(!realm.toString().equals(p.getProperty("realm"))||!settings.policy().fingerprint().equals(p.getProperty("policy")))throw new IllegalStateException("MARKET_POLICY_MISMATCH");}return store.openController(realm,settings.policy(),!existing&&settings.allowNew()).thenAcceptAsync(value->{cursor=value;opened=value;if(!existing){var p=new Properties();p.setProperty("realm",realm.toString());p.setProperty("policy",settings.policy().fingerprint());try(var out=Files.newBufferedWriter(binding,StandardOpenOption.CREATE_NEW)){p.store(out,"Hearth market controller v1");}catch(Exception failure){throw new IllegalStateException("MARKET_BINDING_WRITE");}try(var ch=java.nio.channels.FileChannel.open(binding,StandardOpenOption.WRITE)){ch.force(true);}catch(Exception failure){throw new IllegalStateException("MARKET_BINDING_WRITE");}}},owner);}catch(Exception failure){return CompletableFuture.failedFuture(failure);}}
 public CompletionStage<Boolean> tick(long expected,long wall){return store.tickManaged(realm,expected,wall,settings.policy(),director.advice(expected),EconomyStore.Probe.NONE).thenApplyAsync(result->{if(result.advanced()){cursor=result.cursor();director.observe(result.observation());}return result.advanced();},owner);}
 public Cursor cursor(){return cursor;}public Cursor opened(){return opened;}public MarketSettings settings(){return settings;}public MarketDirector director(){return director;}
 public void console(String[] args,Consumer<String> output){if(Arrays.equals(args,new String[]{"status"})){output.accept("시장 자동 운영="+(cursor==null?"STARTING":cursor.mode())+" Tick="+(cursor==null?0:cursor.tick())+" AI="+director.status()+" 수용="+director.accepted()+" 거부="+director.rejected()+" 마지막 상태="+director.lastFailure());return;}if(Arrays.equals(args,new String[]{"prepare"})){director.prepare().whenCompleteAsync((v,error)->output.accept(error==null?"시장 AI 명시적 준비 완료":"시장 AI 준비 실패 · 규칙 기반 운영 유지"),owner);return;}if(Arrays.equals(args,new String[]{"history"})){store.modeHistory(realm,20).whenCompleteAsync((rows,error)->output.accept(error==null?"시장 운영 전환="+rows:"시장 전환 기록 조회 실패"),owner);return;}output.accept("heartheconomy automation status|history|prepare");}
 @Override public void close(){director.close();}
}
