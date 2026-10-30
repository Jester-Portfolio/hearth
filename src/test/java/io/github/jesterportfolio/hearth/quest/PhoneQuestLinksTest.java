package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.phone.PhoneQuestLinks;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.exploration.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PhoneQuestLinksTest {
    static QuestJournal.View view(Set<String> blocked,List<QuestJournalStore.Notice> notices){
        var x=ExplorationView.empty();
        var places=List.of(new ExplorationView.Place("known","발견 장소","공개 지역","장소","공개 위치","발견",true,true,false),new ExplorationView.Place("marker_unknown","?","공개 지역","미발견","위치 비공개","미발견",false,false,false));
        var exploration=new ExplorationView(true,List.of(),places,List.of(),List.of(),List.of(),x.story(),x.conditions(),null,blocked,List.of());
        return new QuestJournal.View(QuestNavigationTest.states(),new QuestJournalStore.Journal(Set.of("active"),new QuestJournalStore.Preferences(true,0),notices),exploration);
    }
    static QuestJournalStore.Notice n(String id,String type,long revision,boolean read,int time){return new QuestJournalStore.Notice(id,revision,"STARTED",Instant.ofEpochSecond(time),read,type);}
    @Test void questLinksMatchPublicListAndDoNotAllowRemoteReport(){
        var v=view(Set.of("active"),List.of());var quests=PhoneQuestLinks.quests(QuestNavigationTest.catalog(),v);
        assertFalse(quests.stream().anyMatch(q->q.id().equals("active")||q.id().equals("unaccepted")));
        var report=quests.stream().filter(q->q.id().equals("report")).findFirst().orElseThrow();assertTrue(report.reporting());assertTrue(report.trackable());
        var done=quests.stream().filter(q->q.id().equals("done")).findFirst().orElseThrow();assertFalse(done.trackable());assertFalse(done.reporting());assertFalse(quests.toString().contains("command"));
    }
    @Test void onlyVisibleOwnNoticesAreSortedAndReadStateIsPreserved(){
        var list=List.of(n("active","QUEST",0,false,1),n("report","QUEST",1,true,3),n("unaccepted","QUEST",0,false,9),n("known","PLACE",0,false,2),n("marker_unknown","PLACE",0,false,10),n("done","SHARED",2,false,11));
        var before=List.copyOf(list);var rows=PhoneQuestLinks.notices(QuestNavigationTest.catalog(),view(Set.of(),list));
        assertEquals(List.of("report","known","active"),rows.stream().map(row->row.source().quest()).toList());assertTrue(rows.getFirst().label().startsWith("[읽음]"));assertEquals(before,list);
        assertTrue(PhoneQuestLinks.notices(QuestNavigationTest.catalog(),view(Set.of(),List.of())).isEmpty());
    }
    @Test void stableTargetSurvivesChangedReadFlagAndFilteredIndex(){
        var source=n("report","QUEST",1,false,3);var old=view(Set.of(),List.of(source));var c=QuestNavigationTest.catalog();int before=PhoneQuestLinks.questIndex(c,old,"report");
        var current=view(Set.of("active"),List.of(n("report","QUEST",1,true,3)));var resolved=PhoneQuestLinks.find(c,current,source);
        assertNotEquals(before,resolved.target());assertEquals("report",PhoneQuestLinks.quests(c,current).get(resolved.target()).id());assertTrue(resolved.source().read());
        assertThrows(IllegalArgumentException.class,()->PhoneQuestLinks.find(c,view(Set.of("report"),List.of(source)),source));
    }
    @Test void blockedNoticeCannotWriteReadStateEvenWhenAccepted(){
        var f=new PersonalQuestTest.Fixture(true);var ctx=f.ctx(f.a,f.p);var q1=QuestCatalogTest.quest("survey_marker","guide");q1.put("requires-rules",true);
        var catalog=QuestCatalog.parse(List.of(QuestCatalogTest.npc("guide",3)),List.of(q1,QuestCatalogTest.quest("path_marker","guide")));
        f.store.rows.put(f.store.key(ctx,"survey_marker"),new QuestStore.Saved(QuestStore.Phase.ACTIVE,0));
        var store=new QuestBatchTest.JournalStore();var state=new QuestJournalStore.Journal(Set.of(),new QuestJournalStore.Preferences(true,0),List.of(n("survey_marker","QUEST",0,false,1)));store.rows.put(store.key(ctx),state);
        var journal=new QuestJournal(store,f.quests,catalog,f.queue::add,()->{});var result=journal.readNotice(ctx,new Object(),()->true,"survey_marker",0);f.pump();
        assertTrue(result.toCompletableFuture().isCompletedExceptionally());assertEquals(state,store.get(ctx));assertTrue(journal.settled(f.a));assertEquals(0,f.store.rows.get(f.store.key(ctx,"survey_marker")).revision());
    }
}
