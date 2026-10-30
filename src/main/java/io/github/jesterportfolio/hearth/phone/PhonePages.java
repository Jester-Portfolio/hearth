package io.github.jesterportfolio.hearth.phone;

import io.github.jesterportfolio.hearth.quest.GameInformation;
import java.util.*;

/** GUI와 분리된 공개 정보 페이지. 원본 콘텐츠·소유권 ID를 입력받지 않습니다. */
public final class PhonePages {
    private PhonePages(){}
    public record Entry(int index,String label){}
    public record Page(List<Entry> entries,int number,int total){public Page{entries=List.copyOf(entries);}}
    public record Detail(String title,List<String> pages){public Detail{pages=List.copyOf(pages);}}
    public static int size(GameInformation data,String section){return switch(section){case "quests"->data.quests().size();case "map"->data.places().size();case "diary"->data.diary().size();default->throw new IllegalArgumentException("UNKNOWN_PHONE_SECTION");};}
    public static Page list(GameInformation data,String section,int page){
        int size=size(data,section),total=Math.max(1,(size+5)/6);if(page<1||page>total)throw new IllegalArgumentException("PHONE_PAGE_RANGE");
        var entries=new ArrayList<Entry>();for(int i=(page-1)*6;i<Math.min(size,page*6);i++){
            String label=switch(section){case "quests"->{var q=data.quests().get(i);yield "["+q.status()+"] "+q.title();}case "map"->{var p=data.places().get(i);yield p.name()+" · "+p.status();}case "diary"->{var d=data.diary().get(i);yield (d.shared()?"[공유] ":"[개인] ")+d.title();}default->throw new IllegalArgumentException();};
            entries.add(new Entry(i,label));
        }return new Page(entries,page,total);
    }
    public static Detail detail(GameInformation data,String section,int index){
        if(index<0||index>=size(data,section))throw new IllegalArgumentException("PHONE_DETAIL_RANGE");
        String title;List<String> lines;
        switch(section){
            case "quests"->{var q=data.quests().get(index);title=q.title();lines=List.of("NPC: "+q.npc(),"상태: "+q.status(),q.guidance());}
            case "map"->{var p=data.places().get(index);title=p.name();lines=List.of("지역: "+p.region(),"공개 상태: "+p.status(),p.location(),p.hubUnlocked()?"본인 방문 거점 해금":"빠른 이동 거점 미해금");}
            case "diary"->{var d=data.diary().get(index);title=d.title();lines=List.of(d.shared()?"공동체 공유 기록 · 개인 완료/보상과 별개":"개인 기록",d.category(),d.summary(),d.detail());}
            default->throw new IllegalArgumentException("UNKNOWN_PHONE_SECTION");
        }
        return text(title,lines);
    }
    public static Detail text(String title,List<String> lines){
        // 개별 본문 길이를 제한하되 다음 페이지에서 남은 내용도 읽을 수 있게 합니다.
        String text=String.join("\n\n",lines);int[] points=text.codePoints().toArray();var pages=new ArrayList<String>();
        for(int i=0;i<points.length;i+=480)pages.add(new String(points,i,Math.min(480,points.length-i)));
        if(pages.isEmpty())pages.add("");return new Detail(title,pages);
    }
}
