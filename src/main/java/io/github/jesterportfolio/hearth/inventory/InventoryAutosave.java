package io.github.jesterportfolio.hearth.inventory;

import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

/** Paper 메인 task에서 호출합니다. 시간 주기는 실제 시계로 판단하고 DB는 세션의 비동기 경로를 씁니다. */
public final class InventoryAutosave {
    public static final Duration DEFAULT_PERIOD=Duration.ofSeconds(30);
    private final Duration period;
    private final LongSupplier clock;
    private final Runnable checkOwner;
    private final Runnable beforeCycle;
    private long next;
    private boolean stopped;
    private final Map<String,InventorySaveSession> sessions=new LinkedHashMap<>();
    public InventoryAutosave(Duration period,LongSupplier clock,Runnable checkOwner) { this(period,clock,checkOwner,() -> {}); }
    public InventoryAutosave(Duration period,LongSupplier clock,Runnable checkOwner,Runnable beforeCycle) {
        if (period.isNegative() || period.isZero() || period.compareTo(Duration.ofHours(1))>0) throw new IllegalArgumentException("Invalid autosave period");
        this.period=period; this.clock=Objects.requireNonNull(clock); this.checkOwner=Objects.requireNonNull(checkOwner);this.beforeCycle=Objects.requireNonNull(beforeCycle);
        next=Math.addExact(clock.getAsLong(),period.toNanos());
    }
    public Duration period() { return period; }
    public void add(InventorySaveSession session) {
        checkOwner.run(); if (stopped) throw new IllegalStateException("Autosave stopped");
        String key=session.context().storyRunId()+"/"+session.context().playerId();
        if (sessions.putIfAbsent(key,session)!=null) throw new IllegalStateException("Duplicate inventory session");
    }
    public void remove(InventorySaveSession session) { checkOwner.run();sessions.remove(session.context().storyRunId()+"/"+session.context().playerId(),session); }
    public boolean tick() {
        checkOwner.run(); if (stopped || clock.getAsLong()<next) return false;
        next=Math.addExact(clock.getAsLong(),period.toNanos());
        beforeCycle.run();
        for (var session:List.copyOf(sessions.values())) if (session.dirty()) session.flush();
        return true;
    }
    public void stop() { checkOwner.run(); stopped=true; }
}
