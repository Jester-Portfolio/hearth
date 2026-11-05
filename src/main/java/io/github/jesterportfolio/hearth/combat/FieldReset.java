package io.github.jesterportfolio.hearth.combat;

import java.util.*;

/** 제작자가 정한 길목 순서와 초기화 범위. 지형·상자·아이템을 되돌리지 않습니다. */
public record FieldReset(Mode mode,List<String> orderedZones,String checkpoint){
    public enum Mode { ALL,PRESERVE,AFTER_CHECKPOINT }
    public FieldReset{
        Objects.requireNonNull(mode);orderedZones=List.copyOf(orderedZones);
        if(orderedZones.isEmpty()||orderedZones.size()>128||new HashSet<>(orderedZones).size()!=orderedZones.size()||orderedZones.stream().anyMatch(s->s==null||!s.matches("[a-z][a-z0-9_]{0,63}"))||(mode==Mode.AFTER_CHECKPOINT)!=(checkpoint!=null)||checkpoint!=null&&!orderedZones.contains(checkpoint))throw new IllegalArgumentException("Invalid field reset definition");
    }
    public Set<String> selected(FieldContent content){
        var known=content.zones().stream().map(FieldContent.Zone::id).collect(java.util.stream.Collectors.toSet());
        if(!known.containsAll(orderedZones))throw new IllegalArgumentException("Unknown reset zone");
        return switch(mode){case ALL->Set.copyOf(orderedZones);case PRESERVE->Set.of();case AFTER_CHECKPOINT->Set.copyOf(orderedZones.subList(orderedZones.indexOf(checkpoint)+1,orderedZones.size()));};
    }
    public static FieldReset parse(Map<?,?> data){
        if(!(data.get("mode") instanceof String mode)||!(data.get("zones") instanceof List<?> zones)||zones.stream().anyMatch(v->!(v instanceof String))||data.containsKey("checkpoint")&&!(data.get("checkpoint") instanceof String))throw new IllegalArgumentException("Invalid reset content");
        return new FieldReset(Mode.valueOf(mode),zones.stream().map(String.class::cast).toList(),(String)data.get("checkpoint"));
    }
}
