package io.github.jesterportfolio.hearth.network;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
final class BackendTransferTest {
 final AtomicLong clock=new AtomicLong();final ArrayDeque<Runnable> queue=new ArrayDeque<>();final BackendTransfer core=new BackendTransfer(NetworkSettingsTest.settings("economy"),clock::get,queue::add);final UUID player=UUID.randomUUID();final Object token=new Object();
 static final class Port implements BackendTransfer.Port {boolean current=true,frozen;final CompletableFuture<Void> saved=new CompletableFuture<>();final List<NetworkProtocol.Frame> sent=new ArrayList<>();final List<String> notices=new ArrayList<>();public boolean current(){return current;}public void freeze(boolean value){frozen=value;}public CompletionStage<Void> save(){return saved;}public void send(NetworkProtocol.Frame f){sent.add(f);}public void notice(String code){notices.add(code);}}
 void pump(){while(!queue.isEmpty())queue.remove().run();}
 Port waiting(){var p=new Port();core.begin(player,token,p);assertTrue(p.frozen);assertTrue(p.sent.isEmpty());p.saved.complete(null);pump();assertEquals(1,p.sent.size());return p;}
 @Test void source_commit_is_required_before_any_network_frame(){var p=new Port();core.begin(player,token,p);assertTrue(p.frozen);assertTrue(p.sent.isEmpty());assertEquals(BackendTransfer.Phase.SAVING,core.phase(player).orElseThrow());p.saved.complete(null);assertTrue(p.sent.isEmpty());pump();assertEquals(1,p.sent.size());assertEquals("lobby",p.sent.getFirst().to());}
 @Test void failed_or_unknown_source_commit_holds_original_without_sending(){var p=new Port();core.begin(player,token,p);p.saved.completeExceptionally(new IllegalStateException("COMMIT_UNKNOWN"));pump();assertTrue(p.sent.isEmpty());assertTrue(p.frozen);assertEquals(BackendTransfer.Phase.HOLD,core.phase(player).orElseThrow());}
 @Test void only_signed_decoded_matching_definite_refusal_can_unfreeze(){var p=waiting();var f=p.sent.getFirst();core.response(player,new Object(),NetworkProtocol.response(f,NetworkProtocol.Kind.REJECTED));assertTrue(p.frozen);core.response(player,token,NetworkProtocol.response(f,NetworkProtocol.Kind.REJECTED));assertFalse(p.frozen);assertTrue(core.phase(player).isEmpty());}
 @Test void timeout_and_late_refusal_cannot_automatically_resume(){var p=waiting();clock.set(15000);core.tick();assertEquals(BackendTransfer.Phase.HOLD,core.phase(player).orElseThrow());core.response(player,token,NetworkProtocol.response(p.sent.getFirst(),NetworkProtocol.Kind.REJECTED));assertTrue(p.frozen);}
 @Test void quit_before_commit_prevents_late_connect_request(){var p=new Port();core.begin(player,token,p);core.quit(player,token);p.saved.complete(null);pump();assertTrue(p.sent.isEmpty());assertTrue(core.phase(player).isEmpty());}
 @Test void replaced_connection_and_duplicate_click_cannot_send_twice(){var p=new Port();core.begin(player,token,p);assertThrows(IllegalStateException.class,()->core.begin(player,token,p));p.current=false;p.saved.complete(null);pump();assertTrue(p.sent.isEmpty());assertEquals(BackendTransfer.Phase.HOLD,core.phase(player).orElseThrow());}
 @Test void shutdown_does_not_release_uncertain_source_or_send_after_commit(){var p=new Port();core.begin(player,token,p);core.close();p.saved.complete(null);pump();assertTrue(p.sent.isEmpty());assertTrue(p.frozen);}
}
