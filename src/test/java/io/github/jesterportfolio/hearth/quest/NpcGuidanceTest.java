package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NpcGuidanceTest {
    final QuestCatalog catalog=GeneralNpcTest.catalog();
    QuestJournal.View view(boolean recorded,boolean shared,boolean complete){return GeneralNpcTest.view(catalog,recorded,shared,complete,Set.of());}
    NpcGuidance.Page page(QuestJournal.View v){return NpcGuidance.build(catalog,v,null,Set.of());}
    QuestJourneyView.Page journey(String quest,boolean shared){return new QuestJourneyView.Page(new Playthrough.Guide("현재 공개 코스",List.of("현재 안내"),null,quest,false,quest),shared?List.of(new QuestJourneyView.Link("공동 조사",QuestJourneyView.Action.SHARED,null,-1)):List.of(),-1,0);}
    @Test void unacceptedFutureNpcAndOtherPersonsFactNeverBecomeDirectory(){
        assertTrue(page(view(false,false,false)).contacts().isEmpty());assertTrue(page(view(true,true,false)).contacts().isEmpty());
        var own=page(view(true,false,true));assertEquals(Set.of("survey_guide","witness"),own.contacts().stream().map(NpcGuidance.Contact::npc).collect(java.util.stream.Collectors.toSet()));
        assertFalse(page(view(false,false,true)).contacts().stream().anyMatch(c->c.npc().equals("witness")));
    }
    @Test void locationDeniedNpcDisappearsFromDirectoryFactButtonsAndPhoneReaderLinks(){
        var v=view(true,false,true);var denied=new QuestJournal.View(v.states(),v.journal(),v.exploration(),v.completion(),Set.of("witness"));assertFalse(page(denied).contacts().stream().anyMatch(c->c.npc().equals("witness")));assertTrue(NpcContacts.forFact(catalog,denied,"P:found_fact").isEmpty());
        assertFalse(QuestScreens.screen(catalog,denied,"fact","P:found_fact",0).stream().anyMatch(b->b.action().equals("npc-contact")));
        var source=io.github.jesterportfolio.hearth.phone.PhoneReaderLinks.library(catalog,denied,"PERSON",0);assertFalse(io.github.jesterportfolio.hearth.phone.PhoneReaderLinks.links(catalog,denied,Map.of(),source).stream().anyMatch(l->l.kind()==io.github.jesterportfolio.hearth.phone.PhoneReaderLinks.Kind.NPC));
        assertThrows(IllegalStateException.class,()->NpcGuidance.require(page(denied),page(v),page(v).contacts().getLast()));
    }
    @Test void onlyCurrentPublicCourseCanIntroduceUnacceptedQuestGiver(){
        var v=view(false,false,false);var current=NpcGuidance.build(catalog,v,journey("survey_marker",false),Set.of());assertEquals(1,current.contacts().size());assertTrue(current.contacts().getFirst().reasons().getFirst().startsWith("현재 코스"));
        var x=v.exploration();var blocked=new io.github.jesterportfolio.hearth.exploration.ExplorationView(x.registered(),x.regions(),x.places(),x.facts(),x.events(),x.members(),x.story(),x.conditions(),x.nextGoal(),Set.of("survey_marker"),x.branches());
        assertTrue(NpcGuidance.build(catalog,new QuestJournal.View(v.states(),v.journal(),blocked),journey("survey_marker",false),Set.of()).contacts().isEmpty());
    }
    @Test void sharedGuideNeedsCurrentSharedActionAndItsOwnLocationPermission(){
        var v=view(false,false,false);assertTrue(NpcGuidance.build(catalog,v,journey(null,false),Set.of("witness")).contacts().isEmpty());var shared=NpcGuidance.build(catalog,v,journey(null,true),Set.of("witness"));assertEquals("witness",shared.contacts().getFirst().npc());
        var blocked=new QuestJournal.View(v.states(),v.journal(),v.exploration(),v.completion(),Set.of("witness"));assertTrue(NpcGuidance.build(catalog,blocked,journey(null,true),Set.of("witness")).contacts().isEmpty());
    }
    @Test void cardContainsQuestionLabelsAndOwnReasonsButNoAnswersOrRawPosition(){
        var card=page(view(true,false,true)).contacts().stream().filter(c->c.npc().equals("witness")).findFirst().orElseThrow();assertEquals(List.of("현장 질문"),card.questions());String rendered=String.join(" ",card.lines());assertTrue(rendered.contains("내 기록"));for(String secret:List.of("공개 현장 답변","공개 현장 대화","witness","found_fact","10.0"))assertFalse(rendered.contains(secret),secret);assertFalse(card.book().pages().isEmpty());
    }
    @Test void phaseAndSamePhaseRevisionChangesInvalidatePreviousChoice(){
        var before=view(true,false,true);var shown=page(before);var states=new HashMap<>(before.states());states.put("survey_marker",new QuestStore.Saved(QuestStore.Phase.COMPLETED,3));var changed=page(new QuestJournal.View(states,before.journal(),before.exploration()));assertThrows(IllegalStateException.class,()->NpcGuidance.require(changed,shown,shown.contacts().getFirst()));
        states.put("survey_marker",new QuestStore.Saved(QuestStore.Phase.SKIPPED,4));assertFalse(page(new QuestJournal.View(states,before.journal(),before.exploration())).contacts().stream().anyMatch(c->c.npc().equals("survey_guide")));
    }
    @Test void paginationUsesNpcIdentityForDuplicateNamesAndPreservesReadOnlyData(){
        var actors=new ArrayList<Map<String,Object>>();actors.add(QuestCatalogTest.npc("survey_guide",3));for(int i=0;i<14;i++){var n=GeneralNpcTest.witness("witness_"+i,10+i*3);n.put("name","같은 이름");actors.add(n);}var c=QuestCatalog.parse(actors,List.of(QuestCatalogTest.quest("survey_marker","survey_guide")));var v=GeneralNpcTest.view(c,true,false,true,Set.of());String before=v.toString();var p=NpcGuidance.build(c,v,null,Set.of());assertEquals(3,p.pages());assertEquals(6,p.page(1).size());assertEquals(3,p.page(3).size());assertEquals(15,p.contacts().stream().map(NpcGuidance.Contact::npc).distinct().count());assertEquals(before,v.toString());assertThrows(IllegalArgumentException.class,()->p.page(4));assertThrows(UnsupportedOperationException.class,()->p.contacts().clear());assertThrows(UnsupportedOperationException.class,()->p.contacts().getFirst().quests().clear());
    }
    @Test void foreignOrForgedContactCannotExecuteEvenForSameDisplayName(){var shown=page(view(true,false,true));var actual=shown.contacts().getFirst();var foreign=new NpcGuidance.Contact("foreign",actual.name(),actual.reasons(),actual.questions(),actual.quests(),actual.facts());assertThrows(IllegalStateException.class,()->NpcGuidance.require(shown,shown,foreign));NpcGuidance.require(shown,shown,actual);}
    @Test void activeCurrentNpcObjectiveIsShownBeforePersonalAnswerButFutureAndReportingGoalsAreNot(){
        var q=QuestCatalogTest.quest("survey_marker","survey_guide");q.remove("objective");q.put("objectives",List.of(Map.of("description","현재 현장 대화","min-x",9,"max-x",12,"min-y",64,"max-y",67,"min-z",2,"max-z",5,"interaction","npc:witness"),Map.of("description","다음 방문","min-x",15,"max-x",16,"min-y",64,"max-y",67,"min-z",2,"max-z",5)));
        var c=QuestCatalog.parse(List.of(QuestCatalogTest.npc("survey_guide",3),GeneralNpcTest.witness("witness",10)),List.of(q));var base=GeneralNpcTest.view(c,false,false,false,Set.of());var active=new QuestJournal.View(Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.ACTIVE,0,0)),base.journal(),base.exploration());var shown=NpcGuidance.build(c,active,null,Set.of());assertTrue(shown.contacts().stream().anyMatch(contact->contact.npc().equals("witness")&&contact.reasons().stream().anyMatch(s->s.startsWith("현재 의뢰 목표"))));
        for(var saved:List.of(new QuestStore.Saved(QuestStore.Phase.ACTIVE,1,1),new QuestStore.Saved(QuestStore.Phase.REPORT_READY,2,1),new QuestStore.Saved(QuestStore.Phase.FAILED,3,0))){var fresh=NpcGuidance.build(c,new QuestJournal.View(Map.of("survey_marker",saved),base.journal(),base.exploration()),null,Set.of());assertFalse(fresh.contacts().stream().anyMatch(contact->contact.npc().equals("witness")));assertThrows(IllegalStateException.class,()->NpcGuidance.require(fresh,shown,shown.contacts().getLast()));}
    }
}
