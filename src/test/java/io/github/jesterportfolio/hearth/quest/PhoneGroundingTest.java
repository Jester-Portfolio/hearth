package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.phone.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PhoneGroundingTest {
    final QuestCatalog catalog=GeneralNpcTest.catalog();final QuestJournal.View view=PhoneReaderLinksTest.known();
    GameInformation data(QuestJournal.View v){return GameInformation.build(catalog,v);}
    PhoneGrounding.Plan search(QuestJournal.View v,String mode,String question){return PhoneGrounding.search(catalog,v,data(v),mode,question);}
    QuestJournal.View facts(List<ExplorationStore.Fact> facts){return PhoneReaderLinksTest.view(facts,view.exploration().events(),view.exploration().places());}
    @Test void everyPublicModeIsBoundToFullTypedMaterialsAndHasBoundedContext(){
        for(String mode:List.of("quests","map","diary","story")){var p=search(view,mode,"공개 공유");assertFalse(p.materials().isEmpty(),mode);assertTrue(p.sendable());assertEquals(mode,p.scope().mode());assertEquals(p.selected().size(),p.result().sources().size());assertEquals(p,PhoneGrounding.refresh(catalog,view,data(view),p));assertTrue(p.result().context().codePointCount(0,p.result().context().length())<=2400);}
    }
    @Test void onlyIncludedMaterialsAreSentAndNoSelectionCannotMasqueradeAsNoMatch(){
        var v=facts(List.of(PhoneReaderLinksTest.fact("found_fact",2,false),PhoneReaderLinksTest.fact("other",3,false)));var p=search(v,"diary","공개 상세");assertEquals(2,p.materials().size());var one=p.toggle("P:other");assertEquals(List.of("P:found_fact"),one.sent().materials().stream().map(m->m.item().key()).toList());assertEquals(1,one.result().sources().size());assertFalse(one.result().context().contains("상세 3"));var empty=one.toggle("P:found_fact");assertFalse(empty.sendable());assertThrows(IllegalStateException.class,empty::result);assertThrows(IllegalStateException.class,empty::sent);assertThrows(IllegalArgumentException.class,()->p.toggle("foreign"));
    }
    @Test void reorderingResolvesOriginalKeyAndKeepsHistoryScope(){
        var p=search(view,"diary","공개 상세");var reordered=facts(List.of(PhoneReaderLinksTest.fact("other",3,false),PhoneReaderLinksTest.fact("found_fact",2,false)));var current=PhoneGrounding.refresh(catalog,reordered,data(reordered),p);assertEquals(1,current.materials().getFirst().item().source().index());assertEquals("P:found_fact",current.materials().getFirst().item().key());assertEquals(p.scope(),current.scope());assertTrue(String.join(" ",PhoneGrounding.detail(current.materials().getFirst()).pages()).contains("상세 2"));
    }
    @Test void changesOutsideExcerptRejectBeforeSendAndProduceDistinctHistoryScopes(){
        var old=view.exploration().facts().getFirst();String body="공개😀".repeat(300);var f=new ExplorationStore.Fact(old.id(),old.category(),old.title(),old.summary(),body+"처음 끝부분",2,old.first(),old.updated(),false);var first=facts(List.of(f));var p=search(first,"diary","공개");var nextFact=new ExplorationStore.Fact(f.id(),f.category(),f.title(),f.summary(),body+"바뀐 끝부분",3,f.first(),f.updated(),false);var next=facts(List.of(nextFact));var fresh=search(next,"diary","공개");assertEquals(p.result().context(),fresh.result().context());assertNotEquals(p.scope(),fresh.scope());assertThrows(IllegalStateException.class,()->PhoneGrounding.refresh(catalog,next,data(next),p));assertTrue(String.join("",PhoneGrounding.detail(p.materials().getFirst()).pages()).contains("처음 끝부분"));assertFalse(p.result().context().contains("처음 끝부분"));
    }
    @Test void sameStatusQuestRevisionAndSameTextDiaryVersionStillInvalidate(){
        var q=search(view,"quests","공개");var states=new HashMap<>(view.states());var old=states.get("survey_marker");states.put("survey_marker",new QuestStore.Saved(old.phase(),old.revision()+1,old.objectiveIndex()));var next=new QuestJournal.View(states,view.journal(),view.exploration());assertEquals(data(view),data(next));assertThrows(IllegalStateException.class,()->PhoneGrounding.refresh(catalog,next,data(next),q));
        var p=search(view,"diary","공개");var f=view.exploration().facts().getFirst();var changed=facts(List.of(new ExplorationStore.Fact(f.id(),f.category(),f.title(),f.summary(),f.detail(),f.version()+1,f.first(),f.updated(),f.shared())));assertEquals(data(view),data(changed));assertThrows(IllegalStateException.class,()->PhoneGrounding.refresh(catalog,changed,data(changed),p));
    }
    @Test void removedPrivateRecordOrNewlyBlockedQuestCannotBeReused(){
        var p=search(view,"diary","공개");var other=facts(List.of());assertThrows(IllegalStateException.class,()->PhoneGrounding.refresh(catalog,other,data(other),p));var q=search(view,"quests","공개");var x=view.exploration();var blocked=new QuestJournal.View(view.states(),view.journal(),new io.github.jesterportfolio.hearth.exploration.ExplorationView(x.registered(),x.regions(),x.places(),x.facts(),x.events(),x.members(),x.story(),x.conditions(),x.nextGoal(),Set.of("survey_marker"),x.branches()));assertThrows(IllegalStateException.class,()->PhoneGrounding.refresh(catalog,blocked,data(blocked),q));
    }
    @Test void personalAndSharedSameIdHaveSeparateMaterialIdentityAndNoKeysInContext(){
        var v=facts(List.of(PhoneReaderLinksTest.fact("found_fact",2,false),PhoneReaderLinksTest.fact("found_fact",2,true)));var p=search(v,"diary","공개");assertEquals(2,p.materials().size());assertNotEquals(p.materials().get(0).item().key(),p.materials().get(1).item().key());assertFalse(p.result().context().contains("found_fact"));assertFalse(p.result().context().contains("P:"));assertFalse(p.result().context().contains("S:"));assertTrue(p.result().context().contains("공동체"));
    }
    @Test void noMatchRemainsExplicitAndRejectsNewlyMatchingCurrentRecord(){
        var p=search(view,"diary","새단서");assertTrue(p.materials().isEmpty());assertTrue(p.sendable());assertTrue(p.result().context().contains("찾지 못했습니다"));assertEquals(p,PhoneGrounding.refresh(catalog,view,data(view),p));var f=PhoneReaderLinksTest.fact("new_fact",1,false);var next=facts(List.of(new ExplorationStore.Fact(f.id(),f.category(),"새단서",f.summary(),f.detail(),1,f.first(),f.updated(),false)));assertThrows(IllegalStateException.class,()->PhoneGrounding.refresh(catalog,next,data(next),p));
    }
    @Test void singleItemUsesSameAuthorizationAndCannotBeDeselected(){var selection=PhoneHelp.select(catalog,view,data(view),"diary",0);var p=PhoneGrounding.single(catalog,view,data(view),"질문",selection);assertTrue(p.single());assertEquals(PhoneHelp.result(selection),p.result());assertEquals(p,PhoneGrounding.refresh(catalog,view,data(view),p));assertThrows(IllegalArgumentException.class,()->p.toggle(selection.key()));}
    @Test void excludedMaterialChangesDoNotInvalidateConfirmedIncludedMaterial(){var v=facts(List.of(PhoneReaderLinksTest.fact("found_fact",2,false),PhoneReaderLinksTest.fact("other",3,false)));var p=search(v,"diary","공개").toggle("P:other").sent();var changed=facts(List.of(PhoneReaderLinksTest.fact("found_fact",2,false),PhoneReaderLinksTest.fact("other",4,false)));assertEquals(p.scope(),PhoneGrounding.refresh(catalog,changed,data(changed),p).scope());}
    @Test void sharedRevisionChangesRejectEvenWhenSelectedPublicLineIsUnchanged(){var p=search(view,"story","공유");var x=view.exploration();var story=new ExplorationStore.Story(x.story().stage(),x.story().title(),x.story().explorationOpen(),x.story().revision()+1);var changed=new QuestJournal.View(view.states(),view.journal(),new io.github.jesterportfolio.hearth.exploration.ExplorationView(x.registered(),x.regions(),x.places(),x.facts(),x.events(),x.members(),story,x.conditions(),x.nextGoal(),x.blockedQuests(),x.branches()));assertEquals(data(view),data(changed));assertThrows(IllegalStateException.class,()->PhoneGrounding.refresh(catalog,changed,data(changed),p));}
    @Test void plansAreImmutableAndRejectInvalidOrForeignSourceSets(){String before=view.toString();var p=search(view,"diary","공개");assertThrows(UnsupportedOperationException.class,()->p.materials().clear());assertThrows(UnsupportedOperationException.class,()->p.included().clear());assertThrows(UnsupportedOperationException.class,()->p.scope().items().clear());assertEquals(before,view.toString());assertThrows(IllegalArgumentException.class,()->new PhoneGrounding.Plan("map","질문",false,p.materials(),p.included()));assertThrows(IllegalArgumentException.class,()->new PhoneGrounding.Plan("diary","질문",false,p.materials(),Set.of("foreign")));assertThrows(IllegalArgumentException.class,()->search(view,"none","질문"));}
}
