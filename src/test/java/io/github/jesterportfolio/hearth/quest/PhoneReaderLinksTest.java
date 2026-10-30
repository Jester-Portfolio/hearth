package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.phone.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.exploration.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PhoneReaderLinksTest {
    static ExplorationStore.Fact fact(String id,int version,boolean shared){return new ExplorationStore.Fact(id,"PERSON","같은 제목","같은 요약","공개 상세 "+version,version,Instant.EPOCH,Instant.ofEpochSecond(version),shared);}
    static ExplorationStore.Event event(String id,int version,boolean shared){return new ExplorationStore.Event(id,version,"PERSON","과거 기록 "+version,"이전 요약","이전 상세",Instant.ofEpochSecond(version),shared);}
    static QuestJournal.View view(List<ExplorationStore.Fact> facts,List<ExplorationStore.Event> events,List<ExplorationView.Place> places){
        var c=GeneralNpcTest.catalog();var v=GeneralNpcTest.view(c,false,false,true,Set.of());var x=v.exploration();return new QuestJournal.View(v.states(),v.journal(),new ExplorationView(true,List.of(new ExplorationView.Region("public_region","공개 지역","일반 개방")),places,facts,events,x.members(),x.story(),x.conditions(),null,Set.of(),List.of()));
    }
    static QuestJournal.View known(){return view(List.of(fact("found_fact",2,false)),List.of(event("found_fact",1,false),event("found_fact",2,false)),List.of(new ExplorationView.Place("known_place","발견 장소","공개 지역","거점","공개 위치","발견",true,true,true),new ExplorationView.Place("unknown_marker","?","공개 지역","미발견 장소","위치 비공개","미발견",false,false,false)));}
    @Test void personalFactConnectsOnlyAllowedNpcFirstChronicleAndDiscoveredPlace(){
        var c=GeneralNpcTest.catalog();var v=known();var source=PhoneReaderLinks.library(c,v,"PERSON",0);var links=PhoneReaderLinks.links(c,v,Map.of("known_place","P:found_fact"),source);
        assertTrue(links.stream().anyMatch(l->l.kind()==PhoneReaderLinks.Kind.NPC&&l.key().equals("found_fact:witness")));assertTrue(links.stream().anyMatch(l->"CHRONICLE".equals(l.section())&&l.key().equals("P:found_fact:1")));assertTrue(links.stream().anyMatch(l->l.kind()==PhoneReaderLinks.Kind.PLACE&&l.key().equals("known_place")));
        for(var link:links)PhoneReaderLinks.require(c,v,Map.of("known_place","P:found_fact"),source,link);
    }
    @Test void oldChronicleConnectsCurrentFactWithoutOverwritingHistoricalContent(){
        var c=GeneralNpcTest.catalog();var v=known();var before=v.toString();var source=PhoneReaderLinks.resolve(c,v,"CHRONICLE","P:found_fact:1");assertTrue(source.entry().lines().contains("이전 상세"));var target=PhoneReaderLinks.links(c,v,Map.of(),source).getFirst();assertEquals("P:found_fact",target.key());assertEquals("PERSON",target.section());assertTrue(PhoneReaderLinks.resolve(c,v,target.section(),target.key()).entry().lines().contains("공개 상세 2"));assertEquals(before,v.toString());
    }
    @Test void mapAndRegionOnlyLinkVisibleDtoAndNeverRevealHiddenPlaceOrCoordinates(){
        var c=GeneralNpcTest.catalog();var v=known();var links=PhoneReaderLinks.links(c,v,Map.of("known_place","P:found_fact","unknown_marker","P:found_fact"),PhoneReaderLinks.place(v,"unknown_marker"));assertTrue(links.stream().allMatch(l->"regions".equals(l.section())));assertFalse(links.toString().contains("found_fact"));
        var known=PhoneReaderLinks.links(c,v,Map.of("known_place","P:found_fact"),PhoneReaderLinks.place(v,"known_place"));assertTrue(known.stream().anyMatch(l->"PERSON".equals(l.section())));
        var region=PhoneReaderLinks.links(c,v,Map.of(),PhoneReaderLinks.library(c,v,"regions",0));assertEquals(2,region.size());assertTrue(region.stream().anyMatch(l->l.label().startsWith("?")));assertThrows(IllegalArgumentException.class,()->PhoneReaderLinks.place(v,"hidden_secret"));assertFalse(region.toString().contains("hidden_secret"));
    }
    @Test void sharedAndPersonalFactsWithSameIdDoNotBorrowContactsOrHistory(){
        var c=GeneralNpcTest.catalog();var v=view(List.of(fact("found_fact",2,false),fact("found_fact",2,true)),List.of(event("found_fact",1,false),event("found_fact",1,true)),List.of());
        var shared=PhoneReaderLinks.resolve(c,v,"PERSON","S:found_fact");var links=PhoneReaderLinks.links(c,v,Map.of(),shared);assertTrue(links.stream().noneMatch(l->l.kind()==PhoneReaderLinks.Kind.NPC));assertEquals("S:found_fact:1",links.getFirst().key());assertEquals("S:found_fact",PhoneReaderLinks.links(c,v,Map.of(),PhoneReaderLinks.resolve(c,v,"CHRONICLE","S:found_fact:1")).getFirst().key());
    }
    @Test void reorderedRowsResolveByIdentityWhileUpdatedFactsRejectOldSource(){
        var c=GeneralNpcTest.catalog();var v=known();var source=PhoneReaderLinks.resolve(c,v,"PERSON","P:found_fact");var inserted=view(List.of(fact("new_fact",9,false),fact("found_fact",2,false)),v.exploration().events(),v.exploration().places());assertEquals(source,PhoneReaderLinks.resolve(c,inserted,"PERSON",source.key()));assertEquals(1,PhoneReaderLinks.index(c,inserted,"PERSON",source.key()));
        var changed=view(List.of(fact("found_fact",3,false)),v.exploration().events(),List.of());assertThrows(IllegalStateException.class,()->PhoneReaderLinks.links(c,changed,Map.of(),source));
    }
    @Test void forgedNpcAndDifferentPlayerOrRunSnapshotsRejectLinks(){
        var c=GeneralNpcTest.catalog();var v=known();var source=PhoneReaderLinks.library(c,v,"PERSON",0);assertThrows(IllegalStateException.class,()->PhoneReaderLinks.require(c,v,Map.of(),source,new PhoneReaderLinks.Link("다른 NPC",PhoneReaderLinks.Kind.NPC,null,"found_fact:survey_guide")));
        var other=view(List.of(),List.of(),List.of());assertThrows(IllegalArgumentException.class,()->PhoneReaderLinks.links(c,other,Map.of(),source));assertThrows(IllegalArgumentException.class,()->PhoneReaderLinks.resolve(c,other,"CHRONICLE","P:found_fact:1"));
    }
    @Test void lockedQuestionAndRemovedDiscoveryCannotRetainOldNavigation(){
        var c=GeneralNpcTest.catalog();var v=known();var source=PhoneReaderLinks.library(c,v,"PERSON",0);var npc=PhoneReaderLinks.links(c,v,Map.of(),source).stream().filter(l->l.kind()==PhoneReaderLinks.Kind.NPC).findFirst().orElseThrow();var x=v.exploration();var locked=new QuestJournal.View(v.states(),v.journal(),new ExplorationView(true,x.regions(),x.places(),x.facts(),x.events(),x.members(),x.story(),x.conditions(),null,Set.of("survey_marker"),List.of()));assertThrows(IllegalStateException.class,()->PhoneReaderLinks.require(c,locked,Map.of(),source,npc));
        var map=PhoneReaderLinks.place(v,"known_place");assertThrows(IllegalArgumentException.class,()->PhoneReaderLinks.links(c,view(v.exploration().facts(),v.exploration().events(),List.of()),Map.of(),map));
    }
    @Test void duplicateRegionNamesAreNotGuessedAndEntryDtoContainsNoInternalRegionId(){
        var c=GeneralNpcTest.catalog();var v=known();var x=v.exploration();var ambiguous=new QuestJournal.View(v.states(),v.journal(),new ExplorationView(true,List.of(new ExplorationView.Region("one","공개 지역","일반 개방"),new ExplorationView.Region("two","공개 지역","일반 개방")),x.places(),x.facts(),x.events(),x.members(),x.story(),x.conditions(),null,Set.of(),List.of()));assertTrue(PhoneReaderLinks.links(c,ambiguous,Map.of(),PhoneReaderLinks.place(ambiguous,"known_place")).isEmpty());assertFalse(PhoneLibrary.rows(c,v,"regions").toString().contains("public_region"));
    }
    @Test void journalProjectsPlaceFactOnlyAfterDiscoveryAndPrivateRecordExist(){
        var f=new NpcTopicsTest.Fixture();var work=f.journal.load(f.ctx,f.token,()->true);f.f.pump();var base=work.toCompletableFuture().join();var x=base.exploration();var fact=fact("found_fact",1,false);var place=new ExplorationView.Place("public_place","발견 장소","공개 지역","장소","공개 위치","발견",true,true,true);
        var v=new QuestJournal.View(base.states(),base.journal(),new ExplorationView(true,x.regions(),List.of(place),List.of(fact),x.events(),x.members(),x.story(),x.conditions(),null,x.blockedQuests(),List.of()));assertEquals(Map.of("public_place","P:found_fact"),f.journal.placeFacts(f.ctx,v));
        var missing=new QuestJournal.View(base.states(),base.journal(),new ExplorationView(true,x.regions(),List.of(place),List.of(),x.events(),x.members(),x.story(),x.conditions(),null,x.blockedQuests(),List.of()));assertTrue(f.journal.placeFacts(f.ctx,missing).isEmpty());
        var unseen=new ExplorationView.Place("public_place","공개", "공개 지역","장소","위치 비공개","미발견",false,false,false);var u=new QuestJournal.View(base.states(),base.journal(),new ExplorationView(true,x.regions(),List.of(unseen),List.of(fact),x.events(),x.members(),x.story(),x.conditions(),null,x.blockedQuests(),List.of()));assertTrue(f.journal.placeFacts(f.ctx,u).isEmpty());
    }
    @Test void placeProjectionRejectsPendingHoldAndClosedInsteadOfReturningEmpty(){
        var f=new NpcTopicsTest.Fixture();var initial=f.journal.load(f.ctx,f.token,()->true);f.f.pump();var view=initial.toCompletableFuture().join();f.ui.delay=true;var pending=f.journal.load(f.ctx,f.token,()->true);f.f.pump();assertFalse(pending.toCompletableFuture().isDone());assertThrows(IllegalStateException.class,()->f.journal.placeFacts(f.ctx,view));f.ui.pending.complete(f.ui.get(f.ctx));f.f.pump();assertFalse(pending.toCompletableFuture().isCompletedExceptionally());
        f.ui.delay=false;f.ui.fail=true;var failed=f.journal.hud(f.ctx,f.token,()->true,false);f.f.pump();assertTrue(failed.toCompletableFuture().isCompletedExceptionally());assertThrows(IllegalStateException.class,()->f.journal.placeFacts(f.ctx,view));assertTrue(f.ui.rows.isEmpty());
        var closed=new NpcTopicsTest.Fixture();var before=closed.journal.load(closed.ctx,closed.token,()->true);closed.f.pump();closed.journal.shutdown();assertThrows(IllegalStateException.class,()->closed.journal.placeFacts(closed.ctx,before.toCompletableFuture().join()));
    }
    @Test void manyNpcContactsHaveStableUniqueKeysForSixItemPages(){
        var npcs=new ArrayList<Map<String,Object>>();npcs.add(QuestCatalogTest.npc("survey_guide",3));for(int i=0;i<40;i++)npcs.add(GeneralNpcTest.witness("witness_"+i,10+i*3));var catalog=QuestCatalog.parse(npcs,List.of(QuestCatalogTest.quest("survey_marker","survey_guide")));var v=known();var source=PhoneReaderLinks.library(catalog,v,"PERSON",0);var links=PhoneReaderLinks.links(catalog,v,Map.of(),source);assertEquals(40,links.stream().filter(l->l.kind()==PhoneReaderLinks.Kind.NPC).count());assertEquals(links.size(),new HashSet<>(links).size());
        var seen=new HashSet<PhoneReaderLinks.Link>();for(int i=0;i<links.size();i+=6)for(var link:links.subList(i,Math.min(i+6,links.size())))assertTrue(seen.add(link));assertEquals(new HashSet<>(links),seen);assertEquals(links,PhoneReaderLinks.links(catalog,v,Map.of(),source));
    }

}
