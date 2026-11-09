package io.github.jesterportfolio.hearth.administration;

import io.github.jesterportfolio.hearth.features.*;
import io.github.jesterportfolio.hearth.inventory.InventoryPayload;
import io.github.jesterportfolio.hearth.persistence.ServerProgressAccess;
import java.time.Instant;
import java.security.*;
import java.util.*;

/** 보고서에는 상태/개수/해시만 남깁니다. 원시 NBT·대화·아이템 지급이나 복구 기능은 없습니다. */
public final class RecoveryInspection {
    public record Runtime(UUID world,Map<String,String> facts,Set<String> holds,Set<String> pending) {
        public Runtime {facts=Map.copyOf(facts);holds=Set.copyOf(holds);pending=Set.copyOf(pending);}
        public static Runtime absent(){return new Runtime(null,Map.of("runtime","UNAVAILABLE"),Set.of(),Set.of());}
    }
    public record Entry(String domain,String key,long revision,int bytes,String sha256,String state,UUID recoveryWorld,Set<String> issues) {
        public Entry {issues=Set.copyOf(issues);}
        public String id(){return domain+"/"+key;}
    }
    public record Report(UUID request,UUID run,UUID player,Instant observed,List<Entry> entries,Runtime before,Runtime after,Set<String> issues,boolean complete) {
        public Report {entries=List.copyOf(entries);issues=Set.copyOf(issues);}
        public String summary(){return "run="+run+" player="+player+" report="+request+" issues="+String.join(",",new TreeSet<>(issues))+" complete="+complete;}
    }
    public record Difference(List<String> changed,List<String> unconfirmed) {
        public Difference {changed=List.copyOf(changed);unconfirmed=List.copyOf(unconfirmed);}
    }
    public static Report build(UUID request,ServerProgressAccess.Context context,InspectionStore.Snapshot snapshot,Runtime before,Runtime after,Map<String,SceneContent.Scene> scenes) {
        var entries=new ArrayList<Entry>();var issues=new TreeSet<String>();
        issues.addAll(before.holds());issues.addAll(after.holds());
        if(before.world()==null||after.world()==null)issues.add("RUNTIME_WORLD_UNREGISTERED");
        if(!before.pending().isEmpty()||!after.pending().isEmpty())issues.add("RUNTIME_UNSAVED_OR_PENDING");
        if(!before.equals(after))issues.add("RUNTIME_CHANGED_DURING_READ");
        if(snapshot.truncated())issues.add("SNAPSHOT_LIMIT_REACHED");
        for(var row:snapshot.rows()) {
            var flags=new TreeSet<String>();byte[] bytes=row.payload();String state="METADATA";UUID recoveryWorld=null;
            if(row.domain().equals("inventory")||!Set.of("run","player").contains(row.domain())) {
                if(bytes==null){state="NOT_CAPTURED";flags.add("PAYLOAD_NOT_CAPTURED");}
                else try {
                    state=switch(row.domain()) {
                        case "inventory" -> inventory(bytes,41);
                        case "ender" -> row.key().equals("inventory")?inventory(bytes,27):unknown(flags);
                        case "device" -> {
                            if(!row.key().equals("phone"))yield unknown(flags);
                            if(!Arrays.equals(bytes,new byte[]{1}))throw new IllegalArgumentException();
                            yield "ISSUANCE_RECORDED; ITEM_PRESENCE_NOT_INSPECTED";
                        }
                        case "cutscene" -> {
                            var scene=scenes.get(row.key());
                            if(scene==null){flags.add("SCENE_DEFINITION_MISSING");yield "DEFINITION_MISSING";}
                            var value=SceneProgress.decode(scene,bytes);
                            yield "attempts="+value.attempts()+" completed="+value.completed()+" skip="+value.skipFuture()+" frame="+value.frame();
                        }
                        case "combat_return" -> {
                            if(!row.key().equals("current"))yield unknown(flags);
                            var value=RecoveryLedger.decode(bytes);var policy=RecoveryLedger.restart(value);
                            recoveryWorld=value.world();
                            if(policy==RecoveryLedger.Action.HOLD)flags.add("RESTART_REQUIRES_HOLD");
                            if(before.world()!=null&&!before.world().equals(value.world())||after.world()!=null&&!after.world().equals(value.world()))flags.add("RECOVERY_WORLD_MISMATCH");
                            yield "phase="+value.phase()+" attempt="+value.attempt()+" claim="+value.claim()+" world="+value.world()+" restart_policy="+policy;
                        }
                        default -> unknown(flags);
                    };
                }catch(RuntimeException invalid){state="INVALID_OR_CHANGED_DEFINITION";flags.add("PAYLOAD_VALIDATION_FAILED");}
            }
            entries.add(new Entry(row.domain(),row.key(),row.revision(),row.bytes(),bytes==null?null:hash(bytes),state,recoveryWorld,flags));issues.addAll(flags);
        }
        if(entries.stream().noneMatch(e->e.domain().equals("inventory")))issues.add("INVENTORY_ROW_MISSING");
        if(entries.stream().noneMatch(e->e.domain().equals("run")))issues.add("RUN_ROW_MISSING");
        if(entries.stream().noneMatch(e->e.domain().equals("player")))issues.add("PLAYER_ROW_MISSING");
        return new Report(request,context.storyRunId(),context.playerId(),snapshot.observed(),entries,before,after,issues,!snapshot.truncated());
    }
    public static Report observedAfter(Report report,Runtime after) {
        var issues=new TreeSet<>(report.issues());issues.addAll(after.holds());
        if(after.world()==null)issues.add("RUNTIME_WORLD_UNREGISTERED");
        if(!report.before().equals(after))issues.add("RUNTIME_CHANGED_DURING_READ");
        if(!after.pending().isEmpty())issues.add("RUNTIME_UNSAVED_OR_PENDING");
        for(var row:report.entries())if(row.recoveryWorld()!=null&&after.world()!=null&&!row.recoveryWorld().equals(after.world()))issues.add("RECOVERY_WORLD_MISMATCH");
        return new Report(report.request(),report.run(),report.player(),report.observed(),report.entries(),report.before(),after,issues,report.complete());
    }
    private static String unknown(Set<String> flags){flags.add("UNRECOGNIZED_FEATURE");return "UNRECOGNIZED";}
    private static String inventory(byte[] bytes,int capacity) {
        var value=InventoryPayload.parse(bytes);if(capacity==27)PersonalStorage.validate(value);
        int used=0;for(var item:value.slots())if(item!=null)used++;
        return "ENVELOPE_VALID slots="+capacity+" occupied="+used+"; NBT_NOT_DECODED";
    }
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public static Difference compare(Report old,Report current) {
        if(!old.run().equals(current.run())||!old.player().equals(current.player()))throw new SecurityException("INSPECTION_SCOPE_MISMATCH");
        var before=new TreeMap<String,Entry>();var after=new TreeMap<String,Entry>();
        old.entries().forEach(e->before.put(e.id(),e));current.entries().forEach(e->after.put(e.id(),e));
        var ids=new TreeSet<>(before.keySet());ids.addAll(after.keySet());var changed=new ArrayList<String>();var unknown=new ArrayList<String>();
        for(String id:ids) {
            var a=before.get(id);var b=after.get(id);
            if(a==null||b==null){if(old.complete()&&current.complete())changed.add(id);else unknown.add(id);}
            else if(a.revision()!=b.revision()||a.bytes()!=b.bytes())changed.add(id);
            else if(a.bytes()>0&&(a.sha256()==null||b.sha256()==null))unknown.add(id);
            else if(!Objects.equals(a.sha256(),b.sha256()))changed.add(id);
        }
        if(!old.complete()||!current.complete())unknown.add("UNOBSERVED_ROWS");
        return new Difference(changed,unknown);
    }
    public static List<String> lines(Report report) {
        var result=new ArrayList<String>();result.add("HEARTH_RECOVERY_REPORT "+report.summary());result.add("DB snapshot="+report.observed()+" runtime_changed="+!report.before().equals(report.after()));
        report.after().facts().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->result.add("runtime "+e.getKey()+"="+e.getValue()));
        for(var row:report.entries())result.add(row.id()+" revision="+row.revision()+" bytes="+row.bytes()+" sha256="+row.sha256()+" "+row.state()+" issues="+String.join(",",new TreeSet<>(row.issues())));
        result.add("개인 엔더/발급 행의 부재는 아이템 부재 판정이 아닙니다. 원본과 HOLD를 보존하고 별도 확인하세요.");
        return List.copyOf(result);
    }
}
