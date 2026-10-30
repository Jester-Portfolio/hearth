package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.phone.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.exploration.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PhoneHelpTest {
    final QuestCatalog catalog=GeneralNpcTest.catalog();final QuestJournal.View view=PhoneReaderLinksTest.known();
    GameInformation data(QuestJournal.View v){return GameInformation.build(catalog,v);}
    @Test void questPlaceAndDiaryEachSupplyOneSourceWithoutOtherCategoriesOrInternalKeys(){
        var data=data(view);for(String section:List.of("quests","map","diary")){
            var choice=PhoneHelp.select(catalog,view,data,section,0);var result=PhoneHelp.result(choice);assertEquals(1,result.sources().size());assertTrue(result.context().contains(choice.source().title()));assertTrue(result.context().codePointCount(0,result.context().length())<2400);assertFalse(result.context().contains("known_place"));assertFalse(result.context().contains("found_fact"));assertEquals(1,choice.selected().quests().size()+choice.selected().places().size()+choice.selected().diary().size());
        }
    }
    @Test void librarySortOrderIsResolvedToCorrectApprovedDiaryIndex(){
        var before=view.exploration();var inserted=PhoneReaderLinksTest.view(List.of(PhoneReaderLinksTest.fact("other",3,false),PhoneReaderLinksTest.fact("found_fact",2,false)),before.events(),before.places());var source=PhoneReaderLinks.resolve(catalog,inserted,"PERSON","P:found_fact");var choice=PhoneHelp.record(catalog,inserted,data(inserted),source);assertEquals("P:found_fact",choice.key());assertEquals(1,choice.source().index());assertEquals(PhoneHelp.select(catalog,inserted,data(inserted),"diary",1),choice);
    }
    @Test void reorderingDoesNotSwitchSelectionAndContentChangeOrRemovalRejects(){
        var selected=PhoneHelp.select(catalog,view,data(view),"diary",0);var x=view.exploration();var reordered=PhoneReaderLinksTest.view(List.of(PhoneReaderLinksTest.fact("other",3,false),PhoneReaderLinksTest.fact("found_fact",2,false)),x.events(),x.places());var current=PhoneHelp.refresh(catalog,reordered,data(reordered),selected);assertEquals(1,current.source().index());assertTrue(PhoneHelp.same(selected,current));
        var changed=PhoneReaderLinksTest.view(List.of(PhoneReaderLinksTest.fact("found_fact",3,false)),x.events(),x.places());assertThrows(IllegalStateException.class,()->PhoneHelp.refresh(catalog,changed,data(changed),selected));var empty=PhoneReaderLinksTest.view(List.of(),List.of(),List.of());assertThrows(IllegalStateException.class,()->PhoneHelp.refresh(catalog,empty,data(empty),selected));
    }
    @Test void unknownPlaceKeepsOnlyQuestionMarkAndApprovedLocation(){
        var unknown=PhoneHelp.select(catalog,view,data(view),"map",1);assertEquals("?",unknown.source().title());assertTrue(PhoneHelp.result(unknown).context().contains("위치 비공개"));assertFalse(PhoneHelp.result(unknown).context().contains("unknown_marker"));assertThrows(IllegalArgumentException.class,()->PhoneHelp.select(catalog,view,data(view),"story",0));
    }
    @Test void sharedAndPersonalSameRecordIdDoNotShareSelectedContext(){
        var x=view.exploration();var both=PhoneReaderLinksTest.view(List.of(PhoneReaderLinksTest.fact("found_fact",2,false),PhoneReaderLinksTest.fact("found_fact",2,true)),x.events(),x.places());var personal=PhoneHelp.select(catalog,both,data(both),"diary",0);var shared=PhoneHelp.select(catalog,both,data(both),"diary",1);assertFalse(PhoneHelp.same(personal,shared));assertTrue(PhoneHelp.result(shared).context().contains("공동체"));assertFalse(PhoneHelp.result(shared).context().contains("P:"));
    }
    @Test void otherPlayerAndRunCannotReusePrivateSelectionOrLockedQuest(){
        var selected=PhoneHelp.select(catalog,view,data(view),"diary",0);var other=PhoneReaderLinksTest.view(List.of(),List.of(),List.of());assertThrows(IllegalStateException.class,()->PhoneHelp.refresh(catalog,other,data(other),selected));
        var quest=PhoneHelp.select(catalog,view,data(view),"quests",0);var x=view.exploration();var blocked=new QuestJournal.View(view.states(),view.journal(),new ExplorationView(true,x.regions(),x.places(),x.facts(),x.events(),x.members(),x.story(),x.conditions(),null,Set.of("survey_marker"),List.of()));assertThrows(IllegalStateException.class,()->PhoneHelp.refresh(catalog,blocked,data(blocked),quest));
    }
    @Test void longUnicodeContextIsBoundedAndFullPayloadChangeStillInvalidates(){
        var x=view.exploration();var f=x.facts().getFirst();var longFact=new ExplorationStore.Fact(f.id(),f.category(),f.title(),f.summary(),"안내😀".repeat(1000),f.version(),f.first(),f.updated(),false);var v=PhoneReaderLinksTest.view(List.of(longFact),x.events(),x.places());var selected=PhoneHelp.select(catalog,v,data(v),"diary",0);assertTrue(selected.source().text().codePointCount(0,selected.source().text().length())<=400);assertFalse(Character.isHighSurrogate(selected.source().text().charAt(selected.source().text().length()-1)));
        var changed=new ExplorationStore.Fact(f.id(),f.category(),f.title(),f.summary(),longFact.detail()+"새 정보",f.version()+1,f.first(),f.updated(),false);var latest=PhoneReaderLinksTest.view(List.of(changed),x.events(),x.places());assertThrows(IllegalStateException.class,()->PhoneHelp.refresh(catalog,latest,data(latest),selected));
    }
    @Test void selectingAndReturningAreReadOnlyAndHistoryCannotBePassedAsCurrentRecord(){
        String before=view.toString();var source=PhoneReaderLinks.resolve(catalog,view,"PERSON","P:found_fact");var selection=PhoneHelp.record(catalog,view,data(view),source);assertEquals(selection,PhoneHelp.refresh(catalog,view,data(view),selection));assertEquals(before,view.toString());assertThrows(IllegalArgumentException.class,()->PhoneHelp.record(catalog,view,data(view),PhoneReaderLinks.resolve(catalog,view,"CHRONICLE","P:found_fact:1")));assertTrue(PhoneHelp.question().length()<256);
    }
}
