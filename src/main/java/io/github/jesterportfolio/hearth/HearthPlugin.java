package io.github.jesterportfolio.hearth;

import io.github.jesterportfolio.hearth.verification.SmokeVerification;
import io.github.jesterportfolio.hearth.verification.RestartVerification;
import io.github.jesterportfolio.hearth.verification.PlayerActionObserver;
import io.github.jesterportfolio.hearth.verification.DatabaseVerification;
import io.github.jesterportfolio.hearth.verification.InventoryVerification;
import io.github.jesterportfolio.hearth.verification.LifecycleVerification;
import java.nio.file.*;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class HearthPlugin extends JavaPlugin {
    private io.github.jesterportfolio.hearth.survival.SurvivalRuntime survivalRuntime;
    private io.github.jesterportfolio.hearth.network.LobbyRuntime lobbyRuntime;
    private io.github.jesterportfolio.hearth.administration.RuntimeObservation observation;
    private volatile io.github.jesterportfolio.hearth.story.StoryPreparationRuntime storyPreparation;
    public io.github.jesterportfolio.hearth.story.StoryPreparationRuntime storyPreparation(){return java.util.Objects.requireNonNull(storyPreparation);}
    private volatile io.github.jesterportfolio.hearth.story.StoryRuntime storyRuntime;
    public io.github.jesterportfolio.hearth.story.StoryRuntime storyRuntime(){return java.util.Objects.requireNonNull(storyRuntime);}
    private volatile io.github.jesterportfolio.hearth.economy.EconomyRuntime economyRuntime;
    public io.github.jesterportfolio.hearth.economy.EconomyRuntime economyRuntime(){return java.util.Objects.requireNonNull(economyRuntime);}
    private SmokeVerification verification;
    private RestartVerification restartVerification;
    private boolean playerTest;
    private DatabaseVerification databaseVerification;
    private InventoryVerification inventoryVerification;
    private LifecycleVerification lifecycleVerification;

    @Override public void onEnable() {
        saveDefaultConfig();
        final String mode;
        try{mode=io.github.jesterportfolio.hearth.economy.EconomyRuntimeSettings.mode(getConfig().getString("runtime-mode","disabled"),getConfig().getBoolean("verification-enabled"));}
        catch(RuntimeException error){getLogger().severe("HEARTH_RUNTIME_MODE_REFUSED");getServer().getPluginManager().registerEvents(new io.github.jesterportfolio.hearth.economy.EconomyLoginGate(()->false),this);return;}
        if(mode.equals("survival")){
            getServer().getPluginManager().registerEvents(new io.github.jesterportfolio.hearth.economy.EconomyLoginGate(()->survivalRuntime!=null&&survivalRuntime.phase()==io.github.jesterportfolio.hearth.survival.SurvivalRuntime.Phase.READY),this);
            try{survivalRuntime=new io.github.jesterportfolio.hearth.survival.SurvivalRuntime(this);}catch(Exception error){getLogger().log(java.util.logging.Level.SEVERE,"HEARTH_SURVIVAL_REFUSED; admissions remain closed",error);}
            observe("survival");return;
        }
        if(mode.equals("lobby")){
            getServer().getPluginManager().registerEvents(new io.github.jesterportfolio.hearth.economy.EconomyLoginGate(()->lobbyRuntime!=null&&lobbyRuntime.phase()==io.github.jesterportfolio.hearth.network.LobbyRuntime.Phase.READY),this);
            try{lobbyRuntime=new io.github.jesterportfolio.hearth.network.LobbyRuntime(this);}catch(Exception error){getLogger().severe("HEARTH_LOBBY_REFUSED; admissions remain closed");}
            observe("lobby");return;
        }
        if(mode.equals("story-prepare")){
            getServer().getPluginManager().registerEvents(new io.github.jesterportfolio.hearth.story.StoryLoginGate(uuid->false),this);
            java.util.Objects.requireNonNull(getCommand("hearthstoryadmin")).setExecutor((sender,command,label,args)->{sender.sendMessage("스토리 준비 설정 보류 · 서버 콘솔에서 확인하세요.");return true;});
            try{storyPreparation=new io.github.jesterportfolio.hearth.story.StoryPreparationRuntime(this);}catch(Exception error){getLogger().severe("HEARTH_STORY_PREPARATION_REFUSED; admissions remain closed");}
            observe("story-prepare");
            return;
        }
        if(mode.equals("story")){
            java.util.Objects.requireNonNull(getCommand("hearthrecovery")).setExecutor((sender,command,label,args)->{sender.sendMessage("스토리 복구 진단 설정 보류 · 콘솔에서 확인하세요.");return true;});
            getServer().getPluginManager().registerEvents(new io.github.jesterportfolio.hearth.story.StoryLoginGate(uuid->storyRuntime!=null&&storyRuntime.phase()==io.github.jesterportfolio.hearth.story.StoryRuntime.Phase.READY&&storyRuntime.settings().members().containsKey(uuid)),this);
            java.util.Objects.requireNonNull(getCommand("hearthstoryadmin")).setExecutor((sender,command,label,args)->{sender.sendMessage("스토리 실행 설정 보류 · 서버 콘솔에서 로그/설정을 확인하세요.");return true;});
            try{storyRuntime=new io.github.jesterportfolio.hearth.story.StoryRuntime(this);}
            catch(Exception error){getLogger().severe("HEARTH_STORY_RUNTIME_REFUSED; admissions remain closed");}
            observe("story");
            return;
        }
        if(mode.equals("economy")){
            getServer().getPluginManager().registerEvents(new io.github.jesterportfolio.hearth.economy.EconomyLoginGate(()->economyRuntime!=null&&economyRuntime.phase()==io.github.jesterportfolio.hearth.economy.EconomyRuntime.Phase.READY),this);
            java.util.Objects.requireNonNull(getCommand("heartheconomy")).setExecutor((sender,command,label,args)->{sender.sendMessage("경제 실행 설정 보류 · 서버 콘솔에서 로그/설정을 확인하세요.");return true;});
            try{economyRuntime=new io.github.jesterportfolio.hearth.economy.EconomyRuntime(this);}
            catch(Exception error){getLogger().severe("HEARTH_ECONOMY_RUNTIME_REFUSED; admissions remain closed");}
            observe("economy");
            return;
        }
        getLogger().info("Hearth enabled; gameplay is inactive unless an explicit runtime or isolated verification mode is configured.");
        if (getConfig().getBoolean("verification-enabled")) {
            try {
                Path cwd = Path.of("").toRealPath();
                String approved = System.getProperty("hearth.verification.root", "");
                boolean lifecycle = cwd.getParent().getFileName().toString().startsWith("hearth-s4-11.");
                boolean inventory = cwd.getParent().getFileName().toString().startsWith("hearth-s4-6.");
                boolean s45 = cwd.getParent().getFileName().toString().startsWith("hearth-s4-5.");
                boolean s42 = cwd.getParent().getFileName().toString().startsWith("hearth-s4-2.");
                io.github.jesterportfolio.hearth.verification.IsolatedPaths.require(cwd.getParent(),lifecycle ? "hearth-s4-11." : inventory ? "hearth-s4-6." : s45 ? "hearth-s4-5." : s42 ? "hearth-s4-2." : "hearth-s4-1.");
                if (approved.isBlank() || !cwd.equals(Path.of(approved).toRealPath())
                        || !(lifecycle || inventory || s45 || s42 || cwd.getParent().getFileName().toString().startsWith("hearth-s4-1."))
                        || !Files.readString(cwd.resolve(".hearth-s4-isolated")).trim()
                                .equals((lifecycle ? "S4-11" : inventory ? "S4-6" : s45 ? "S4-5" : s42 ? "S4-2" : "S4-1") + " disposable test data only")) {
                    throw new IllegalStateException("Isolated runtime gate missing");
                }
                verification = new SmokeVerification(this);
                if (lifecycle || inventory || s42 || s45) restartVerification = new RestartVerification(this);
                playerTest = (s42 || lifecycle && getConfig().getBoolean("session-verification-enabled") && Boolean.getBoolean("hearth.verification.sessions"))
                        && Boolean.getBoolean("hearth.verification.players");
                if (playerTest) getServer().getPluginManager().registerEvents(new PlayerActionObserver(this), this);
                if (getConfig().getBoolean("lifecycle-verification-enabled")) {
                    if (!lifecycle || !Boolean.getBoolean("hearth.verification.lifecycle")) throw new IllegalStateException("Lifecycle gate missing");
                    lifecycleVerification = new LifecycleVerification(this,cwd.getParent());
                }
                if (getConfig().getBoolean("db-verification-enabled")) {
                    if (!s45 || !Boolean.getBoolean("hearth.verification.database")) throw new IllegalStateException("DB test gate missing");
                    databaseVerification = new DatabaseVerification(this, cwd.getParent());
                }
                if (getConfig().getBoolean("inventory-verification-enabled")) {
                    if (!inventory || !Boolean.getBoolean("hearth.verification.inventory")) throw new IllegalStateException("Inventory gate missing");
                    inventoryVerification = new InventoryVerification(this,cwd.getParent());
                }
            } catch (Exception e) {
                getLogger().severe("Verification refused: " + e.getMessage());
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }
        java.util.Objects.requireNonNull(getCommand("hearthquest")).setExecutor((sender,cmd,label,args)->{
            sender.sendMessage("격리 퀘스트는 준비된 플레이어의 NPC 대화에서만 사용할 수 있습니다.");return true;
        });
        java.util.Objects.requireNonNull(getCommand("hearthai")).setExecutor((sender,cmd,label,args)->{
            sender.sendMessage(sender instanceof ConsoleCommandSender?"AI 진단 모듈이 준비되지 않았습니다.":"AI 관리 진단은 서버 콘솔 전용입니다.");return true;
        });
        java.util.Objects.requireNonNull(getCommand("hearthrecovery")).setExecutor((sender,cmd,label,args)->{
            if(!(sender instanceof ConsoleCommandSender)){sender.sendMessage("관리 진단은 서버 콘솔 전용입니다.");return true;}
            if(lifecycleVerification==null){sender.sendMessage("스토리 관리 진단 모듈이 준비되지 않았습니다.");return true;}
            lifecycleVerification.recoveryAdministration(true,args,sender::sendMessage).exceptionally(error->{sender.sendMessage("관리 진단 보류 code="+io.github.jesterportfolio.hearth.administration.RecoveryConsole.errorCode(error));return null;});return true;
        });
        var command = java.util.Objects.requireNonNull(getCommand("hearthverify"));
        command.setExecutor((sender, ignored, label, args) -> {
            if (!(sender instanceof ConsoleCommandSender) || verification == null) {
                sender.sendMessage("Only an explicitly gated isolated verification console can run this command.");
            } else if(lifecycleVerification!=null&&args.length==2&&args[0].equals("client-environment")&&java.util.Set.of("ui","combat").contains(args[1])){
                try{lifecycleVerification.clientEnvironment(args[1]);}catch(RuntimeException e){sender.sendMessage("격리 시험 환경 변경 불가");}
            } else if(lifecycleVerification!=null&&args.length==2&&args[0].equals("client-pack")&&args[1].matches("[A-Za-z0-9_]{3,16}")){
                try{lifecycleVerification.clientPack(args[1]);}catch(RuntimeException e){sender.sendMessage("격리 클라이언트 팩 준비 불가");}
            } else if(lifecycleVerification!=null&&args.length==2&&args[0].equals("gathering-fixture")&&args[1].matches("[A-Za-z0-9_]{3,16}")){
                try{lifecycleVerification.gatheringFixture(args[1]);}catch(RuntimeException e){sender.sendMessage("채집 fixture 거부: "+e.getMessage());}
            } else if (lifecycleVerification!=null&&((args.length==2&&(args[0].equals("combat-player-start")||args[0].equals("combat-player-release")))||(args.length==3&&args[0].equals("combat-player-revive")))&&args[1].matches("[A-Za-z0-9_]{3,16}")) {
                try{lifecycleVerification.combatPlayer(args[0],args[1],args.length==3?args[2]:null);sender.sendMessage("격리 전투 시험 명령 접수");}catch(RuntimeException e){sender.sendMessage("격리 전투 시험 거부: "+e.getMessage());}
            } else if(lifecycleVerification!=null&&((args.length==2&&args[0].equals("economy-pending"))||((args.length==2||args.length==3)&&args[0].equals("economy-history"))||(args.length==3&&(args[0].equals("economy-inspect")||args[0].equals("economy-event")))||(args.length==4&&args[0].equals("economy-recover")))){
                try{lifecycleVerification.economyAdministration(args);}catch(RuntimeException e){sender.sendMessage("경제 관리 명령 거부: "+e.getMessage());}
            } else if(args.length==2&&(args[0].equals("economy-fixture")||args[0].equals("economy-link-existing"))&&lifecycleVerification!=null){
                try{lifecycleVerification.economyFixture(args[1],args[0].equals("economy-link-existing"));}catch(RuntimeException e){sender.sendMessage("경제 fixture 준비 거부: "+e.getMessage());}
            } else if (args.length == 2 && (args[0].equals("session-fixture") || args[0].equals("session-link-existing")) && lifecycleVerification != null) {
                try { lifecycleVerification.fixturePlayer(args[1],args[0].equals("session-link-existing")); }
                catch (RuntimeException e) { sender.sendMessage("격리 fixture 준비 거부: "+e.getMessage()); }
            } else if (args.length == 2 && args[0].equals("session-status") && lifecycleVerification != null) {
                try { lifecycleVerification.playerStatus(args[1]); }
                catch (RuntimeException e) { sender.sendMessage("격리 상태 조회 거부: "+e.getMessage()); }
            } else if (args.length == 2 && args[0].equals("lifecycle") && lifecycleVerification != null) {
                lifecycleVerification.start(args[1]);
            } else if (args.length == 2 && args[0].equals("inventory") && inventoryVerification != null) {
                inventoryVerification.start(args[1]);
            } else if (args.length == 2 && args[0].equals("db") && databaseVerification != null) {
                databaseVerification.start(args[1]);
            } else if (args.length == 1 && args[0].equals("smoke")) {
                verification.start();
            } else if (args.length == 2 && args[0].equals("restart") && restartVerification != null
                    && (args[1].equals("prepare") || args[1].equals("resume") || playerTest && args[1].equals("prepare-player"))) {
                restartVerification.start(args[1]);
            } else if (playerTest && args.length == 1 && args[0].equals("player-open")) {
                restartVerification.openForPlayers();
            } else if (playerTest && args.length == 2 && args[0].equals("player-status")) {
                var player = getServer().getPlayerExact(args[1]);
                if (player == null) sender.sendMessage("NOT_CONNECTED");
                else sender.sendMessage("HEARTH_S4_3_STATUS uuid=" + player.getUniqueId() + " world=" + player.getWorld().getKey()
                        + " op=" + player.isOp() + " bypass=" + PlayerActionObserver.bypass(player));
            } else {
                sender.sendMessage("Usage: hearthverify smoke (one run per fresh isolated server directory)");
            }
            return true;
        });
    }

    private void observe(String mode){try{observation=io.github.jesterportfolio.hearth.administration.RuntimeObservation.start(this,mode,()->{
        String phase="REFUSED";int worlds=0,held=0;boolean ai=false;
        if(mode.equals("story")&&storyRuntime!=null){phase=storyRuntime.phase().name();ai=storyRuntime.aiPreparationRecorded();var values=storyRuntime.worlds().values();worlds=values.size();for(var world:values)if(world.state()==io.github.jesterportfolio.hearth.lifecycle.WorldLifecycle.State.ERROR_HOLD||world.state()==io.github.jesterportfolio.hearth.lifecycle.WorldLifecycle.State.RECOVERY_REQUIRED)held++;}
        else if(mode.equals("economy")&&economyRuntime!=null){phase=economyRuntime.phase().name();try{var world=economyRuntime.economy().world;worlds=1;if(world.state()==io.github.jesterportfolio.hearth.lifecycle.WorldLifecycle.State.ERROR_HOLD||world.state()==io.github.jesterportfolio.hearth.lifecycle.WorldLifecycle.State.RECOVERY_REQUIRED)held=1;}catch(NullPointerException unavailable){}}
        else if(mode.equals("survival")&&survivalRuntime!=null){ai=survivalRuntime.aiPreparationRecorded();phase=survivalRuntime.phase().name();worlds=survivalRuntime.loadedWorldCount();held=phase.equals("HOLD")?worlds:0;}
        else if(mode.equals("lobby")&&lobbyRuntime!=null){phase=lobbyRuntime.phase().name();worlds=1;held=phase.equals("HOLD")?1:0;}
        else if(mode.equals("story-prepare")&&storyPreparation!=null)phase=storyPreparation.phase().name();
        return new io.github.jesterportfolio.hearth.administration.RuntimeObservation.State(phase,worlds,held,ai,mode.equals("survival")&&survivalRuntime!=null?survivalRuntime.platformDiagnostics():java.util.Map.of());
    });}catch(RuntimeException invalid){getLogger().warning("HEARTH_RUNTIME_OBSERVATION_REFUSED");}}

    @Override public void onDisable() {
        try{io.github.jesterportfolio.hearth.util.ShutdownCleanup.close(survivalRuntime,lobbyRuntime,storyPreparation,storyRuntime,economyRuntime,observation,lifecycleVerification,inventoryVerification,databaseVerification,verification,restartVerification);}
        catch(RuntimeException error){getLogger().log(java.util.logging.Level.SEVERE,"HEARTH_DISABLE_CLEANUP_FAILED",error);}
    }
}
