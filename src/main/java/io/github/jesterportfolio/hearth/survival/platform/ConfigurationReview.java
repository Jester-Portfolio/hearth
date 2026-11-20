package io.github.jesterportfolio.hearth.survival.platform;

import java.util.*;
import io.github.jesterportfolio.hearth.economy.PlatformStore;

/** 로컬 저널과 PG를 합쳐 판정한다. 읽기 전용이며 HOLD나 파일을 변경하지 않는다. */
public record ConfigurationReview(List<Entry> entries,List<String> issues) {
    public record Entry(UUID id,String name,String local,String database,boolean restart,String problem) {
        public boolean blocked(){return !problem.isEmpty();}
    }
    public ConfigurationReview {entries=List.copyOf(entries);issues=List.copyOf(issues);}
    public boolean clear(){return issues.isEmpty()&&entries.stream().noneMatch(Entry::blocked);}
    public boolean allows(UUID id){return issues.isEmpty()&&entries.stream().noneMatch(e->!e.id.equals(id)&&e.blocked());}
    public static ConfigurationReview read(ConfigurationChanges changes,List<PlatformStore.Config> rows)throws Exception {
        var scan=changes.scan();var db=new HashMap<UUID,PlatformStore.Config>();for(var row:rows)if(db.putIfAbsent(row.request(),row)!=null)throw new IllegalStateException("CONFIG_DUPLICATE");
        var out=new ArrayList<Entry>();for(var plan:scan.plans()){
            var row=db.remove(plan.id());String state=changes.state(plan),problem="";
            if(row!=null&&(!plan.name().equals(row.name())||!plan.before().equals(row.before())||!plan.after().equals(row.after())))problem="IDENTITY_MISMATCH";
            else if(ConfigurationChanges.pendingState(state))problem="INCOMPLETE";
            else if(row==null&&!state.equals("STAGED"))problem="DATABASE_MISSING";
            else if(row!=null&&(!state.equals(row.state())||state.equals("STAGED")))problem="STATE_MISMATCH";
            out.add(new Entry(plan.id(),plan.name(),state,row==null?"NONE":row.state(),plan.restart(),problem));
        }
        for(var row:db.values())out.add(new Entry(row.request(),row.name(),"MISSING",row.state(),row.name().startsWith("rpg-"),"EVIDENCE_MISSING"));
        out.sort(Comparator.comparing(Entry::blocked).reversed().thenComparing(e->e.id.toString()));return new ConfigurationReview(out,scan.issues());
    }
}
