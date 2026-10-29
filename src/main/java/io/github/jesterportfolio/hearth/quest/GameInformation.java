package io.github.jesterportfolio.hearth.quest;

import java.util.*;

/** 승인된 개인 조회 결과만 담습니다. 월드·진행 UUID, 미수락 행, 원본 콘텐츠는 내보내지 않습니다. */
public record GameInformation(List<Quest> quests,List<Place> places,List<Diary> diary,List<String> story){
    public record Quest(String title,String npc,String status,String guidance){}
    public record Place(String name,String region,String location,String status,boolean hubUnlocked){}
    public record Diary(String category,String title,String summary,String detail,boolean shared){}
    public GameInformation{quests=List.copyOf(quests);places=List.copyOf(places);diary=List.copyOf(diary);story=List.copyOf(story);}
    static GameInformation build(QuestCatalog catalog,QuestJournal.View view){
        var quests=new ArrayList<Quest>();
        for(var q:QuestListView.entries(catalog,view.states()))if(!view.exploration().blockedQuests().contains(q.questId())){
            var c=catalog.quest(q.questId());quests.add(new Quest(q.title(),c.name(),q.status(),c.detailGuidance(view.states().get(q.questId()))));
        }
        var x=view.exploration();var places=x.places().stream().map(p->new Place(p.name(),p.region(),p.location(),p.status(),p.hubUnlocked())).toList();
        var diary=x.facts().stream().map(f->new Diary(f.category(),f.title(),f.summary(),f.detail(),f.shared())).toList();
        var story=new ArrayList<String>();story.add(x.story().title()==null?"공유 진행 미등록":"현재 공유 진행: "+x.story().title());
        if(x.nextGoal()!=null)story.add("공유 목표: "+x.nextGoal());
        story.add(x.story().explorationOpen()==null?"자유 탐험 상태 미등록":Boolean.TRUE.equals(x.story().explorationOpen())?"공동체 자유 탐험 개방":"공동체 자유 탐험 미개방");
        x.branches().forEach(b->story.add("확정 분기: "+b));story.addAll(x.conditions().lines());
        return new GameInformation(quests,places,diary,story);
    }
    public record Query(String section,int page){
        public static Optional<Query> parse(String command){
            var m=java.util.regex.Pattern.compile("/hearthinfo(?: (summary|quests|map|diary|story)(?: ([1-9][0-9]{0,3}))?)?").matcher(command);
            return m.matches()?Optional.of(new Query(m.group(1)==null?"summary":m.group(1),m.group(2)==null?1:Integer.parseInt(m.group(2)))):Optional.empty();
        }
    }
    public List<String> lines(Query query){
        List<String> rows=switch(query.section()){
            case "summary"->List.of("수락한 공개 퀘스트 "+quests.size()+"개 · 공개 장소 "+places.size()+"개 · 조회 가능한 일지 "+diary.size()+"개","/hearthinfo quests | map | diary | story [페이지]",story.getFirst());
            case "quests"->quests.stream().map(q->"["+q.status()+"] "+q.title()+" · NPC: "+q.npc()+" · "+q.guidance()).toList();
            case "map"->places.stream().map(p->p.name()+" · "+p.region()+" · "+p.status()+" · "+p.location()+(p.hubUnlocked()?" · 본인 거점 해금":"")).toList();
            case "diary"->diary.stream().map(d->(d.shared()?"[공동체] ":"[개인] ")+d.title()+" · "+d.summary()+" · "+d.detail()).toList();
            case "story"->story;
            default->throw new IllegalArgumentException("Unknown information section");
        };
        if(query.page()<1||query.page()>9999)throw new IllegalArgumentException("Invalid information page");
        int start=(query.page()-1)*8;
        if(start>=rows.size())return List.of(rows.isEmpty()?"조회 가능한 정보가 없습니다.":"해당 페이지가 없습니다.");
        String label=switch(query.section()){case "summary"->"요약";case "quests"->"퀘스트";case "map"->"지도";case "diary"->"일지";case "story"->"공유 진행";default->throw new IllegalArgumentException("Unknown information section");};
        var out=new ArrayList<String>();out.add("게임 정보 · "+label+" · "+query.page()+"페이지");
        rows.subList(start,Math.min(rows.size(),start+8)).forEach(line->out.add(line.length()>384?line.substring(0,384)+"…":line));
        if(start+8<rows.size())out.add("다음: /hearthinfo "+query.section()+" "+(query.page()+1));return List.copyOf(out);
    }
}
