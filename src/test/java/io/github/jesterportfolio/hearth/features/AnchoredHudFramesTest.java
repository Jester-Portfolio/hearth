package io.github.jesterportfolio.hearth.features;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class AnchoredHudFramesTest {
    @Test void expiredSnapshotHidesBothPanelsWithoutReadingWorldOrDatabase() {
        var clock=new AtomicLong();var frames=new AnchoredHudFrames<Object>(clock::get);var id=UUID.randomUUID();var actor=new Object();
        var value=AnchoredHudFrames.format(List.of("본인 현재 목표"),List.of());frames.publish(id,actor,new Object(),value);
        assertEquals("본인 현재 목표",frames.read(id,actor).quest(0));clock.set(3_000_000_000L);
        assertEquals("",frames.read(id,actor).quest(0));assertEquals("",frames.read(id,actor).map(0));
    }
    @Test void oldConnectionAndLateClearCannotReadOrEraseNewScopeOfSameUuid() {
        var frames=new AnchoredHudFrames<Object>(()->0);var id=UUID.randomUUID();var old=new Object();var actor=new Object();var oldScope=new Object();var scope=new Object();
        frames.publish(id,old,oldScope,AnchoredHudFrames.format(List.of("이전 진행"),List.of()));
        frames.publish(id,actor,scope,AnchoredHudFrames.format(List.of("새 진행"),List.of()));
        assertEquals("",frames.read(id,old).quest(0));frames.clear(id,old,oldScope);
        assertEquals("새 진행",frames.read(id,actor).quest(0));frames.clear(id,actor,new Object());
        assertEquals("새 진행",frames.read(id,actor).quest(0));frames.clear(id,actor,scope);
        assertEquals("",frames.read(id,actor).quest(0));
    }
    @Test void formattingDoesNotInterpretQuestMarkupOrCutSurrogatePairs() {
        String raw="가".repeat(79)+"😀"+"초과";
        var lines=new ArrayList<>(List.of(raw,"<red>원본</red>\n목표"));var copy=List.copyOf(lines);
        var value=AnchoredHudFrames.format(lines,List.of());
        assertEquals("가".repeat(79)+"😀",value.quest(0));assertEquals("\\<red>원본\\</red> 목표",value.quest(1));assertEquals(copy,lines);
        lines.clear();assertFalse(value.quests().isEmpty());assertThrows(UnsupportedOperationException.class,()->value.quests().clear());
    }
    @Test void boundedPanelReportsAdditionalTrackingWithoutRemovingTheOriginal() {
        var all=java.util.stream.IntStream.range(0,9).mapToObj(i->"개인 목표 "+i).toList();var frame=AnchoredHudFrames.format(all,List.of());
        assertEquals(6,frame.quests().size());assertTrue(frame.quest(5).contains("외 4개"));assertEquals(9,all.size());assertEquals("",frame.quest(6));
    }
    @Test void roundMapPreservesTransparencyAndPublishesSixteenColoredRows() {
        var pixels=HudMinimap.frame(new byte[16384],List.of(),0,true,c->0);var frame=AnchoredHudFrames.format(List.of(),pixels);
        assertEquals(16,frame.mapRows().size());assertTrue(frame.map(0).startsWith("<space:4>"));
        assertTrue(frame.map(8).contains("<#ffffff>\ue100<space:-1>"));assertEquals("",frame.map(16));
        assertThrows(IllegalArgumentException.class,()->AnchoredHudFrames.format(List.of(),List.of(List.of(0))));
    }
    @Test void concurrentReadersOnlySeeWholeImmutableFrames() throws Exception {
        var frames=new AnchoredHudFrames<Object>(()->0);var id=UUID.randomUUID();var actor=new Object();var scope=new Object();
        var a=AnchoredHudFrames.format(List.of("A","A"),List.of());var b=AnchoredHudFrames.format(List.of("B","B"),List.of());frames.publish(id,actor,scope,a);
        try(var tasks=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var reader=tasks.submit(()->{for(int i=0;i<1000;i++){var f=frames.read(id,actor);assertEquals(f.quest(0),f.quest(1));}});
            for(int i=0;i<1000;i++)frames.publish(id,actor,scope,(i&1)==0?a:b);
            reader.get();
        }
    }
}
