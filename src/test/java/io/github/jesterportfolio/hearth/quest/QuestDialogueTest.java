package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class QuestDialogueTest {
    static QuestCatalog shared(){return QuestCatalog.parse(List.of(QuestCatalogTest.npc("survey_guide",3)),List.of(QuestCatalogTest.quest("survey_marker","survey_guide"),QuestCatalogTest.quest("path_marker","survey_guide")));}
    final QuestDialogue d=new QuestDialogue(shared());
    final UUID player=UUID.randomUUID(),run=UUID.randomUUID(),world=UUID.randomUUID(),entity=UUID.randomUUID();final Object session=new Object();
    List<QuestDialogue.Ticket> options(){return d.options(player,session,run,world,entity,"survey_guide");}
    QuestDialogue.Request request(QuestDialogue.Ticket t){return QuestDialogue.Request.parse(d.command(t,t.action()));}
    QuestDialogue.Ticket authorize(QuestDialogue.Ticket t){return d.authorize(player,session,run,world,entity,request(t));}
    QuestDialogue.Ticket action(String name){var selected=options().getFirst();authorize(selected);return d.action(selected,name);}
    @Test void duplicateAndWrongNpcQuestOrActionDoNotReachTheAction(){
        var t=options().getFirst();var r=request(t);
        for(var bad:List.of(new QuestDialogue.Request("select","survey_guide","path_marker",r.nonce()),new QuestDialogue.Request("select","missing",t.quest(),r.nonce()),new QuestDialogue.Request("accept",t.npc(),t.quest(),r.nonce())))
            assertThrows(IllegalArgumentException.class,()->d.authorize(player,session,run,world,entity,bad));
        assertSame(t,authorize(t));assertThrows(IllegalArgumentException.class,()->authorize(t));
        var accept=d.action(t,"accept");assertFalse(d.current(t));assertNotEquals(t.nonce(),accept.nonce());
        assertThrows(IllegalArgumentException.class,()->d.authorize(player,session,run,world,entity,new QuestDialogue.Request("report",accept.npc(),accept.quest(),accept.nonce())));
        assertSame(accept,authorize(accept));assertThrows(IllegalArgumentException.class,()->authorize(accept));
    }
    @Test void playerRunSessionWorldAndEntityAreBoundToCurrentConversation(){
        var t=action("report");var r=request(t);
        assertThrows(IllegalArgumentException.class,()->d.authorize(UUID.randomUUID(),session,run,world,entity,r));
        assertThrows(IllegalArgumentException.class,()->d.authorize(player,new Object(),run,world,entity,r));
        assertThrows(IllegalArgumentException.class,()->d.authorize(player,session,UUID.randomUUID(),world,entity,r));
        assertThrows(IllegalArgumentException.class,()->d.authorize(player,session,run,UUID.randomUUID(),entity,r));
        assertThrows(IllegalArgumentException.class,()->d.authorize(player,session,run,world,UUID.randomUUID(),r));
        assertSame(t,authorize(t));
    }
    @Test void optionsAndActionsForTwoQuestsAreIndependent(){
        var rows=options();var one=rows.getFirst();var two=rows.getLast();
        assertEquals(List.of("survey_marker","path_marker"),rows.stream().map(QuestDialogue.Ticket::quest).toList());
        authorize(one);var accept=d.action(one,"accept");assertTrue(d.current(two));authorize(two);var report=d.action(two,"report");
        assertTrue(d.current(accept));authorize(accept);authorize(report);
        assertThrows(UnsupportedOperationException.class,()->rows.clear());
    }
    @Test void npcSwitchLogoutAndWorldUnloadRejectOldCallbacks(){
        var other=new QuestDialogue(QuestCatalogTest.catalog());var old=other.options(player,session,run,world,entity,"survey_guide").getFirst();
        var next=other.options(player,session,run,world,UUID.randomUUID(),"path_guide").getFirst();assertFalse(other.current(old));assertTrue(other.current(next));
        assertThrows(IllegalArgumentException.class,()->other.authorize(player,session,run,world,entity,new QuestDialogue.Request("select",old.npc(),old.quest(),old.nonce())));
        other.clearWorld(world);assertFalse(other.current(next));
        var rows=options();d.clear(player);rows.forEach(t->assertFalse(d.current(t)));
    }
    @Test void malformedRequestsUnknownLinksAndUnselectedActionsAreRejected(){
        for(String c:List.of("/hearthquest accept","/hearthquest select survey_guide missing nope","/hearthquest reset survey_guide survey_marker "+UUID.randomUUID(),"/hearthquest select ../bad survey_marker "+UUID.randomUUID()))assertThrows(IllegalArgumentException.class,()->QuestDialogue.Request.parse(c));
        assertThrows(NullPointerException.class,()->d.options(player,session,run,world,entity,"missing"));
        var t=options().getFirst();assertThrows(IllegalArgumentException.class,()->d.action(t,"accept"));
        assertThrows(IllegalArgumentException.class,()->d.command(t,"accept"));
    }
    @Test void delayedSelectionCannotRenderAfterNewConversationOrSession(){
        var old=options().getFirst();authorize(old);
        var next=d.options(player,new Object(),run,world,entity,"survey_guide");
        assertFalse(d.current(old));assertThrows(IllegalArgumentException.class,()->d.action(old,"accept"));
        assertTrue(d.current(next.getFirst()));
    }
    @Test void savingOrHoldRejectsOnlyAffectedQuestDialogue(){
        var f=new PersonalQuestTest.Fixture(true);var context=f.ctx(f.a,f.p);var token=new Object();
        var one=f.open(context,"survey_marker",token);var two=f.open(context,"path_marker",token);
        f.quests.advance(one,QuestStore.Phase.ACTIVE);assertThrows(IllegalStateException.class,()->f.quests.requireDialogue(one));assertEquals(QuestStore.Phase.NOT_ACCEPTED,f.quests.requireDialogue(two).phase());
        f.store.pending.getFirst().completeExceptionally(new StorageFailure("quest","23514"));f.pump();
        assertThrows(IllegalStateException.class,()->f.quests.requireDialogue(one));f.quests.advance(two,QuestStore.Phase.ACTIVE);f.commit(1);
        assertEquals(QuestStore.Phase.ACTIVE,f.quests.requireDialogue(two).phase());assertEquals(QuestStore.Phase.NOT_ACCEPTED,one.saved().phase());
    }
    @Test void delayedPersonalReadRequiresCurrentSessionBeforeDialogue(){
        var f=new PersonalQuestTest.Fixture(true);var context=f.ctx(f.a,f.p);f.store.deferReads=true;
        var pending=f.quests.open(context,"survey_marker",new Object(),()->f.valid);f.pump();f.valid=false;
        f.store.readResults.getFirst().complete(f.store.readSnapshots.getFirst());f.pump();
        assertTrue(pending.toCompletableFuture().isCompletedExceptionally());
    }
}
