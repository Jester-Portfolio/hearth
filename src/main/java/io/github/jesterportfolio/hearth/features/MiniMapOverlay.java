package io.github.jesterportfolio.hearth.features;
import java.util.*;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;
import org.bukkit.map.*;
/** 선택형 전용 glyph BossBar. 다른 BossBar/ActionBar를 지우지 않습니다. */
public final class MiniMapOverlay implements AutoCloseable {
    private record Entry(Object session,BossBar bar,List<List<Integer>> rows){}private final Map<Player,Entry> entries=new IdentityHashMap<>();private final int offset;
    public MiniMapOverlay(int offset){HudMinimap.shifts(offset);this.offset=offset;}
    private static Component shift(int pixels){return Component.text(HudMinimap.shifts(pixels)).font(Key.key("hearth:minimap_shift"));}
    public static Component title(List<List<Integer>> rows,int offset){var text=Component.empty().append(shift(offset));for(int y=0;y<16;y++){for(int rgb:rows.get(y))text=text.append(rgb<0?shift(4):Component.text("\ue100\ue101").font(Key.key("hearth:minimap_row_"+y)).color(TextColor.color(rgb)));text=text.append(shift(-64));}return text.append(shift(-offset));}
    public void show(Player p,Object session,byte[] pixels,MapCursorCollection cursors,boolean player){var marks=new ArrayList<HudMinimap.Mark>();for(int i=0;i<cursors.size();i++){var c=cursors.getCursor(i);if(c.getType()!=MapCursor.Type.PLAYER)marks.add(new HudMinimap.Mark(c.getX(),c.getY(),c.getType()==MapCursor.Type.RED_X?0xff6262:c.getType()==MapCursor.Type.BLUE_MARKER?0x76baff:c.getType()==MapCursor.Type.BANNER_GREEN?0x79ff96:0xffdf76));}var rows=HudMinimap.frame(pixels,marks,p.getYaw(),player,index->MapPalette.getColor((byte)index).getRGB()&0xffffff);var old=entries.get(p);if(old!=null&&old.session!=session){clear(p);old=null;}if(old==null){old=new Entry(session,BossBar.bossBar(title(rows,offset),0,BossBar.Color.PURPLE,BossBar.Overlay.PROGRESS),rows);entries.put(p,old);p.showBossBar(old.bar);}else if(!old.rows.equals(rows)){old.bar.name(title(rows,offset));entries.put(p,new Entry(session,old.bar,rows));}}
    public void clear(Player p){var old=entries.remove(p);if(old!=null)p.hideBossBar(old.bar);}public void close(){for(var p:List.copyOf(entries.keySet()))clear(p);}
}
