package io.github.jesterportfolio.hearth.survival.housing;
import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;import static io.github.jesterportfolio.hearth.survival.housing.HousingState.*;
class HousingStateTest{
 private final UUID owner=UUID.randomUUID(),resident=UUID.randomUUID(),visitor=UUID.randomUUID(),pending=UUID.randomUUID();private final House h=new House(UUID.randomUUID(),owner,"v1",0,64,10,42,UUID.randomUUID(),Phase.ACTIVE,2);
 private Snapshot s(){return new Snapshot(List.of(h),List.of(new Member(h.id(),resident,Role.RESIDENT,true),new Member(h.id(),visitor,Role.VISITOR,true),new Member(h.id(),pending,Role.RESIDENT,false)),Map.of(),List.of(),Map.of());}
 @Test void owner_and_accepted_residents_build_while_visitors_only_enter(){var s=s();assertTrue(s.build(owner,h));assertTrue(s.build(resident,h));assertTrue(s.enter(visitor,h));assertFalse(s.build(visitor,h));assertFalse(s.enter(pending,h));assertFalse(s.enter(UUID.randomUUID(),h));}
 @Test void pending_and_committed_but_unapplied_worlds_never_admit(){for(var phase:List.of(Phase.PREPARING,Phase.APPLYING)){var closed=new House(h.id(),owner,h.version(),0,64,10,42,h.world(),phase,3);assertFalse(s().enter(owner,closed));assertFalse(s().enter(resident,closed));}}
 @Test void membership_removal_and_foreign_house_cannot_reuse_role(){var foreign=new House(UUID.randomUUID(),UUID.randomUUID(),"v1",0,64,0,42,UUID.randomUUID(),Phase.ACTIVE,0);assertFalse(s().enter(resident,foreign));assertFalse(new Snapshot(List.of(h),List.of(),Map.of(),List.of(),Map.of()).enter(resident,h));assertThrows(UnsupportedOperationException.class,()->s().members().clear());}
}
