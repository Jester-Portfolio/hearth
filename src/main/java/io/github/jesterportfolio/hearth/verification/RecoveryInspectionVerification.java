package io.github.jesterportfolio.hearth.verification;

import io.github.jesterportfolio.hearth.administration.*;
import io.github.jesterportfolio.hearth.features.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.combat.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** 새 격리 DB의 진단/보존 경계. 콘솔 외 Player 대역이나 실제 사용자 조작은 사용하지 않습니다. */
final class RecoveryInspectionVerification {
    private final JavaPlugin plugin;private final PostgresProgressStore db;private final FeatureStore features;private final InspectionStore inspections;private final RecoveryConsole console;
    private final RecoveryLedger ledger;private final Map<String,SceneContent.Scene> scenes;private final IntFunction<ServerProgressAccess.Context> contexts;
    private final Consumer<ServerProgressAccess.Context> register;private final Function<ServerProgressAccess.Context,RecoveryInspection.Runtime> runtime;
    private final Executor owner;private final BiConsumer<Boolean,String> check;private final UUID world;
    RecoveryInspectionVerification(JavaPlugin plugin,PostgresProgressStore db,FeatureStore features,InspectionStore inspections,RecoveryConsole console,RecoveryLedger ledger,Map<String,SceneContent.Scene> scenes,IntFunction<ServerProgressAccess.Context> contexts,Consumer<ServerProgressAccess.Context> register,Function<ServerProgressAccess.Context,RecoveryInspection.Runtime> runtime,Executor owner,BiConsumer<Boolean,String> check,UUID world) {
        this.plugin=plugin;this.db=db;this.features=features;this.inspections=inspections;this.console=console;this.ledger=ledger;this.scenes=scenes;this.contexts=contexts;this.register=register;this.runtime=runtime;this.owner=owner;this.check=check;this.world=world;
    }
    private <T> CompletionStage<T> main(CompletionStage<T> work){return work.thenApplyAsync(Function.identity(),owner);}
    private CompletionStage<Void> rejected(CompletionStage<?> work){return main(work.handle((v,error)->{if(error==null)throw new IllegalStateException("EXPECTED_INSPECTION_FAILURE");return (Void)null;}));}
    private InspectionStore.Row row(InspectionStore.Snapshot snapshot,String domain){return snapshot.rows().stream().filter(r->r.domain().equals(domain)).findFirst().orElseThrow();}
    private CompletionStage<Void> fixture(int id){var c=contexts.apply(id);return main(db.createPlayer(c,"private source must not appear in report")).thenCompose(v->main(db.createInventory(c,PersonalStorage.empty()))).thenRun(()->register.accept(c));}
    private CompletionStage<UUID> command(String... args){var lines=new ArrayList<String>();return console.command(true,args,lines::add).thenApply(v->{check.accept(lines.stream().noneMatch(s->s.contains("private source must not appear")),"inspection_console_does_not_publish_private_progress_payload");return lines.stream().filter(s->s.startsWith("HEARTH_RECOVERY_REPORT")).map(s->UUID.fromString(s.split("report=",2)[1].split(" ")[0])).reduce((a,b)->b).orElseThrow();});}
    private CompletionStage<UUID> inspect(int id){var c=contexts.apply(id);return command("inspect",c.storyRunId().toString(),c.playerId().toString());}
    private RecoveryInspection.Report report(UUID id){return console.report(id).orElseThrow();}
    CompletionStage<Void> run() {
        check.accept(plugin.getCommand("hearthrecovery")!=null&&Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"hearthrecovery help"),"inspection_real_console_command_registered");
        CompletionStage<Void> work=CompletableFuture.completedFuture(null);for(int i=201;i<=208;i++){final int n=i;work=work.thenCompose(v->fixture(n));}
        return work.thenCompose(v->seed()).thenCompose(v->main(inspections.read(contexts.apply(201)))).thenCompose(before->inspect(201).thenCompose(id->{
            var value=report(id);check.accept(value.entries().stream().anyMatch(e->e.domain().equals("combat_return")&&e.state().contains("phase=ACTIVE"))&&value.issues().contains("RESTART_REQUIRES_HOLD"),"inspection_active_recovery_restart_policy_visible");
            check.accept(value.entries().stream().filter(e->e.domain().equals("device")).allMatch(e->e.state().contains("ITEM_PRESENCE_NOT_INSPECTED")),"inspection_issuance_record_does_not_infer_phone_absence");
            check.accept(value.entries().stream().anyMatch(e->e.domain().equals("cutscene")&&e.state().contains("completed=false")),"inspection_mandatory_scene_not_completed_by_read");
            return command("compare",id.toString()).thenCompose(next->{check.accept(RecoveryInspection.compare(value,report(next)).changed().isEmpty(),"inspection_baseline_compare_data_unchanged");return main(inspections.read(contexts.apply(201)));});
        }).thenAccept(after->check.accept(RecoveryInspection.compare(build(contexts.apply(201),before),build(contexts.apply(201),after)).changed().isEmpty(),"inspection_reads_preserve_all_original_rows_and_revisions")))
            .thenCompose(v->inspect(202)).thenAccept(id->check.accept(report(id).entries().stream().anyMatch(e->e.domain().equals("combat_return")&&e.state().contains("phase=RETURN_PENDING")&&e.state().contains("restart_policy=RETURN")),"inspection_pending_return_report_does_not_claim_or_move"))
            .thenCompose(v->inspect(203)).thenAccept(id->check.accept(report(id).entries().stream().anyMatch(e->e.domain().equals("combat_return")&&e.state().contains("phase=RETURNING")&&e.state().contains("claim=")),"inspection_unknown_movement_original_claim_visible"))
            .thenCompose(v->inspect(204)).thenAccept(id->check.accept(report(id).issues().contains("RECOVERY_WORLD_MISMATCH"),"inspection_wrong_return_world_is_reported_without_reassignment"))
            .thenCompose(v->inspect(205)).thenAccept(id->check.accept(report(id).issues().contains("PAYLOAD_VALIDATION_FAILED"),"inspection_corrupt_ender_envelope_preserved"))
            .thenCompose(v->rejected(console.command(false,new String[]{"list"},line->{throw new IllegalStateException("unauthorized output");})))
            .thenCompose(v->rejected(console.command(true,new String[]{"inspect",world.toString(),contexts.apply(201).playerId().toString()},line->{throw new IllegalStateException("wrong world accepted");})))
            .thenRun(()->check.accept(true,"inspection_console_only_and_unknown_verified_scope_denied"))
            .thenCompose(v->command("list","1")).thenCompose(v->command("list","2"))
            .thenCompose(v->limits()).thenCompose(v->atomicRace()).thenCompose(v->failure())
            .thenCompose(v->rejected(inspections.read(new ServerProgressAccess().forVerifiedServerLink(contexts.apply(201).storyRunId(),contexts.apply(201).playerId()))))
            .thenCompose(v->rejected(inspections.read(contexts.apply(999))))
            .thenRun(()->check.accept(true,"inspection_foreign_issuer_and_missing_player_fail_without_empty_success"));
    }
    private RecoveryInspection.Report build(ServerProgressAccess.Context c,InspectionStore.Snapshot value){var live=runtime.apply(c);return RecoveryInspection.build(UUID.randomUUID(),c,value,live,live,scenes);}
    private CompletionStage<Void> seed() {
        var exit=new CombatContent.Exit(new FieldContent.Point(2,64,2),0,0,.5,List.of());CompletionStage<Void> work=CompletableFuture.completedFuture(null);
        for(int n=201;n<=204;n++){final int id=n;work=work.thenCompose(v->{var c=contexts.apply(id);UUID attempt=new UUID(600,id);return main(ledger.start(c,attempt,null,id==204?UUID.randomUUID():world,exit,"SURVIVAL")).thenCompose(s->id==201?CompletableFuture.completedFuture(s):main(ledger.end(c,attempt))).thenCompose(s->id==203?main(ledger.claim(c,attempt,new UUID(601,id))):CompletableFuture.completedFuture(s)).thenApply(s->null);});}
        var c=contexts.apply(201);var scene=scenes.get("public_arrival");
        return work.thenCompose(v->main(features.write(c,"device","phone",-1,new byte[]{1}))).thenCompose(v->main(features.write(c,"cutscene",scene.id(),-1,SceneProgress.encode(scene,new Cutscene.State(1,false,false,UUID.randomUUID(),0)))))
            .thenCompose(v->main(features.write(contexts.apply(205),"ender","inventory",-1,new byte[]{7}))).thenApply(v->null);
    }
    private CompletionStage<Void> limits() {
        CompletionStage<Void> work=CompletableFuture.completedFuture(null);var large=new byte[950000];Arrays.fill(large,(byte)7);
        for(int i=0;i<5;i++){final String key="large_"+i;work=work.thenCompose(v->main(features.write(contexts.apply(207),"device",key,-1,large)).thenApply(x->null));}
        for(int i=0;i<65;i++){final String key="small_"+i;work=work.thenCompose(v->main(features.write(contexts.apply(208),"device",key,-1,new byte[]{1})).thenApply(x->null));}
        return work.thenCompose(v->main(inspections.read(contexts.apply(207)))).thenAccept(snapshot->{check.accept(snapshot.truncated()&&snapshot.rows().stream().map(InspectionStore.Row::payload).filter(Objects::nonNull).mapToInt(x->x.length).sum()<=InspectionStore.BYTE_LIMIT,"inspection_SQL_limits_captured_payload_bytes");check.accept(!RecoveryInspection.compare(build(contexts.apply(207),snapshot),build(contexts.apply(207),snapshot)).unconfirmed().isEmpty(),"inspection_budget_limited_reports_never_claim_full_equality");})
            .thenCompose(v->main(inspections.read(contexts.apply(208)))).thenAccept(snapshot->check.accept(snapshot.truncated()&&snapshot.rows().stream().filter(r->r.domain().equals("device")).count()==64,"inspection_SQL_limits_feature_rows_and_marks_incomplete"));
    }
    private CompletionStage<Void> atomicRace() {
        var c=contexts.apply(206);var empty=PersonalStorage.empty();
        return main(features.inventory(c,0,empty,-1,empty)).thenCompose(first->{
            CompletionStage<FeatureStore.InventoryCommit> writer=CompletableFuture.completedFuture(first);
            for(int i=0;i<8;i++)writer=writer.thenCompose(saved->features.inventory(c,saved.inventory().revision(),empty,saved.feature().revision(),empty));
            var work=new ArrayList<CompletableFuture<?>>();work.add(writer.toCompletableFuture());
            for(int i=0;i<12;i++)work.add(main(inspections.read(c)).thenAccept(snapshot->check.accept(row(snapshot,"inventory").revision()==row(snapshot,"ender").revision()+1,"inspection_single_SELECT_sees_one_atomic_41_27_revision_pair")).toCompletableFuture());
            return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));
        }).thenCompose(v->main(inspections.read(c))).thenCompose(before->rejected(features.inventory(c,row(before,"inventory").revision(),empty,-1,empty)).thenCompose(v->main(inspections.read(c))).thenAccept(after->check.accept(RecoveryInspection.compare(build(c,before),build(c,after)).changed().isEmpty(),"inspection_originals_unchanged_after_real_PG_CAS_rollback")));
    }
    private CompletionStage<Void> failure() {
        var c=contexts.apply(201);
        return inspect(201).thenCompose(id->{var original=report(id);return main(db.module("inspection_failure_fixture",connection->{try(var s=connection.createStatement()){s.execute("ALTER TABLE hearth_progress.personal_feature RENAME TO personal_feature_inspection_hidden");}return (Void)null;}))
            .thenCompose(v->console.command(true,new String[]{"compare",id.toString()},line->{throw new IllegalStateException("read failure published success");}).handle((ignored,error)->error))
            .thenCompose(error->main(db.module("inspection_failure_restore",connection->{try(var s=connection.createStatement()){s.execute("ALTER TABLE hearth_progress.personal_feature_inspection_hidden RENAME TO personal_feature");}return error;})))
            .thenCompose(error->{check.accept(error!=null&&RecoveryConsole.errorCode(error).equals("42P01")&&console.report(id).orElseThrow()==original,"inspection_real_SQL_failure_keeps_previous_receipt_and_no_empty_success");return command("compare",id.toString());})
            .thenAccept(after->check.accept(RecoveryInspection.compare(original,report(after)).changed().isEmpty(),"inspection_SQL_recovery_preserves_original_payloads_and_revisions"));});
    }
}
