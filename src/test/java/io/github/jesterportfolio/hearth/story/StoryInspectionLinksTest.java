package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StoryInspectionLinksTest {
    final UUID run=new UUID(1,1),player=new UUID(2,1);
    StoryInspectionLinks links(){var b=new StoryBinding(run,"hearth_story:a",new UUID(3,1),"v1","a".repeat(64),"b".repeat(64));var s=new StoryRuntimeSettings(Map.of(run,new StoryRuntimeSettings.Run(b,1,64,1,0,0,Set.of("protected"))),Map.of(player,run),"content.yml",b.contentHash(),1,4,"localhost",5432,"hearth","hearth","HEARTH_PASSWORD");return new StoryInspectionLinks(s,null,new ServerProgressAccess());}
    @Test void existingConnectionIdentityIsStableAndRemovedMemberIsInvalidated(){var l=links();var m=new StoryInspectionLinks.Member(run,player);l.replace(List.of(m));var first=l.contexts().getFirst();l.replace(List.of(m));assertSame(first,l.contexts().getFirst());l.replace(List.of());assertTrue(l.contexts().isEmpty());l.replace(List.of(m));assertNotSame(first,l.contexts().getFirst());}
    @Test void unknownAccountOtherRunAndDuplicateMembershipCannotIssueContexts(){var l=links();for(var rows:List.of(List.of(new StoryInspectionLinks.Member(run,UUID.randomUUID())),List.of(new StoryInspectionLinks.Member(UUID.randomUUID(),player)),List.of(new StoryInspectionLinks.Member(run,player),new StoryInspectionLinks.Member(run,player)))){assertThrows(SecurityException.class,()->l.replace(rows));assertTrue(l.contexts().isEmpty());}}
    @Test void clearRevokesAllReportLinks(){var l=links();l.replace(List.of(new StoryInspectionLinks.Member(run,player)));l.clear();assertTrue(l.contexts().isEmpty());}
}
