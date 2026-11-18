package io.github.jesterportfolio.hearth.network;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** 고정 Paper 배포에서 실제 로드된 전달 설정을 확인합니다. 파일 설정만 보고 인증을 허용하지 않습니다. */
public final class BackendAuthentication {
    private BackendAuthentication(){}
    public static NetworkSettings settings(JavaPlugin plugin,String role){if(!plugin.getConfig().getBoolean("network.enabled"))return null;if(plugin.getConfig().getBoolean("verification-enabled")||!role.equals("lobby")&&!role.equals("economy"))throw new IllegalArgumentException("NETWORK_RUNTIME_ROLE");var settings=NetworkSettings.parse(java.util.Objects.requireNonNull(plugin.getConfig().getConfigurationSection("network")).getValues(false));if(settings.lobbyRole()!=role.equals("lobby"))throw new IllegalArgumentException("NETWORK_RUNTIME_ROLE");return settings;}
    private static Object field(Object owner,String name)throws Exception{return owner.getClass().getField(name).get(owner);}
    public static void require(NetworkSettings settings){if(settings==null){if(!Bukkit.getOnlineMode()||!Bukkit.hasWhitelist())throw new IllegalStateException("BACKEND_AUTH_REQUIRED");return;}try{
        var global=Class.forName("io.papermc.paper.configuration.GlobalConfiguration").getMethod("get").invoke(null);var proxies=field(global,"proxies");var velocity=field(proxies,"velocity");boolean legacy=Class.forName("org.spigotmc.SpigotConfig").getField("bungee").getBoolean(null);String active=(String)field(velocity,"secret"),expected=System.getenv(settings.forwardingEnvironment());NetworkSettings.secret(expected);NetworkSettings.secret(System.getenv(settings.relayEnvironment()));
        boolean same=active!=null&&MessageDigest.isEqual(active.getBytes(StandardCharsets.UTF_8),expected.getBytes(StandardCharsets.UTF_8));var evidence=new AuthenticationPolicy(Bukkit.getOnlineMode(),Bukkit.hasWhitelist(),Bukkit.getIp(),legacy,(Boolean)field(velocity,"enabled"),(Boolean)field(velocity,"onlineMode"),(Boolean)field(proxies,"proxyProtocol"));if(!evidence.permits(true,same))throw new IllegalStateException("BACKEND_AUTH_REQUIRED");
    }catch(Exception error){throw new IllegalStateException("NETWORK_MODERN_AUTH_REQUIRED");}}
}
