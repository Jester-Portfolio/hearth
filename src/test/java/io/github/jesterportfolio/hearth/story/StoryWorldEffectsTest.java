package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StoryWorldEffectsTest {
    final StoryContent story=new StoryContent("open",Map.of("open",new StoryContent.Stage("open","공개","확인",null,null,null,true,List.of(),null)),List.of(new StoryContent.RegionRule("protected",StoryContent.Policy.CONTENT,"open",new io.github.jesterportfolio.hearth.quest.QuestContent.Area(0,15,60,70,0,15))));
    final StoryStore.Snapshot saved=new StoryStore.Snapshot(new ExplorationStore.Story("open","공개",true,1),Set.of(),Map.of());
    Map<String,Object> row(){return new HashMap<>(Map.of("id","gate","stage","open","x",5,"y",63,"z",5,"expected","STONE","target","EMERALD_BLOCK","regions",List.of("protected")));}
    StoryWorldEffects parse(List<?> rows){return StoryWorldEffects.parse(Map.of("world-effects",Map.of("format",1,"changes",rows)),story);}
    StoryWorldEffects.Target target(){return new StoryWorldEffects.Target(new StoryBinding(new UUID(1,1),"hearth_story:run_a",new UUID(2,1),"v1","a".repeat(64),"b".repeat(64)),"/owned/world",1);}
    static final class Block implements StoryWorldEffects.Block{String value="STONE";int writes;public String read(){return value;}public void write(String next){value=next;writes++;}}
    @Test void absentContentHasNoEffectOrInventedRegions(){var c=StoryWorldEffects.parse(Map.of(),story);assertTrue(c.changes().isEmpty());assertTrue(c.regions().isEmpty());assertFalse(c.required(saved));}
    @Test void contentMustReferToKnownStageAndSafeMaterial(){for(var field:List.of("stage","expected","target")){var r=row();r.put(field,field.equals("stage")?"secret_unknown":"CHEST");assertThrows(IllegalArgumentException.class,()->parse(List.of(r)));}}
    @Test void duplicateIdsAndCrossStageCoordinatesAreRejectedBeforeAnyWrites(){var one=row();var two=row();two.put("id","another");assertThrows(IllegalArgumentException.class,()->parse(List.of(one,two)));two.put("x",6);two.put("id","gate");assertThrows(IllegalArgumentException.class,()->parse(List.of(one,two)));}
    @Test void limitsFractionalCoordinatesAndUnregisteredRegionsAreRejected(){var r=row();r.put("x",1.5);assertThrows(IllegalArgumentException.class,()->parse(List.of(r)));r.put("x",Integer.MIN_VALUE);assertThrows(IllegalArgumentException.class,()->parse(List.of(r)));r.put("x",5);r.put("regions",List.of("protected","protected"));assertThrows(IllegalArgumentException.class,()->parse(List.of(r)));}
    @Test void expectedAndAlreadyAppliedConvergeWithoutDuplicateWrite(){var c=parse(List.of(row())).changes().getFirst();var t=target();var b=new Block();assertTrue(StoryWorldEffects.reconcile(c,saved,t,t,b,()->true));assertTrue(StoryWorldEffects.reconcile(c,saved,t,t,b,()->true));assertEquals(1,b.writes);}
    @Test void conflictAndAuthorityChangePreserveOriginalBlock(){var c=parse(List.of(row())).changes().getFirst();var t=target();var b=new Block();b.value="DIAMOND_BLOCK";assertThrows(IllegalStateException.class,()->StoryWorldEffects.reconcile(c,saved,t,t,b,()->true));b.value="STONE";var calls=new AtomicInteger();assertThrows(IllegalStateException.class,()->StoryWorldEffects.reconcile(c,saved,t,t,b,()->calls.incrementAndGet()==1));assertEquals("STONE",b.value);assertEquals(0,b.writes);}
    @Test void changedRunPhysicalIdentityTemplateDirectoryOrGenerationNeverWrites(){var c=parse(List.of(row())).changes().getFirst();var t=target();var b=new Block();var binding=t.binding();for(var actual:List.of(new StoryWorldEffects.Target(binding,t.directory(),2),new StoryWorldEffects.Target(binding,"/other",1),new StoryWorldEffects.Target(new StoryBinding(binding.run(),binding.worldKey(),UUID.randomUUID(),"v1",binding.templateHash(),binding.contentHash()),t.directory(),1),new StoryWorldEffects.Target(new StoryBinding(binding.run(),binding.worldKey(),binding.worldId(),"v2",binding.templateHash(),binding.contentHash()),t.directory(),1))){assertThrows(IllegalStateException.class,()->StoryWorldEffects.reconcile(c,saved,t,actual,b,()->true));}assertEquals(0,b.writes);}
    @Test void completedStageStillRequiresEffectButUnconfirmedStageDoesNot(){var c=parse(List.of(row()));assertTrue(c.required(new StoryStore.Snapshot(new ExplorationStore.Story(null,null,false,2),Set.of("open"),Map.of())));assertFalse(c.required(new StoryStore.Snapshot(new ExplorationStore.Story(null,null,false,-1),Set.of(),Map.of())));}
}
