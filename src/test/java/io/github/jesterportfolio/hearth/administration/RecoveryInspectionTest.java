package io.github.jesterportfolio.hearth.administration;

import io.github.jesterportfolio.hearth.features.*;
import io.github.jesterportfolio.hearth.inventory.InventoryPayload;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.combat.*;
import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RecoveryInspectionTest {
    final ServerProgressAccess access=new ServerProgressAccess();
    final ServerProgressAccess.Context context=access.forVerifiedServerLink(UUID.randomUUID(),UUID.randomUUID());
    final UUID world=UUID.randomUUID();
    RecoveryInspection.Runtime runtime(){return new RecoveryInspection.Runtime(world,Map.of("session.state","ACTIVE"),Set.of(),Set.of());}
    List<InspectionStore.Row> rows(){return new ArrayList<>(List.of(new InspectionStore.Row("player","progress",0,0,null),new InspectionStore.Row("run","progress",0,0,null),row("inventory","inventory",0,PersonalStorage.empty().bytes())));}
    InspectionStore.Row row(String domain,String key,long rev,byte[] data){return new InspectionStore.Row(domain,key,rev,data.length,data);}
    RecoveryInspection.Report report(List<InspectionStore.Row> rows,boolean truncated){return RecoveryInspection.build(UUID.randomUUID(),context,new InspectionStore.Snapshot(Instant.EPOCH,rows,truncated),runtime(),runtime(),Map.of());}
    @Test void validatesEnvelopeWithoutInterpretingOrLoggingRawNbt() {
        var slots=new byte[41][];slots[0]="private item text".getBytes(java.nio.charset.StandardCharsets.UTF_8);var payload=InventoryPayload.pack(slots);
        var rows=rows();rows.set(2,row("inventory","inventory",7,payload.bytes()));var value=report(rows,false);
        assertTrue(value.entries().get(2).state().contains("occupied=1"));assertTrue(value.entries().get(2).state().contains("NBT_NOT_DECODED"));
        assertEquals(64,value.entries().get(2).sha256().length());assertFalse(RecoveryInspection.lines(value).toString().contains("private item text"));assertEquals(payload,InventoryPayload.parse(rows.get(2).payload()));
    }
    @Test void corruptAndUnknownPayloadsAreRetainedAsProblemsInsteadOfResetting() {
        var rows=rows();rows.add(row("ender","inventory",3,new byte[]{4}));rows.add(row("device","phone",0,new byte[]{9}));rows.add(row("device","other_key",0,new byte[]{1}));
        var value=report(rows,false);assertTrue(value.issues().containsAll(Set.of("PAYLOAD_VALIDATION_FAILED","UNRECOGNIZED_FEATURE")));
        assertArrayEquals(new byte[]{4},rows.get(3).payload());assertEquals(3,value.entries().get(3).revision());
    }
    @Test void absentIssuanceOrEnderRowsNeverBecomeProofOfAbsentItems() {
        var value=report(rows(),false);assertTrue(value.entries().stream().noneMatch(e->e.domain().equals("device")||e.domain().equals("ender")));
        assertTrue(RecoveryInspection.lines(value).getLast().contains("아이템 부재 판정이 아닙니다"));
        var rows=rows();rows.add(row("device","phone",1,new byte[]{1}));assertTrue(report(rows,false).entries().getLast().state().contains("ITEM_PRESENCE_NOT_INSPECTED"));
    }
    @Test void restartPolicyAndWorldMismatchRemainSeparateFromLiveSessionState() {
        var exit=new CombatContent.Exit(new FieldContent.Point(1,64,1),0,0,.5,List.of());
        var state=new RecoveryLedger.State(UUID.randomUUID(),RecoveryLedger.Phase.ACTIVE,UUID.randomUUID(),exit,"SURVIVAL",null);
        var rows=rows();rows.add(row("combat_return","current",2,RecoveryLedger.encode(state)));var value=report(rows,false);
        assertTrue(value.issues().containsAll(Set.of("RESTART_REQUIRES_HOLD","RECOVERY_WORLD_MISMATCH")));assertTrue(value.entries().getLast().state().contains("restart_policy=HOLD"));assertEquals("ACTIVE",value.after().facts().get("session.state"));
    }
    @Test void aLateRuntimeChangeCannotProduceAStableInspection() {
        var value=report(rows(),false);var changed=new RecoveryInspection.Runtime(world,Map.of("session.state","HOLD"),Set.of("SESSION_INVENTORY_HOLD"),Set.of("saving"));
        var after=RecoveryInspection.observedAfter(value,changed);
        assertTrue(after.issues().containsAll(Set.of("RUNTIME_CHANGED_DURING_READ","SESSION_INVENTORY_HOLD","RUNTIME_UNSAVED_OR_PENDING")));assertEquals(value.entries(),after.entries());
    }
    @Test void compareUsesRevisionAndWholePayloadHashAndRefusesForeignScope() {
        var before=report(rows(),false);var rows=rows();rows.set(2,row("inventory","inventory",1,PersonalStorage.empty().bytes()));var after=report(rows,false);
        assertEquals(List.of("inventory/inventory"),RecoveryInspection.compare(before,after).changed());assertTrue(RecoveryInspection.compare(before,before).changed().isEmpty());
        var foreign=new RecoveryInspection.Report(UUID.randomUUID(),UUID.randomUUID(),context.playerId(),Instant.EPOCH,before.entries(),runtime(),runtime(),Set.of(),true);
        assertThrows(SecurityException.class,()->RecoveryInspection.compare(before,foreign));
    }
    @Test void truncatedSnapshotsCannotClaimEqualityOfUnseenOriginals() {
        var rows=rows();rows.add(new InspectionStore.Row("ender","inventory",4,900000,null));var a=report(rows,true);var difference=RecoveryInspection.compare(a,a);
        assertFalse(a.complete());assertTrue(difference.unconfirmed().containsAll(List.of("ender/inventory","UNOBSERVED_ROWS")));assertTrue(a.issues().contains("SNAPSHOT_LIMIT_REACHED"));
    }
    @Test void aMissingHashCannotBeReportedAsAConfirmedPayloadChange() {
        var rows=rows();var captured=report(rows,false);var source=rows.get(2);
        rows.set(2,new InspectionStore.Row(source.domain(),source.key(),source.revision(),source.bytes(),null));
        var missing=report(rows,true);var difference=RecoveryInspection.compare(captured,missing);
        assertTrue(difference.changed().isEmpty());assertTrue(difference.unconfirmed().contains("inventory/inventory"));
        rows.set(2,new InspectionStore.Row(source.domain(),source.key(),source.revision()+1,source.bytes(),null));
        assertTrue(RecoveryInspection.compare(captured,report(rows,true)).changed().contains("inventory/inventory"));
    }
    @Test void sourceArraysAndReturnedSnapshotsCannotMutateOriginalEvidence() {
        byte[] raw={1,2};var row=row("device","phone",0,raw);raw[0]=9;var copy=row.payload();copy[0]=8;assertArrayEquals(new byte[]{1,2},row.payload());
        var value=new InspectionStore.Snapshot(Instant.EPOCH,List.of(row),false);assertThrows(UnsupportedOperationException.class,()->value.rows().clear());
    }
}
