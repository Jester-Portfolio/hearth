package io.github.jesterportfolio.hearth.verification;

import com.google.gson.Gson;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import io.github.jesterportfolio.hearth.phone.*;
import io.github.jesterportfolio.hearth.quest.*;
import io.github.jesterportfolio.hearth.session.PlayerSessions;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.exploration.ExplorationContent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.plugin.java.JavaPlugin;

/** 실제 PG와 고정 모델이 함께 실행되는 네 논리 세션. Minecraft Player를 만들지 않습니다. */
public final class JointPhoneVerification implements Listener,AutoCloseable {
    public record Client(ServerProgressAccess.Context context,PlayerSessions.Session session,Supplier<InventoryPayload> capture){}
    private static final List<String> COLOURS=List.of("청색","황색","흑색","백색");
    private static final String FACT="joint_marker",QUESTION="자료의 표식 한 단어와 [자료 1]만 답해.";
    private final JavaPlugin plugin;private final PostgresProgressStore store;private final QuestJournal journal;private final QuestCatalog catalog;private final PlayerSessions sessions;private final List<Client> clients;private final Executor owner;private final Path result;
    private final MeasuredAssistant backend;private final PhoneTelemetry metrics=new PhoneTelemetry();private final TickObservation ticks=new TickObservation();
    private final List<Map<String,Object>> exchanges=Collections.synchronizedList(new ArrayList<>());
    private final List<PhoneChat> chats=new ArrayList<>();private final List<PhoneGrounding.Plan> plans=new ArrayList<>();private final List<InventoryStore.Stored> inventories=new ArrayList<>();private final List<Map<String,Object>> cases=new ArrayList<>();private final List<Long> pgMillis=new ArrayList<>();
    private final AtomicLongArray starts=new AtomicLongArray(4),ends=new AtomicLongArray(4);private final List<Long> concurrentMillis=new ArrayList<>();private final List<String> answers=new ArrayList<>();private boolean closed,prepared,played;private Throwable tickFailure;private ProgressStore.State shared;
    public JointPhoneVerification(JavaPlugin plugin,PostgresProgressStore store,QuestJournal journal,QuestCatalog catalog,PlayerSessions sessions,List<Client> clients,Executor owner)throws Exception{
        if(!Bukkit.isPrimaryThread()||!Boolean.getBoolean("hearth.verification.jointAI")||clients.size()!=4||!Bukkit.getOnlinePlayers().isEmpty())throw new IllegalStateException("JOINT_AI_GATE");var root=IsolatedPaths.require(Path.of("").toRealPath().getParent(),"hearth-s4-11.");if(!Files.readString(root.resolve(".hearth-lifecycle-isolated")).equals("S4-11 isolated test only\n"))throw new IllegalStateException("JOINT_AI_GATE");
        this.plugin=plugin;this.store=store;this.journal=journal;this.catalog=catalog;this.sessions=sessions;this.clients=List.copyOf(clients);this.owner=owner;result=root.resolve("joint-phone-result.json");
        if(clients.stream().map(c->c.context().storyRunId()).distinct().count()!=1||clients.stream().map(c->c.context().playerId()).distinct().count()!=4)throw new IllegalStateException("JOINT_AI_ROSTER");
        backend=new MeasuredAssistant(LocalAssistant.configured("ollama","http://127.0.0.1:11435/api/generate"));
        for(int i=0;i<4;i++){int index=i;var service=new AssistantService(){
            public boolean allowsCache(){return true;}public CompletionStage<String> ask(String q){throw new IllegalStateException("TYPED_REQUEST_REQUIRED");}
            public CompletionStage<String> ask(AssistantRequest request){for(var c:clients)if(request.prompt().contains(c.context().playerId().toString())||request.prompt().contains(c.context().storyRunId().toString())||request.prompt().contains(FACT))throw new IllegalStateException("INTERNAL_ID_IN_PROMPT");starts.set(index,System.nanoTime());var work=backend.ask(request);work.whenComplete((value,error)->{ends.set(index,System.nanoTime());exchanges.add(Map.of("logical_client",index,"request",request,"answer",value==null?"":value,"failure",error==null?"":AssistantFailure.reason(error).name()));});return work;}
            public CompletionStage<String> confirmCached(String answer){return backend.confirmCached(answer);}
        };var chat=new PhoneChat(service,owner,metrics);chat.includeHistory(false);chats.add(chat);}
        ticks.phase(TickObservation.Phase.PREPARATION);Bukkit.getPluginManager().registerEvents(this,plugin);
    }
    private void check(boolean ok,String label){if(!ok)throw new IllegalStateException("JOINT_AI_CHECK_"+label);plugin.getLogger().info("HEARTH_SESSION_CHECK PASS joint_AI_"+label);}
    private void quality(String label,boolean ok){cases.add(Map.of("name",label,"outcome",ok?"PASS":"QUALITY_REVIEW_REQUIRED"));}
    private boolean current(int index){return !closed&&sessions.playable(clients.get(index).session());}
    private <T> CompletionStage<T> on(CompletionStage<T> task){return task.thenApplyAsync(v->v,owner);}
    private CompletionStage<QuestJournal.View> load(int index){long start=System.nanoTime();var c=clients.get(index);return journal.load(c.context(),c.session(),()->current(index)).whenCompleteAsync((v,error)->{if(pgMillis.size()>=64)throw new IllegalStateException("PG_SAMPLE_LIMIT");pgMillis.add(Math.max(0,(System.nanoTime()-start)/1_000_000));},owner);}
    private PhoneGrounding.Plan refresh(int index,QuestJournal.View view,PhoneGrounding.Plan plan){return PhoneGrounding.refresh(catalog,view,journal.projectInformation(clients.get(index).context(),view,()->current(index)),plan);}
    private ExplorationContent.Fact fact(int index,int version){return new ExplorationContent.Fact(FACT,"PERSON","공개 표식 기록","표식 안내","이 기록의 표식은 "+COLOURS.get(index)+"이다.",version,true);}
    public CompletionStage<Void> prepare(){
        if(prepared||closed)throw new IllegalStateException("JOINT_AI_PREPARE_ONCE");CompletionStage<Void> chain=on(store.readShared(clients.getFirst().context())).thenAccept(value->shared=value);
        for(int i=0;i<4;i++){int n=i;chain=chain.thenCompose(v->on(store.readInventory(clients.get(n).context())).thenAccept(inventories::add)).thenCompose(v->on(store.recordFact(clients.get(n).context(),fact(n,1),false)).thenApply(x->null)).thenCompose(v->load(n)).thenAccept(view->{var plan=PhoneGrounding.search(catalog,view,journal.projectInformation(clients.get(n).context(),view,()->current(n)),"diary",QUESTION).sent();check(plan.materials().size()==1&&plan.result().context().contains(COLOURS.get(n)),"own_PG_source_"+n);plans.add(plan);});}
        return chain.thenCompose(v->on(backend.prepare())).thenAccept(receipt->{check(receipt!=null&&!receipt.isBlank(),"explicit_fixed_model_preparation");prepared=true;ticks.phase(TickObservation.Phase.BASELINE);});
    }
    private CompletionStage<Void> send(int index,boolean cancel,boolean changeDuringValidation){
        var flow=metrics.begin();flow.start(PhoneTelemetry.Phase.PREFLIGHT);var expected=plans.get(index);var shown=chats.get(index).previewGrounded(expected);
        return load(index).thenComposeAsync(view->{flow.finish(PhoneTelemetry.Phase.PREFLIGHT);var plan=refresh(index,view,expected);var work=chats.get(index).submitGroundedPreview(shown,plan,()->current(index),()->{
            CompletionStage<Void> before=changeDuringValidation?on(store.recordFact(clients.get(index).context(),fact(index,2),false)).thenApply(v->null):CompletableFuture.completedFuture(null);
            return before.thenCompose(v->load(index)).thenApplyAsync(latest->{try{refresh(index,latest,plan);return true;}catch(RuntimeException stale){return false;}},owner);
        },flow);if(cancel)chats.get(index).cancel();return work;},owner).whenCompleteAsync((v,error)->{if(flow.active())flow.complete(PhoneTelemetry.Outcome.PREFLIGHT_FAILED);},owner);
    }
    private String answer(int n){return chats.get(n).history().getLast().answer();}
    private boolean colour(String answer,int n){return answer.contains(COLOURS.get(n))&&answer.contains("[자료 1]")&&COLOURS.stream().filter(c->!c.equals(COLOURS.get(n))).noneMatch(answer::contains);}
    public CompletionStage<Void> play(){
        if(!prepared||played||closed)throw new IllegalStateException("JOINT_AI_PLAY_ONCE");played=true;ticks.phase(TickObservation.Phase.REQUESTS);
        return send(0,false,false).thenRunAsync(()->{answers.add(answer(0));quality("prepared_PG_baseline",colour(answer(0),0));},owner)
            .thenComposeAsync(v->{long one=System.nanoTime();var a=send(1,false,false).whenComplete((x,e)->concurrentMillis.add((System.nanoTime()-one)/1_000_000));long two=System.nanoTime();var b=send(2,false,false).whenComplete((x,e)->concurrentMillis.add((System.nanoTime()-two)/1_000_000));return CompletableFuture.allOf(a.toCompletableFuture(),b.toCompletableFuture());},owner)
            .thenRunAsync(()->{answers.add(answer(1));answers.add(answer(2));check(starts.get(1)>0&&starts.get(2)>0&&ends.get(1)>0&&ends.get(2)>0&&Math.max(starts.get(1),starts.get(2))<Math.min(ends.get(1),ends.get(2)),"model_request_intervals_overlap");quality("two_real_PG_sources_accepted_separately",colour(answer(1),1)&&colour(answer(2),2));},owner)
            .thenComposeAsync(v->send(3,true,false).handleAsync((x,error)->{check(error!=null&&!chats.get(3).busy()&&chats.get(3).history().isEmpty()&&!chats.get(3).draft().isBlank(),"cancel_keeps_draft_no_acceptance");return (Void)null;},owner),owner)
            .thenComposeAsync(v->send(3,false,false),owner).thenRunAsync(()->{answers.add(answer(3));quality("new_PG_question_after_cancel",colour(answer(3),3));},owner)
            .thenComposeAsync(v->{long generated=backend.snapshot().usage().getFirst().started();return send(0,false,false).thenRunAsync(()->check(backend.snapshot().usage().getFirst().started()==generated,"cache_checks_model_and_real_PG_without_generation"),owner);},owner)
            .thenComposeAsync(v->{var history=chats.getFirst().history();long generated=backend.snapshot().usage().getFirst().started();return send(0,false,true).handleAsync((x,error)->{check(error!=null&&chats.getFirst().history().equals(history)&&chats.getFirst().cacheStats().entries()==0&&backend.snapshot().usage().getFirst().started()==generated,"real_PG_version_change_rejects_cached_answer");return (Void)null;},owner);},owner)
            .thenComposeAsync(v->{CompletionStage<Void> verify=CompletableFuture.completedFuture(null);for(int i=0;i<4;i++){int n=i;verify=verify.thenCompose(x->on(store.readInventory(clients.get(n).context())).thenAccept(row->check(row.equals(inventories.get(n))&&new PaperInventoryCodec().equivalent(row.payload(),clients.get(n).capture().get()),"inventory_and_revision_preserved_"+n)));}return verify;},owner)
            .thenComposeAsync(v->on(store.readShared(clients.getFirst().context())),owner).thenAcceptAsync(latest->{check(latest.equals(shared),"shared_progress_unchanged");var m=metrics.snapshot();check(m.timings().stream().allMatch(t->t.active()==0)&&m.outcomes().get(PhoneTelemetry.Outcome.ACCEPTED)==4&&m.outcomes().get(PhoneTelemetry.Outcome.CACHED)==1&&m.outcomes().get(PhoneTelemetry.Outcome.CANCELLED)==1&&m.outcomes().get(PhoneTelemetry.Outcome.VALIDATION_FAILED)==1,"terminal_counts_only_after_real_PG_acceptance");ticks.phase(TickObservation.Phase.AFTER);},owner);
    }
    @EventHandler public void tick(ServerTickEndEvent event){if(!closed)try{ticks.tick(event.getTickDuration());}catch(RuntimeException failure){tickFailure=failure;}}
    public void report()throws Exception{
        if(!prepared||!played||closed||tickFailure!=null)throw new IllegalStateException("JOINT_AI_REPORT_INCOMPLETE");var report=new LinkedHashMap<String,Object>();report.put("real_model",true);report.put("actual_minecraft_client",false);report.put("four_player_capacity_pass",false);report.put("logical_sessions",4);report.put("active_story_runs",1);report.put("concurrent_AI_requests",2);report.put("answers",answers);report.put("exchanges",List.copyOf(exchanges));report.put("concurrent_elapsed_millis",concurrentMillis);report.put("backend_request_intervals_overlap",true);report.put("PG_read_millis",pgMillis);report.put("flow_telemetry",metrics.snapshot());report.put("backend_usage",backend.snapshot());report.put("tick_windows",ticks.close());report.put("cases",cases);report.put("required_checks_passed",cases.stream().allMatch(c->c.get("outcome").equals("PASS")));Files.writeString(result,new Gson().toJson(report)+"\n",StandardOpenOption.CREATE_NEW);check(cases.stream().allMatch(c->c.get("outcome").equals("PASS")),"bounded_fixed_model_quality");
    }
    @Override public void close(){if(closed)return;closed=true;chats.forEach(PhoneChat::close);var observed=ticks.close();HandlerList.unregisterAll(this);backend.close();if(!Files.exists(result))try{Files.writeString(result,new Gson().toJson(Map.of("real_model",prepared,"required_checks_passed",false,"actual_minecraft_client",false,"four_player_capacity_pass",false,"cases",cases,"exchanges",List.copyOf(exchanges),"flow_telemetry",metrics.snapshot(),"backend_usage",backend.snapshot(),"tick_windows",observed))+"\n",StandardOpenOption.CREATE_NEW);}catch(Exception failure){plugin.getLogger().warning("HEARTH_JOINT_AI_PARTIAL_EVIDENCE_FAILED");}}

}
