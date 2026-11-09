package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.phone.*;
import java.util.*;

/** 진행판에서 이미 공개된 장소/일지/연대기와 현재 공동체 정보를 읽는 공통 화면 모델입니다. */
public final class CourseRecordView {
    private static final Set<String> SOURCES=Set.of("map","regions","STORY","PERSON","EXPLORATION","CHRONICLE");
    private static final Set<String> COMMUNITY=Set.of("shared","conditions","branches");
    public enum Action { REFRESH, BACK, PREVIOUS, NEXT, RELATED }
    public record Target(String section,String key){
        public Target{if(section==null||!SOURCES.contains(section)&&!COMMUNITY.contains(section)||key==null||key.length()>180||!key.matches("[A-Za-z0-9_:]+")||COMMUNITY.contains(section)&&!key.equals("current"))throw new IllegalArgumentException("COURSE_RECORD_TARGET");}
    }
    public record Link(String label,Target target){}
    public record Page(int number,int pages,List<String> lines,List<Link> links){
        public Page{lines=List.copyOf(lines);links=List.copyOf(links);}
        public QuestBookView book(String title){return new QuestBookView(title,QuestBookView.paginate(lines));}
    }
    public record Record(Target target,String title,List<PhoneLibrary.Entry> entries,List<String> text,List<Link> links){
        public Record{entries=List.copyOf(entries);text=List.copyOf(text);links=List.copyOf(links);}
        public int pages(){return Math.max(1,Math.max(text.size(),(links.size()+5)/6));}
        public Page page(int number){
            if(number<1||number>pages())throw new IllegalArgumentException("COURSE_RECORD_PAGE");var lines=new ArrayList<String>();
            lines.add(number<=text.size()?text.get(number-1):"추가 관련 정보를 선택하세요.");lines.add(number+" / "+pages()+"쪽");
            int from=Math.min(links.size(),(number-1)*6);return new Page(number,pages(),lines,links.subList(from,Math.min(links.size(),from+6)));
        }
    }
    public record Screen(PlaythroughOverview.Board board,PlaythroughOverview.Row row,int coursePage,List<Target> path,Record record,Page page){public Screen{path=List.copyOf(path);}}
    private CourseRecordView(){}
    public static boolean readable(PlaythroughOverview.Row row){return row.kind()==PlaythroughOverview.Kind.EXPLORATION||row.kind()==PlaythroughOverview.Kind.SHARED;}
    public static String label(PlaythroughOverview.Row row){return row.kind()==PlaythroughOverview.Kind.SHARED?"현재 공동체 상태":"장소와 관련 기록";}
    private static Target root(Playthrough plan,QuestJournal.View view,PlaythroughOverview.Row row){
        var step=plan.steps().stream().filter(s->s.id().equals(row.key())).findFirst().orElseThrow(()->new IllegalStateException("COURSE_RECORD_UNAVAILABLE"));
        if(row.kind()==PlaythroughOverview.Kind.EXPLORATION&&step.kind()==Playthrough.Kind.PLACE){var place=view.exploration().place(step.reference()).orElseThrow(()->new IllegalStateException("COURSE_RECORD_UNAVAILABLE"));return new Target("map",place.key());}
        if(row.kind()==PlaythroughOverview.Kind.SHARED&&(step.kind()==Playthrough.Kind.STAGE||step.kind()==Playthrough.Kind.STAGE_COMPLETE))return new Target("shared","current");
        throw new IllegalStateException("COURSE_RECORD_UNAVAILABLE");
    }
    private static Record read(QuestCatalog catalog,QuestJournal.View view,Map<String,String> placeFacts,Target target){
        var links=new ArrayList<Link>();List<PhoneLibrary.Entry> entries;String title;
        if(SOURCES.contains(target.section())){
            var source=PhoneReaderLinks.resolve(catalog,view,target.section(),target.key());entries=List.of(source.entry());title=source.entry().title();
            for(var link:PhoneReaderLinks.links(catalog,view,placeFacts,source))if(link.kind()!=PhoneReaderLinks.Kind.NPC)links.add(new Link(link.label(),new Target(link.section(),link.key())));
        }else{
            entries=PhoneLibrary.rows(catalog,view,target.section());title="현재 공동체 · "+PhoneLibrary.title(target.section());
            for(String section:List.of("shared","conditions","branches"))if(!section.equals(target.section()))links.add(new Link(PhoneLibrary.title(section),new Target(section,"current")));
        }
        var lines=new ArrayList<String>();for(var entry:entries){lines.add(entry.title());lines.addAll(entry.lines());}
        if(entries.isEmpty())lines.add("현재 공개된 기록이 없습니다.");
        if(COMMUNITY.contains(target.section()))lines.add("현재 공동체의 공개 정보입니다. 선택한 과거 단계의 당시 상태를 재현하지 않습니다.");
        return new Record(target,title,entries,PhonePages.text(title,lines).pages(),links);
    }
    private static Record resolve(Playthrough plan,QuestCatalog catalog,QuestJournal.View view,Map<String,String> placeFacts,PlaythroughOverview.Row row,List<Target> path){
        if(path.isEmpty()||path.size()>8||!path.getFirst().equals(root(plan,view,row)))throw new IllegalStateException("STALE_COURSE_RECORD_PATH");
        Record current=read(catalog,view,placeFacts,path.getFirst());
        for(int n=1;n<path.size();n++){var next=path.get(n);if(current.links().stream().noneMatch(l->l.target().equals(next)))throw new IllegalStateException("STALE_COURSE_RECORD_PATH");current=read(catalog,view,placeFacts,next);}
        return current;
    }
    public static Screen open(Playthrough plan,QuestCatalog catalog,QuestJournal.View view,Map<String,String> placeFacts,PlaythroughOverview.Board fresh,PlaythroughOverview.Board shown,PlaythroughOverview.Row row,int coursePage){
        PlaythroughOverview.requireRow(fresh,shown,row);if(!fresh.page(coursePage).rows().contains(row)||!readable(row))throw new IllegalStateException("COURSE_RECORD_UNAVAILABLE");
        var path=List.of(root(plan,view,row));var record=resolve(plan,catalog,view,placeFacts,row,path);return new Screen(fresh,row,coursePage,path,record,record.page(1));
    }
    public static Screen navigate(Playthrough plan,QuestCatalog catalog,QuestJournal.View view,Map<String,String> placeFacts,PlaythroughOverview.Board fresh,Screen shown,Action action,Link selected){
        var row=shown.row();boolean refresh=action==Action.REFRESH||action==Action.BACK;
        if(refresh){var previous=row;row=fresh.rows().stream().filter(r->r.key().equals(previous.key())&&r.kind()==previous.kind()).findFirst().orElseThrow(()->new IllegalStateException("COURSE_RECORD_UNAVAILABLE"));PlaythroughOverview.requireRow(fresh,fresh,row);}
        else PlaythroughOverview.requireRow(fresh,shown.board(),row);
        var path=shown.path();if(action==Action.BACK){if(path.size()<2)throw new IllegalStateException("COURSE_RECORD_NO_PARENT");path=List.copyOf(path.subList(0,path.size()-1));}
        var record=resolve(plan,catalog,view,placeFacts,row,path);int page=1;
        if(!refresh){
            if(!record.equals(shown.record()))throw new IllegalStateException("STALE_COURSE_RECORD");
            switch(action){
                case PREVIOUS->page=shown.page().number()-1;
                case NEXT->page=shown.page().number()+1;
                case RELATED->{
                    if(selected==null||!record.page(shown.page().number()).links().contains(selected))throw new IllegalStateException("STALE_COURSE_RECORD_LINK");
                    var next=new ArrayList<>(path);int existing=next.indexOf(selected.target());if(existing>=0)next.subList(existing+1,next.size()).clear();else{if(next.size()>=8)throw new IllegalStateException("COURSE_RECORD_DEPTH");next.add(selected.target());}
                    path=List.copyOf(next);record=resolve(plan,catalog,view,placeFacts,row,path);
                }
                default->throw new IllegalArgumentException("COURSE_RECORD_ACTION");
            }
        }else if(action==Action.REFRESH)page=Math.min(shown.page().number(),record.pages());
        return new Screen(fresh,row,fresh.rows().indexOf(row)/PlaythroughOverview.PAGE_SIZE+1,path,record,record.page(page));
    }
}
