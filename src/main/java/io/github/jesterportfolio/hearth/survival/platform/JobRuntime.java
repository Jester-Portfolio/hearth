package io.github.jesterportfolio.hearth.survival.platform;

import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicBoolean;
import io.github.jesterportfolio.hearth.economy.JobStore;
import io.github.jesterportfolio.hearth.economy.JobStore.*;
import io.github.jesterportfolio.hearth.survival.SurvivalWorld;
import org.bukkit.Bukkit;

/** effect 전 PG 의도 확정, 완료 뒤 다음 주기 갱신. RUNNING/HOLD는 자동 재실행 금지. */
public final class JobRuntime implements AutoCloseable {
    private final PlatformRuntime p;public final JobStore store;
    private final Map<String,JobHandler> handlers=new TreeMap<>();
    private final java.security.SecureRandom random=new java.security.SecureRandom();
    private Snapshot snapshot=new Snapshot(List.of(),List.of());
    private boolean opened,closed,busy,uncertain;private long lastTick=-1;
    private AtomicBoolean lease;
    public JobRuntime(PlatformRuntime p){this.p=p;var economy=p.rpg.runtime.economy();store=new JobStore(economy.database(),economy.store,economy.settings.realm());
        register("resource_reset",new JobHandler(){
            public Map<String,String> prepare(String target,Map<String,String> args){if(!p.settings().resourceReset())throw new IllegalStateException("RESOURCE_RESET_DISABLED");if(!JobPolicy.ROLES.contains(target)||!Set.of("seed").containsAll(args.keySet()))throw new IllegalArgumentException("JOB_RESOURCE_TARGET");long seed=args.containsKey("seed")?Long.parseLong(args.get("seed")):random.nextLong();return Map.of("seed",Long.toString(seed));}
            public CompletionStage<Void> execute(Job job,java.util.function.BooleanSupplier current){if(!current.getAsBoolean())return CompletableFuture.failedFuture(new IllegalStateException("JOB_STALE"));return p.resources.reset(SurvivalWorld.id(job.target()),Long.parseLong(job.arguments().get("seed")),job.id());}
            public CompletionStage<Outcome> inspect(Job job){return p.store.resets().thenApplyAsync(rows->{var row=rows.stream().filter(r->r.request().equals(job.id())).findFirst();if(row.isEmpty())return Outcome.NOT_STARTED;var reset=row.get();if(!reset.role().equals(job.target())||reset.seed()!=Long.parseLong(job.arguments().get("seed")))throw new IllegalStateException("JOB_EFFECT_IDENTITY");return switch(reset.phase()){case "ACTIVE","RETIRED"->{var active=p.resources.active().get(SurvivalWorld.id(job.target()));boolean pending=p.resources.pending().stream().anyMatch(row2->row2.request().equals(job.id()));yield !pending&&active!=null&&active.generation()>=reset.generation()?Outcome.SUCCEEDED:Outcome.PENDING;}case "ABORTED"->Outcome.CANCELLED;default->Outcome.PENDING;};},p.owner);}
        });
        for(var registration:Bukkit.getServicesManager().getRegistrations(JobExtension.class))registration.getProvider().install(this);
    }
    public void register(String kind,JobHandler handler){if(opened||closed||!kind.matches("[a-z][a-z0-9_]{0,40}")||handlers.size()>=32||handlers.putIfAbsent(kind,Objects.requireNonNull(handler))!=null)throw new IllegalArgumentException("JOB_HANDLER");}
    public Snapshot snapshot(){return snapshot;}public Set<String> kinds(){return Set.copyOf(handlers.keySet());}public boolean busy(){return busy;}public boolean held(){return uncertain||snapshot.active().isPresent();}
    public String summary(){return "종류="+handlers.keySet()+" 예약="+snapshot.schedules()+" 활성="+snapshot.active().map(j->j.id()+"/"+j.state()+"/"+j.target()).orElse("없음")+" 최근="+snapshot.jobs().size()+" 불명="+uncertain;}
    private long now(){return p.rpg.runtime.economy().logicalTick();}
    public CompletionStage<Void> open(){return store.recover().thenAcceptAsync(state->snapshot=state,p.owner).thenCompose(v->p.maintenance()?CompletableFuture.completedFuture(null):configure(p.settings().jobs())).thenRunAsync(()->opened=true,p.owner);}
    public CompletionStage<Void> configure(JobPolicy policy){if(busy)return CompletableFuture.failedFuture(new IllegalStateException("JOB_BUSY"));return store.configure(policy.active(),now()).thenAcceptAsync(state->{snapshot=state;lastTick=-1;},p.owner);}
    public CompletionStage<Snapshot> refresh(){return store.snapshot().thenApplyAsync(state->{snapshot=state;uncertain=false;return state;},p.owner);}
    public CompletionStage<Optional<Job>> inspect(UUID id){return store.inspect(id);}
    public void pulse(){if(!opened||closed||busy||held()||!p.jobsAllowed()||p.resources.busy()||!p.settings().jobs().enabled())return;long tick=now();if(tick==lastTick)return;lastTick=tick;var due=snapshot.schedules().stream().filter(s->s.interval()>0&&s.next()<=tick).min(Comparator.comparingLong(Schedule::next).thenComparing(Schedule::target));if(due.isPresent())submit(UUID.randomUUID(),"resource_reset",due.get().target(),Map.of(),due.get()).whenComplete((v,e)->{});}
    public CompletionStage<Job> runResource(String role,long seed){return submit(UUID.randomUUID(),"resource_reset",role,Map.of("seed",Long.toString(seed)),null);}
    public CompletionStage<Job> submit(UUID request,String kind,String target,Map<String,String> template,Schedule schedule){
        if(!opened||closed||busy||held()||!p.jobsAllowed()||p.resources.busy())return CompletableFuture.failedFuture(new IllegalStateException("JOB_BUSY_OR_NOT_READY"));
        var handler=Objects.requireNonNull(handlers.get(kind),"JOB_HANDLER_REQUIRED");var args=Map.copyOf(handler.prepare(target,Map.copyOf(template)));long tick=now();busy=true;long start=System.nanoTime();lease=new AtomicBoolean(true);var token=lease;
        return store.reserve(p.rpg.runtime.economy().store.context(p.rpg.runtime.economy().settings.realm(),new UUID(0,0)),request,kind,target,args,tick,schedule).thenComposeAsync(reservation->{var job=reservation.job();snapshot=new Snapshot(snapshot.schedules(),List.of(job));if(reservation.replay())return CompletableFuture.completedFuture(job);p.plugin.getLogger().info("HEARTH_JOB_STARTED id="+job.id()+" kind="+job.kind()+" target="+job.target());CompletionStage<Void> effect;try{effect=Objects.requireNonNull(handler.execute(job,()->!closed&&token.get()));}catch(Exception failure){effect=CompletableFuture.failedFuture(failure);}return effect.toCompletableFuture().orTimeout(p.settings().resetTimeout(),TimeUnit.SECONDS).handleAsync((v,error)->{
            if(closed||!token.get())throw new CompletionException(new IllegalStateException("JOB_CLOSED"));
            return store.finish(job,error==null?State.SUCCEEDED:State.HOLD,error==null?"COMPLETE":"EFFECT_REVIEW",now());
        },p.owner).thenCompose(v->v);},p.owner).thenComposeAsync(job->store.snapshot().thenApplyAsync(state->{snapshot=state;return job;},p.owner),p.owner).whenCompleteAsync((job,error)->{token.set(false);busy=false;if(error!=null){uncertain=true;p.plugin.getLogger().warning("HEARTH_JOB_HOLD request="+request);}p.metrics.record("job",System.nanoTime()-start,error!=null||job!=null&&job.state()==State.HOLD);},p.owner);
    }
    /** 조작을 재실행하지 않고 기록된 효과 결과만 확인한다. */
    public CompletionStage<Job> reconcile(UUID request,boolean cancel){if(closed||busy)return CompletableFuture.failedFuture(new IllegalStateException("JOB_BUSY"));busy=true;return store.inspect(request).thenComposeAsync(found->{var job=found.orElseThrow();if(job.state()==State.SUCCEEDED||job.state()==State.CANCELLED)return CompletableFuture.completedFuture(job);var handler=Objects.requireNonNull(handlers.get(job.kind()),"JOB_HANDLER_REQUIRED");return handler.inspect(job).thenComposeAsync(outcome->{if(cancel&&outcome!=JobHandler.Outcome.NOT_STARTED&&outcome!=JobHandler.Outcome.CANCELLED||!cancel&&outcome!=JobHandler.Outcome.SUCCEEDED&&outcome!=JobHandler.Outcome.CANCELLED)throw new IllegalStateException("JOB_EFFECT_REQUIRES_REVIEW");return store.finish(job,cancel||outcome==JobHandler.Outcome.CANCELLED?State.CANCELLED:State.SUCCEEDED,"OPERATOR_RECONCILED",now());},p.owner);},p.owner).thenComposeAsync(job->refresh().thenApply(v->job),p.owner).whenCompleteAsync((v,e)->{busy=false;if(e!=null)uncertain=true;},p.owner);}
    public CompletionStage<Void> drain(){return busy?CompletableFuture.failedFuture(new IllegalStateException("JOB_BUSY")):CompletableFuture.completedFuture(null);}
    public void close(){closed=true;if(lease!=null)lease.set(false);}
}
