package io.github.jesterportfolio.hearth.phone;

import io.github.jesterportfolio.hearth.quest.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;

/** 서버 내부 화면 연결. 공개 조회 스냅샷만 사용하며 키를 본문이나 AI 문맥으로 전달하지 않습니다. */
public final class PhoneReaderLinks {
    private PhoneReaderLinks(){}
    public enum Kind{LIBRARY,PLACE,NPC}
    public record Source(String section,String key,PhoneLibrary.Entry entry){}
    public record Link(String label,Kind kind,String section,String key){}
    private static String scope(boolean shared){return shared?"S:":"P:";}
    public static Source library(QuestCatalog catalog,QuestJournal.View view,String section,int index){
        var entry=PhoneLibrary.rows(catalog,view,section).get(index);String key=switch(section){
            case "STORY","PERSON","EXPLORATION"->{var f=PhoneLibrary.facts(view,section).get(index);yield scope(f.shared())+f.id();}
            case "CHRONICLE"->{var e=PhoneLibrary.events(view).get(index);yield scope(e.shared())+e.id()+":"+e.version();}
            case "regions"->view.exploration().regions().get(index).id();
            default->throw new IllegalArgumentException("READER_SOURCE_UNAVAILABLE");
        };return new Source(section,key,entry);
    }
    public static Source place(QuestJournal.View view,String key){
        var p=view.exploration().place(key).orElseThrow(()->new IllegalArgumentException("PLACE_UNAVAILABLE"));
        return new Source("map",key,new PhoneLibrary.Entry(p.name(),List.of(p.region(),p.category(),p.location(),p.status(),"직접 방문: "+p.visited(),"거점 해금: "+p.hubUnlocked()),null,-1));
    }
    private static List<String> keys(QuestJournal.View view,String section){return switch(section){
        case "STORY","PERSON","EXPLORATION"->PhoneLibrary.facts(view,section).stream().map(f->scope(f.shared())+f.id()).toList();
        case "CHRONICLE"->PhoneLibrary.events(view).stream().map(e->scope(e.shared())+e.id()+":"+e.version()).toList();
        case "regions"->view.exploration().regions().stream().map(r->r.id()).toList();
        case "map"->view.exploration().places().stream().map(p->p.key()).toList();
        default->throw new IllegalArgumentException("READER_SOURCE_UNAVAILABLE");
    };}
    public static int index(QuestCatalog catalog,QuestJournal.View view,String section,String key){int index=keys(view,section).indexOf(key);if(index<0)throw new IllegalArgumentException("READER_SOURCE_UNAVAILABLE");return index;}
    public static Source resolve(QuestCatalog catalog,QuestJournal.View view,String section,String key){return section.equals("map")?place(view,key):library(catalog,view,section,index(catalog,view,section,key));}
    public static List<Link> links(QuestCatalog catalog,QuestJournal.View view,Map<String,String> placeFacts,Source source){
        if(!resolve(catalog,view,source.section(),source.key()).equals(source))throw new IllegalStateException("STALE_READER_SOURCE");var out=new ArrayList<Link>();
        if(Set.of("STORY","PERSON","EXPLORATION").contains(source.section())){
            var f=PhoneLibrary.facts(view,source.section()).get(index(catalog,view,source.section(),source.key()));
            for(var npc:NpcContacts.forFact(catalog,view,source.key()).stream().sorted(Comparator.comparing(NpcContacts.Contact::name).thenComparing(NpcContacts.Contact::npc)).toList())out.add(new Link("관련 NPC: "+npc.name(),Kind.NPC,null,npc.key()));
            PhoneLibrary.events(view).stream().filter(e->e.id().equals(f.id())&&e.shared()==f.shared()).min(Comparator.comparing(ExplorationStore.Event::time).thenComparingInt(ExplorationStore.Event::version)).ifPresent(e->out.add(new Link("최초 기록",Kind.LIBRARY,"CHRONICLE",scope(e.shared())+e.id()+":"+e.version())));
            for(var p:view.exploration().places())if(p.discovered()&&source.key().equals(placeFacts.get(p.key())))out.add(new Link("발견 장소: "+p.name(),Kind.PLACE,"map",p.key()));
        }else if(source.section().equals("CHRONICLE")){
            var event=PhoneLibrary.events(view).get(index(catalog,view,source.section(),source.key()));view.exploration().facts().stream().filter(f->f.id().equals(event.id())&&f.shared()==event.shared()).findFirst().ifPresent(f->out.add(new Link("현재 기록",Kind.LIBRARY,f.category(),scope(f.shared())+f.id())));
        }else if(source.section().equals("map")){
            var p=view.exploration().place(source.key()).orElseThrow();var regions=view.exploration().regions().stream().filter(r->r.name().equals(p.region())).toList();if(regions.size()==1)out.add(new Link("지역 정보: "+regions.getFirst().name(),Kind.LIBRARY,"regions",regions.getFirst().id()));
            if(p.discovered()){String key=placeFacts.get(p.key());view.exploration().facts().stream().filter(f->!f.shared()&&(scope(false)+f.id()).equals(key)).findFirst().ifPresent(f->out.add(new Link("발견 일지",Kind.LIBRARY,f.category(),key)));}
        }else if(source.section().equals("regions")){
            var region=view.exploration().regions().get(index(catalog,view,source.section(),source.key()));if(view.exploration().regions().stream().filter(r->r.name().equals(region.name())).count()==1)for(var p:view.exploration().places())if(p.region().equals(region.name()))out.add(new Link(p.name()+" · "+p.status(),Kind.PLACE,"map",p.key()));
        }return List.copyOf(out);
    }
    public static void require(QuestCatalog catalog,QuestJournal.View view,Map<String,String> placeFacts,Source expected,Link selected){if(!links(catalog,view,placeFacts,expected).contains(selected))throw new IllegalStateException("STALE_READER_LINK");}
}
