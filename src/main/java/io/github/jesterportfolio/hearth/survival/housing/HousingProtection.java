package io.github.jesterportfolio.hearth.survival.housing;

import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.flags.*;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.*;
import io.github.jesterportfolio.hearth.persistence.PostgresProgressStore;
import io.github.jesterportfolio.hearth.survival.SurvivalWorld;
import static io.github.jesterportfolio.hearth.survival.housing.HousingState.*;

/** 일반 블록/간접 보호는 WorldGuard에 맡기며 관리하는 구역만 변경합니다. */
public final class HousingProtection {
 private final PostgresProgressStore db;private final HousingSettings settings;private final Location spawn;private final Map<SurvivalWorld,World> common;private final java.util.function.Function<SurvivalWorld,Location> entries;
 public HousingProtection(PostgresProgressStore db,HousingSettings settings,Location spawn,Map<SurvivalWorld,World> common,java.util.function.Function<SurvivalWorld,Location> entries){this.db=db;this.settings=settings;this.spawn=spawn.clone();this.common=Map.copyOf(common);this.entries=entries;}
 public static RegionManager manager(World w){return Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(w)),"HOUSING_WG_UNAVAILABLE");}
 private ProtectedRegion region(World w,String id,int minX,int minY,int minZ,int maxX,int maxY,int maxZ,int priority){var m=manager(w);var lo=BlockVector3.at(minX,minY,minZ);var hi=BlockVector3.at(maxX,maxY,maxZ);var r=m.getRegion(id);if(r==null){r=new ProtectedCuboidRegion(id,lo,hi);r.setPriority(priority);r.setFlag(Flags.PASSTHROUGH,StateFlag.State.DENY);m.addRegion(r);}else if(!r.getMinimumPoint().equals(lo)||!r.getMaximumPoint().equals(hi)||r.getPriority()!=priority||r.getFlag(Flags.PASSTHROUGH)!=StateFlag.State.DENY)throw new IllegalStateException("HOUSING_REGION_CONFLICT");return r;}
 private void environmental(ProtectedRegion r){for(var flag:List.of(Flags.TNT,Flags.CREEPER_EXPLOSION,Flags.OTHER_EXPLOSION,Flags.GHAST_FIREBALL,Flags.FIRE_SPREAD,Flags.LAVA_FIRE))r.setFlag(flag,StateFlag.State.DENY);}
 public CompletionStage<Void> common(Snapshot snapshot){var w=spawn.getWorld();var r=region(w,"hearth_housing_public",-29999984,w.getMinHeight(),-29999984,29999984,w.getMaxHeight()-1,29999984,1000);r.setFlag(Flags.INTERACT,StateFlag.State.ALLOW);r.setFlag(Flags.USE,StateFlag.State.ALLOW);environmental(r);
  for(var plot:settings.plots()){int x=spawn.getBlockX()+plot.x(),z=spawn.getBlockZ()+plot.z(),y=spawn.getBlockY();var area=region(w,"hearth_plot_"+plot.id(),x,y,z,x+plot.width()-1,Math.min(w.getMaxHeight()-1,y+plot.height()-1),z+plot.width()-1,1001);var owners=new DefaultDomain();snapshot.plots().forEach((player,id)->{if(id.equals(plot.id()))owners.addPlayer(player);});area.setOwners(owners);}
  var managers=new HashSet<RegionManager>();managers.add(manager(w));for(var role:List.of(SurvivalWorld.WILDERNESS,SurvivalWorld.NETHER)){var world=common.get(role);var at=entries.apply(role);int radius=settings.resourceRadius();var area=region(world,"hearth_resource_spawn",at.getBlockX()-radius,world.getMinHeight(),at.getBlockZ()-radius,at.getBlockX()+radius,world.getMaxHeight()-1,at.getBlockZ()+radius,1000);environmental(area);managers.add(manager(world));}
  return save(managers);
 }
 public CompletionStage<Void> house(House h,World w,Snapshot snapshot){var m=manager(w);var base=region(w,"hearth_house_outside",-29999984,w.getMinHeight(),-29999984,29999984,w.getMaxHeight()-1,29999984,1000);environmental(base);String id="hearth_house_inside";var old=m.getRegion(id);var owners=new DefaultDomain();owners.addPlayer(h.owner());var members=new DefaultDomain();for(var member:snapshot.members())if(member.house().equals(h.id())&&member.accepted()&&member.role()==Role.RESIDENT)members.addPlayer(member.player());
  int half=h.width()/2;var lo=BlockVector3.at(-half,w.getMinHeight(),-half);var hi=BlockVector3.at(half-1,w.getMaxHeight()-1,half-1);
  if(old!=null){var min=old.getMinimumPoint();var max=old.getMaximumPoint();if(old.getPriority()!=1001||old.getFlag(Flags.PASSTHROUGH)!=StateFlag.State.DENY||min.x()!=-max.x()-1||min.z()!=min.x()||max.z()!=max.x()||min.y()!=w.getMinHeight()||max.y()!=w.getMaxHeight()-1||max.x()>hi.x())throw new IllegalStateException("HOUSING_REGION_CONFLICT");}
  var area=new ProtectedCuboidRegion(id,lo,hi);area.setPriority(1001);area.setFlag(Flags.PASSTHROUGH,StateFlag.State.DENY);area.setOwners(owners);area.setMembers(members);m.addRegion(area);w.getWorldBorder().setCenter(0,0);w.getWorldBorder().setSize(h.width());return save(Set.of(m));
 }
 private CompletionStage<Void> save(Set<RegionManager> managers){return db.module("housing_wg_save",c->{for(var m:managers)m.save();return null;});}
}
