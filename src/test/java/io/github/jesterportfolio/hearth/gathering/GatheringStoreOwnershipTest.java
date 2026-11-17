package io.github.jesterportfolio.hearth.gathering;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GatheringStoreOwnershipTest {
    final ServerProgressAccess access=new ServerProgressAccess();final UUID owner=new UUID(5,1),world=new UUID(6,1);final ServerProgressAccess.Context context=access.forVerifiedServerLink(owner,new UUID(7,1));
    GatheringStore.State row(UUID run,UUID target,long revision){return new GatheringStore.State(run,"mine",target,revision,0,"a".repeat(64),"v1","b".repeat(64),GatheringStore.Phase.OPEN,null,false,10);}
    @Test void bothDomainsRefuseForeignIssuerBeforeAnyDatabaseWork(){var foreign=new ServerProgressAccess().forVerifiedServerLink(owner,context.playerId());assertThrows(SecurityException.class,()->new PostgresGatheringStore(null,access,foreign,"mine"));assertThrows(SecurityException.class,()->PostgresGatheringStore.economy(null,access::requireIssued,foreign,"mine"));}
    @Test void economicStoreCannotWriteAnotherOwnerOrChangePhysicalMapping(){var store=PostgresGatheringStore.economy(null,access::requireIssued,context,"mine");assertThrows(SecurityException.class,()->store.create(row(UUID.randomUUID(),world,0)));assertThrows(SecurityException.class,()->store.save(row(owner,world,0),row(owner,UUID.randomUUID(),1)));}
    @Test void storyStoreRetainsOwnerAndRevisionFencesWithoutConnecting(){var store=new PostgresGatheringStore(null,access,context,"mine");assertThrows(SecurityException.class,()->store.create(row(UUID.randomUUID(),world,0)));assertThrows(IllegalArgumentException.class,()->store.save(row(owner,world,0),row(owner,world,2)));}
}
