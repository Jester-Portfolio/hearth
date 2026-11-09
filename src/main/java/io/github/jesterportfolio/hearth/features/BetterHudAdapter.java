package io.github.jesterportfolio.hearth.features;

import io.github.jesterportfolio.hearth.session.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import kr.toxicity.hud.api.*;
import kr.toxicity.hud.api.hud.Hud;
import kr.toxicity.hud.api.placeholder.HudPlaceholder;
import kr.toxicity.hud.api.player.HudPlayer;
import kr.toxicity.hud.api.plugin.ReloadState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** 선택형 공개 API 연동. BetterHud에는 검증된 개인 표시만 주고 다른 HUD/게임 상태는 변경하지 않습니다. */
public final class BetterHudAdapter implements AutoCloseable,Listener {
    public static final String VERSION="2.1.0-SNAPSHOT-448";
    private record Binding(HudPlayer actor,Object scope,Hud quest,Hud minimap) {}
    private final JavaPlugin plugin;
    private final PaperSessionAdapter players;
    private final Executor owner;
    private final BetterHud api;
    private final org.bukkit.plugin.Plugin better;
    private final AnchoredHudFrames<HudPlayer> frames=new AnchoredHudFrames<>(System::nanoTime);
    private final HudPackGate<Player> packGate=new HudPackGate<>();
    private final Map<Player,Binding> bindings=new IdentityHashMap<>();
    private final CompletableFuture<Void> ready=new CompletableFuture<>();
    private final BukkitTask startup;
    private volatile boolean closed;
    private boolean loading;
    private int polls;

    public BetterHudAdapter(JavaPlugin plugin,PaperSessionAdapter players,Executor owner) {
        this.plugin=plugin;this.players=players;this.owner=owner;
        better=Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("BetterHud"),"BETTERHUD_REQUIRED");
        if(!better.isEnabled()||!VERSION.equals(better.getPluginMeta().getVersion()))throw new IllegalStateException("BETTERHUD_UNSUPPORTED_VERSION");
        api=BetterHudAPI.inst();
        if(api.isMergeBossBar())throw new IllegalStateException("BETTERHUD_MERGE_BOSS_BAR_MUST_BE_FALSE");
        var registry=api.getPlaceholderManager().getStringContainer();
        registry.addPlaceholder("hearth_quest",placeholder(false));
        registry.addPlaceholder("hearth_map",placeholder(true));
        startup=Bukkit.getScheduler().runTaskTimer(plugin,this::prepare,1,5);
        Bukkit.getPluginManager().registerEvents(this,plugin);
        ready.whenComplete((v,error)->{if(error!=null&&!closed)plugin.getLogger().warning("HEARTH_BETTERHUD_UNAVAILABLE "+error.getClass().getSimpleName());});
        ready.thenRun(()->{
            api.addReloadStartTask(()->{if(!closed)frames.clear();});
            api.addReloadEndTask(result->{
                if(closed)return;
                if(result instanceof ReloadState.Success success)try {
                    UUID id=exportPack(success);
                    owner.execute(()->{if(!closed)packGate.expect(id);});
                }catch(Exception error){owner.execute(packGate::clear);}
                else owner.execute(packGate::clear);
            });
        });
    }
    private HudPlaceholder<String> placeholder(boolean map) {
        return HudPlaceholder.<String>builder().requiredArgsLength(1).function((args,event)->{
            if(args.size()!=1)throw new IllegalArgumentException("HEARTH_HUD_ROW");
            int row=Integer.parseInt(args.getFirst());
            if(row<0||row>=(map?16:6))throw new IllegalArgumentException("HEARTH_HUD_ROW");
            return actor->{var value=frames.read(actor.uuid(),actor);return map?value.map(row):value.quest(row);};
        }).build();
    }
    private void prepare() {
        if(closed||ready.isDone()){startup.cancel();return;}
        if(++polls>480){ready.completeExceptionally(new IllegalStateException("BETTERHUD_START_TIMEOUT"));startup.cancel();return;}
        if(loading||api.isOnReload())return;
        try{BetterHudBundle.install(plugin,better);}catch(java.io.IOException error){ready.completeExceptionally(error);return;}
        loading=true;
        Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{
            try {
                var result=api.reload();
                UUID packId=result instanceof ReloadState.Success success?exportPack(success):null;
                if(!closed)owner.execute(()->{
                    if(!(result instanceof ReloadState.Success success)||!success.resourcePack().containsKey("assets/hearth/font/phone.json")||!success.resourcePack().containsKey("assets/hearth/items/phone.json")||api.getHudManager().getHud("hearth_quest")==null||api.getHudManager().getHud("hearth_minimap")==null)
                        ready.completeExceptionally(new IllegalStateException("BETTERHUD_LAYOUT_LOAD_FAILED"));
                    else {packGate.expect(packId);ready.complete(null);}
                });
            }catch(Throwable error){ready.completeExceptionally(error);}
        });
    }
    private UUID exportPack(ReloadState.Success success) throws java.io.IOException {
        if(!success.resourcePack().containsKey("assets/hearth/font/phone.json")||!success.resourcePack().containsKey("assets/hearth/items/phone.json"))throw new java.io.IOException("BETTERHUD_PHONE_PACK_MISSING");
        UUID id=HudPackGate.contentId(success.resourcePack());
        var file=plugin.getDataFolder().toPath().resolve("betterhud-pack.json");
        if(java.nio.file.Files.isSymbolicLink(file))throw new java.io.IOException("BETTERHUD_PACK_METADATA_SYMLINK");
        java.nio.file.Files.writeString(file,"{\"pack_id\":\""+id+"\",\"betterhud_version\":\""+VERSION+"\"}\n");
        return id;
    }
    public CompletionStage<Void> ready(){return ready;}
    public boolean show(Player player,PlayerSessions.Session session,List<String> quests,List<List<Integer>> pixels) {
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("BETTERHUD_MAIN_THREAD");
        if(closed||players.prepared(player)!=session||!ready.isDone()||ready.isCompletedExceptionally()||api.isOnReload()||!packGate.allowed(player)){clear(player);return false;}
        var actor=api.getPlayerManager().getHudPlayer(player.getUniqueId());
        if(actor==null||actor.handle()!=player){clear(player);return false;}
        var quest=api.getHudManager().getHud("hearth_quest");var map=api.getHudManager().getHud("hearth_minimap");
        if(quest==null||map==null){clear(player);return false;}
        var old=bindings.get(player);
        if(old!=null&&(old.actor!=actor||old.scope!=session||old.quest!=quest||old.minimap!=map)){clear(player);old=null;}
        if(old==null)bindings.put(player,new Binding(actor,session,quest,map));
        frames.publish(player.getUniqueId(),actor,session,AnchoredHudFrames.format(quests,pixels));
        if(quests.isEmpty())quest.remove(actor);else quest.add(actor);
        if(pixels.isEmpty())map.remove(actor);else map.add(actor);
        return true;
    }
    public void clear(Player player) {
        var old=bindings.remove(player);
        if(old!=null) {
            frames.clear(player.getUniqueId(),old.actor,old.scope);
            old.quest.remove(old.actor);old.minimap.remove(old.actor);
        }
    }
    public void verify(BiConsumer<Boolean,String> check) {
        check.accept(ready.isDone()&&!ready.isCompletedExceptionally(),"betterhud_real_API_reload_completed");
        for(String name:List.of("hearth_quest","hearth_minimap")) {
            var hud=api.getHudManager().getHud(name);
            check.accept(hud!=null&&!hud.isDefault()&&hud.tick()==5,"betterhud_owned_anchored_definition_"+name);
        }
        var all=api.getPlaceholderManager().getStringContainer().getAllPlaceholders();
        check.accept(all.get("hearth_quest").getRequiredArgsLength()==1&&all.get("hearth_map").getRequiredArgsLength()==1,"betterhud_snapshot_placeholders_registered_without_fake_player");
        check.accept(!api.isMergeBossBar(),"betterhud_combat_bossbar_merge_disabled");
        check.accept(packGate.expected()!=null,"betterhud_content_bound_resource_pack_ID_exported");
    }
    @EventHandler public void resourcePack(PlayerResourcePackStatusEvent event) {
        packGate.status(event.getPlayer(),event.getID(),event.getStatus()==PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        if(!packGate.allowed(event.getPlayer()))clear(event.getPlayer());
    }
    @EventHandler public void quit(PlayerQuitEvent event){clear(event.getPlayer());packGate.forget(event.getPlayer());}
    public void close() {
        closed=true;startup.cancel();ready.completeExceptionally(new IllegalStateException("BETTERHUD_CLOSED"));
        HandlerList.unregisterAll(this);packGate.clear();
        RuntimeException failure=null;
        for(var player:List.copyOf(bindings.keySet()))try{clear(player);}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        frames.clear();
        bindings.clear();
        if(failure!=null)throw failure;
    }
}
