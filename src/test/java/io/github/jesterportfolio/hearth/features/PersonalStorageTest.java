package io.github.jesterportfolio.hearth.features;
import io.github.jesterportfolio.hearth.inventory.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class PersonalStorageTest {
    @Test void onlyTwentySevenPersonalSlotsCanBeStored(){var slots=new byte[41][];slots[26]=new byte[]{1};assertSame(PersonalStorage.validate(InventoryPayload.pack(slots)).getClass(),InventoryPayload.class);slots[27]=new byte[]{2};assertThrows(IllegalArgumentException.class,()->PersonalStorage.validate(InventoryPayload.pack(slots)));}
    @Test void emptyEnvelopeDoesNotBorrowGlobalOrAnotherInventory(){var a=PersonalStorage.empty();var bytes=a.slots();bytes[0]=new byte[]{5};assertNull(a.slots()[0]);assertEquals(a,PersonalStorage.empty());}
    @Test void priorIssuanceRequiresExplicitDeviceRecovery(){assertTrue(DeviceRules.issue(false,false));assertTrue(DeviceRules.issue(false,true));assertFalse(DeviceRules.issue(true,false));assertTrue(DeviceRules.issue(true,true));}
    @Test void featurePayloadCopiesCannotMutateConfirmedState(){byte[] bytes={1,2};var state=new FeatureStore.Saved(bytes,0);bytes[0]=9;assertEquals(1,state.payload()[0]);var read=state.payload();read[0]=8;assertEquals(1,state.payload()[0]);}
}
