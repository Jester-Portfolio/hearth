package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.persistence.StoryStore;
import io.github.jesterportfolio.hearth.exploration.CommunityBoundary;
import java.util.*;

/** NPC와 채팅이 공유하는 공개 진행 안내. 조회만 하며 다음 분기/개인 조건 ID를 내보내지 않습니다. */
public final class StoryGuide {
    private StoryGuide(){}
    public record Line(String text,String command){}
    /** 서버에서 보유하는 요청 표식이며 클라이언트가 진행 소유권을 선택하지 않습니다. */
    public record Action(UUID run,long revision,String stage,String command,String label){}
    public record Panel(List<String> lines,Optional<Action> action){public Panel{lines=List.copyOf(lines);}}
    public static Panel panel(UUID run,StoryContent content,StoryStore.Snapshot saved,SharedStory.Phase phase,CommunityBoundary.Status conditions){
        var guide=lines(content,saved,phase);var text=new ArrayList<String>();
        guide.stream().filter(line->line.command()==null).forEach(line->text.add(line.text()));
        boolean available=saved!=null&&phase!=SharedStory.Phase.HOLD&&phase!=SharedStory.Phase.RESULT_UNKNOWN;
        if(available){text.add("다음 중요 진행 조건");text.addAll(conditions.lines());}
        var action=available?guide.stream().filter(line->Set.of("/hearthstory next","/hearthstory vote").contains(line.command()==null?"":line.command())).findFirst().map(line->new Action(run,saved.story().revision(),saved.story().stage(),line.command(),line.text())):Optional.<Action>empty();
        return new Panel(text,action);
    }
    public static void require(StoryContent content,UUID run,StoryStore.Snapshot saved,SharedStory.Phase phase,Action action){
        if(action==null||!run.equals(action.run())||saved==null||phase!=SharedStory.Phase.READY||saved.story().revision()!=action.revision()||!Objects.equals(saved.story().stage(),action.stage())||lines(content,saved,phase).stream().noneMatch(line->Objects.equals(action.command(),line.command())&&Set.of("/hearthstory next","/hearthstory vote").contains(action.command())))throw new IllegalStateException("STALE_STORY_ACTION");
    }
    public static List<Line> lines(StoryContent content,StoryStore.Snapshot saved,SharedStory.Phase phase){
        if(saved==null || phase==SharedStory.Phase.HOLD || phase==SharedStory.Phase.RESULT_UNKNOWN)
            return List.of(new Line("공유 진행을 확인할 수 없습니다. 저장 상태 확인이 필요합니다.",null));
        var rows=new ArrayList<Line>();String id=saved.story().stage();
        rows.add(new Line(id==null?"공동 조사를 시작할 준비가 필요합니다.":"현재 공동체 진행: "+saved.story().title(),null));
        if(id!=null)rows.add(new Line("공유 목표: "+content.stage(id).goal(),null));
        rows.add(new Line("[개인 퀘스트·추적 확인]","/hearthquest list"));
        rows.add(new Line("[스마트폰에서 진행 조건·지도·일지 확인]","/hearthphone"));
        if(phase!=SharedStory.Phase.READY){rows.add(new Line(phase==SharedStory.Phase.OPEN?"채팅에 표시된 선택지로 투표해 주세요.":"진행 요청을 확인 중입니다. 저장 확인 전에는 결과가 확정되지 않습니다.",null));return List.copyOf(rows);}
        if(id!=null && saved.completed().contains(id)){rows.add(new Line("등록된 공동 조사를 완료했습니다. 개인 퀘스트와 탐험은 별도로 계속할 수 있습니다.",null));return List.copyOf(rows);}
        rows.add(new Line("다음 진행 전 현재 접속한 구성원 전원의 조건을 확인합니다. 개인 완료는 공유 진행과 별개입니다.",null));
        boolean vote=id!=null&&!content.stage(id).choices().isEmpty();
        rows.add(new Line(vote?"[조건 확인 후 채팅 분기 투표 시작]":"[조건 확인 후 공동 조사 진행]",vote?"/hearthstory vote":"/hearthstory next"));
        return List.copyOf(rows);
    }
}
