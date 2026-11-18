package io.github.jesterportfolio.hearth.administration;

import java.util.*;
import com.google.gson.Gson;

/** 운영 관측은 집계/기동 식별만 기록합니다. 개인 ID·원문·주소·자격 증명을 받지 않습니다. */
public record RuntimeReport(UUID boot,long pid,long observedMillis,String mode,String phase,int worlds,int heldWorlds,boolean aiPreparationRecorded,double tps,double mspt,long heapUsedBytes) {
    private static final Set<String> MODES=Set.of("story","economy","story-prepare","lobby");
    private static final Set<String> PHASES=Set.of("STARTING","READY","DRAINING","DRAINED","HOLD","CLOSED","REFUSED");
    public RuntimeReport{Objects.requireNonNull(boot);if(pid<1||observedMillis<0||!MODES.contains(mode)||!PHASES.contains(phase)||worlds<0||heldWorlds<0||heldWorlds>worlds||!Double.isFinite(tps)||tps<0||!Double.isFinite(mspt)||mspt<0||heapUsedBytes<0)throw new IllegalArgumentException("RUNTIME_REPORT_INVALID");}
    public boolean controllerReady(){return phase.equals("READY")&&heldWorlds==0;}
    public String json(){var fields=new LinkedHashMap<String,Object>();fields.put("format",1);fields.put("boot",boot.toString());fields.put("pid",pid);fields.put("observed_ms",observedMillis);fields.put("mode",mode);fields.put("phase",phase);fields.put("controller_ready",controllerReady());fields.put("worlds",worlds);fields.put("held_worlds",heldWorlds);fields.put("ai_preparation_recorded",aiPreparationRecorded);fields.put("tps",tps);fields.put("mspt",mspt);fields.put("heap_used_bytes",heapUsedBytes);return new Gson().toJson(fields);}
}
