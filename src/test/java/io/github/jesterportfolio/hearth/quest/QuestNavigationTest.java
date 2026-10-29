package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.exploration.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class QuestNavigationTest {
    static QuestCatalog catalog(){return QuestCatalog.parse(List.of(QuestCatalogTest.npc("guide",3)),List.of(QuestCatalogTest.quest("active","guide"),QuestCatalogTest.quest("report","guide"),QuestCatalogTest.quest("done","guide"),QuestCatalogTest.quest("unaccepted","guide")));}
    static Map<String,QuestStore.Saved> states(){return Map.of("active",new QuestStore.Saved(QuestStore.Phase.ACTIVE,0),"report",new QuestStore.Saved(QuestStore.Phase.REPORT_READY,1),"done",new QuestStore.Saved(QuestStore.Phase.COMPLETED,2),"unaccepted",new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1));}
    static QuestJournal.View view(){return new QuestJournal.View(states(),new QuestJournalStore.Journal(Set.of("active","done"),new QuestJournalStore.Preferences(true,0),List.of()),ExplorationView.empty());}
    @Test void filtersPrioritizeReportAndExcludeUnacceptedBlockedAndCompletedTracking(){
        var c=catalog();var rows=states();assertEquals(List.of("report","active","done"),QuestListView.filtered(c,rows,Set.of("active"),Set.of(),QuestListView.Filter.ALL).stream().map(QuestListView.Entry::questId).toList());
        assertEquals(List.of("report"),QuestListView.filtered(c,rows,Set.of(),Set.of(),QuestListView.Filter.REPORTING).stream().map(QuestListView.Entry::questId).toList());
        assertEquals(List.of("active"),QuestListView.filtered(c,rows,Set.of("active","done"),Set.of(),QuestListView.Filter.TRACKED).stream().map(QuestListView.Entry::questId).toList());
        assertTrue(QuestListView.filtered(c,rows,Set.of(),Set.of("report"),QuestListView.Filter.REPORTING).isEmpty());assertEquals(states(),rows);
    }
    @Test void detailOffersConversationOnlyForReportReadyAndCannotReportRemotely(){
        var c=catalog();var v=view();
        assertTrue(QuestScreens.screen(c,v,"detail","report",0).stream().anyMatch(b->b.action().equals("npc-dialogue")));
        for(String id:List.of("active","done","unaccepted","missing"))assertFalse(QuestScreens.screen(c,v,"detail",id,0).stream().anyMatch(b->b.action().equals("npc-dialogue")));
        var links=QuestScreens.screen(c,v,"detail","active",0);assertTrue(links.stream().anyMatch(b->b.action().equals("notices")));assertTrue(links.stream().anyMatch(b->b.action().equals("tracked")));
        assertFalse(QuestScreens.screen(c,v,"detail","report",0).stream().anyMatch(b->b.action().equals("report")));
    }
    @Test void tabControlsAreUniqueAndUnknownQuestsNeverAppear(){
        for(String page:List.of("quests","reporting","tracked","completed")){
            var buttons=QuestScreens.screen(catalog(),view(),page,null,0);assertEquals(buttons.size(),buttons.stream().map(QuestScreens.Button::slot).distinct().count());
            assertFalse(buttons.toString().contains("unaccepted"));assertTrue(buttons.stream().anyMatch(b->b.label().equals("보고 가능")));
        }
    }
    @Test void pagesPreserveSelectedFilterAndPersonalViewsRemainIndependent(){
        var items=new ArrayList<Map<String,Object>>();var rows=new HashMap<String,QuestStore.Saved>();
        for(int i=0;i<40;i++){String id="quest"+i;items.add(QuestCatalogTest.quest(id,"guide"));rows.put(id,new QuestStore.Saved(QuestStore.Phase.COMPLETED,2));}
        var c=QuestCatalog.parse(List.of(QuestCatalogTest.npc("guide",3)),items);var v=new QuestJournal.View(rows,view().journal());
        assertTrue(QuestScreens.screen(c,v,"completed",null,0).stream().anyMatch(b->b.action().equals("completed")&&b.revision()==36));
        assertEquals(4,QuestScreens.screen(c,v,"completed",null,36).stream().filter(b->b.action().equals("detail")).count());
        assertTrue(QuestListView.filtered(c,rows,Set.of(),Set.of(),QuestListView.Filter.REPORTING).isEmpty());
    }
}
