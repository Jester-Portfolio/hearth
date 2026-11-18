package io.github.jesterportfolio.hearth.survival.economy;

import java.util.*;
/** 생존 세계 묶음과 별도 경제 realm을 명시합니다. 기존 PG 인벤토리 이전은 없습니다. */
public record NativeEconomySettings(UUID realm,String contentFile,String host,int port,String database,String user,String passwordEnvironment,boolean allowNew){
    public NativeEconomySettings{Objects.requireNonNull(realm);if(contentFile==null||!contentFile.matches("[A-Za-z0-9_-]+\\.properties")||host==null||!host.matches("[A-Za-z0-9.-]{1,253}")||port<1||port>65535||database==null||!database.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")||user==null||!user.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")||passwordEnvironment==null||!passwordEnvironment.matches("[A-Z][A-Z0-9_]{0,63}"))throw new IllegalArgumentException("NATIVE_ECONOMY_CONFIG");}
    public static NativeEconomySettings parse(Map<String,?> p){if(!(p.get("db-port") instanceof Integer port)||!(p.get("allow-new-realm") instanceof Boolean allow))throw new IllegalArgumentException("NATIVE_ECONOMY_CONFIG");return new NativeEconomySettings(UUID.fromString((String)p.get("realm")),(String)p.get("content-file"),(String)p.get("db-host"),port,(String)p.get("db-name"),(String)p.get("db-user"),(String)p.get("password-environment"),allow);}
}
