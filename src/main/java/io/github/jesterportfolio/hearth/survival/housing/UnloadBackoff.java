package io.github.jesterportfolio.hearth.survival.housing;
import java.util.*;
/** Failure applies to one loaded world, never an unbounded save loop. Explicit retry resets the counter. */
public final class UnloadBackoff {
 public record Failure(int attempts,long retryAt,boolean held){}
 private final Map<UUID,Failure> failed=new HashMap<>();
 public boolean allowed(UUID id,long now){var f=failed.get(id);return f==null||!f.held()&&now>=f.retryAt();}
 public Failure fail(UUID id,long now){int n=failed.getOrDefault(id,new Failure(0,0,false)).attempts()+1;var f=new Failure(n,now+Math.min(60000,5000L*n),n>=3);failed.put(id,f);return f;}
 public void clear(UUID id){failed.remove(id);}public Map<UUID,Failure> snapshot(){return Map.copyOf(failed);}
}
