package io.github.jesterportfolio.hearth.combat;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BossPatternContentTest {
    static Map<String,Object> root(){return new HashMap<>(Map.of("format",1,"id","boss","title","공개 보스","health-per-player",20,"attack",2,"return-protection-ms",3000,"revival",Map.of("enabled",true,"condition","rescue","pool","SHARED","limit",2,"health-fraction",0.5,"location","START","effects",List.of())));}
    static Map<String,Object> phase(String id,double threshold){return new HashMap<>(Map.of("id",id,"title","공개 범위 공격","health-below",threshold,"interval-ms",1000,"windup-ms",200,"range",4,"damage",2));}
    @Test void oldDefinitionsAndConstructorsRemainDisabledAndContentRoundTrips(){assertTrue(CombatContent.parse(root()).patterns().isEmpty());var p=root();p.put("patterns",List.of(phase("first",1),phase("second",0.5)));var content=CombatContent.parse(p);assertEquals(2,content.patterns().size());assertEquals("second",content.patterns().get(1).id());assertEquals(200,content.patterns().getFirst().windupMillis());assertTrue(new CombatContent(content.id(),content.title(),content.healthPerPlayer(),content.attack(),content.returnProtectionMillis(),content.revival()).patterns().isEmpty());}
    @Test void duplicateUnorderedMissingCoverageAndMalformedDefinitionsAreRejected(){for(var phases:List.of(List.of(phase("same",1),phase("same",0.5)),List.of(phase("a",1),phase("b",1)),List.of(phase("a",0.5)),List.of(phase("a",1),phase("b",0.2),phase("c",0.5)))){var p=root();p.put("patterns",phases);assertThrows(IllegalArgumentException.class,()->CombatContent.parse(p));}for(var key:List.of("interval-ms","windup-ms","range","damage")){var p=root();var row=phase("a",1);row.put(key,-1);p.put("patterns",List.of(row));assertThrows(IllegalArgumentException.class,()->CombatContent.parse(p));}}    @Test void directionalDefinitionsRequireTheirOwnDimensionsAndKeepOldDefaults(){
        var root=root();var cone=phase("cone",1);cone.putAll(Map.of("shape","CONE","origin","LOCKED","angle",90,"height",4));var line=phase("line",0.5);line.putAll(Map.of("shape","LINE","width",2,"height",4));root.put("patterns",List.of(cone,line));var patterns=CombatContent.parse(root).patterns();assertEquals(CombatContent.Shape.CONE,patterns.getFirst().shape());assertEquals(CombatContent.Origin.LOCKED,patterns.getFirst().origin());assertEquals(CombatContent.Shape.LINE,patterns.getLast().shape());assertEquals(CombatContent.Origin.LIVE,patterns.getLast().origin());
    }
    @Test void missingDimensionsUnknownShapesAndUnusedFieldsAreRejected(){
        for(var properties:List.of(Map.of("shape","CONE"),Map.of("shape","LINE","width",2),Map.of("shape","UNKNOWN"),Map.of("origin","UNKNOWN"),Map.of("height",4),Map.of("width",2),Map.of("shape","CONE","angle",181,"height",4),Map.of("shape","LINE","width",Double.NaN,"height",4))){var root=root();var row=phase("bad",1);row.putAll(properties);root.put("patterns",List.of(row));assertThrows(IllegalArgumentException.class,()->CombatContent.parse(root));}
    }

}
