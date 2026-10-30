package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.phone.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.exploration.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PhoneLibraryTest {
    static QuestJournal.View view(){
        var e=ExplorationView.empty();var fact=new ExplorationStore.Fact("private_id","PERSON","확인한 인물 정보","요약","상세😀".repeat(300),2,Instant.EPOCH,Instant.ofEpochSecond(5),false);
        var shared=new ExplorationStore.Fact("shared_id","STORY","공유 사건","공유 요약","공유 상세",1,Instant.EPOCH,Instant.EPOCH,true);
        var events=List.of(new ExplorationStore.Event("private_id",1,"PERSON","과거 정보","이전 요약","이전 상세",Instant.EPOCH,false),new ExplorationStore.Event("private_id",2,"PERSON","새 정보","새 요약","새 상세",Instant.ofEpochSecond(5),false));
        var members=List.of(new CommunityBoundary.Member(UUID.randomUUID(),"오프라인",false,false),new CommunityBoundary.Member(UUID.randomUUID(),"나",true,true));
        var x=new ExplorationView(true,List.of(new ExplorationView.Region("region_id","공개 지역","일반 개방")),List.of(),List.of(fact,shared),events,members,e.story(),new CommunityBoundary.Status(false,List.of("진행 조건 미등록")),null,Set.of(),List.of("확정 선택"));
        return new QuestJournal.View(QuestNavigationTest.states(),new QuestJournalStore.Journal(Set.of("active","done"),new QuestJournalStore.Preferences(true,0),List.of()),x);
    }
    @Test void questTabsKeepOriginalTargetIndexAndDoNotTreatSkipAsCompleted(){
        var c=QuestNavigationTest.catalog();var v=view();
        for(String section:List.of("tracked","reporting","completed")){
            var rows=PhoneLibrary.rows(c,v,section);assertEquals(1,rows.size());var target=rows.getFirst();assertEquals("quests",target.targetSection());String id=PhoneQuestLinks.quests(c,v).get(target.targetIndex()).id();assertEquals(section.equals("tracked")?"active":section.equals("reporting")?"report":"done",id);
        }
        var rows=new HashMap<>(v.states());rows.put("done",new QuestStore.Saved(QuestStore.Phase.SKIPPED,3));assertTrue(PhoneLibrary.rows(c,new QuestJournal.View(rows,v.journal(),v.exploration()),"completed").isEmpty());
    }
    @Test void journalCategoriesAndChronologyPreservePersonalSharedAndOldFacts(){
        var c=QuestNavigationTest.catalog();var v=view();var before=v.toString();
        assertEquals(1,PhoneLibrary.rows(c,v,"PERSON").size());assertEquals(1,PhoneLibrary.rows(c,v,"STORY").size());assertTrue(PhoneLibrary.rows(c,v,"EXPLORATION").isEmpty());
        var events=PhoneLibrary.rows(c,v,"CHRONICLE");assertEquals(2,events.size());assertTrue(events.getFirst().title().contains("새 정보"));assertTrue(events.getLast().lines().contains("이전 상세"));
        var detail=PhoneLibrary.rows(c,v,"PERSON").getFirst();var pages=PhonePages.text(detail.title(),detail.lines());assertTrue(pages.pages().size()>1);assertTrue(String.join("",pages.pages()).contains("상세😀".repeat(300)));assertEquals(before,v.toString());
    }
    @Test void membersExposeOnlyNamesStatusAndSelfWithoutPersonalRecordsOrIds(){
        var c=QuestNavigationTest.catalog();var v=view();var rows=PhoneLibrary.rows(c,v,"members");assertTrue(rows.getFirst().title().contains("온라인"));assertTrue(rows.getFirst().title().contains("(나)"));
        assertTrue(rows.getFirst().lines().getFirst().contains("전체 구성원 명단을 보장하지 않습니다"));
        for(var m:v.exploration().members())assertFalse(rows.toString().contains(m.player().toString()));assertFalse(rows.toString().contains("private_id"));assertFalse(rows.toString().contains("active"));
    }
    @Test void sharedAndConditionsStayUnregisteredWithoutInventedStory(){
        var c=QuestNavigationTest.catalog();var v=view();assertEquals("공유 단계 미등록",PhoneLibrary.rows(c,v,"shared").getFirst().title());assertTrue(PhoneLibrary.rows(c,v,"shared").getFirst().lines().contains("자유 탐험 상태 미등록"));assertEquals("진행 조건 미등록",PhoneLibrary.rows(c,v,"conditions").getFirst().title());assertEquals("확정 선택",PhoneLibrary.rows(c,v,"branches").getFirst().title());
        assertFalse(PhoneLibrary.rows(c,v,"regions").toString().contains("region_id"));assertThrows(IllegalArgumentException.class,()->PhoneLibrary.rows(c,v,"research"));
    }
    @Test void anotherPlayerOrRunCannotInheritThePassedViewsRecords(){
        var c=QuestNavigationTest.catalog();var empty=new QuestJournal.View(QuestNavigationTest.states(),view().journal());
        for(String section:List.of("STORY","PERSON","EXPLORATION","CHRONICLE","members","regions","branches"))assertTrue(PhoneLibrary.rows(c,empty,section).isEmpty());assertFalse(PhoneLibrary.rows(c,view(),"PERSON").isEmpty());
    }
}
