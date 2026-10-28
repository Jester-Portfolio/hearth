package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.story.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GameplayGuideTest {
    @Test void initialActiveVotingAndFinishedGuidanceUsesOnlyCurrentSharedState(){
        var f=new SharedStoryTest.F();var content=f.story.content();
        assertTrue(StoryGuide.lines(content,f.story.cached(f.a),f.story.phase(f.a)).stream().anyMatch(x->"/hearthstory next".equals(x.command())));
        f.readyVote();var before=f.store.snapshots.get(f.a);
        var rows=StoryGuide.lines(content,f.story.cached(f.a),f.story.phase(f.a));
        assertTrue(rows.stream().anyMatch(x->"/hearthstory vote".equals(x.command())));
        assertTrue(rows.stream().anyMatch(x->"/hearthphone".equals(x.command())));
        assertFalse(rows.toString().contains("survey_marker"));assertFalse(rows.toString().contains("path_marker"));
        assertSame(before,f.store.snapshots.get(f.a));
        var vote=f.ok(f.story.openVote(f.c,f.t1,()->true));
        assertFalse(StoryGuide.lines(content,before,f.story.phase(f.a)).stream().anyMatch(x->"/hearthstory vote".equals(x.command())));
        f.ok(f.story.cast(f.c,f.t1,()->true,vote.id(),"one"));f.ok(f.story.cast(f.access.forVerifiedServerLink(f.a,f.p2),f.t2,()->true,vote.id(),"one"));
        var terminal=new StoryStore.Snapshot(f.story.cached(f.a).story(),Set.of("arrival","vote","open"),f.story.cached(f.a).branches());
        assertFalse(StoryGuide.lines(content,terminal,SharedStory.Phase.READY).stream().anyMatch(x->x.command()!=null&&x.command().startsWith("/hearthstory")));
        assertNull(f.story.cached(f.b).story().stage());
    }
    @Test void holdsUnknownAndBusyNeverOfferAdvanceOrLeakContent(){
        var f=new SharedStoryTest.F();f.readyVote();
        for(var phase:List.of(SharedStory.Phase.HOLD,SharedStory.Phase.RESULT_UNKNOWN)){
            var rows=StoryGuide.lines(f.story.content(),f.story.cached(f.a),phase);
            assertTrue(rows.stream().allMatch(x->x.command()==null));assertFalse(rows.toString().contains("vote"));
        }
        assertTrue(StoryGuide.lines(f.story.content(),f.story.cached(f.a),SharedStory.Phase.PERSISTING).stream().noneMatch(x->x.command()!=null&&x.command().startsWith("/hearthstory")));
    }
    @Test void guideNpcReferencesAreOptionalValidatedAndNeverInvented(){
        var root=SharedStoryTest.root();assertTrue(SharedStoryTest.definitions().guideNpcs().isEmpty());
        String npc=QuestCatalogTest.catalog().npcs().iterator().next().npcId();root.put("guide-npcs",List.of(npc));
        assertEquals(Set.of(npc),StoryContent.parse(root,QuestCatalogTest.catalog(),ExplorationTest.content()).guideNpcs());
        for(var bad:List.of(List.of("missing_npc"),List.of(npc,npc))){root.put("guide-npcs",bad);assertThrows(IllegalArgumentException.class,()->StoryContent.parse(root,QuestCatalogTest.catalog(),ExplorationTest.content()));}
    }
    @Test void sharedChangesSignalRunWithoutBroadcastingOtherPlayersPrivateData(){
        var f=new SharedStoryTest.F();var journal=new QuestJournal(new QuestBatchTest.JournalStore(),null,null,f.queue::add,()->{});
        var signals=new ArrayList<UUID>();journal.sharedStory(f.story,new StoryAccess(f.story));journal.sharedChanged(signals::add);
        f.readyVote();assertFalse(signals.isEmpty());assertTrue(signals.stream().allMatch(f.a::equals));
        assertNull(f.story.cached(f.b).story().stage());
    }
}
