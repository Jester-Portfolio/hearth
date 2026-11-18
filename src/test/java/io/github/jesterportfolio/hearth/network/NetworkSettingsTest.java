package io.github.jesterportfolio.hearth.network;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
final class NetworkSettingsTest {
 static NetworkSettings settings(String server){return new NetworkSettings(new UUID(1,1),server,"lobby","economy","HEARTH_FORWARDING_SECRET","HEARTH_NETWORK_SECRET");}
 @Test void roles_only_connect_in_fixed_opposite_directions(){var s=settings("lobby");assertTrue(s.lobbyRole());assertEquals("economy",s.destination());assertEquals("lobby",settings("economy").destination());assertTrue(s.edge("economy","lobby"));assertFalse(s.edge("economy","unknown"));}
 @Test void overlapping_unknown_and_address_roles_are_refused(){assertThrows(IllegalArgumentException.class,()->settings("story"));assertThrows(IllegalArgumentException.class,()->new NetworkSettings(UUID.randomUUID(),"lobby","lobby","lobby","FORWARD_SECRET","RELAY_SECRET"));}
 @Test void secret_material_is_fixed_entropy_and_not_part_of_settings(){assertEquals(32,NetworkSettings.secret("a".repeat(64)).length);for(String value:List.of("","password","A".repeat(64),"a".repeat(63)))assertThrows(IllegalArgumentException.class,()->NetworkSettings.secret(value));assertThrows(IllegalArgumentException.class,()->NetworkSettings.secret(null));assertFalse(settings("lobby").toString().contains("aaaa"));}
 @Test void authenticated_direct_backend_remains_supported(){assertTrue(new AuthenticationPolicy(true,true,"127.0.0.1",false,false,true,false).permits(false,false));assertFalse(new AuthenticationPolicy(false,true,"127.0.0.1",false,false,true,false).permits(false,true));}
 @Test void modern_requires_every_active_security_condition(){var good=new AuthenticationPolicy(false,true,"127.0.0.1",false,true,true,false);assertTrue(good.permits(true,true));assertFalse(good.permits(true,false));for(var e:List.of(new AuthenticationPolicy(true,true,"127.0.0.1",false,true,true,false),new AuthenticationPolicy(false,false,"127.0.0.1",false,true,true,false),new AuthenticationPolicy(false,true,"0.0.0.0",false,true,true,false),new AuthenticationPolicy(false,true,"127.0.0.1",true,true,true,false),new AuthenticationPolicy(false,true,"127.0.0.1",false,false,true,false),new AuthenticationPolicy(false,true,"127.0.0.1",false,true,false,false),new AuthenticationPolicy(false,true,"127.0.0.1",false,true,true,true)))assertFalse(e.permits(true,true));}
}
