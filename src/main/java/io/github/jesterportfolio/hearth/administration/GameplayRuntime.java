package io.github.jesterportfolio.hearth.administration;

import org.bukkit.plugin.java.JavaPlugin;

/** 정상 스토리는 실제 콘텐츠 연결자가 모듈을 생성합니다. 격리 기본 경계는 유지합니다. */
public final class GameplayRuntime {
    private GameplayRuntime(){}
    public static boolean story(JavaPlugin plugin){return story(plugin.getConfig().getString("runtime-mode","disabled"),plugin.getConfig().getBoolean("verification-enabled"));}
    public static boolean story(String mode,boolean verification){return "story".equals(mode)&&!verification;}
    public static boolean economy(JavaPlugin plugin){return economy(plugin.getConfig().getString("runtime-mode","disabled"),plugin.getConfig().getBoolean("verification-enabled"));}
    public static boolean economy(String mode,boolean verification){return "economy".equals(mode)&&!verification;}
    public static boolean economyGathering(JavaPlugin plugin){return economy(plugin)||plugin.getConfig().getBoolean("verification-enabled")&&plugin.getConfig().getBoolean("economy-verification-enabled")&&Boolean.getBoolean("hearth.verification.economyGathering");}
    public static boolean allowed(JavaPlugin plugin,String flag,String property){return story(plugin)||plugin.getConfig().getBoolean(flag)&&Boolean.getBoolean(property);}
}
