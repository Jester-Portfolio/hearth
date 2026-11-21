package io.github.jesterportfolio.hearth.administration;

import java.util.*;
import com.google.gson.Gson;

/** 운영 관측은 집계/기동 식별만 기록합니다. 개인 ID·원문·주소·자격 증명을 받지 않습니다. */
public record RuntimeReport(UUID boot,long pid,long observedMillis,String mode,String phase,int worlds,int heldWorlds,boolean aiPreparationRecorded,double tps,double mspt,long heapUsedBytes,Map<String,Long> platform) {
    private static final Set<String> MODES=Set.of("story","economy","story-prepare","lobby","survival");
    private static final Set<String> PHASES=Set.of("STARTING","READY","DRAINING","DRAINED","HOLD","CLOSED","REFUSED");
    public RuntimeReport(UUID boot,long pid,long observedMillis,String mode,String phase,int worlds,int heldWorlds,boolean aiPreparationRecorded,double tps,double mspt,long heapUsedBytes){this(boot,pid,observedMillis,mode,phase,worlds,heldWorlds,aiPreparationRecorded,tps,mspt,heapUsedBytes,Map.of());}
    public RuntimeReport{platform=Map.copyOf(platform);if(!platform.isEmpty()&&(!platform.keySet().equals(Set.of("enabled","ready","maintenance","config_busy","config_held","vault_held","job_held","io_pending","sales","generation","reset_pending"))||platform.values().stream().anyMatch(v->v<0)||platform.entrySet().stream().anyMatch(e->Set.of("enabled","ready","maintenance","config_busy","job_held").contains(e.getKey())&&e.getValue()>1)))throw new IllegalArgumentException("PLATFORM_REPORT_INVALID");Objects.requireNonNull(boot);if(pid<1||observedMillis<0||!MODES.contains(mode)||!PHASES.contains(phase)||worlds<0||heldWorlds<0||heldWorlds>worlds||!Double.isFinite(tps)||tps<0||!Double.isFinite(mspt)||mspt<0||heapUsedBytes<0)throw new IllegalArgumentException("RUNTIME_REPORT_INVALID");}
    public boolean controllerReady(){return phase.equals("READY")&&heldWorlds==0&&(platform.isEmpty()||platform.get("ready")==1&&platform.get("job_held")==0&&platform.get("reset_pending")==0&&platform.get("maintenance")==0&&platform.get("config_busy")==0&&platform.get("config_held")==0&&platform.get("vault_held")==0);}
    public String json(){var fields=new LinkedHashMap<String,Object>();fields.put("format",platform.isEmpty()?1:2);if(!platform.isEmpty())fields.put("platform",platform);fields.put("boot",boot.toString());fields.put("pid",pid);fields.put("observed_ms",observedMillis);fields.put("mode",mode);fields.put("phase",phase);fields.put("controller_ready",controllerReady());fields.put("worlds",worlds);fields.put("held_worlds",heldWorlds);fields.put("ai_preparation_recorded",aiPreparationRecorded);fields.put("tps",tps);fields.put("mspt",mspt);fields.put("heap_used_bytes",heapUsedBytes);return new Gson().toJson(fields);}
}
