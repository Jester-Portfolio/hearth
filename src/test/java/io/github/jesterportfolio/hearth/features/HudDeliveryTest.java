package io.github.jesterportfolio.hearth.features;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class HudDeliveryTest {
    @Test void oneQuestOutputSurvivesNormalAndReadOnlyRefreshesWithoutLegacyDuplicates() {
        var events=new ArrayList<String>();
        var hud=new HudDelivery<String,String>((p,v)->events.add("legacy:"+v),p->events.add("clear legacy"));
        hud.show("p","접속");
        hud.replace((p,v)->events.add("fixed:"+v),p->events.add("clear fixed"));
        hud.show("p","의뢰 수락");hud.show("p","관전 중 읽기 전용");hud.show("p","복귀 뒤 갱신");
        assertEquals(List.of("legacy:접속","clear legacy","fixed:의뢰 수락","fixed:관전 중 읽기 전용","fixed:복귀 뒤 갱신"),events);
        hud.clear("p");hud.clear("p");
        assertEquals(1,events.stream().filter("clear fixed"::equals).count());
    }
    @Test void clearOnePlayerCannotRemoveAnotherPlayersOrCombatChannel() {
        var visible=new HashMap<String,String>();var combat=new HashMap<>(Map.of("p1","보스 체력"));
        var hud=new HudDelivery<String,String>(visible::put,visible::remove);
        hud.show("p1","개인 의뢰1");hud.show("p2","개인 의뢰2");hud.clear("p1");
        assertEquals(Map.of("p2","개인 의뢰2"),visible);assertEquals(Map.of("p1","보스 체력"),combat);
        hud.close();assertTrue(visible.isEmpty());
    }
    @Test void failingPartialDisplayIsClearedAndNeverFallsBackToADuplicateRenderer() {
        var events=new ArrayList<String>();
        var hud=new HudDelivery<String,String>((p,v)->events.add("legacy"),p->events.add("legacy clear"));
        hud.replace((p,v)->{events.add("partial");throw new IllegalStateException("display failed");},p->events.add("clear partial"));
        assertThrows(IllegalStateException.class,()->hud.show("p","view"));hud.clear("p");
        assertEquals(List.of("partial","clear partial"),events);
    }
    @Test void closeAttemptsEveryOwnedDisplayEvenWhenOneCleanupFails() {
        var cleared=new HashSet<String>();
        var hud=new HudDelivery<String,String>((p,v)->{},p->{boolean first=cleared.add(p);if(p.equals("p1")&&first)throw new IllegalStateException("cleanup");});
        hud.show("p1","1");hud.show("p2","2");
        assertThrows(IllegalStateException.class,hud::close);assertEquals(Set.of("p1","p2"),cleared);
        assertDoesNotThrow(hud::close);
    }
    @Test void lateDisconnectOfEqualIdentityCannotClearTheNewConnection() {
        record Client(String id) {}
        var old=new Client("same UUID");var next=new Client("same UUID");
        var visible=new IdentityHashMap<Client,String>();
        var hud=new HudDelivery<Client,String>(visible::put,visible::remove);
        hud.show(old,"old session");hud.show(next,"new session");hud.clear(old);
        assertEquals("new session",visible.get(next));assertEquals(1,visible.size());
        hud.close();assertTrue(visible.isEmpty());
    }
}
