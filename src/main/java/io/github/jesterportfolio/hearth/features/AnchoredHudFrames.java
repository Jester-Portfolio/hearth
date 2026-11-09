package io.github.jesterportfolio.hearth.features;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** 비동기 출력에는 검증이 끝난 문자열만 공개합니다. Player/월드/DB를 조회하지 않습니다. */
public final class AnchoredHudFrames<A> {
    public record Frame(List<String> quests,List<String> mapRows) {
        public Frame {quests=List.copyOf(quests);mapRows=List.copyOf(mapRows);if(quests.size()>6||!mapRows.isEmpty()&&mapRows.size()!=16)throw new IllegalArgumentException("HUD_FRAME_SIZE");}
        public String quest(int row){return row>=0&&row<quests.size()?quests.get(row):"";}
        public String map(int row){return row>=0&&row<mapRows.size()?mapRows.get(row):"";}
    }
    private record Published<A>(A actor,Object scope,Frame frame,long expires) {}
    private final ConcurrentHashMap<UUID,Published<A>> values=new ConcurrentHashMap<>();
    private final LongSupplier clock;
    public AnchoredHudFrames(LongSupplier clock){this.clock=clock;}
    public void publish(UUID id,A actor,Object scope,Frame frame) {
        Objects.requireNonNull(actor);Objects.requireNonNull(scope);Objects.requireNonNull(frame);
        if(values.size()>=128&&!values.containsKey(id))throw new IllegalStateException("HUD_CACHE_FULL");
        values.put(id,new Published<>(actor,scope,frame,clock.getAsLong()+3_000_000_000L));
    }
    public Frame read(UUID id,A actor) {
        var value=values.get(id);
        return value!=null&&value.actor==actor&&value.expires-clock.getAsLong()>0?value.frame:new Frame(List.of(),List.of());
    }
    public void clear(UUID id,A actor,Object scope){values.computeIfPresent(id,(k,v)->v.actor==actor&&v.scope==scope?null:v);}
    public void clear(){values.clear();}
    public static Frame format(List<String> lines,List<List<Integer>> pixels) {
        var quests=new ArrayList<String>();
        for(int i=0;i<Math.min(6,lines.size());i++) {
            String text=i==5&&lines.size()>6?"외 "+(lines.size()-5)+"개 추적 · 스마트폰에서 확인":lines.get(i);
            text=text.replace('\n',' ').replace('\r',' ');
            if(text.codePointCount(0,text.length())>80)text=text.substring(0,text.offsetByCodePoints(0,80));
            quests.add(text.replace("\\","\\\\").replace("<","\\<"));
        }
        var rows=new ArrayList<String>();
        if(!pixels.isEmpty()) {
            if(pixels.size()!=16||pixels.stream().anyMatch(row->row.size()!=16))throw new IllegalArgumentException("HUD_MAP_SIZE");
            for(var row:pixels) {
                var out=new StringBuilder();
                for(int rgb:row) {
                    if(rgb<0)out.append("<space:4>");
                    else {if(rgb>0xffffff)throw new IllegalArgumentException("HUD_COLOR");out.append(String.format(Locale.ROOT,"<#%06x>",rgb)).append('\ue100').append("<space:-1>");}
                }
                rows.add(out.toString());
            }
        }
        return new Frame(quests,rows);
    }
}
