package io.github.jesterportfolio.hearth.survival.housing;
import java.util.*;
public final class HousingState {
 private HousingState(){}
 public enum Role{RESIDENT,VISITOR}public enum Phase{PREPARING,APPLYING,ACTIVE}
 public record House(UUID id,UUID owner,String version,int tier,int width,long paid,long seed,UUID world,Phase phase,long revision){public House{Objects.requireNonNull(id);Objects.requireNonNull(owner);Objects.requireNonNull(version);Objects.requireNonNull(phase);if(tier<0||width<16||paid<0||revision<0)throw new IllegalArgumentException("HOUSING_HOUSE");}public String key(){return "hearth_housing:h_"+id.toString().replace("-","");}}
 public record Member(UUID house,UUID player,Role role,boolean accepted){}
 public record Request(UUID id,UUID house,UUID owner,String version,int tier,long cost,long revision,boolean paid){}
 public record Snapshot(List<House> houses,List<Member> members,Map<UUID,String> plots,List<UUID> players,Map<String,HousingSettings.Catalog> catalogs){public Snapshot{houses=List.copyOf(houses);members=List.copyOf(members);plots=Map.copyOf(plots);players=List.copyOf(players);catalogs=Map.copyOf(catalogs);}public Optional<House> owned(UUID p){return houses.stream().filter(h->h.owner().equals(p)).findFirst();}public boolean enter(UUID p,House h){return h.phase()==Phase.ACTIVE&&(h.owner().equals(p)||members.stream().anyMatch(m->m.house().equals(h.id())&&m.player().equals(p)&&m.accepted()));}public boolean build(UUID p,House h){return enter(p,h)&&(h.owner().equals(p)||members.stream().anyMatch(m->m.house().equals(h.id())&&m.player().equals(p)&&m.accepted()&&m.role()==Role.RESIDENT));}}
}
