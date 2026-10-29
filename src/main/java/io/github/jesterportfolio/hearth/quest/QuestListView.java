package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.QuestStore;
import java.util.*;

/** 채팅/향후 GUI가 공유할 읽기 전용 표시 데이터. 미수락 콘텐츠는 행을 만들지 않습니다. */
public final class QuestListView {
    private QuestListView() {}
    public static boolean available(QuestContent c,Map<String,QuestStore.Saved> rows){
        var own=Objects.requireNonNull(rows.get(c.id()),"Missing quest state");
        return own.phase()!=QuestStore.Phase.NOT_ACCEPTED || c.requiresCompleted()==null
            || Objects.requireNonNull(rows.get(c.requiresCompleted()),"Missing prerequisite state").phase()==QuestStore.Phase.COMPLETED;
    }
    public static final String UNAVAILABLE="조회할 수 있는 퀘스트가 없습니다";
    public record Detail(Entry quest,String npc,String guidance) {}
    public record Entry(String questId,String title,QuestStore.Phase phase,String status) {}
    public static List<Entry> entries(QuestCatalog catalog,Map<String,QuestStore.Saved> states) {
        if(!states.keySet().equals(catalog.questIds()))throw new IllegalArgumentException("Incomplete quest list response");
        var rows=new ArrayList<Entry>();
        for(String id:new TreeSet<>(catalog.questIds())) {
            var state=Objects.requireNonNull(states.get(id));
            if(state.phase()==QuestStore.Phase.NOT_ACCEPTED)continue;
            String status=switch(state.phase()) {
                case ACTIVE -> "진행 중";
                case REPORT_READY -> "보고 대기";
                case COMPLETED -> "완료";
                default -> throw new IllegalArgumentException("Unsupported list phase");
            };
            rows.add(new Entry(id,catalog.quest(id).title(),state.phase(),status));
        }
        return List.copyOf(rows);
    }
    public enum Filter{ALL,REPORTING,TRACKED,COMPLETED}
    public static List<Entry> filtered(QuestCatalog catalog,Map<String,QuestStore.Saved> states,Set<String> tracked,Set<String> blocked,Filter filter){
        return entries(catalog,states).stream().filter(q->!blocked.contains(q.questId())).filter(q->switch(filter){
            case ALL->true;case REPORTING->q.phase()==QuestStore.Phase.REPORT_READY;case TRACKED->tracked.contains(q.questId())&&q.phase()!=QuestStore.Phase.COMPLETED;case COMPLETED->q.phase()==QuestStore.Phase.COMPLETED;
        }).sorted(Comparator.comparingInt((Entry q)->q.phase()==QuestStore.Phase.REPORT_READY?0:tracked.contains(q.questId())&&q.phase()!=QuestStore.Phase.COMPLETED?1:q.phase()==QuestStore.Phase.ACTIVE?2:3).thenComparing(Entry::questId)).toList();
    }
    /** 목록에서 허용된 항목만 찾아 상세 데이터를 만듭니다. 미수락/미존재의 결과는 같습니다. */
    public static Optional<Detail> detail(QuestCatalog catalog,Map<String,QuestStore.Saved> states,String id) {
        return entries(catalog,states).stream().filter(row->row.questId().equals(id)).findFirst().map(row->{
            var content=catalog.quest(row.questId());
            String key=switch(row.phase()){case ACTIVE->"active";case REPORT_READY->"report-ready";case COMPLETED->"completed";default->throw new IllegalArgumentException("Unsupported detail phase");};
            return new Detail(row,content.name(),content.detailGuidance(states.get(row.questId())));
        });
    }
    public static String infoCommand(Entry row) {
        if(!row.questId().matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid detail target");
        return "/hearthquest info "+row.questId();
    }
    public static Optional<String> infoId(String command) {
        var match=java.util.regex.Pattern.compile("/hearthquest info ([a-z][a-z0-9_]{0,63})").matcher(command);
        return match.matches()?Optional.of(match.group(1)):Optional.empty();
    }
    public static List<String> detailLines(Optional<Detail> detail) {
        return detail.map(row->List.of("퀘스트: "+row.quest().title(),"NPC: "+row.npc(),"상태: "+row.quest().status(),"안내: "+row.guidance()))
            .orElseGet(()->List.of(UNAVAILABLE));
    }
    public static List<String> lines(List<Entry> rows) {
        return rows.isEmpty()?List.of("수락한 퀘스트가 없습니다"):rows.stream().map(row->"["+row.status()+"] "+row.title()).toList();
    }
}
