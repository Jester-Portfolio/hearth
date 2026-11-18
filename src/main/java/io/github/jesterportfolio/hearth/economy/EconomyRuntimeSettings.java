package io.github.jesterportfolio.hearth.economy;

import java.util.*;

/** 비밀 값은 포함하지 않는 명시적 실행 설정. DB 비밀번호는 환경 변수에서만 받습니다. */
public record EconomyRuntimeSettings(UUID realm,String worldKey,UUID worldId,double x,double y,double z,float yaw,float pitch,long idleSeconds,Set<String> regions,String contentFile,String dbHost,int dbPort,String dbName,String dbUser,String passwordEnvironment){
    public static String mode(String value,boolean verification){if(value==null||!Set.of("disabled","economy","lobby","story","story-prepare").contains(value)||verification&&!value.equals("disabled"))throw new IllegalArgumentException("RUNTIME_MODE_CONFLICT");return value;}
    public EconomyRuntimeSettings{
        Objects.requireNonNull(realm);Objects.requireNonNull(worldId);regions=Set.copyOf(regions);
        if(worldKey==null||!worldKey.matches("[a-z0-9_]+:[a-z0-9_]+")||worldKey.startsWith("minecraft:")||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Float.isFinite(yaw)||!Float.isFinite(pitch)||Math.abs(pitch)>90||Math.abs(x)>29_999_000||Math.abs(z)>29_999_000||idleSeconds<1||idleSeconds>3600||regions.isEmpty()||regions.size()>64||regions.stream().anyMatch(s->!s.matches("[A-Za-z0-9_-]{1,64}")))throw new IllegalArgumentException("ECON_RUNTIME_WORLD_CONFIG");
        if(contentFile==null||!contentFile.matches("[a-zA-Z0-9_-]+\\.properties")||dbHost==null||!dbHost.matches("[A-Za-z0-9.-]{1,253}")||dbPort<1||dbPort>65535||dbName==null||!dbName.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")||dbUser==null||!dbUser.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")||passwordEnvironment==null||!passwordEnvironment.matches("[A-Z][A-Z0-9_]{0,63}"))throw new IllegalArgumentException("ECON_RUNTIME_CONNECTION_CONFIG");
    }
    private static String text(Map<String,?> p,String k){Object value=p.get(k);if(!(value instanceof String s)||s.isBlank())throw new IllegalArgumentException("ECON_RUNTIME_REQUIRED_SETTING");return s;}
    private static double number(Map<String,?> p,String k){Object value=p.get(k);if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("ECON_RUNTIME_NUMBER");return n.doubleValue();}
    private static long whole(Map<String,?> p,String k){double n=number(p,k);if(n!=Math.rint(n)||n<Long.MIN_VALUE||n>Long.MAX_VALUE)throw new IllegalArgumentException("ECON_RUNTIME_INTEGER");return (long)n;}
    public static EconomyRuntimeSettings parse(Map<String,?> p){var regions=p.get("required-regions");if(!(regions instanceof List<?> list)||list.stream().anyMatch(s->!(s instanceof String)))throw new IllegalArgumentException("ECON_RUNTIME_REGIONS");return new EconomyRuntimeSettings(UUID.fromString(text(p,"realm")),text(p,"world-key"),UUID.fromString(text(p,"world-uuid")),number(p,"entry-x"),number(p,"entry-y"),number(p,"entry-z"),(float)number(p,"entry-yaw"),(float)number(p,"entry-pitch"),whole(p,"idle-seconds"),new HashSet<>(list.stream().map(String.class::cast).toList()),text(p,"content-file"),text(p,"db-host"),Math.toIntExact(whole(p,"db-port")),text(p,"db-name"),text(p,"db-user"),text(p,"password-environment"));}
}
