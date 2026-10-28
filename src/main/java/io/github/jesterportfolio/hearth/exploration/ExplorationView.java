package io.github.jesterportfolio.hearth.exploration;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;

/** GUI/S5가 공유할 공개 DTO. 서버 발견 bounds/미공개 좌표 참조를 포함하지 않습니다. */
public record ExplorationView(boolean registered,List<Region> regions,List<Place> places,List<ExplorationStore.Fact> facts,List<ExplorationStore.Event> events,
        List<CommunityBoundary.Member> members,ExplorationStore.Story story,CommunityBoundary.Status conditions,String nextGoal,Set<String> blockedQuests,List<String> branches,String effectStatus){
    public ExplorationView(boolean registered,List<Region> regions,List<Place> places,List<ExplorationStore.Fact> facts,List<ExplorationStore.Event> events,List<CommunityBoundary.Member> members,ExplorationStore.Story story,CommunityBoundary.Status conditions,String nextGoal,Set<String> blockedQuests,List<String> branches){this(registered,regions,places,facts,events,members,story,conditions,nextGoal,blockedQuests,branches,"NOT_IMPLEMENTED");}
    public ExplorationView withEffectStatus(String status){return new ExplorationView(registered,regions,places,facts,events,members,story,conditions,nextGoal,blockedQuests,branches,status);}
    public record Region(String id,String name,String access){}
    public record Place(String key,String name,String region,String category,String location,String status,boolean visited,boolean discovered,boolean hubUnlocked){}
    public ExplorationView{regions=List.copyOf(regions);places=List.copyOf(places);facts=List.copyOf(facts);events=List.copyOf(events);members=List.copyOf(members);blockedQuests=Set.copyOf(blockedQuests);branches=List.copyOf(branches);}
    public static ExplorationView empty(){return new ExplorationView(false,List.of(),List.of(),List.of(),List.of(),List.of(),new ExplorationStore.Story(null,null,null,-1),new CommunityBoundary.Status(false,List.of("진행 조건 미등록")),null,Set.of(),List.of());}
    public static ExplorationView build(ExplorationContent content,ExplorationStore.Data data,List<CommunityBoundary.Member> members,CommunityBoundary.Status status){
        return build(content,data,members,status,p->true,r->"일반 개방",Set.of(),List.of());
    }
    public static ExplorationView build(ExplorationContent content,ExplorationStore.Data data,List<CommunityBoundary.Member> members,CommunityBoundary.Status status,java.util.function.Predicate<ExplorationContent.Place> visible,java.util.function.Function<String,String> access,Set<String> blocked,List<String> branches){
        var places=new ArrayList<Place>();var knownRegions=new HashSet<String>();
        for(var r:content.regions().values())if(r.published())knownRegions.add(r.id());
        for(var p:content.places().values()){
            if(!visible.test(p))continue;
            var saved=data.places().get(p.id());boolean discovered=saved!=null && saved.discovered()!=null;
            if(!discovered && p.visibility()==ExplorationContent.Visibility.HIDDEN)continue;
            var r=content.regions().get(p.region());if(discovered)knownRegions.add(r.id());
            boolean named=discovered || p.visibility()==ExplorationContent.Visibility.PUBLIC;
            String region=r.published()||discovered?r.name():"미공개 지역";
            places.add(new Place(named?p.id():p.marker(),named?p.name():"?",region,named?p.category():"미발견 장소",named?p.location().text():p.unknownLocation().text(),discovered?"발견":"미발견",saved!=null&&saved.visited()!=null,discovered,saved!=null&&saved.hub()));
        }
        return new ExplorationView(true,content.regions().values().stream().filter(r->knownRegions.contains(r.id())).map(r->new Region(r.id(),r.name(),access.apply(r.id()))).sorted(Comparator.comparing(Region::id)).toList(),places.stream().sorted(Comparator.comparing(Place::key)).toList(),data.facts(),data.events(),members,data.story(),status,data.story().stage()==null?null:content.sharedGoals().get(data.story().stage()),blocked,branches);
    }
    public Optional<Place> place(String key){return places.stream().filter(p->p.key().equals(key)).findFirst();}
}
