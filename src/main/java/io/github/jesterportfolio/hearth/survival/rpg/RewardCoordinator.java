package io.github.jesterportfolio.hearth.survival.rpg;
import java.util.*;import java.util.function.*;import java.util.random.RandomGenerator;
/** 한 행동의 최종 보상 채널을 한번만 결정한다. 자동 판매도 필터 이후 결과만 받는다. */
public final class RewardCoordinator {
    public record Decision(boolean suppressItems,boolean suppressVanillaXp,double skillFactor){}
    private record Entry(Decision decision,long expires,Set<String> paid){}
    private final Map<UUID,Entry> entries=new HashMap<>();private final LongSupplier clock;private final RandomGenerator random;
    public RewardCoordinator(LongSupplier clock,RandomGenerator random){this.clock=clock;this.random=random;}
    public Decision begin(UUID id,boolean penalized){expire();var old=entries.get(id);if(old!=null)return old.decision();if(entries.size()>=8192)throw new IllegalStateException("REWARD_CAPACITY");var decision=penalized?new Decision(random.nextDouble()<.5,random.nextDouble()<.5,.5):new Decision(false,false,1);entries.put(id,new Entry(decision,clock.getAsLong()+10000,new HashSet<>()));return decision;}
    public boolean claim(UUID action,String producer){var row=entries.get(action);return row!=null&&clock.getAsLong()<row.expires()&&row.paid().add(producer);}
    public <T>boolean deliver(UUID action,String producer,List<T> finalItems,Consumer<List<T>> giveOrSell){var row=entries.get(action);if(row==null||!claim(action,producer))return false;giveOrSell.accept(row.decision().suppressItems()?List.of():List.copyOf(finalItems));return true;}
    public void expire(){long now=clock.getAsLong();entries.values().removeIf(e->e.expires()<=now);}
}
