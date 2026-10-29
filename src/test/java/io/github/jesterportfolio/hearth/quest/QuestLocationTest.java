package io.github.jesterportfolio.hearth.quest;
import java.util.*;
import io.github.jesterportfolio.hearth.persistence.QuestStore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class QuestLocationTest {
    static final QuestContent.Area AREA=new QuestContent.Area(10,12,64,66,10,12);
    @Test void onlyExplicitPublicCoordinatesAreFormatted(){
        assertTrue(QuestLocation.parse(Map.of("kind","EXACT","x",11,"y",64,"z",11),AREA).text().contains("11.0"));
        var rough=QuestLocation.parse(Map.of("kind","AREA","min-x",0,"max-x",20,"min-y",60,"max-y",70,"min-z",0,"max-z",20),AREA);assertFalse(rough.text().contains("11.0"));
        assertEquals("위치 비공개",QuestLocation.parse(Map.of("kind","HIDDEN"),AREA).text());
    }
    @Test void malformedOrMisleadingCoordinatesFailBeforeActivation(){
        for(var bad:List.of(Map.of("kind","HIDDEN","x",11),Map.of("kind","EXACT","x",99,"y",64,"z",11),Map.of("kind","EXACT","x",Double.NaN,"y",64,"z",11),Map.of("kind","UNKNOWN")))assertThrows(IllegalArgumentException.class,()->QuestLocation.parse(bad,AREA));
    }
    @Test void stagePublicLocationDoesNotExposeOtherStagesAndLegacyRemainsUnchanged(){
        var q=QuestCatalogTest.quest("survey_marker","guide");q.remove("objective");
        var first=new HashMap<>(QuestBatchTest.goal(12,"현재 단계"));var second=new HashMap<>(QuestBatchTest.goal(18,"다음 단계"));
        first.put("location",Map.of("kind","HIDDEN"));second.put("location",Map.of("kind","EXACT","x",19,"y",64,"z",13));q.put("objectives",List.of(first,second));
        var c=QuestCatalog.parse(List.of(QuestCatalogTest.npc("guide",3)),List.of(q)).quest("survey_marker");
        var hidden=c.detailGuidance(new QuestStore.Saved(QuestStore.Phase.ACTIVE,0,0));assertTrue(hidden.contains("위치 비공개"));assertFalse(hidden.contains("19.0"));assertFalse(hidden.contains("다음 단계"));
        assertTrue(c.detailGuidance(new QuestStore.Saved(QuestStore.Phase.ACTIVE,1,1)).contains("19.0"));assertFalse(c.detailGuidance(new QuestStore.Saved(QuestStore.Phase.REPORT_READY,2,1)).contains("19.0"));
        var legacy=QuestCatalogTest.catalog().quest("survey_marker");var state=new QuestStore.Saved(QuestStore.Phase.ACTIVE,0);assertEquals(legacy.guidance(state),legacy.detailGuidance(state));
    }
}
