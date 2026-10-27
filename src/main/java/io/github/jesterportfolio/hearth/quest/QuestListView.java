package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.QuestStore;
import java.util.*;

/** 채팅/향후 GUI가 공유할 읽기 전용 표시 데이터. 미수락 콘텐츠는 행을 만들지 않습니다. */
public final class QuestListView {
    private QuestListView() {}
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
    public static List<String> lines(List<Entry> rows) {
        return rows.isEmpty()?List.of("수락한 퀘스트가 없습니다"):rows.stream().map(row->"["+row.status()+"] "+row.title()).toList();
    }
}
