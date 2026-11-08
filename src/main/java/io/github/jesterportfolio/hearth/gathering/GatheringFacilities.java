package io.github.jesterportfolio.hearth.gathering;

import java.util.*;

/** 현장 시설 정의/확인 표식. 운영 배치나 새로운 입장 조건을 확정하지 않습니다. */
public record GatheringFacilities(List<Site> sites){
    public enum Action{ENTER,LEAVE}
    public record Site(String id,String title,Action action,int x,int y,int z,String material){
        public Site{GatheringContent.key(id);Objects.requireNonNull(action);if(title==null||title.isBlank()||title.length()>80||!"STONE_BUTTON".equals(material)||Math.abs((long)x)>29999984||Math.abs((long)z)>29999984||y< -64||y>319)throw new IllegalArgumentException("GATHERING_FACILITY");}
        public boolean near(double px,double py,double pz){return Math.pow(px-x-0.5,2)+Math.pow(py-y-0.5,2)+Math.pow(pz-z-0.5,2)<=16;}
    }
    public record Stamp(UUID run,Object session,UUID world,long worldGeneration,long generation,long revision,GatheringStore.Phase phase,boolean member,boolean busy){public Stamp{Objects.requireNonNull(run);Objects.requireNonNull(session);Objects.requireNonNull(world);Objects.requireNonNull(phase);}}
    public record Choice(Site site,Stamp stamp){}
    public GatheringFacilities{sites=List.copyOf(sites);if(sites.size()!=2||sites.stream().map(Site::id).distinct().count()!=sites.size()||sites.stream().map(s->s.x()+"/"+s.y()+"/"+s.z()).distinct().count()!=sites.size()||sites.stream().map(Site::action).distinct().count()!=2)throw new IllegalArgumentException("GATHERING_FACILITY_LINKS");}
    public static GatheringFacilities parse(Map<?,?> root,GatheringContent content){
        if(!Integer.valueOf(1).equals(root.get("format"))||!Boolean.TRUE.equals(root.get("isolated-prototype"))||!content.id().equals(root.get("gathering-id"))||!(root.get("sites") instanceof List<?> rows))throw new IllegalArgumentException("GATHERING_FACILITY_FORMAT");
        var sites=new ArrayList<Site>();for(Object value:rows){if(!(value instanceof Map<?,?> row))throw new IllegalArgumentException("GATHERING_FACILITY_MAP");var s=new Site(text(row,"id"),text(row,"title"),Action.valueOf(text(row,"action")),integer(row,"x"),integer(row,"y"),integer(row,"z"),text(row,"material"));if(content.area().contains(s.x(),s.y(),s.z())||s.action()==Action.LEAVE&&!s.near(content.spawn().x(),content.spawn().y(),content.spawn().z()))throw new IllegalArgumentException("GATHERING_FACILITY_RESET_BOUNDARY");sites.add(s);}return new GatheringFacilities(sites);
    }
    public Choice choose(Site site,Stamp stamp,boolean allowed){if(!sites.contains(site)||!allowed||stamp.busy()||stamp.phase()!=GatheringStore.Phase.OPEN||stamp.member()!=(site.action()==Action.LEAVE))throw new IllegalStateException("GATHERING_FACILITY_UNAVAILABLE");return new Choice(site,stamp);}
    public void require(Choice shown,Site site,Stamp fresh,boolean allowed){if(shown.site()!=site||shown.stamp().session()!=fresh.session()||!shown.equals(choose(site,fresh,allowed)))throw new IllegalStateException("STALE_GATHERING_FACILITY");}
    private static String text(Map<?,?> m,String k){if(!(m.get(k) instanceof String s))throw new IllegalArgumentException("GATHERING_FACILITY_TEXT");return s;}
    private static int integer(Map<?,?> m,String k){if(!(m.get(k) instanceof Number n)||n.doubleValue()!=n.intValue())throw new IllegalArgumentException("GATHERING_FACILITY_POSITION");return n.intValue();}
}
