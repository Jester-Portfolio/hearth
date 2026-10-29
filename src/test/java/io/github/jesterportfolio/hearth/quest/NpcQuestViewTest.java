package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.QuestStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NpcQuestViewTest {
    static QuestCatalog catalog(){
        var a=QuestCatalogTest.quest("first","guide");var b=QuestCatalogTest.quest("second","guide");var c=QuestCatalogTest.quest("locked","guide");c.put("requires-completed","first");
        var auto=QuestCatalogTest.quest("auto","guide");auto.put("start-mode","automatic");auto.put("start-area",Map.of("min-x",1,"max-x",2,"min-y",64,"max-y",65,"min-z",1,"max-z",2));
        return QuestCatalog.parse(List.of(QuestCatalogTest.npc("guide",3)),List.of(a,b,c,auto));
    }
    static Map<String,QuestStore.Saved> rows(QuestCatalog c){var m=new HashMap<String,QuestStore.Saved>();c.questIds().forEach(id->m.put(id,new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1)));return m;}
    @Test void reportPriorityAndStatusLabelsArePersonalAndNeverRevealLockedQuests(){
        var c=catalog();var rows=rows(c);rows.put("second",new QuestStore.Saved(QuestStore.Phase.REPORT_READY,1));var before=Map.copyOf(rows);
        var view=NpcQuestView.build(c,"guide",rows,q->true);assertEquals(NpcQuestView.Marker.REPORT_READY,view.marker());assertEquals("second",view.options().getFirst().quest());assertEquals("보고 가능",view.options().getFirst().status());
        assertFalse(view.toString().contains("locked"));assertEquals(before,rows);
        var other=NpcQuestView.build(c,"guide",rows(c),q->true);assertEquals(NpcQuestView.Marker.AVAILABLE,other.marker());assertNotEquals(view,other);
    }
    @Test void completedPrerequisiteUnlocksOnlyOwnOptionsAndRestrictedContentIsRemoved(){
        var c=catalog();var p1=rows(c);var p2=rows(c);p1.put("first",new QuestStore.Saved(QuestStore.Phase.COMPLETED,2));
        assertTrue(NpcQuestView.build(c,"guide",p1,q->true).options().stream().anyMatch(q->q.quest().equals("locked")));
        assertFalse(NpcQuestView.build(c,"guide",p2,q->true).toString().contains("locked"));
        var hidden=NpcQuestView.build(c,"guide",p1,q->false);assertTrue(hidden.options().isEmpty());assertEquals(NpcQuestView.Marker.NONE,hidden.marker());
    }
    @Test void automaticUnacceptedAndActiveCompletedDoNotCreateOfferMarker(){
        var c=catalog();var rows=rows(c);rows.put("first",new QuestStore.Saved(QuestStore.Phase.ACTIVE,0));rows.put("second",new QuestStore.Saved(QuestStore.Phase.COMPLETED,2));
        assertEquals(NpcQuestView.Marker.NONE,NpcQuestView.build(c,"guide",rows,q->true).marker());
        assertTrue(NpcQuestView.build(c,"guide",rows,q->true).options().stream().anyMatch(q->q.quest().equals("auto")&&q.status().equals("지역 진입으로 시작")));
    }
    @Test void incompleteResponsesAreRejectedInsteadOfEmptyOrAvailable(){
        var c=catalog();assertThrows(IllegalArgumentException.class,()->NpcQuestView.build(c,"guide",Map.of(),q->true));
        var rows=rows(c);rows.put("first",null);assertThrows(NullPointerException.class,()->NpcQuestView.build(c,"guide",rows,q->true));
    }
}
