package io.github.jesterportfolio.hearth.features;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class HudPackGateTest {
    @Test void pendingDeclinedFailedAndForeignPacksNeverEnableTheOverlay() {
        var gate=new HudPackGate<Object>();var actor=new Object();var id=UUID.randomUUID();gate.expect(id);
        assertFalse(gate.allowed(actor));gate.status(actor,UUID.randomUUID(),true);assertFalse(gate.allowed(actor));
        gate.status(actor,id,false);assertFalse(gate.allowed(actor));gate.status(actor,id,true);assertTrue(gate.allowed(actor));
        gate.status(actor,id,false);assertFalse(gate.allowed(actor));
    }
    @Test void changedPackAndEqualUuidNewConnectionsRequireTheirOwnSuccessfulReply() {
        record Actor(String uuid) {}var old=new Actor("same");var next=new Actor("same");
        var gate=new HudPackGate<Actor>();var id=UUID.randomUUID();gate.expect(id);gate.status(old,id,true);
        assertFalse(gate.allowed(next));var changed=UUID.randomUUID();gate.expect(changed);
        assertFalse(gate.allowed(old));gate.status(old,id,true);assertFalse(gate.allowed(old));
        gate.status(next,changed,true);gate.forget(old);assertTrue(gate.allowed(next));gate.forget(next);assertFalse(gate.allowed(next));
    }
    @Test void contentIdentifierIsOrderIndependentAndChangesWithAssets() {
        var first=new LinkedHashMap<String,byte[]>();first.put("font.json",new byte[]{1});first.put("pixel.png",new byte[]{2});
        var second=new LinkedHashMap<String,byte[]>();second.put("pixel.png",new byte[]{2});second.put("font.json",new byte[]{1});
        assertEquals(HudPackGate.contentId(first),HudPackGate.contentId(second));second.put("pixel.png",new byte[]{3});
        assertNotEquals(HudPackGate.contentId(first),HudPackGate.contentId(second));
    }
    @Test void successBeforeManifestIsRetainedAndUnrelatedPackCannotOverrideIt() {
        var gate=new HudPackGate<Object>();var actor=new Object();var id=UUID.randomUUID();
        gate.status(actor,id,true);assertFalse(gate.allowed(actor));gate.expect(id);assertTrue(gate.allowed(actor));
        var other=UUID.randomUUID();gate.status(actor,other,true);gate.status(actor,other,false);
        assertTrue(gate.allowed(actor));
    }
}
