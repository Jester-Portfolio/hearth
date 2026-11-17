package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.persistence.ServerProgressAccess;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StoryCommunityTest {
    final UUID run=new UUID(1,1),player=new UUID(2,1),past=new UUID(2,2);final ServerProgressAccess access=new ServerProgressAccess();
    StoryCommunity cache(){var binding=new StoryBinding(run,"hearth_story:a",new UUID(3,1),"v1","a".repeat(64),"b".repeat(64));var settings=new StoryRuntimeSettings(Map.of(run,new StoryRuntimeSettings.Run(binding,1,64,1,0,0,Set.of("protected"))),Map.of(player,run),"content.yml",binding.contentHash(),1,4,"localhost",5432,"hearth","hearth","HEARTH_PASSWORD");return new StoryCommunity(settings,access);}
    @Test void unavailableRosterDoesNotPretendToBeAnEmptyCommunity(){assertThrows(IllegalStateException.class,()->cache().refs());}
    @Test void hydrationIncludesPastStoredMemberButNotNeverAdmittedConfiguredMember(){var c=cache();c.replace(List.of(new StoryRegistry.Member(run,past)));assertEquals(1,c.refs().size());assertEquals(past,c.refs().getFirst().player());assertFalse(c.refs().getFirst().online());}
    @Test void confirmedAdmissionAddsExactlyOneStoredOfflineMember(){var c=cache();c.replace(List.of(new StoryRegistry.Member(run,past)));var context=access.forVerifiedServerLink(run,player);c.confirmed(context);c.confirmed(context);assertEquals(Set.of(player,past),new HashSet<>(c.refs().stream().map(r->r.player()).toList()));assertTrue(c.refs().stream().noneMatch(r->r.online()));}
    @Test void scopeAndIssuerFailuresPreservePreviouslyLoadedRoster(){var c=cache();c.replace(List.of(new StoryRegistry.Member(run,past)));var original=c.refs();assertThrows(SecurityException.class,()->c.replace(List.of(new StoryRegistry.Member(UUID.randomUUID(),past))));assertThrows(SecurityException.class,()->c.confirmed(new ServerProgressAccess().forVerifiedServerLink(run,player)));assertThrows(SecurityException.class,()->c.confirmed(access.forVerifiedServerLink(run,past)));assertSame(original,c.refs());}
    @Test void cachedProfileNamesLabelStoredMembersWithoutInventingMembership(){var c=cache();c.replace(List.of(new StoryRegistry.Member(run,past)));c.names(Map.of(past,"이전 구성원",player,"허용 계정",UUID.randomUUID(),"다른 계정"));assertEquals(1,c.refs().size());assertEquals("이전 구성원",c.refs().getFirst().name());c.confirmed(access.forVerifiedServerLink(run,player));assertEquals("허용 계정",c.refs().stream().filter(r->r.player().equals(player)).findFirst().orElseThrow().name());}
    @Test void duplicateMembershipCannotReplaceKnownRoster(){var c=cache();c.replace(List.of(new StoryRegistry.Member(run,past)));var original=c.refs();assertThrows(SecurityException.class,()->c.replace(List.of(new StoryRegistry.Member(run,past),new StoryRegistry.Member(run,past))));assertSame(original,c.refs());}
}
