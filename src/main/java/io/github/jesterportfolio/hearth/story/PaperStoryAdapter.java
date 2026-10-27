package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.session.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** 기본 채팅의 개발 명령/선택 링크. 별도 투표 GUI·알림 DB를 만들지 않습니다. */
public final class PaperStoryAdapter implements AutoCloseable {
    private final PaperSessionAdapter players;private final SharedStory story;private final StoryAccess access;private final Executor owner;private final BukkitTask timer;
    public PaperStoryAdapter(JavaPlugin plugin,PaperSessionAdapter players,SharedStory story,Executor owner){this.players=players;this.story=story;this.owner=owner;access=new StoryAccess(story);
        players.onlineChanged(story::rosterChanged);players.entryAccess((ctx,location)->access.entry(ctx.storyRunId(),location.getX(),location.getY(),location.getZ()));
        story.notice((run,text)->{for(Player p:Bukkit.getOnlinePlayers()){var s=players.current(p);if(s!=null&&s.context().storyRunId().equals(run)){p.sendMessage(Component.text(text));sendVote(p,run);}}});
        timer=Bukkit.getScheduler().runTaskTimer(plugin,()->{var contexts=new HashMap<UUID,ServerProgressAccess.Context>();for(Player p:Bukkit.getOnlinePlayers()){var s=players.current(p);if(s!=null)contexts.put(s.context().storyRunId(),s.context());}contexts.values().forEach(story::poll);},20,20);
    }
    public StoryAccess access(){return access;}
    private void sendVote(Player p,UUID run){story.vote(run).filter(v->v.state().equals("OPEN")).ifPresent(v->{for(var choice:v.choices())p.sendMessage(Component.text("["+choice.label()+"]").clickEvent(ClickEvent.runCommand("/hearthstory vote "+v.id()+" "+choice.id())));p.sendMessage(Component.text("[기권]").clickEvent(ClickEvent.runCommand("/hearthstory vote "+v.id()+" abstain")));});}
    public boolean command(Player p,String command){if(!command.startsWith("/hearthstory"))return false;var s=players.current(p);if(s==null)return true;var ctx=s.context();String[] args=command.split(" ",-1);CompletionStage<?> work;
        try{if(args.length==2&&args[1].equals("next"))work=story.advance(ctx,s,()->players.current(p)==s);
            else if(args.length==2&&args[1].equals("vote"))work=story.openVote(ctx,s,()->players.current(p)==s);
            else if(args.length==4&&args[1].equals("vote"))work=story.cast(ctx,s,()->players.current(p)==s,UUID.fromString(args[2]),args[3]);
            else if(args.length==2&&args[1].equals("resolve"))work=story.resolve(ctx);
            else if(args.length==2&&args[1].equals("refresh"))work=story.refresh(ctx);
            else{p.sendMessage(Component.text("공유 진행: /hearthstory next | vote | resolve | refresh"));sendVote(p,ctx.storyRunId());return true;}
        }catch(RuntimeException e){p.sendMessage(Component.text("공유 진행 요청을 처리할 수 없습니다."));return true;}
        work.whenCompleteAsync((value,error)->{if(players.current(p)!=s)return;p.sendMessage(Component.text(error==null?"요청 처리를 확인했습니다. 공유 결과는 저장 확인 안내를 따릅니다.":"공유 진행 조건·저장 상태를 확인할 수 없습니다. 진행을 확정하지 않았습니다."));},owner);return true;
    }
    @Override public void close(){timer.cancel();players.onlineChanged(c->{});players.entryAccess((c,l)->true);story.notice((r,s)->{});}
}
