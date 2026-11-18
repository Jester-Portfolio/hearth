package io.github.jesterportfolio.hearth.network;

import com.google.inject.Inject;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.*;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.event.connection.*;
import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import java.nio.file.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** 프록시는 정품 인증/현대식 전달·서버 연결만 소유합니다. PG/게임 상태를 읽거나 이전하지 않습니다. */
public final class VelocityNetwork {
    private static final MinecraftChannelIdentifier CHANNEL=MinecraftChannelIdentifier.from(NetworkProtocol.CHANNEL);
    private final ProxyServer server;private final Logger logger;private final Path directory;private volatile boolean ready;private NetworkSettings settings;private ProxyTransfers transfers;private byte[] key;private Object bootConfiguration;private Map<String,RegisteredServer> targets=Map.of();
    @Inject public VelocityNetwork(ProxyServer server,Logger logger,@DataDirectory Path directory){this.server=server;this.logger=logger;this.directory=directory;}
    @Subscribe public void start(ProxyInitializeEvent event){try{
        var file=directory.resolve("network.properties").toAbsolutePath().normalize();if(!file.equals(file.toRealPath())||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>16384)throw new IllegalStateException();var p=new Properties();try(var input=Files.newBufferedReader(file)){p.load(input);}var map=new HashMap<String,Object>();p.forEach((k,v)->map.put((String)k,v));settings=NetworkSettings.parse(map);key=NetworkSettings.secret(System.getenv(settings.relayEnvironment()));String forwarding=System.getenv(settings.forwardingEnvironment());NetworkSettings.secret(forwarding);
        var config=server.getConfiguration();if(!config.isOnlineMode()||!config.getAttemptConnectionOrder().equals(List.of(settings.lobby()))||!config.getForcedHosts().isEmpty()||!config.getServers().keySet().equals(Set.of(settings.lobby(),settings.economy())))throw new IllegalStateException();
        var type=config.getClass();if(!type.getMethod("getPlayerInfoForwardingMode").invoke(config).toString().equals("MODERN")||!MessageDigest.isEqual((byte[])type.getMethod("getForwardingSecret").invoke(config),forwarding.getBytes(StandardCharsets.UTF_8))||(Boolean)type.getMethod("isBungeePluginChannelEnabled").invoke(config)||(Boolean)type.getMethod("isFailoverOnUnexpectedServerDisconnect").invoke(config)||(Boolean)type.getMethod("isProxyProtocol").invoke(config))throw new IllegalStateException();
        var ports=new HashSet<Integer>();for(String value:config.getServers().values()){if(!value.matches("127\\.0\\.0\\.1:[0-9]{4,5}"))throw new IllegalStateException();int port=Integer.parseInt(value.substring(value.lastIndexOf(':')+1));if(port<1024||port>65535||!ports.add(port))throw new IllegalStateException();}
        if(server.getPlayerCount()!=0)throw new IllegalStateException();bootConfiguration=config;targets=Map.of(settings.lobby(),server.getServer(settings.lobby()).orElseThrow(),settings.economy(),server.getServer(settings.economy()).orElseThrow());transfers=new ProxyTransfers(settings,System::currentTimeMillis);server.getChannelRegistrar().register(CHANNEL);ready=true;logger.info("HEARTH_NETWORK_PROXY_READY backends=2 authenticated=true");
    }catch(Exception error){ready=false;logger.error("HEARTH_NETWORK_PROXY_HOLD; admissions and switches remain closed");}}
    private boolean active(){if(!ready)return false;if(server.getConfiguration()!=bootConfiguration||!server.getConfiguration().isOnlineMode()){ready=false;logger.warn("HEARTH_NETWORK_PROXY_CONFIG_CHANGED_HOLD");return false;}return true;}
    @Subscribe public void reload(ProxyReloadEvent event){ready=false;logger.warn("HEARTH_NETWORK_PROXY_RELOAD_HOLD; reviewed restart required");}
    @Subscribe(order=PostOrder.LAST) public void login(LoginEvent event){if(!active())event.setResult(ResultedEvent.ComponentResult.denied(Component.text("Hearth 네트워크 준비 보류")));}
    @Subscribe public void joined(PostLoginEvent event){if(!active()){event.getPlayer().disconnect(Component.text("Hearth 네트워크 준비 보류"));return;}try{transfers.connected(event.getPlayer().getUniqueId(),event.getPlayer());}catch(RuntimeException conflict){event.getPlayer().disconnect(Component.text("중복 또는 미정리 접속입니다."));}}
    @Subscribe(order=PostOrder.LAST) public void before(ServerPreConnectEvent event){String previous=event.getPreviousServer()==null?null:event.getPreviousServer().getServerInfo().getName();var target=event.getResult().getServer();if(!active()||target.isEmpty()||targets.get(target.get().getServerInfo().getName())!=target.get()||!transfers.permit(event.getPlayer().getUniqueId(),event.getPlayer(),previous,target.get().getServerInfo().getName())){event.setResult(ServerPreConnectEvent.ServerResult.denied());event.getPlayer().sendMessage(Component.text("서버 이동은 현재 서버의 /hearthserver에서 저장 후 요청하세요."));}}
    @Subscribe public void arrived(ServerConnectedEvent event){if(!active()||!transfers.arrived(event.getPlayer().getUniqueId(),event.getPlayer(),event.getServer().getServerInfo().getName()))event.getPlayer().disconnect(Component.text("서버 연결 상태 확인 보류"));}
    @Subscribe public void quit(DisconnectEvent event){if(transfers!=null)transfers.disconnected(event.getPlayer().getUniqueId(),event.getPlayer());}
    @Subscribe public void message(PluginMessageEvent event){if(!CHANNEL.equals(event.getIdentifier()))return;event.setResult(PluginMessageEvent.ForwardResult.handled());if(!active()||!(event.getSource() instanceof ServerConnection source)||event.getTarget()!=source.getPlayer())return;var player=source.getPlayer();if(player.getCurrentServer().orElse(null)!=source||targets.get(source.getServerInfo().getName())!=source.getServer())return;final NetworkProtocol.Frame frame;try{frame=NetworkProtocol.decode(event.getData(),key);}catch(RuntimeException malformed){return;}if(!transfers.request(player.getUniqueId(),player,source.getServerInfo().getName(),frame)){reply(source,frame,NetworkProtocol.Kind.HOLD);return;}var target=Optional.ofNullable(targets.get(frame.to()));if(target.isEmpty()){refused(source,frame);return;}
        player.createConnectionRequest(target.get()).connect().orTimeout(12,TimeUnit.SECONDS).whenComplete((result,error)->{if(error!=null){reply(source,frame,NetworkProtocol.Kind.HOLD);return;}if(!result.isSuccessful())refused(source,frame);});
    }
    private void refused(ServerConnection source,NetworkProtocol.Frame frame){var player=source.getPlayer();if(player.getCurrentServer().orElse(null)==source&&transfers.refused(player.getUniqueId(),player,frame.request(),source.getServerInfo().getName()))reply(source,frame,NetworkProtocol.Kind.REJECTED);else reply(source,frame,NetworkProtocol.Kind.HOLD);}
    private void reply(ServerConnection source,NetworkProtocol.Frame frame,NetworkProtocol.Kind kind){try{source.sendPluginMessage(CHANNEL,NetworkProtocol.encode(NetworkProtocol.response(frame,kind),key));}catch(RuntimeException ignored){}}
}
