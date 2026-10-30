package io.github.jesterportfolio.hearth.phone;

import io.github.jesterportfolio.hearth.quest.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;

/** 기존 개인 조회 스냅샷의 분류/공동체 읽기 전용 화면. 가입·진행 변경·원본 콘텐츠 조회는 하지 않습니다. */
public final class PhoneLibrary {
    private PhoneLibrary(){}
    public record Entry(String title,List<String> lines,String targetSection,int targetIndex){public Entry{lines=List.copyOf(lines);}}
    private static Entry row(String title,String... lines){return new Entry(title,List.of(lines),null,-1);}
    public static String title(String section){return switch(section){case "tracked"->"추적 중";case "reporting"->"보고 가능";case "completed"->"완료";case "STORY"->"스토리 일지";case "PERSON"->"인물 일지";case "EXPLORATION"->"탐험 일지";case "CHRONICLE"->"연대기";case "regions"->"공개 지역";case "members"->"확인 가능한 구성원";case "shared"->"공유 스토리 진행";case "conditions"->"현재 접속자 진행 조건";case "branches"->"확정된 공유 분기";default->throw new IllegalArgumentException("UNKNOWN_PHONE_LIBRARY");};}
    public static List<ExplorationStore.Fact> facts(QuestJournal.View view,String category){return view.exploration().facts().stream().filter(f->f.category().equals(category)).sorted(Comparator.comparing(ExplorationStore.Fact::updated).reversed().thenComparing(ExplorationStore.Fact::title).thenComparing(ExplorationStore.Fact::shared).thenComparing(ExplorationStore.Fact::id)).toList();}
    public static List<ExplorationStore.Event> events(QuestJournal.View view){return view.exploration().events().stream().sorted(Comparator.comparing(ExplorationStore.Event::time).reversed().thenComparing(ExplorationStore.Event::title).thenComparing(ExplorationStore.Event::shared).thenComparing(ExplorationStore.Event::id).thenComparingInt(ExplorationStore.Event::version)).toList();}
    public static List<Entry> rows(QuestCatalog catalog,QuestJournal.View view,String section){
        title(section);var out=new ArrayList<Entry>();var x=view.exploration();
        switch(section){
            case "tracked","reporting","completed"->{var quests=PhoneQuestLinks.quests(catalog,view);for(int i=0;i<quests.size();i++){var q=quests.get(i);var state=view.states().get(q.id());boolean include=section.equals("tracked")?q.tracked()&&!state.satisfies():section.equals("reporting")?q.reporting():state.phase()==QuestStore.Phase.COMPLETED;if(include)out.add(new Entry("["+QuestListView.status(state.phase())+"] "+q.title(),List.of(),"quests",i));}}
            case "STORY","PERSON","EXPLORATION"->facts(view,section).stream().forEach(f->out.add(row((f.shared()?"[공유] ":"[개인] ")+f.title(),f.summary(),f.detail(),"정보 버전: "+f.version(),"최초 확인: "+f.first(),"최근 갱신: "+f.updated(),f.shared()?"공동체 공유 기록 · 개인 퀘스트/보상과 별개":"개인적으로 확인한 정보")));
            case "CHRONICLE"->events(view).stream().forEach(e->out.add(row((e.shared()?"[공유] ":"[개인] ")+e.title(),e.summary(),e.detail(),"발견/발생: "+e.time(),"정보 버전: "+e.version(),e.shared()?"공유 사건 · 개인 경험 여부 미판정":"개인 사건")));
            case "regions"->x.regions().forEach(r->out.add(row(r.name(),"접근 정책: "+r.access(),"지도 표시·개인 장소 발견·거점 직접 방문 해금은 서로 다릅니다. 빠른 이동 실행 기능은 제공하지 않습니다.")));
            case "members"->x.members().stream().sorted(Comparator.comparing(io.github.jesterportfolio.hearth.exploration.CommunityBoundary.Member::online).reversed().thenComparing(io.github.jesterportfolio.hearth.exploration.CommunityBoundary.Member::name)).forEach(m->out.add(row((m.online()?"[온라인] ":"[오프라인] ")+m.name()+(m.self()?" (나)":""),"서버에서 확인한 같은 진행 연결만 표시합니다. 전체 구성원 명단을 보장하지 않습니다.","다른 사람의 개인 퀘스트·인벤토리는 공개하지 않습니다.")));
            case "shared"->{out.add(row(x.story().title()==null?"공유 단계 미등록":x.story().title(),x.nextGoal()==null?"다음 목표 미등록":"다음 공유 목표: "+x.nextGoal(),x.story().explorationOpen()==null?"자유 탐험 상태 미등록":Boolean.TRUE.equals(x.story().explorationOpen())?"공동체 자유 탐험 개방":"자유 탐험 미개방","개인 퀘스트/보상/거점 해금과 별개입니다."));}
            case "conditions"->{for(String line:x.conditions().lines())out.add(row(line,"현재 접속한 해당 진행 플레이어만 검사합니다. 오프라인은 제외하며 조회로 공유 단계를 진행하지 않습니다."));}
            case "branches"->x.branches().forEach(branch->out.add(row(branch,"한 번 확정된 공동체 분기는 변경하지 않습니다. 조회로 개인 퀘스트·보상을 지급하지 않습니다.")));
            default->throw new IllegalArgumentException();
        }return List.copyOf(out);
    }
}
