package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.QuestStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NpcDialogueBookTest {
    final UUID p=UUID.randomUUID(),r=UUID.randomUUID(),w=UUID.randomUUID(),e=UUID.randomUUID();
    final Object s=new Object();final QuestDialogue d=new QuestDialogue(NpcTopicsTest.catalog());
    QuestDialogue.Ticket accept(){var selected=d.options(p,s,r,w,e,"survey_guide").getFirst();d.authorize(p,s,r,w,e,QuestDialogue.Request.parse(d.command(selected,"select")));return d.action(selected,"accept");}
    @Test void declineConsumesAcceptanceAndInvalidatesOldActions(){
        var ticket=accept();var scope=d.conversation(p);
        var request=QuestDialogue.Request.parse(d.declineCommand(ticket));d.decline(p,s,r,w,e,request);
        assertFalse(d.current(ticket));assertFalse(d.current(scope));
        assertThrows(IllegalArgumentException.class,()->d.authorize(p,s,r,w,e,new QuestDialogue.Request("accept",ticket.npc(),ticket.quest(),ticket.nonce())));
        assertThrows(IllegalArgumentException.class,()->d.decline(p,s,r,w,e,request));
    }
    @Test void declineIsBoundToPlayerSessionRunWorldEntityNpcAndQuest(){
        var t=accept();var q=QuestDialogue.Request.parse(d.declineCommand(t));
        assertThrows(IllegalArgumentException.class,()->d.decline(UUID.randomUUID(),s,r,w,e,q));
        assertThrows(IllegalArgumentException.class,()->d.decline(p,new Object(),r,w,e,q));
        assertThrows(IllegalArgumentException.class,()->d.decline(p,s,UUID.randomUUID(),w,e,q));
        assertThrows(IllegalArgumentException.class,()->d.decline(p,s,r,UUID.randomUUID(),e,q));
        assertThrows(IllegalArgumentException.class,()->d.decline(p,s,r,w,UUID.randomUUID(),q));
        for(var bad:List.of(new QuestDialogue.Request("decline","wrong",t.quest(),t.nonce()),new QuestDialogue.Request("decline",t.npc(),"wrong",t.nonce()),new QuestDialogue.Request("decline",t.npc(),t.quest(),UUID.randomUUID())))assertThrows(IllegalArgumentException.class,()->d.decline(p,s,r,w,e,bad));
        d.decline(p,s,r,w,e,q);
    }
    @Test void acceptedOrReportActionCannotBeDeclined(){
        var t=accept();d.authorize(p,s,r,w,e,QuestDialogue.Request.parse(d.command(t,"accept")));
        assertThrows(IllegalArgumentException.class,()->d.decline(p,s,r,w,e,QuestDialogue.Request.parse(d.declineCommand(t))));
        var selected=d.options(p,s,r,w,e,"survey_guide").getFirst();d.authorize(p,s,r,w,e,QuestDialogue.Request.parse(d.command(selected,"select")));var report=d.action(selected,"report");assertThrows(IllegalArgumentException.class,()->d.declineCommand(report));
    }
    @Test void menuReturnAndCloseAreScopedAndOldConversationIsInvalidAfterRefresh(){
        accept();var c=d.conversation(p);assertTrue(d.menuCommand(c).endsWith(c.nonce().toString()));assertTrue(d.endCommand(c).startsWith("/hearthtalk end "));
        assertSame(c,d.authorizeMenu(p,s,r,w,e,c.npc(),c.nonce()));
        assertThrows(IllegalArgumentException.class,()->d.authorizeMenu(p,new Object(),r,w,e,c.npc(),c.nonce()));
        assertThrows(IllegalArgumentException.class,()->d.authorizeMenu(p,s,UUID.randomUUID(),w,e,c.npc(),c.nonce()));
        assertThrows(IllegalArgumentException.class,()->d.authorizeMenu(p,s,r,UUID.randomUUID(),e,c.npc(),c.nonce()));
        assertThrows(IllegalArgumentException.class,()->d.authorizeMenu(p,s,r,w,UUID.randomUUID(),c.npc(),c.nonce()));
        d.options(p,s,r,w,e,c.npc());assertThrows(IllegalArgumentException.class,()->d.authorizeMenu(p,s,r,w,e,c.npc(),c.nonce()));
        var next=d.conversation(p);d.clearWorld(w);assertFalse(d.current(next));assertThrows(IllegalArgumentException.class,()->d.menuCommand(next));
    }
    @Test void questionInventoryFiltersSecretsAndRefreshesAvailableQuestions(){
        var c=NpcTopicsTest.catalog();var rows=Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1));
        var menu=QuestScreens.npc(c,"survey_guide",rows,q->true,0);assertTrue(menu.stream().anyMatch(b->b.action().equals("npc-read")));assertTrue(menu.stream().anyMatch(b->b.action().equals("npc-topics-open")));
        var topics=QuestScreens.npcTopics(c,"survey_guide",rows,q->true,0);assertEquals(1,topics.stream().filter(b->b.action().equals("npc-topic")).count());assertFalse(topics.toString().contains("조사 후 질문"));
        var complete=Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.COMPLETED,2));assertEquals(2,QuestScreens.npcTopics(c,"survey_guide",complete,q->true,0).stream().filter(b->b.action().equals("npc-topic")).count());
        assertEquals(1,QuestScreens.npcTopics(c,"survey_guide",complete,q->false,0).stream().filter(b->b.action().equals("npc-topic")).count());assertThrows(IllegalArgumentException.class,()->QuestScreens.npcTopics(c,"survey_guide",Map.of(),q->true,0));
    }
    @Test void ordinaryNpcHasBooksAndQuestionsWithoutInventingQuests(){
        var c=GeneralNpcTest.catalog();var rows=GeneralNpcTest.view(c,false,false,true,Set.of()).states();var menu=QuestScreens.npc(c,"witness",rows,q->true,0);
        assertFalse(menu.stream().anyMatch(b->Set.of("npc-select","npc-action").contains(b.action())));assertTrue(menu.stream().anyMatch(b->b.action().equals("npc-read")));assertTrue(menu.stream().anyMatch(b->b.action().equals("npc-topics-open")));
    }
    @Test void questionMenuRespectsContentLimitAndDoesNotOverlapControls(){
        var npc=QuestCatalogTest.npc("guide",3);var topics=new ArrayList<Map<String,Object>>();for(int i=0;i<16;i++)topics.add(Map.of("id","topic_"+i,"label","질문 "+i,"text","공개 답변"));npc.put("topics",topics);
        var catalog=QuestCatalog.parse(List.of(npc),List.of(QuestCatalogTest.quest("test","guide")));var rows=Map.of("test",new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1));
        var menu=QuestScreens.npcTopics(catalog,"guide",rows,q->true,0);assertEquals(16,menu.stream().filter(b->b.action().equals("npc-topic")).count());assertEquals(menu.size(),menu.stream().map(QuestScreens.Button::slot).distinct().count());assertTrue(menu.stream().anyMatch(b->b.slot()==45&&b.action().equals("npc-menu")));assertFalse(menu.stream().anyMatch(b->b.slot()==51));
    }
    @Test void topicSelectionUsesCurrentSessionAndOneUseTicket(){
        var catalog=NpcTopicsTest.catalog();var dialogue=new QuestDialogue(catalog);dialogue.options(p,s,r,w,e,"survey_guide");var topic=dialogue.topicOptions(p,s,r,w,e,"survey_guide",catalog.availableTopics("survey_guide",Map.of(),q->true)).getFirst();
        assertSame(topic,dialogue.topicSelection(p,s,"survey_guide",topic.topic()));assertThrows(IllegalArgumentException.class,()->dialogue.topicSelection(p,new Object(),"survey_guide",topic.topic()));
        assertThrows(IllegalArgumentException.class,()->dialogue.topicSelection(p,s,"survey_guide","clue"));dialogue.authorizeTopic(p,s,r,w,e,"survey_guide",topic.topic(),topic.nonce());assertThrows(IllegalArgumentException.class,()->dialogue.topicSelection(p,s,"survey_guide",topic.topic()));
        dialogue.clear(p);assertFalse(dialogue.current(topic));
    }
}
