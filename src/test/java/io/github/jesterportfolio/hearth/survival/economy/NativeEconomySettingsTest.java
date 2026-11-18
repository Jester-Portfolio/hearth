package io.github.jesterportfolio.hearth.survival.economy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class NativeEconomySettingsTest{
 static Map<String,Object> config(){return new HashMap<>(Map.of("realm",UUID.randomUUID().toString(),"content-file","market.properties","db-host","127.0.0.1","db-port",5432,"db-name","hearth","db-user","hearth","password-environment","HEARTH_DB_PASSWORD","allow-new-realm",false));}
 @Test void native_economy_is_explicit_and_contains_only_password_environment(){var settings=NativeEconomySettings.parse(config());assertEquals("HEARTH_DB_PASSWORD",settings.passwordEnvironment());assertFalse(settings.allowNew());}
 @Test void absent_values_and_unapproved_paths_or_connection_strings_are_rejected(){for(String key:config().keySet()){var p=config();p.remove(key);assertThrows(RuntimeException.class,()->NativeEconomySettings.parse(p));}for(var invalid:List.of(Map.of("content-file","../data.properties"),Map.of("db-port",1.5),Map.of("db-host","user@host"),Map.of("password-environment","secret=value"),Map.of("allow-new-realm","true"))){var p=config();p.putAll(invalid);assertThrows(RuntimeException.class,()->NativeEconomySettings.parse(p));}}
}
