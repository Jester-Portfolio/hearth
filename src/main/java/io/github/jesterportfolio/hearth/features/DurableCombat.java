package io.github.jesterportfolio.hearth.features;

import io.github.jesterportfolio.hearth.combat.*;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.GameMode;

/** 도전 시작·확정 종료·복귀 claim/DONE만 저장합니다. 몹 틱마다 DB를 쓰지 않습니다. */
public final class DurableCombat implements PaperCombatPlayers.Durability {
    private final RecoveryLedger ledger;private final ServerProgressAccess access;private final Executor owner;private final Map<UUID,Set<CompletionStage<Void>>> pending=new HashMap<>();
    public DurableCombat(RecoveryLedger ledger,ServerProgressAccess access,Executor owner){this.ledger=ledger;this.access=access;this.owner=owner;}
    private CompletionStage<Void> track(UUID run,CompletionStage<Void> work){pending.computeIfAbsent(run,k->new HashSet<>()).add(work);work.whenCompleteAsync((v,error)->{if(error==null){var set=pending.get(run);if(set!=null){set.remove(work);if(set.isEmpty())pending.remove(run,set);}}},owner);return work;}
    public CompletionStage<Void> started(CombatAttempt.Snapshot state,UUID world,CombatContent.Exit exit,Map<UUID,GameMode> modes,UUID parent){CompletionStage<Void> work=CompletableFuture.completedFuture(null);for(var m:state.members()){var c=access.forVerifiedServerLink(state.run(),m.player());String mode=modes.get(m.player()).name();work=work.thenComposeAsync(v->ledger.start(c,state.attempt(),parent,world,exit,mode).thenApply(s->null),owner);}return track(state.run(),work);}
    public CompletionStage<Void> ended(CombatAttempt.Snapshot state){CompletionStage<Void> work=CompletableFuture.completedFuture(null);for(var m:state.members()){var c=access.forVerifiedServerLink(state.run(),m.player());work=work.thenComposeAsync(v->ledger.end(c,state.attempt()).thenApply(s->null),owner);}return track(state.run(),work);}
    public CompletionStage<Void> claim(ServerProgressAccess.Context c,UUID attempt,UUID request){return track(c.storyRunId(),ledger.claim(c,attempt,request).thenApply(s->null));}
    public CompletionStage<Void> done(ServerProgressAccess.Context c,UUID attempt,UUID request){return track(c.storyRunId(),ledger.done(c,attempt,request).thenApply(s->null));}
    public CompletionStage<Void> barrier(UUID run){return CompletableFuture.allOf(pending.getOrDefault(run,Set.of()).stream().map(CompletionStage::toCompletableFuture).toArray(CompletableFuture[]::new));}
}
