package io.github.jesterportfolio.hearth.story;

import java.util.*;

/** 운영자가 준비한 차원과 계정 UUID의 내부 연결. 가입/초대/파티 역할을 추가하지 않습니다. */
public record StoryRuntimeSettings(Map<UUID,Run> runs,Map<UUID,UUID> members,String contentFile,String contentHash,long idleSeconds,int capacity,
        String dbHost,int dbPort,String dbName,String dbUser,String passwordEnvironment){
    public record Run(StoryBinding binding,double x,double y,double z,float yaw,float pitch,Set<String> regions){
        public Run{Objects.requireNonNull(binding);regions=Set.copyOf(regions);if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.abs(x)>29_999_000||Math.abs(z)>29_999_000||!Float.isFinite(yaw)||!Float.isFinite(pitch)||Math.abs(pitch)>90||regions.isEmpty()||regions.size()>64||regions.stream().anyMatch(s->!s.matches("[A-Za-z0-9_-]{1,64}")))throw invalid();}
    }
    public StoryRuntimeSettings{
        runs=Map.copyOf(runs);members=Map.copyOf(members);
        if(runs.isEmpty()||runs.size()>64||members.isEmpty()||members.size()>4096||!runs.keySet().containsAll(members.values())||idleSeconds<1||idleSeconds>3600||capacity<1||capacity>64
                ||runs.entrySet().stream().anyMatch(e->!e.getKey().equals(e.getValue().binding().run()))||new HashSet<>(runs.values().stream().map(r->r.binding().worldKey()).toList()).size()!=runs.size()
                ||new HashSet<>(runs.values().stream().map(r->r.binding().worldId()).toList()).size()!=runs.size()
                ||contentFile==null||!contentFile.matches("[a-zA-Z0-9_-]+\\.yml")||contentHash==null||!contentHash.matches("[a-f0-9]{64}")||runs.values().stream().anyMatch(r->!r.binding().contentHash().equals(contentHash))
                ||dbHost==null||!dbHost.matches("[A-Za-z0-9.-]{1,253}")||dbPort<1||dbPort>65535||dbName==null||!dbName.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")||dbUser==null||!dbUser.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")||passwordEnvironment==null||!passwordEnvironment.matches("[A-Z][A-Z0-9_]{0,63}"))throw invalid();
    }
    public static StoryRuntimeSettings parse(Map<String,?> p){
        String hash=text(p,"content-sha256");var runs=new LinkedHashMap<UUID,Run>();
        for(var row:rows(p,"runs")){
            UUID id=UUID.fromString(text(row,"run"));var binding=new StoryBinding(id,text(row,"world-key"),UUID.fromString(text(row,"world-uuid")),text(row,"template-version"),text(row,"template-sha256"),hash);
            Object regions=row.get("required-regions");if(!(regions instanceof List<?> list)||list.stream().anyMatch(v->!(v instanceof String)))throw invalid();
            var run=new Run(binding,number(row,"entry-x"),number(row,"entry-y"),number(row,"entry-z"),(float)number(row,"entry-yaw"),(float)number(row,"entry-pitch"),new HashSet<>(list.stream().map(String.class::cast).toList()));
            if(runs.putIfAbsent(id,run)!=null)throw invalid();
        }
        var members=new LinkedHashMap<UUID,UUID>();for(var row:rows(p,"members"))if(members.putIfAbsent(UUID.fromString(text(row,"player")),UUID.fromString(text(row,"run")))!=null)throw invalid();
        return new StoryRuntimeSettings(runs,members,text(p,"content-file"),hash,whole(p,"idle-seconds"),Math.toIntExact(whole(p,"capacity")),text(p,"db-host"),Math.toIntExact(whole(p,"db-port")),text(p,"db-name"),text(p,"db-user"),text(p,"password-environment"));
    }
    @SuppressWarnings("unchecked") private static List<Map<String,?>> rows(Map<String,?> p,String key){Object value=p.get(key);if(!(value instanceof List<?> list)||list.stream().anyMatch(v->!(v instanceof Map<?,?> m)||m.keySet().stream().anyMatch(k->!(k instanceof String))))throw invalid();return (List<Map<String,?>>)(List<?>)list;}
    private static String text(Map<String,?> p,String k){if(!(p.get(k) instanceof String value)||value.isBlank())throw invalid();return value;}
    private static double number(Map<String,?> p,String k){if(!(p.get(k) instanceof Number n)||!Double.isFinite(n.doubleValue()))throw invalid();return n.doubleValue();}
    private static long whole(Map<String,?> p,String k){double n=number(p,k);if(n!=Math.rint(n)||n<Long.MIN_VALUE||n>Long.MAX_VALUE)throw invalid();return (long)n;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("STORY_RUNTIME_CONFIG_INVALID");}
}
