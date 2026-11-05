package io.github.jesterportfolio.hearth.combat;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class CheckpointContentTest {
 static Map<String,Object> combat(){return new HashMap<>(Map.of("format",1,"id","trial","title","공개 시험","health-per-player",20,"attack",2,"return-protection-ms",3000,"revival",Map.of("enabled",true,"condition","rescue","pool","SHARED","limit",2,"health-fraction",0.5,"location","START","effects",List.of())));}
 static Map<String,Object> area(){return Map.of("min-x",0,"max-x",4,"min-y",64,"max-y",68,"min-z",0,"max-z",4);}
 static Map<String,Object> entry(){return Map.of("entry",Map.of("x",2,"y",64,"z",2,"yaw",0,"pitch",0),"health-fraction",1,"effects",List.of());}
 static Map<String,Object> cp(String id){return Map.of("id",id,"quest","trial","area",area(),"entry",entry());}
 static Map<String,Object> path(){return new HashMap<>(Map.of("combat",combat(),"gathering",area(),"boss-entry",entry()));}
 @Test void legacyAndMultipleDefinitionsKeepExplicitSelection(){var root=combat();var path=path();path.put("checkpoint",cp("one"));root.put("path",path);assertEquals(1,CombatContent.parse(root).path().checkpoints().size());path.remove("checkpoint");path.put("checkpoints",List.of(cp("one"),cp("two")));path.put("restart-checkpoint","two");var parsed=CombatContent.parse(root).path();assertEquals(2,parsed.checkpoints().size());assertEquals("two",parsed.checkpoint().id());}
 @Test void missingUnknownDuplicateAndMixedSelectionsFailBeforeActivation(){for(int mode=0;mode<4;mode++){var root=combat();var path=path();path.put("checkpoints",List.of(cp("one"),cp(mode==2?"one":"two")));if(mode!=0)path.put("restart-checkpoint",mode==1?"unknown":"one");if(mode==3)path.put("checkpoint",cp("one"));root.put("path",path);assertThrows(IllegalArgumentException.class,()->CombatContent.parse(root));}}
}
