package io.github.jesterportfolio.hearth.network;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
final class NetworkProtocolTest {
 static final byte[] KEY=new byte[32];
 static NetworkProtocol.Frame frame(){return new NetworkProtocol.Frame(new UUID(1,1),UUID.randomUUID(),"lobby","economy",15000,NetworkProtocol.Kind.REQUEST);}
 @Test void versioned_signed_frame_roundtrips_without_player_or_inventory(){var f=frame();byte[] data=NetworkProtocol.encode(f,KEY);assertEquals(f,NetworkProtocol.decode(data,KEY));assertTrue(data.length<512);}
 @Test void changed_body_mac_or_key_is_refused(){byte[] data=NetworkProtocol.encode(frame(),KEY);data[7]^=1;assertThrows(IllegalArgumentException.class,()->NetworkProtocol.decode(data,KEY));data[7]^=1;data[data.length-1]^=1;assertThrows(IllegalArgumentException.class,()->NetworkProtocol.decode(data,KEY));var other=KEY.clone();other[0]=1;assertThrows(IllegalArgumentException.class,()->NetworkProtocol.decode(NetworkProtocol.encode(frame(),KEY),other));}
 @Test void oversized_truncated_and_trailing_bytes_are_refused(){assertThrows(IllegalArgumentException.class,()->NetworkProtocol.decode(new byte[513],KEY));assertThrows(IllegalArgumentException.class,()->NetworkProtocol.decode(new byte[2],KEY));var bytes=NetworkProtocol.encode(frame(),KEY);assertThrows(IllegalArgumentException.class,()->NetworkProtocol.decode(Arrays.copyOf(bytes,bytes.length+1),KEY));}
 @Test void only_named_registered_roles_not_addresses_or_same_server(){assertThrows(IllegalArgumentException.class,()->new NetworkProtocol.Frame(UUID.randomUUID(),UUID.randomUUID(),"127.0.0.1:25565","economy",5,NetworkProtocol.Kind.REQUEST));assertThrows(IllegalArgumentException.class,()->new NetworkProtocol.Frame(UUID.randomUUID(),UUID.randomUUID(),"lobby","lobby",5,NetworkProtocol.Kind.REQUEST));}
 @Test void response_preserves_request_identity_and_expiry(){var f=frame();var r=NetworkProtocol.response(f,NetworkProtocol.Kind.REJECTED);assertEquals(f.request(),r.request());assertEquals(f.expiresMillis(),r.expiresMillis());assertEquals(r,NetworkProtocol.decode(NetworkProtocol.encode(r,KEY),KEY));}
}
