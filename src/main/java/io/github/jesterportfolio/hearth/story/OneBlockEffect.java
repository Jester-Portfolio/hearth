package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.persistence.StoryStore;
import java.util.*;
import java.util.function.BooleanSupplier;

/** 공개 격리 fixture의 단일 블록만 다룹니다. 범용 effect/재시도 엔진이 아닙니다. */
public final class OneBlockEffect {
    private OneBlockEffect(){}
    public record Definition(int version,String template,UUID run,String world,String stage,int x,int y,int z,String expected,String target,Set<String> regions){
        public Definition{regions=Set.copyOf(regions);if(version!=1||!"synthetic-restart-v1".equals(template)||run==null||!world.matches("hearth_verify:restart_[ab]")||!stage.matches("[a-z][a-z0-9_]{0,63}")||x<0||x>15||z<0||z>15||y<63||y>66||expected.equals(target)||!Set.of("STONE","GOLD_BLOCK","DIAMOND_BLOCK","EMERALD_BLOCK").containsAll(List.of(expected,target))||regions.isEmpty())throw new IllegalArgumentException("Invalid isolated one-block effect definition");}
        public boolean required(StoryStore.Snapshot saved){return saved.story().revision()>=0&&(stage.equals(saved.story().stage())||saved.completed().contains(stage));}
    }
    public record Target(UUID run,UUID physical,String world,String directory,long generation,String template,int version){}
    public interface Block{String read();void write(String type);}
    public static boolean reconcile(Definition d,UUID run,StoryStore.Snapshot saved,Target expected,Target actual,Block block,BooleanSupplier confirmed){
        if(!confirmed.getAsBoolean()||!d.required(saved))throw new IllegalStateException("EFFECT_UNCONFIRMED");
        if(!run.equals(d.run())||!run.equals(expected.run())||!expected.equals(actual)||!d.world().equals(actual.world())||!d.template().equals(actual.template())||d.version()!=actual.version())throw new IllegalStateException("EFFECT_STALE");
        String current=block.read();if(!confirmed.getAsBoolean())throw new IllegalStateException("EFFECT_STALE");
        if(current.equals(d.target()))return true;
        if(!current.equals(d.expected()))throw new IllegalStateException("EFFECT_CONFLICT");
        block.write(d.target());if(!block.read().equals(d.target()))throw new IllegalStateException("EFFECT_VERIFY_FAILED");return true;
    }
    public static Definition parse(Map<?,?> m){if(integer(m,"format")!=1)throw new IllegalArgumentException("Unknown effect format");var regions=new HashSet<String>();if(!(m.get("regions") instanceof List<?> rows))throw new IllegalArgumentException("Missing effect regions");for(Object row:rows){if(!(row instanceof String s)||!s.matches("[a-z][a-z0-9_]{0,63}")||!regions.add(s))throw new IllegalArgumentException("Invalid effect region");}
        return new Definition(integer(m,"effect-version"),text(m,"template-version"),UUID.fromString(text(m,"run-id")),text(m,"world-key"),text(m,"stage"),integer(m,"x"),integer(m,"y"),integer(m,"z"),text(m,"expected"),text(m,"target"),regions);
    }
    private static int integer(Map<?,?> m,String key){if(!(m.get(key) instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.intValue())throw new IllegalArgumentException("Invalid effect integer");return n.intValue();}
    private static String text(Map<?,?> m,String key){if(!(m.get(key) instanceof String s)||s.isBlank())throw new IllegalArgumentException("Missing effect text");return s;}
}
