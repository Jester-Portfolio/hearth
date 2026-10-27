package io.github.jesterportfolio.hearth.quest;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class QuestDialogueTest {
    final QuestDialogue d=new QuestDialogue(QuestCatalogTest.catalog());
    final UUID player=UUID.randomUUID(),run=UUID.randomUUID(),world=UUID.randomUUID(),entity=UUID.randomUUID();final Object session=new Object();
    QuestDialogue.Ticket begin(){return d.begin(player,session,run,world,entity,"survey_guide","survey_marker");}
    @Test void duplicateAndWrongNpcQuestRequestsDoNotReachTheAction(){
        var t=begin();var r=QuestDialogue.Request.parse(d.command(t,"accept"));
        assertThrows(IllegalArgumentException.class,()->d.authorize(player,session,run,world,entity,new QuestDialogue.Request("accept","path_guide","path_marker",r.nonce())));
        assertSame(t,d.authorize(player,session,run,world,entity,r));assertThrows(IllegalArgumentException.class,()->d.authorize(player,session,run,world,entity,r));
    }
    @Test void playerRunSessionWorldAndEntityAreBoundToCurrentConversation(){
        var t=begin();var r=QuestDialogue.Request.parse(d.command(t,"report"));
        assertThrows(IllegalArgumentException.class,()->d.authorize(UUID.randomUUID(),session,run,world,entity,r));
        assertThrows(IllegalArgumentException.class,()->d.authorize(player,new Object(),run,world,entity,r));
        assertThrows(IllegalArgumentException.class,()->d.authorize(player,session,UUID.randomUUID(),world,entity,r));
        assertThrows(IllegalArgumentException.class,()->d.authorize(player,session,run,UUID.randomUUID(),entity,r));
        assertThrows(IllegalArgumentException.class,()->d.authorize(player,session,run,world,UUID.randomUUID(),r));
        assertSame(t,d.authorize(player,session,run,world,entity,r));
    }
    @Test void switchingNpcRejectsOldChatChoiceAndCallback(){
        var t=begin();var old=QuestDialogue.Request.parse(d.command(t,"accept"));
        var next=d.begin(player,session,run,world,UUID.randomUUID(),"path_guide","path_marker");assertFalse(d.current(t));assertTrue(d.current(next));
        assertThrows(IllegalArgumentException.class,()->d.authorize(player,session,run,world,entity,old));
        d.clearWorld(world);assertFalse(d.current(next));
    }
    @Test void malformedRequestsUnknownLinksAndLogoutAreRejected(){
        for(String c:List.of("/hearthquest accept","/hearthquest accept survey_guide missing nope","/hearthquest reset survey_guide survey_marker "+UUID.randomUUID(),"/hearthquest accept ../bad survey_marker "+UUID.randomUUID()))assertThrows(IllegalArgumentException.class,()->QuestDialogue.Request.parse(c));
        assertThrows(IllegalArgumentException.class,()->d.begin(player,session,run,world,entity,"missing","survey_marker"));
        var t=begin();var r=QuestDialogue.Request.parse(d.command(t,"accept"));d.clear(player);assertFalse(d.current(t));assertThrows(IllegalArgumentException.class,()->d.authorize(player,session,run,world,entity,r));
    }
}
