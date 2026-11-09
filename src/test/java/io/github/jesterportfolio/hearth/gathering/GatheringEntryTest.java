package io.github.jesterportfolio.hearth.gathering;

import java.util.UUID;
import io.github.jesterportfolio.hearth.session.PlayerSessions.State;
import org.junit.jupiter.api.Test;
import static io.github.jesterportfolio.hearth.gathering.GatheringEntry.*;
import static org.junit.jupiter.api.Assertions.*;

final class GatheringEntryTest {
    private final UUID world=UUID.randomUUID();
    @Test void restoredInventoryCanMoveIntoItsStoryWorldBeforeActive(){assertEquals(Gate.OUTSIDE,gate(State.MOVING,world,world,false));}
    @Test void initialMovementCannotEnterTheGatheringArea(){assertEquals(Gate.DENY,gate(State.MOVING,world,world,true));}
    @Test void activeGatheringEntryStillRequiresMembership(){assertEquals(Gate.MEMBERSHIP,gate(State.ACTIVE,world,world,true));assertEquals(Gate.OUTSIDE,gate(State.ACTIVE,world,world,false));}
    @Test void anotherWorldAndMissingWorldRemainDenied(){for(var state:new State[]{State.MOVING,State.ACTIVE}){assertEquals(Gate.DENY,gate(state,world,UUID.randomUUID(),false));assertEquals(Gate.DENY,gate(state,null,world,false));}}
    @Test void heldOrUnpreparedSessionsNeverGainEntry(){for(var state:State.values())if(state!=State.MOVING&&state!=State.ACTIVE){assertEquals(Gate.DENY,gate(state,world,world,false));assertEquals(Gate.DENY,gate(state,world,world,true));}assertEquals(Gate.DENY,gate(null,world,world,false));}
}
