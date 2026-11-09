package io.github.jesterportfolio.hearth.features;

import java.util.*;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

/** 개인 sidebar 소유권을 확인하고 종료 시 원래 scoreboard만 복원합니다. 전투 ActionBar는 덮지 않습니다. */
public final class FixedHud implements AutoCloseable {
    private record Entry(Object session,Scoreboard previous,Scoreboard board,List<String> lines){}
    private final Map<Player,Entry> entries=new IdentityHashMap<>();
    public void show(Player p,Object session,List<String> source,BooleanSupplier current){if(!current.getAsBoolean()){clear(p);return;}var lines=source.stream().limit(12).map(s->s.codePointCount(0,s.length())>80?s.substring(0,s.offsetByCodePoints(0,80)):s).toList();if(lines.isEmpty()){clear(p);return;}var old=entries.get(p);if(old!=null&&old.session==session&&old.lines.equals(lines)&&p.getScoreboard()==old.board)return;if(old!=null&&p.getScoreboard()!=old.board){entries.remove(p);return;}if(old!=null&&old.session!=session){clear(p);old=null;}if(old==null&&p.getScoreboard().getObjective(DisplaySlot.SIDEBAR)!=null)return;var previous=old==null?p.getScoreboard():old.previous;var board=board(lines);p.setScoreboard(board);entries.put(p,new Entry(session,previous,board,lines));}
    /** 순정 객체 검증용 factory. 표시/개인 상태를 변경하지 않습니다. */
    public static Scoreboard board(List<String> source){var lines=source.stream().limit(12).map(s->s.codePointCount(0,s.length())>80?s.substring(0,s.offsetByCodePoints(0,80)):s).toList();var board=Bukkit.getScoreboardManager().getNewScoreboard();var objective=board.registerNewObjective("hearth",Criteria.DUMMY,Component.text("Hearth · 현재 목표"));objective.setDisplaySlot(DisplaySlot.SIDEBAR);for(int i=0;i<lines.size();i++){var team=board.registerNewTeam("line"+i);String key=ChatColor.values()[i].toString()+ChatColor.RESET;team.addEntry(key);team.prefix(Component.text(lines.get(i)));objective.getScore(key).setScore(lines.size()-i);}return board;}
    public void clear(Player p){var old=entries.remove(p);if(old!=null&&p.getScoreboard()==old.board)p.setScoreboard(old.previous);}
    public void close(){for(var p:List.copyOf(entries.keySet()))clear(p);}
}
