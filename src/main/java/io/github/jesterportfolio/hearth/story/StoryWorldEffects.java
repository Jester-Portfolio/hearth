package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.persistence.StoryStore;
import java.util.*;
import java.util.function.BooleanSupplier;

/** 검토한 콘텐츠의 고정 블록 치환. DB 확정과 물리 대상이 일치할 때만 수렴합니다. */
public record StoryWorldEffects(List<Change> changes) {
    private static final Set<String> MATERIALS=Set.of("AIR","STONE","STONE_BRICKS","COBBLESTONE","OAK_PLANKS","OAK_LOG","GLASS","GOLD_BLOCK","DIAMOND_BLOCK","EMERALD_BLOCK","IRON_BLOCK","DIRT","GRASS_BLOCK");
    public record Position(int x,int y,int z) {
        public Position{if(Math.abs((long)x)>29_999_000||Math.abs((long)z)>29_999_000||y < -64||y>319)throw invalid();}
    }
    public record Change(String id,String stage,Position position,String expected,String target,Set<String> regions) {
        public Change{regions=Set.copyOf(regions);Objects.requireNonNull(position);if(!id.matches("[a-z][a-z0-9_]{0,63}")||!stage.matches("[a-z][a-z0-9_]{0,63}")||!MATERIALS.contains(expected)||!MATERIALS.contains(target)||expected.equals(target)||regions.isEmpty()||regions.size()>64||regions.stream().anyMatch(s->!s.matches("[A-Za-z0-9_-]{1,64}")))throw invalid();}
        public boolean required(StoryStore.Snapshot saved){return saved.story().revision()>=0&&(stage.equals(saved.story().stage())||saved.completed().contains(stage));}
    }
    public record Target(StoryBinding binding,String directory,long generation) {}
    public interface Block{String read();void write(String material);}
    public StoryWorldEffects{changes=List.copyOf(changes);if(changes.size()>128||changes.stream().map(Change::id).distinct().count()!=changes.size()||changes.stream().map(Change::position).distinct().count()!=changes.size())throw invalid();}
    public Set<String> regions(){var result=new HashSet<String>();changes.forEach(c->result.addAll(c.regions()));return Set.copyOf(result);}
    public boolean required(StoryStore.Snapshot saved){return changes.stream().anyMatch(c->c.required(saved));}
    public static boolean reconcile(Change change,StoryStore.Snapshot saved,Target expected,Target actual,Block block,BooleanSupplier permit){
        if(!permit.getAsBoolean()||!change.required(saved))throw new IllegalStateException("EFFECT_UNCONFIRMED");
        if(!expected.equals(actual))throw new IllegalStateException("EFFECT_STALE");
        String before=block.read();if(!permit.getAsBoolean())throw new IllegalStateException("EFFECT_STALE");
        if(before.equals(change.target()))return true;
        if(!before.equals(change.expected()))throw new IllegalStateException("EFFECT_CONFLICT");
        block.write(change.target());if(!block.read().equals(change.target()))throw new IllegalStateException("EFFECT_VERIFY_FAILED");return true;
    }
    public static StoryWorldEffects parse(Map<String,?> root,StoryContent story){
        if(!root.containsKey("world-effects"))return new StoryWorldEffects(List.of());
        if(!(root.get("world-effects") instanceof Map<?,?> section)||!(section.get("format") instanceof Number n)||n.doubleValue()!=1||!(section.get("changes") instanceof List<?> rows))throw invalid();
        var changes=new ArrayList<Change>();for(var value:rows){if(!(value instanceof Map<?,?> row))throw invalid();String stage=text(row,"stage");if(!story.stages().containsKey(stage))throw invalid();
            if(!(row.get("regions") instanceof List<?> regions)||regions.stream().anyMatch(r->!(r instanceof String))||new HashSet<>(regions).size()!=regions.size())throw invalid();
            var change=new Change(text(row,"id"),stage,new Position(integer(row,"x"),integer(row,"y"),integer(row,"z")),text(row,"expected"),text(row,"target"),new HashSet<>(regions.stream().map(String.class::cast).toList()));
            if(change.regions().stream().anyMatch(id->story.regions().stream().noneMatch(r->r.region().equals(id)&&r.policy()!=StoryContent.Policy.OPEN&&r.bounds().contains(change.position().x(),change.position().y(),change.position().z()))))throw invalid();
            changes.add(change);
        }return new StoryWorldEffects(changes);
    }
    private static int integer(Map<?,?> row,String key){if(!(row.get(key) instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.intValue())throw invalid();return n.intValue();}
    private static String text(Map<?,?> row,String key){if(!(row.get(key) instanceof String s)||s.isBlank())throw invalid();return s;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("STORY_EFFECT_CONTENT_INVALID");}
}
