package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.exploration.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ExplorationTest {
    static Map<String,Object> region(String id,boolean published){return Map.of("id",id,"name",id.equals("public_area")?"공개 지역":"개인 발견 지역","public",published);}
    static Map<String,Object> fact(){return Map.of("id","found_fact","category","EXPLORATION","title","공개 발견 기록","summary","요약","detail","공개 합성 상세","version",1,"important",true);}
    static Map<String,Object> place(String id,String visibility,String mode,int x){
        var p=new HashMap<String,Object>();p.put("id",id);p.put("marker-key","marker_"+id);p.put("name","표시 "+id);p.put("region","private_area");p.put("category","조사");p.put("visibility",visibility);p.put("discovery",mode);p.put("hub",id.equals("public_place"));
        p.put("bounds",Map.of("min-x",x,"max-x",x+2,"min-y",64,"max-y",67,"min-z",x,"max-z",x+2));p.put("location",Map.of("kind","EXACT","x",x+1,"y",64,"z",x+1));
        if(mode.equals("INTERACTION"))p.put("interaction","npc:survey_guide");p.put("journal","found_fact");return p;
    }
    static Map<String,Object> root(){
        var hint=place("hint_place","QUESTION","ENTRY",98);hint.put("unknown-location",Map.of("kind","AREA","min-x",80,"max-x",110,"min-y",60,"max-y",80,"min-z",80,"max-z",110));
        return new HashMap<>(Map.of("format",1,"regions",List.of(region("public_area",true),region("private_area",false)),"journal",List.of(fact()),"places",List.of(place("public_place","PUBLIC","ENTRY",1),place("hidden_place","HIDDEN","ENTRY",20),hint,place("guide_place","HIDDEN","INTERACTION",4))));
    }
    static ExplorationContent content(){return ExplorationContent.parse(root(),QuestCatalogTest.catalog());}
    static ExplorationStore.Data empty(){return new ExplorationStore.Data(Map.of(),List.of(),List.of(),new ExplorationStore.Story(null,null,null,-1));}
    @Test void hiddenCoordinatesAndNamesNeverEnterPublicDTO(){
        var c=content();var v=ExplorationView.build(c,empty(),List.of(),new CommunityBoundary.Status(false,List.of("미등록")));
        assertEquals(1,v.regions().size());assertEquals(2,v.places().size());assertFalse(v.toString().contains("hidden_place"));assertFalse(v.toString().contains("99.0"));assertFalse(v.toString().contains("표시 hint_place"));
        var hint=v.place("marker_hint_place").orElseThrow();assertEquals("?",hint.name());assertTrue(hint.location().contains("80.0"));assertEquals("미공개 지역",hint.region());
        assertFalse(v.place("public_place").orElseThrow().visited());assertFalse(v.place("public_place").orElseThrow().hubUnlocked());
        var discovered=new ExplorationStore.PlaceState("hidden_place",java.time.Instant.EPOCH,java.time.Instant.EPOCH,false,1);
        var own=ExplorationView.build(c,new ExplorationStore.Data(Map.of("hidden_place",discovered),List.of(),List.of(),empty().story()),List.of(),new CommunityBoundary.Status(false,List.of("미등록")));
        assertTrue(own.place("hidden_place").orElseThrow().location().contains("21.0"));assertEquals(2,own.regions().size());
    }
    @Test void invalidReferencesAndDuplicateContentAreRejected(){
        var c=QuestCatalogTest.catalog();var r=root();r.put("places",List.of(place("dup","PUBLIC","ENTRY",1),place("dup","PUBLIC","ENTRY",3)));assertThrows(IllegalArgumentException.class,()->ExplorationContent.parse(r,c));
        var p=place("one","PUBLIC","ENTRY",1);p.put("region","missing");r.put("places",List.of(p));assertThrows(IllegalArgumentException.class,()->ExplorationContent.parse(r,c));
        p=place("one","PUBLIC","ENTRY",1);p.put("journal","missing");r.put("places",List.of(p));assertThrows(IllegalArgumentException.class,()->ExplorationContent.parse(r,c));
    }
    @Test void rosterAndConditionsUseOnlyVerifiedSameRunOnlineTargets(){
        var access=new ServerProgressAccess();UUID run=UUID.randomUUID(),other=UUID.randomUUID(),p1=UUID.randomUUID(),p2=UUID.randomUUID(),p3=UUID.randomUUID();var ctx=access.forVerifiedServerLink(run,p1);
        var refs=List.of(new CommunityBoundary.Ref(run,p2,"둘",false),new CommunityBoundary.Ref(other,p3,"타 진행",true),new CommunityBoundary.Ref(run,p1,"하나",true));
        var received=new HashSet<UUID>();var boundary=new CommunityBoundary(c->refs,(c,ids)->{received.addAll(ids);return Optional.of(new CommunityBoundary.Condition("test_stage",Map.of(p1,true)));});
        var members=boundary.members(ctx);assertEquals(2,members.size());assertTrue(members.getFirst().self());assertTrue(members.getFirst().online());
        assertFalse(boundary.status(ctx,empty().story(),members).registered());assertTrue(received.isEmpty());
        var status=boundary.status(ctx,new ExplorationStore.Story("test_stage","공개 단계",true,0),members);assertTrue(status.registered());assertEquals(Set.of(p1),received);assertEquals(List.of("하나: 조건 충족"),status.lines());assertFalse(status.toString().contains("quest"));
    }
    static final class Store implements ExplorationStore {
        final Map<String,Map<String,PlaceState>> rows=new HashMap<>();int writes;boolean fail;
        String key(ServerProgressAccess.Context c){return c.storyRunId()+"/"+c.playerId();}
        public CompletionStage<Data> readExploration(ServerProgressAccess.Context c){return CompletableFuture.completedFuture(new Data(rows.getOrDefault(key(c),Map.of()),List.of(),List.of(),empty().story()));}
        public CompletionStage<Boolean> recordPlace(ServerProgressAccess.Context c,ExplorationContent.Place p,ExplorationContent.Fact f,boolean discover,boolean visit){
            writes++;if(fail)return CompletableFuture.failedFuture(new StorageFailure("discovery","23514"));var data=rows.computeIfAbsent(key(c),x->new HashMap<>());var old=data.get(p.id());boolean first=discover&&(old==null||old.discovered()==null);
            data.put(p.id(),new PlaceState(p.id(),visit?java.time.Instant.EPOCH:old==null?null:old.visited(),discover?java.time.Instant.EPOCH:old==null?null:old.discovered(),visit&&p.hub(),old==null?1:old.revision()+1));return CompletableFuture.completedFuture(first);
        }
        public CompletionStage<Boolean> recordFact(ServerProgressAccess.Context c,ExplorationContent.Fact f,boolean shared){return CompletableFuture.completedFuture(false);}
        public CompletionStage<Void> saveStory(ServerProgressAccess.Context c,Story s,String stage,String title,Boolean open){return CompletableFuture.completedFuture(null);}
    }
    static QuestJournal journal(PersonalQuestTest.Fixture f,QuestBatchTest.JournalStore js,Store store){var journal=new QuestJournal(js,f.quests,QuestCatalogTest.catalog(),f.queue::add,()->{});journal.exploration(store,content(),new CommunityBoundary(c->List.of(),(c,online)->Optional.empty()));return journal;}
    @Test void discoveriesWaitForBusyReadAndCoalesceDuplicateEntries(){
        var f=new PersonalQuestTest.Fixture(true);var js=new QuestBatchTest.JournalStore();var store=new Store();var journal=journal(f,js,store);var ctx=f.ctx(f.a,f.p);Object token=new Object();js.delay=true;
        var view=journal.load(ctx,token,()->true);f.pump();var first=journal.enterPlaces(ctx,token,()->true,0,64,0,2,64,2);var duplicate=journal.enterPlaces(ctx,token,()->true,0,64,0,2,64,2);assertEquals(0,store.writes);
        var barrier=journal.barrier(f.a);assertFalse(barrier.toCompletableFuture().isDone());js.delay=false;js.pending.complete(js.get(ctx));f.pump();
        assertTrue(first.toCompletableFuture().isDone());assertTrue(duplicate.toCompletableFuture().isDone());assertEquals(1,store.writes);assertTrue(barrier.toCompletableFuture().isDone());assertTrue(journal.settled(f.a));
        assertTrue(store.rows.get(store.key(ctx)).get("public_place").hub());assertFalse(store.rows.containsKey(store.key(f.ctx(f.b,f.p))));assertFalse(store.rows.containsKey(store.key(f.ctx(f.a,f.p2))));
    }
    @Test void discoveryWaitsConcurrentQuestWriteInsteadOfLosingEntry(){
        var f=new PersonalQuestTest.Fixture(true);var ctx=f.ctx(f.a,f.p);Object token=new Object();var q=f.open(ctx,"survey_marker",token);var js=new QuestBatchTest.JournalStore();var store=new Store();var journal=journal(f,js,store);
        var write=f.quests.accept(q,QuestCatalogTest.catalog().quest("survey_marker"));var discovery=journal.enterPlaces(ctx,token,()->true,0,64,0,2,64,2);f.pump();assertEquals(0,store.writes);assertFalse(discovery.toCompletableFuture().isDone());
        f.commit(0);assertTrue(write.toCompletableFuture().isDone());assertTrue(discovery.toCompletableFuture().isDone());assertDoesNotThrow(()->discovery.toCompletableFuture().join());assertEquals(1,store.writes);
    }
    @Test void interactionRequiresMatchingServerReferenceAndDiscoveryFailureHoldsOnlyOwner(){
        var f=new PersonalQuestTest.Fixture(true);var js=new QuestBatchTest.JournalStore();var store=new Store();var journal=journal(f,js,store);var ctx=f.ctx(f.a,f.p);Object token=new Object();
        journal.interactPlace(ctx,token,()->true,"npc:wrong",5,64,5);journal.interactPlace(ctx,token,()->true,"npc:survey_guide",30,64,30);f.pump();assertEquals(0,store.writes);
        var valid=journal.interactPlace(ctx,token,()->true,"npc:survey_guide",5,64,5);f.pump();assertFalse(valid.toCompletableFuture().isCompletedExceptionally());assertNotNull(store.rows.get(store.key(ctx)).get("guide_place").discovered());
        store.fail=true;var failed=journal.enterPlaces(ctx,token,()->true,0,64,0,21,64,21);f.pump();assertTrue(failed.toCompletableFuture().isCompletedExceptionally());assertFalse(journal.settled(f.a));assertTrue(journal.barrier(f.a).toCompletableFuture().isCompletedExceptionally());assertTrue(journal.settled(f.b));
    }
    @Test void staleDiscoveryAndChangedRosterCannotApplyOldView(){
        var f=new PersonalQuestTest.Fixture(true);var js=new QuestBatchTest.JournalStore();var store=new Store();var ctx=f.ctx(f.a,f.p);var links=new ArrayList<CommunityBoundary.Ref>();
        var journal=new QuestJournal(js,f.quests,QuestCatalogTest.catalog(),f.queue::add,()->{});journal.exploration(store,content(),new CommunityBoundary(c->List.copyOf(links),(c,online)->Optional.empty()));
        var loaded=journal.load(ctx,new Object(),()->true);f.pump();var view=loaded.toCompletableFuture().join();links.add(new CommunityBoundary.Ref(f.a,f.p,"확인 이름",true));assertThrows(IllegalStateException.class,()->journal.requireView(ctx,view));
        js.delay=true;var request=journal.enterPlaces(ctx,new Object(),()->f.valid,0,64,0,2,64,2);f.pump();f.valid=false;js.pending.complete(js.get(ctx));f.pump();assertTrue(request.toCompletableFuture().isCompletedExceptionally());assertEquals(0,store.writes);
    }
    @Test void realMenusUsePublicPlacesDiaryAndSixIndependentSettings(){
        var c=content();var data=ExplorationView.build(c,empty(),List.of(),new CommunityBoundary.Status(false,List.of("진행 조건 미등록")));
        var view=new QuestJournal.View(Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1),"path_marker",new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1)),new QuestJournalStore.Journal(Set.of(),new QuestJournalStore.Preferences(true,-1),List.of()),data);
        var q=QuestCatalogTest.catalog();assertTrue(QuestScreens.screen(q,view,"map",null,0).stream().anyMatch(b->b.action().equals("place")));assertFalse(QuestScreens.screen(q,view,"map",null,0).toString().contains("hidden_place"));
        assertEquals(4,QuestScreens.screen(q,view,"diary",null,0).stream().filter(b->!b.action().equals("home")).count());assertEquals(3,QuestScreens.screen(q,view,"community",null,0).stream().filter(b->!b.action().equals("home")).count());
        for(String flag:List.of("hud","minimap","quest_marker","npc_marker","player_marker","place_marker"))assertTrue(view.journal().preferences().flag(flag));
        assertEquals(5,QuestScreens.screen(q,view,"settings",null,0).stream().filter(b->b.action().startsWith("setting:")).count());
    }
}
