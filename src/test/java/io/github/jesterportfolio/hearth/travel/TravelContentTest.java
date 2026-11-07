package io.github.jesterportfolio.hearth.travel;
import io.github.jesterportfolio.hearth.exploration.ExplorationContent;
import io.github.jesterportfolio.hearth.quest.QuestContent;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TravelContentTest {
    private ExplorationContent exploration(boolean hub){var p=new ExplorationContent.Place("village","marker","거점","plain","거점",ExplorationContent.Visibility.PUBLIC,ExplorationContent.Discovery.ENTRY,new QuestContent.Area(0,5,64,68,0,5),new ExplorationContent.Location("EXACT","공개 위치"),new ExplorationContent.Location("NONE","위치 비공개"),hub,null,null);return new ExplorationContent(Map.of("plain",new ExplorationContent.Region("plain","평원",true)),Map.of("village",p),Map.of(),Map.of(),Map.of());}
    private Map<String,Object> row(){return new HashMap<>(Map.of("id","station","place","village","facility",Map.of("x",2,"y",64,"z",2,"material","STONE"),"arrival",Map.of("x",2.5,"y",64,"z",1.5,"yaw",0,"pitch",0)));}
    private Map<String,Object> root(List<?> rows){return new HashMap<>(Map.of("format",1,"isolated-unpriced-prototype",true,"stations",rows));}
    @Test void parsesExplicitPrototypeOnly(){assertEquals(1,TravelContent.parse(root(List.of(row())),exploration(true)).stations().size());var r=root(List.of(row()));r.remove("isolated-unpriced-prototype");assertThrows(IllegalArgumentException.class,()->TravelContent.parse(r,exploration(true)));}
    @Test void rejectsUnknownNonHubDuplicatesAndOutOfBounds(){var unknown=row();unknown.put("place","missing");assertThrows(IllegalArgumentException.class,()->TravelContent.parse(root(List.of(unknown)),exploration(true)));assertThrows(IllegalArgumentException.class,()->TravelContent.parse(root(List.of(row())),exploration(false)));assertThrows(IllegalArgumentException.class,()->TravelContent.parse(root(List.of(row(),row())),exploration(true)));var r=row();r.put("arrival",Map.of("x",99,"y",64,"z",99,"yaw",0,"pitch",0));var bad=r;assertThrows(IllegalArgumentException.class,()->TravelContent.parse(root(List.of(bad)),exploration(true)));}
    @Test void immutableAndInvalidCoordinates(){var c=TravelContent.parse(root(List.of(row())),exploration(true));assertThrows(UnsupportedOperationException.class,()->c.stations().clear());assertThrows(IllegalArgumentException.class,()->new TravelContent.Point(Double.NaN,64,0));assertThrows(IllegalArgumentException.class,()->new TravelContent.Point(0,999,0));assertThrows(IllegalArgumentException.class,()->c.get("missing"));}
}
