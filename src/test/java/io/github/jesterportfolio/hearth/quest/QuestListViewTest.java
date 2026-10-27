package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.QuestStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class QuestListViewTest {
    static QuestStore.Saved missing(){return new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1);}
    @Test void unacceptedMetadataNeverAppearsInDisplayData(){
        var c=QuestCatalogTest.catalog();var rows=QuestListView.entries(c,Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.ACTIVE,0),"path_marker",missing()));
        assertEquals(1,rows.size());assertEquals("survey_marker",rows.getFirst().questId());
        String output=QuestListView.lines(rows).toString();assertTrue(output.contains(c.quest("survey_marker").title()));
        assertFalse(output.contains(c.quest("path_marker").title()));assertFalse(output.contains("path_marker"));assertFalse(output.contains("path_guide"));
    }
    @Test void acceptedPhasesHaveKoreanStatusesAndEmptyHasExactMessage(){
        var c=QuestCatalogTest.catalog();
        for(var entry:Map.of(QuestStore.Phase.ACTIVE,"진행 중",QuestStore.Phase.REPORT_READY,"보고 대기",QuestStore.Phase.COMPLETED,"완료").entrySet()) {
            var rows=QuestListView.entries(c,Map.of("survey_marker",new QuestStore.Saved(entry.getKey(),3),"path_marker",missing()));
            assertEquals(entry.getValue(),rows.getFirst().status());assertEquals(List.of("["+entry.getValue()+"] "+c.quest("survey_marker").title()),QuestListView.lines(rows));
        }
        assertEquals(List.of("수락한 퀘스트가 없습니다"),QuestListView.lines(QuestListView.entries(c,Map.of("survey_marker",missing(),"path_marker",missing()))));
    }
    @Test void incompleteOrFailedResponsesCannotBecomeEmptyList(){
        assertThrows(IllegalArgumentException.class,()->QuestListView.entries(QuestCatalogTest.catalog(),Map.of()));
    }
}
