package io.github.jesterportfolio.hearth.features;
import java.util.*;
import java.util.function.IntUnaryOperator;
/** 128 지도를 16×16 원형 HUD로 축소합니다. 클라이언트 GUI 배율의 위치 보정은 표시 설정값입니다. */
public final class HudMinimap {
    public record Mark(int x,int y,int rgb){}
    public static List<List<Integer>> frame(byte[] pixels,List<Mark> markers,float yaw,boolean player,IntUnaryOperator color){if(pixels.length!=16384||markers.size()>32||!Float.isFinite(yaw))throw new IllegalArgumentException("HUD_MAP_LAYOUT");var rows=new ArrayList<List<Integer>>();for(int y=0;y<16;y++){var row=new ArrayList<Integer>();for(int x=0;x<16;x++){double d=Math.hypot(x-7.5,y-7.5);int b=Byte.toUnsignedInt(pixels[(y*8+4)*128+x*8+4]);row.add(d>7.8?-1:d>6.8?0x526176:b==0?0x18202a:color.applyAsInt(b));}rows.add(row);}for(var m:markers){int x=8+m.x/16,y=8+m.y/16;if(x>=0&&x<16&&y>=0&&y<16&&rows.get(y).get(x)>=0)rows.get(y).set(x,m.rgb);}if(player){rows.get(8).set(8,0xffffff);int x=8+(int)Math.round(-Math.sin(Math.toRadians(yaw))*2),y=8+(int)Math.round(Math.cos(Math.toRadians(yaw))*2);rows.get(y).set(x,0xffffff);}return rows.stream().map(List::copyOf).toList();}
    public static String shifts(int offset){if(offset< -512||offset>512)throw new IllegalArgumentException("HUD_OFFSET");var text=new StringBuilder();int amount=Math.abs(offset);for(int bit=0;bit<=9;bit++)if((amount&(1<<bit))!=0)text.appendCodePoint((offset<0?0xe210:0xe200)+bit);return text.toString();}
}
