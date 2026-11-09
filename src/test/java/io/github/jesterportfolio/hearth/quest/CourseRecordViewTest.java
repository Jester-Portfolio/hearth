package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.exploration.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.story.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CourseRecordViewTest {
    final QuestCatalog catalog=GeneralNpcTest.catalog();
    final Playthrough plan=placePlan("known_place");final Map<String,String> facts=Map.of("known_place","P:found_fact");
    static Playthrough placePlan(String id){return new Playthrough(List.of(new Playthrough.Step("place",Playthrough.Kind.PLACE,id,"원본 비공개 제목","원본 비공개 위치 99.0")));}
    static QuestJournal.View changed(QuestJournal.View v,List<ExplorationView.Place> places,List<ExplorationStore.Fact> facts,List<ExplorationStore.Event> events){var x=v.exploration();return new QuestJournal.View(v.states(),v.journal(),new ExplorationView(x.registered(),x.regions(),places,facts,events,x.members(),x.story(),x.conditions(),x.nextGoal(),x.blockedQuests(),x.branches(),x.effectStatus()),v.completion());}
    PlaythroughOverview.Board board(Playthrough plan,QuestJournal.View v){var shared=new StoryStore.Snapshot(v.exploration().story(),Set.of("arrival"),Map.of());return PlaythroughOverview.build(plan,catalog,v,shared,SharedStory.Phase.READY);}
    CourseRecordView.Screen open(QuestJournal.View v){var board=board(plan,v);return CourseRecordView.open(plan,catalog,v,facts,board,board,board.rows().getFirst(),1);}
    CourseRecordView.Screen move(QuestJournal.View v,CourseRecordView.Screen shown,CourseRecordView.Action action,CourseRecordView.Link link){return CourseRecordView.navigate(plan,catalog,v,facts,board(plan,v),shown,action,link);}
    static CourseRecordView.Link link(CourseRecordView.Screen screen,String section){return screen.page().links().stream().filter(l->l.target().section().equals(section)).findFirst().orElseThrow();}
    @Test void placePersonalJournalChronicleAndBackUseExistingPublicRecords(){
        var v=PhoneReaderLinksTest.known();String original=v.toString();var place=open(v);assertTrue(place.page().lines().toString().contains("공개 위치"));assertFalse(place.record().toString().contains("99.0"));
        var fact=move(v,place,CourseRecordView.Action.RELATED,link(place,"PERSON"));assertTrue(fact.page().lines().toString().contains("공개 상세 2"));assertEquals(2,fact.path().size());
        var old=move(v,fact,CourseRecordView.Action.RELATED,link(fact,"CHRONICLE"));assertTrue(old.page().lines().toString().contains("이전 상세"));assertFalse(old.page().lines().toString().contains("공개 상세 2"));
        var current=move(v,old,CourseRecordView.Action.RELATED,link(old,"PERSON"));assertEquals(2,current.path().size());assertTrue(current.page().lines().toString().contains("공개 상세 2"));
        assertEquals(place.record(),move(v,current,CourseRecordView.Action.BACK,null).record());assertEquals(original,v.toString());
    }
    @Test void undiscoveredAndFilteredPlacesNeverExposeDefinitionOrMappedFact(){
        var v=PhoneReaderLinksTest.known();var hiddenPlan=placePlan("unknown_marker");var b=board(hiddenPlan,v);var screen=CourseRecordView.open(hiddenPlan,catalog,v,Map.of("unknown_marker","P:found_fact"),b,b,b.rows().getFirst(),1);
        assertTrue(screen.page().lines().toString().contains("위치 비공개"));assertTrue(screen.record().links().stream().allMatch(l->l.target().section().equals("regions")));assertFalse(screen.record().toString().contains("found_fact"));
        var shown=open(v);var filtered=changed(v,List.of(),v.exploration().facts(),v.exploration().events());var next=board(plan,filtered);assertThrows(RuntimeException.class,()->CourseRecordView.open(plan,catalog,filtered,facts,next,shown.board(),shown.row(),1));assertThrows(RuntimeException.class,()->move(filtered,shown,CourseRecordView.Action.REFRESH,null));assertFalse(next.toString().contains("known_place"));
    }
    @Test void sharedAndPersonalFactsWithSameIdStaySeparate(){
        var v=PhoneReaderLinksTest.known();v=changed(v,v.exploration().places(),List.of(PhoneReaderLinksTest.fact("found_fact",2,false),PhoneReaderLinksTest.fact("found_fact",2,true)),List.of(PhoneReaderLinksTest.event("found_fact",1,false),PhoneReaderLinksTest.event("found_fact",1,true)));
        var place=open(v);var personal=move(v,place,CourseRecordView.Action.RELATED,link(place,"PERSON"));assertEquals("P:found_fact",personal.record().target().key());assertTrue(personal.record().links().stream().filter(l->l.target().section().equals("CHRONICLE")).allMatch(l->l.target().key().startsWith("P:")));
        var shared=new CourseRecordView.Link("위조 공유 기록",new CourseRecordView.Target("PERSON","S:found_fact"));var own=v;assertThrows(IllegalStateException.class,()->move(own,place,CourseRecordView.Action.RELATED,shared));
    }
    @Test void changedCurrentFactRejectsOldSelectionAndRefreshLoadsNewVersion(){
        var v=PhoneReaderLinksTest.known();var current=move(v,open(v),CourseRecordView.Action.RELATED,link(open(v),"PERSON"));var changed=changed(v,v.exploration().places(),List.of(PhoneReaderLinksTest.fact("found_fact",3,false)),v.exploration().events());
        assertEquals(current.board(),board(plan,changed));assertThrows(IllegalStateException.class,()->move(changed,current,CourseRecordView.Action.RELATED,link(current,"CHRONICLE")));
        var fresh=move(changed,current,CourseRecordView.Action.REFRESH,null);assertTrue(fresh.page().lines().toString().contains("공개 상세 3"));assertEquals(1,fresh.coursePage());
    }
    @Test void vanishedParentConnectionCannotBeKeptByRefreshingChild(){var v=PhoneReaderLinksTest.known();var root=open(v);var child=move(v,root,CourseRecordView.Action.RELATED,link(root,"PERSON"));assertThrows(IllegalStateException.class,()->CourseRecordView.navigate(plan,catalog,v,Map.of(),board(plan,v),child,CourseRecordView.Action.REFRESH,null));var parent=CourseRecordView.navigate(plan,catalog,v,Map.of(),board(plan,v),child,CourseRecordView.Action.BACK,null);assertTrue(parent.record().links().stream().noneMatch(l->l.target().section().equals("PERSON")));}
    @Test void publicRegionLinksArePagedWithoutLossOrDuplicateAndCyclesCollapse(){
        var v=PhoneReaderLinksTest.known();var places=new ArrayList<ExplorationView.Place>();for(int i=0;i<20;i++)places.add(new ExplorationView.Place(i==0?"known_place":"site_"+i,"공개 장소 "+i,"공개 지역","시설","공개 탐색 구역","미발견",false,false,false));v=changed(v,places,List.of(),List.of());var root=open(v);var region=move(v,root,CourseRecordView.Action.RELATED,link(root,"regions"));assertEquals(4,region.page().pages());var seen=new HashSet<CourseRecordView.Target>();var cursor=region;
        while(true){assertTrue(cursor.page().links().size()<=6);for(var l:cursor.page().links())assertTrue(seen.add(l.target()));assertTrue(cursor.page().book(cursor.record().title()).pages().size()+5<100);if(cursor.page().number()==cursor.page().pages())break;cursor=move(v,cursor,CourseRecordView.Action.NEXT,null);}assertEquals(20,seen.size());var last=cursor;var own=v;assertThrows(RuntimeException.class,()->move(own,last,CourseRecordView.Action.NEXT,null));
        var returned=move(v,region,CourseRecordView.Action.RELATED,region.page().links().getFirst());assertEquals(1,returned.path().size());assertEquals(root.record().target(),returned.record().target());
    }
    @Test void longUnicodeRecordsRetainEveryPageWithinBookBudget(){
        var v=PhoneReaderLinksTest.known();String detail="조사🙂".repeat(700)+"마지막 기록";var fact=new ExplorationStore.Fact("found_fact","PERSON","긴 기록","요약",detail,2,Instant.EPOCH,Instant.EPOCH,false);v=changed(v,v.exploration().places(),List.of(fact),v.exploration().events());var root=open(v);var source=move(v,root,CourseRecordView.Action.RELATED,link(root,"PERSON"));assertTrue(String.join("",source.record().text()).contains(detail));assertTrue(source.page().pages()>1);
        for(int n=1;n<=source.record().pages();n++){var page=source.record().page(n);assertTrue(page.book(source.record().title()).pages().size()+5<100);String text=String.join("",page.lines());for(int i=0;i<text.length();i++){char c=text.charAt(i);if(Character.isHighSurrogate(c))assertTrue(i+1<text.length()&&Character.isLowSurrogate(text.charAt(++i)));else assertFalse(Character.isLowSurrogate(c));}}
    }
    @Test void sharedHistoryOpensCurrentStatusConditionsAndFinalizedBranches(){
        var v=PhoneReaderLinksTest.known();var x=v.exploration();v=new QuestJournal.View(v.states(),v.journal(),new ExplorationView(x.registered(),x.regions(),x.places(),x.facts(),x.events(),x.members(),new ExplorationStore.Story("open","현재 공유 단계",true,3),new CommunityBoundary.Status(false,List.of("현재 접속자 조건")),"현재 공개 목표",Set.of(),List.of("공개 확정 분기")));
        var sharedPlan=new Playthrough(List.of(new Playthrough.Step("past",Playthrough.Kind.STAGE,"arrival","과거 단계","과거 비공개 설명")));var b=board(sharedPlan,v);var root=CourseRecordView.open(sharedPlan,catalog,v,Map.of(),b,b,b.rows().getFirst(),1);
        assertTrue(root.page().lines().toString().contains("현재 공유 단계"));assertFalse(root.record().toString().contains("과거 비공개 설명"));assertTrue(root.page().lines().toString().contains("당시 상태를 재현하지 않습니다"));
        var conditions=CourseRecordView.navigate(sharedPlan,catalog,v,Map.of(),b,root,CourseRecordView.Action.RELATED,link(root,"conditions"));assertTrue(conditions.page().lines().toString().contains("현재 접속자 조건"));var branches=CourseRecordView.navigate(sharedPlan,catalog,v,Map.of(),b,conditions,CourseRecordView.Action.RELATED,link(conditions,"branches"));assertTrue(branches.page().lines().toString().contains("공개 확정 분기"));assertTrue(branches.record().entries().stream().allMatch(e->e.targetSection()==null));
    }
    @Test void boardHoldChangedPublicPlaceAndForgedPathRejectOldRequests(){
        var v=PhoneReaderLinksTest.known();var screen=open(v);var hold=PlaythroughOverview.build(plan,catalog,v,new StoryStore.Snapshot(v.exploration().story(),Set.of(),Map.of()),SharedStory.Phase.HOLD);assertThrows(IllegalStateException.class,()->CourseRecordView.navigate(plan,catalog,v,facts,hold,screen,CourseRecordView.Action.RELATED,link(screen,"PERSON")));
        var forged=new CourseRecordView.Screen(screen.board(),screen.row(),1,List.of(new CourseRecordView.Target("PERSON","P:found_fact")),screen.record(),screen.page());assertThrows(IllegalStateException.class,()->move(v,forged,CourseRecordView.Action.REFRESH,null));
        var ownOnly=changed(v,List.of(v.exploration().places().getFirst()),v.exploration().facts(),v.exploration().events());assertThrows(IllegalStateException.class,()->CourseRecordView.open(plan,catalog,ownOnly,facts,board(plan,ownOnly),screen.board(),new PlaythroughOverview.Row("fake",PlaythroughOverview.Kind.EXPLORATION,"위조","발견",false,true,false,null,-1,List.of()),1));
    }
    @Test void refreshedRecordReturnsToCurrentPageOfOriginalItem(){
        var v=PhoneReaderLinksTest.known();var steps=new ArrayList<Playthrough.Step>();var places=new ArrayList<ExplorationView.Place>();for(int i=0;i<7;i++){String id=i==6?"known_place":"old_"+i;steps.add(new Playthrough.Step("step_"+i,Playthrough.Kind.PLACE,id,"원본","원본"));places.add(new ExplorationView.Place(id,"공개 장소 "+i,"공개 지역","시설","공개 위치","발견",true,true,false));}var multi=new Playthrough(steps);v=changed(v,places,v.exploration().facts(),v.exploration().events());var b=board(multi,v);var screen=CourseRecordView.open(multi,catalog,v,facts,b,b,b.rows().getLast(),2);var filtered=changed(v,List.of(places.getLast()),v.exploration().facts(),v.exploration().events());var fresh=CourseRecordView.navigate(multi,catalog,filtered,facts,board(multi,filtered),screen,CourseRecordView.Action.REFRESH,null);assertEquals("step_6",fresh.row().key());assertEquals(1,fresh.coursePage());
    }
    @Test void immutableSourcesAndInvalidPageOrTargetNeverMutateOriginal(){var v=PhoneReaderLinksTest.known();String original=v.toString();var screen=open(v);assertThrows(UnsupportedOperationException.class,()->screen.path().clear());assertThrows(UnsupportedOperationException.class,()->screen.record().links().clear());assertThrows(UnsupportedOperationException.class,()->screen.record().entries().clear());assertThrows(UnsupportedOperationException.class,()->screen.page().lines().clear());assertThrows(IllegalArgumentException.class,()->screen.record().page(0));assertThrows(IllegalArgumentException.class,()->new CourseRecordView.Target("unknown","private"));assertThrows(IllegalArgumentException.class,()->new CourseRecordView.Target("shared","future_stage"));assertThrows(RuntimeException.class,()->move(v,screen,CourseRecordView.Action.BACK,null));assertEquals(original,v.toString());}
}
