package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.QuestStore;
import java.util.*;

/** 이미 허용된 개인 정보만 책 페이지로 구성합니다. 실제 책 아이템은 지급하지 않습니다. */
public record QuestBookView(String title,List<String> pages){
    public QuestBookView{pages=List.copyOf(pages);}
    public static Optional<QuestBookView> accepted(QuestCatalog catalog,Map<String,QuestStore.Saved> rows,Set<String> blocked,String id){
        if(blocked.contains(id))return Optional.empty();
        return QuestListView.detail(catalog,rows,id).map(detail->new QuestBookView(detail.quest().title(),paginate(QuestListView.detailLines(Optional.of(detail)))));
    }
    public static QuestBookView dialogue(QuestContent content,QuestStore.Saved saved){
        String text=switch(saved.phase()){
            case NOT_ACCEPTED->content.text().get("offer");
            case REPORT_READY->content.text().get("report-ready");
            case COMPLETED->content.text().get("completed");
            default->content.detailGuidance(saved);
        };
        return new QuestBookView(content.title(),paginate(List.of("퀘스트: "+content.title(),"NPC: "+content.name(),"상태: "+QuestListView.status(saved.phase()),text)));
    }
    /** 긴 설명도 조용히 잘리지 않도록 보수적인 줄/페이지 크기로 나눕니다. */
    static List<String> paginate(List<String> paragraphs){
        var pages=new ArrayList<String>();var page=new StringBuilder();int lines=0;
        for(String paragraph:paragraphs){
            for(String line:paragraph.split("\\R",-1)){
                int[] points=line.codePoints().toArray();
                for(int i=0;i<Math.max(1,points.length);i+=12){
                    if(lines==12){pages.add(page.toString());page.setLength(0);lines=0;}
                    if(lines++>0)page.append('\n');if(points.length>0)page.append(new String(points,i,Math.min(12,points.length-i)));
                }
            }
            if(lines<12){page.append('\n');lines++;}
        }
        if(!page.isEmpty())pages.add(page.toString());
        if(pages.isEmpty())pages.add("");if(pages.size()>99)throw new IllegalArgumentException("QUEST_BOOK_TOO_LONG");
        return List.copyOf(pages);
    }
}
