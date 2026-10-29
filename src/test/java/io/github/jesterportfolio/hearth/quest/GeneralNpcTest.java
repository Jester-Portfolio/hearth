package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.exploration.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GeneralNpcTest {
    static Map<String,Object> witness(String id,int x){var n=QuestCatalogTest.npc(id,x);n.put("dialogue-pages",List.of("공개 현장 대화"));n.put("topics",List.of(Map.of("id","clue","label","현장 질문","text","공개 현장 답변","requires-completed","survey_marker","journal","found_fact")));return n;}
    static QuestCatalog catalog(){return QuestCatalog.parse(List.of(QuestCatalogTest.npc("survey_guide",3),witness("witness",10)),List.of(QuestCatalogTest.quest("survey_marker","survey_guide")));}
    static QuestJournal.View view(QuestCatalog c,boolean recorded,boolean shared,boolean complete,Set<String> blocked){
        var fact=new ExplorationStore.Fact("found_fact","PERSON","개인 확인 기록","요약","상세",1,Instant.EPOCH,Instant.EPOCH,shared);
        var base=ExplorationView.build(ExplorationContent.parse(ExplorationTest.root(),c),new ExplorationStore.Data(Map.of(),recorded?List.of(fact):List.of(),List.of(),ExplorationTest.empty().story()),List.of(),new CommunityBoundary.Status(false,List.of("미등록")));
        var e=new ExplorationView(base.registered(),base.regions(),base.places(),base.facts(),base.events(),base.members(),base.story(),base.conditions(),base.nextGoal(),blocked,base.branches());
        return new QuestJournal.View(Map.of("survey_marker",new QuestStore.Saved(complete?QuestStore.Phase.COMPLETED:QuestStore.Phase.NOT_ACCEPTED,complete?2:-1)),new QuestJournalStore.Journal(Set.of(),new QuestJournalStore.Preferences(true,-1),List.of()),e);
    }
    @Test void standaloneNpcHasRealIdentityWithoutFakeQuestOrQuestMarker(){
        var c=catalog();assertEquals(2,c.npcs().size());assertEquals(10,c.npc("witness").x());assertTrue(c.forNpc("witness").isEmpty());assertFalse(c.linked("witness","survey_marker"));assertEquals(Set.of("survey_marker"),c.questIds());
        var display=NpcQuestView.build(c,"witness",view(c,false,false,true,Set.of()).states(),q->true);assertTrue(display.options().isEmpty());assertEquals(NpcQuestView.Marker.NONE,display.marker());assertEquals(1,c.pages("witness").size());
        assertThrows(UnsupportedOperationException.class,()->c.forNpc("witness").add(c.quest("survey_marker")));
    }
    @Test void standalonePositionAndDefinitionAreValidatedBeforeUse(){
        for(Object bad:List.of(Double.NaN,Double.POSITIVE_INFINITY,30000000)){var n=witness("witness",10);n.put("x",bad);assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(QuestCatalogTest.npc("survey_guide",3),n),List.of(QuestCatalogTest.quest("survey_marker","survey_guide"))));}
        assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(QuestCatalogTest.npc("survey_guide",3),witness("witness",3)),List.of(QuestCatalogTest.quest("survey_marker","survey_guide"))));
        var noPurpose=QuestCatalogTest.npc("unused",10);assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(QuestCatalogTest.npc("survey_guide",3),noPurpose),List.of(QuestCatalogTest.quest("survey_marker","survey_guide"))));
    }
    @Test void emptyQuestTicketSetStillHasCurrentConversationAndCancelsLateCallbacks(){
        var d=new QuestDialogue(catalog());UUID p=UUID.randomUUID(),r=UUID.randomUUID(),w=UUID.randomUUID(),entity=UUID.randomUUID();Object token=new Object();
        assertTrue(d.options(p,token,r,w,entity,"witness").isEmpty());var first=d.conversation(p);assertTrue(d.current(first));
        var page=d.beginPages(p,token,r,w,entity,"witness").orElseThrow();d.turn(p,token,r,w,entity,"witness",page.nonce(),"close");assertFalse(d.current(first));
        d.options(p,token,r,w,entity,"witness");var second=d.conversation(p);d.options(p,new Object(),r,w,entity,"survey_guide");assertFalse(d.current(second));var third=d.conversation(p);d.clearWorld(w);assertFalse(d.current(third));
    }
    @Test void questCanTargetStandaloneNpcAndKeepOldGiver(){
        var q=QuestCatalogTest.quest("survey_marker","survey_guide");var goal=new HashMap<String,Object>(Map.of("min-x",9,"max-x",12,"min-y",64,"max-y",67,"min-z",2,"max-z",5,"interaction","npc:witness"));q.put("objective",goal);
        var c=QuestCatalog.parse(List.of(QuestCatalogTest.npc("survey_guide",3),witness("witness",10)),List.of(q));var f=new PersonalQuestTest.Fixture();var ctx=f.ctx(f.a,f.p);var session=f.open(ctx,new Object());f.quests.accept(session,c.quest("survey_marker"));f.commit(0);
        assertTrue(f.quests.interact(session,c.quest("survey_marker"),"npc:survey_guide").toCompletableFuture().isCompletedExceptionally());f.quests.interact(session,c.quest("survey_marker"),"npc:witness");f.commit(1);assertEquals(QuestStore.Phase.REPORT_READY,session.saved().phase());assertEquals("survey_guide",c.quest("survey_marker").npcId());
    }
    @Test void diaryContactsRequireOwnFactAndCurrentlyAllowedQuestion(){
        var c=catalog();for(var v:List.of(view(c,false,false,true,Set.of()),view(c,true,true,true,Set.of()),view(c,true,false,false,Set.of()),view(c,true,false,true,Set.of("survey_marker")))){
            assertTrue(NpcContacts.forFact(c,v,"P:found_fact").isEmpty());assertTrue(NpcContacts.resolve(c,v,"found_fact:witness").isEmpty());assertFalse(QuestScreens.screen(c,v,"fact","P:found_fact",0).stream().anyMatch(b->b.action().equals("npc-contact")));}
        var v=view(c,true,false,true,Set.of());assertEquals(1,NpcContacts.forFact(c,v,"P:found_fact").size());assertTrue(NpcContacts.resolve(c,v,"found_fact:witness").isPresent());
        for(String bad:List.of("missing:witness","found_fact:other","P:found_fact:witness","../found_fact:witness"))assertTrue(NpcContacts.resolve(c,v,bad).isEmpty());
        assertTrue(QuestScreens.screen(c,v,"fact","P:found_fact",0).stream().anyMatch(b->b.action().equals("npc-contact")&&b.quest().equals("found_fact:witness")));
    }
    @Test void contactButtonsUseExistingPaginationAndPreserveFactKey(){
        var npcs=new ArrayList<Map<String,Object>>();npcs.add(QuestCatalogTest.npc("survey_guide",3));for(int i=0;i<40;i++)npcs.add(witness("witness_"+i,10+i*3));var c=QuestCatalog.parse(npcs,List.of(QuestCatalogTest.quest("survey_marker","survey_guide")));var v=view(c,true,false,true,Set.of());
        var before=v.toString();var first=QuestScreens.screen(c,v,"fact","P:found_fact",0);var next=first.stream().filter(b->b.slot()==51).findFirst().orElseThrow();assertEquals("fact",next.action());assertEquals("P:found_fact",next.quest());
        var second=QuestScreens.screen(c,v,"fact",next.quest(),(int)next.revision());assertTrue(second.stream().anyMatch(b->b.action().equals("npc-contact")));assertTrue(second.stream().anyMatch(b->b.slot()==47&&"P:found_fact".equals(b.quest())));assertEquals(before,v.toString());
    }
}
