package io.github.jesterportfolio.hearth.combat;

import java.util.*;
import static io.github.jesterportfolio.hearth.combat.FieldContent.*;

/** owner 스레드의 한 개발 실행 범위. 언로드/실패 후 임의 재소환하지 않습니다. */
public final class FieldEncounters {
    public enum Phase { SPAWNING,ACTIVE,CLEARED,ERROR_HOLD,RETIRED }
    public record Binding(UUID run,UUID world,long generation) {public Binding{Objects.requireNonNull(run);Objects.requireNonNull(world);if(generation<0)throw new IllegalArgumentException("Invalid field generation");}}
    public record Presence(Binding binding,UUID player,Point location) {}
    public record Snapshot(Phase phase,int players,Tier difficulty,Set<UUID> mobs) {public Snapshot{mobs=Set.copyOf(mobs);}}
    public interface Port {UUID spawn(Binding binding,Zone zone,Tier difficulty);void remove(UUID entity);}
    private record Key(UUID run,String zone) {}
    private final FieldContent content;private final Port port;private final Runnable check;
    private final Map<UUID,Binding> bindings=new HashMap<>();private final Set<UUID> retired=new HashSet<>();private final Map<Key,Snapshot> events=new HashMap<>();
    public FieldEncounters(FieldContent content,Port port,Runnable check){this.content=content;this.port=port;this.check=check;}
    public void bind(Binding b){check.run();if(retired.contains(b.run()))throw new IllegalStateException("FIELD_RETIRED");if(bindings.values().stream().anyMatch(v->v.world().equals(b.world())&&!v.run().equals(b.run())))throw new IllegalStateException("FIELD_WORLD_CONFLICT");var old=bindings.putIfAbsent(b.run(),b);if(old!=null&&!old.equals(b))throw new IllegalStateException("FIELD_STALE_GENERATION");}
    public boolean current(Binding b){check.run();return b.equals(bindings.get(b.run()));}
    public Optional<Snapshot> state(UUID run,String zone){check.run();return Optional.ofNullable(events.get(new Key(run,zone)));}
    public boolean mayAttack(Binding b,UUID entity){check.run();return current(b)&&events.entrySet().stream().anyMatch(e->e.getKey().run().equals(b.run())&&e.getValue().phase()==Phase.ACTIVE&&e.getValue().mobs().contains(entity));}
    public List<String> enter(Binding binding,UUID player,Point before,Point after,List<Presence> online){
        check.run();if(!current(binding))throw new IllegalStateException("FIELD_STALE_GENERATION");var done=new ArrayList<String>();
        for(var zone:content.zones()){
            if(zone.trigger().contains(before)||!zone.trigger().contains(after))continue;
            var key=new Key(binding.run(),zone.id());if(events.containsKey(key))continue;
            var eligible=new HashSet<UUID>();for(var p:online)if(p.binding().equals(binding)&&zone.population().contains(p.location()))eligible.add(p.player());
            if(!eligible.contains(player))throw new IllegalStateException("FIELD_UNVERIFIED_ENTRANT");
            int count=eligible.size();var tier=zone.scale(count);var created=new LinkedHashSet<UUID>();events.put(key,new Snapshot(Phase.SPAWNING,count,tier,Set.of()));
            try{for(int i=0;i<tier.count();i++){if(!current(binding))throw new IllegalStateException("FIELD_STALE_GENERATION");UUID id=Objects.requireNonNull(port.spawn(binding,zone,tier));if(!created.add(id))throw new IllegalStateException("FIELD_DUPLICATE_ENTITY");}if(!current(binding))throw new IllegalStateException("FIELD_STALE_GENERATION");events.put(key,new Snapshot(Phase.ACTIVE,count,tier,created));done.add(zone.id());}
            catch(RuntimeException error){var remaining=new HashSet<UUID>();for(UUID id:created)try{port.remove(id);}catch(RuntimeException cleanup){remaining.add(id);error.addSuppressed(cleanup);}events.put(key,new Snapshot(Phase.ERROR_HOLD,count,tier,remaining));throw error;}
        }return List.copyOf(done);
    }
    public void died(Binding b,String zone,UUID entity){check.run();if(!current(b))return;var key=new Key(b.run(),zone);var prior=events.get(key);if(prior==null||prior.phase()!=Phase.ACTIVE||!prior.mobs().contains(entity))return;var left=new HashSet<>(prior.mobs());left.remove(entity);events.put(key,new Snapshot(left.isEmpty()?Phase.CLEARED:Phase.ACTIVE,prior.players(),prior.difficulty(),left));}
    public void retire(UUID run){check.run();bindings.remove(run);retired.add(run);var failures=new ArrayList<RuntimeException>();events.replaceAll((key,old)->{if(!key.run().equals(run))return old;var left=new HashSet<UUID>();for(UUID id:old.mobs())try{port.remove(id);}catch(RuntimeException e){left.add(id);failures.add(e);}return new Snapshot(left.isEmpty()?Phase.RETIRED:Phase.ERROR_HOLD,old.players(),old.difficulty(),left);});if(!failures.isEmpty())throw new IllegalStateException("FIELD_CLEANUP_HOLD",failures.getFirst());}
}
