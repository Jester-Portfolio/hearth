package io.github.jesterportfolio.hearth.quest;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class QuestCatalogTest {
    static Map<String,Object> npc(String id,int x){return new HashMap<>(Map.of("id",id,"name","공개 시험 안내원","x",x,"y",64,"z",3));}
    static Map<String,Object> quest(String id,String npc){
        var text=new HashMap<String,Object>();for(String k:List.of("offer","accept","active","report-ready","report","completed","failure"))text.put(k,"공개 시험 문구");
        return new HashMap<>(Map.of("id",id,"npc-id",npc,"objective",new HashMap<>(Map.of("min-x",12,"max-x",14,"min-y",64,"max-y",67,"min-z",12,"max-z",14)),"text",text));
    }
    static QuestCatalog catalog(){return QuestCatalog.parse(List.of(npc("survey_guide",3),npc("path_guide",10)),List.of(quest("survey_marker","survey_guide"),quest("path_marker","path_guide")));}
    @Test void explicitLinksAndBoundsAreImmutable(){
        var catalog=catalog();assertEquals(Set.of("survey_marker","path_marker"),catalog.questIds());assertTrue(catalog.linked("survey_guide","survey_marker"));assertFalse(catalog.linked("path_guide","survey_marker"));
        assertTrue(catalog.quest("survey_marker").contains(13,64,13));assertFalse(catalog.quest("survey_marker").contains(2,64,2));
        assertThrows(UnsupportedOperationException.class,()->catalog.quest("survey_marker").text().put("offer","변경"));
    }
    @Test void duplicateNpcAndQuestIdsAreRejected(){
        assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(npc("one",3),npc("one",10)),List.of(quest("q1","one"))));
        assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(npc("one",3),npc("two",10)),List.of(quest("q1","one"),quest("q1","two"))));
    }
    @Test void missingUnusedAndAmbiguousLinksAreRejected(){
        assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(npc("one",3)),List.of(quest("q1","missing"))));
        assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(npc("one",3),npc("unused",10)),List.of(quest("q1","one"))));
        assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(npc("one",3)),List.of(quest("q1","one"),quest("q2","one"))));
    }
    @Test void malformedNumbersBoundsAndTextAreRejected(){
        for(Object value:List.of("64",Double.NaN,Double.POSITIVE_INFINITY)){
            var n=npc("one",3);n.put("y",value);assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(n),List.of(quest("q1","one"))));
        }
        var q=quest("q1","one");q.put("objective",Map.of("min-x",15,"max-x",14,"min-y",64,"max-y",67,"min-z",12,"max-z",14));
        assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(npc("one",3)),List.of(q)));
        q.put("text",Map.of());assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(npc("one",3)),List.of(q)));
    }
    @Test void invalidIdsShapesAndDuplicatePositionsAreRejected(){
        assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(npc("../one",3)),List.of(quest("q1","../one"))));
        assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of("not a map"),List.of(quest("q1","one"))));
        assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(npc("one",3),npc("two",3)),List.of(quest("q1","one"),quest("q2","two"))));
    }
}
