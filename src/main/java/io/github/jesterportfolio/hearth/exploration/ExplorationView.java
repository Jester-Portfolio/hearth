package io.github.jesterportfolio.hearth.exploration;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;

/** GUI/S5가 공유할 공개 DTO. 서버 발견 bounds/미공개 좌표 참조를 포함하지 않습니다. */
public record ExplorationView(boolean registered,List<Region> regions,List<Place> places,List<ExplorationStore.Fact> facts,List<ExplorationStore.Event> events,
        List<CommunityBoundary.Member> members,ExplorationStore.Story story,CommunityBoundary.Status conditions){
    public record Region(String id,String name){}
    public record Place(String key,String name,String region,String category,String location,String status,boolean visited,boolean discovered,boolean hubUnlocked){}
    public ExplorationView{regions=List.copyOf(regions);places=List.copyOf(places);facts=List.copyOf(facts);events=List.copyOf(events);members=List.copyOf(members);}
    public static ExplorationView empty(){return new ExplorationView(false,List.of(),List.of(),List.of(),List.of(),List.of(),new ExplorationStore.Story(null,null,null,-1),new CommunityBoundary.Status(false,List.of("진행 조건 미등록")));}
    public static ExplorationView build(ExplorationContent content,ExplorationStore.Data data,List<CommunityBoundary.Member> members,CommunityBoundary.Status status){
        var places=new ArrayList<Place>();var knownRegions=new HashSet<String>();
        for(var r:content.regions().values())if(r.published())knownRegions.add(r.id());
        for(var p:content.places().values()){
            var saved=data.places().get(p.id());boolean discovered=saved!=null && saved.discovered()!=null;
            if(!discovered && p.visibility()==ExplorationContent.Visibility.HIDDEN)continue;
            var r=content.regions().get(p.region());if(discovered)knownRegions.add(r.id());
            boolean named=discovered || p.visibility()==ExplorationContent.Visibility.PUBLIC;
            String region=r.published()||discovered?r.name():"미공개 지역";
            places.add(new Place(named?p.id():p.marker(),named?p.name():"?",region,named?p.category():"미발견 장소",named?p.location().text():p.unknownLocation().text(),discovered?"발견":"미발견",saved!=null&&saved.visited()!=null,discovered,saved!=null&&saved.hub()));
        }
        return new ExplorationView(true,content.regions().values().stream().filter(r->knownRegions.contains(r.id())).map(r->new Region(r.id(),r.name())).sorted(Comparator.comparing(Region::id)).toList(),places.stream().sorted(Comparator.comparing(Place::key)).toList(),data.facts(),data.events(),members,data.story(),status);
    }
    public Optional<Place> place(String key){return places.stream().filter(p->p.key().equals(key)).findFirst();}
}
