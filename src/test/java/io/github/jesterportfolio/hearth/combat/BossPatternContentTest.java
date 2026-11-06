package io.github.jesterportfolio.hearth.combat;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BossPatternContentTest {
    static Map<String,Object> root(){return new HashMap<>(Map.of("format",1,"id","boss","title","공개 보스","health-per-player",20,"attack",2,"return-protection-ms",3000,"revival",Map.of("enabled",true,"condition","rescue","pool","SHARED","limit",2,"health-fraction",0.5,"location","START","effects",List.of())));}
    static Map<String,Object> phase(String id,double threshold){return new HashMap<>(Map.of("id",id,"title","공개 범위 공격","health-below",threshold,"interval-ms",1000,"windup-ms",200,"range",4,"damage",2));}
    @Test void oldDefinitionsAndConstructorsRemainDisabledAndContentRoundTrips(){assertTrue(CombatContent.parse(root()).patterns().isEmpty());var p=root();p.put("patterns",List.of(phase("first",1),phase("second",0.5)));var content=CombatContent.parse(p);assertEquals(2,content.patterns().size());assertEquals("second",content.patterns().get(1).id());assertEquals(200,content.patterns().getFirst().windupMillis());assertTrue(new CombatContent(content.id(),content.title(),content.healthPerPlayer(),content.attack(),content.returnProtectionMillis(),content.revival()).patterns().isEmpty());}
    @Test void duplicateUnorderedMissingCoverageAndMalformedDefinitionsAreRejected(){for(var phases:List.of(List.of(phase("same",1),phase("same",0.5)),List.of(phase("a",1),phase("b",1)),List.of(phase("a",0.5)),List.of(phase("a",1),phase("b",0.2),phase("c",0.5)))){var p=root();p.put("patterns",phases);assertThrows(IllegalArgumentException.class,()->CombatContent.parse(p));}for(var key:List.of("interval-ms","windup-ms","range","damage")){var p=root();var row=phase("a",1);row.put(key,-1);p.put("patterns",List.of(row));assertThrows(IllegalArgumentException.class,()->CombatContent.parse(p));}}
}
