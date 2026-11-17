package io.github.jesterportfolio.hearth.story;

import java.util.*;

/** 새 진행 준비의 고정 입력과 I/O 상한. 운영 배포 값/계정 역할을 추정하지 않습니다. */
public record StoryPreparationSettings(Map<String,Job> jobs,String contentFile,String contentHash,int maxFiles,long maxBytes,long reserveBytes,
        String dbHost,int dbPort,String dbName,String dbUser,String passwordEnvironment){
    public record Job(String id,UUID run,String key,String template,String version,String spatialHash,String regionHash,double x,double y,double z,float yaw,float pitch,Set<String> regions){
        public Job{regions=Set.copyOf(regions);Objects.requireNonNull(run);if(id==null||!id.matches("[a-z][a-z0-9_]{0,63}")||key==null||!key.matches("hearth_story:[a-z0-9_]{1,64}")||template==null||!template.matches("[a-z][a-z0-9_-]{0,63}")||version==null||!version.matches("[A-Za-z0-9_.-]{1,64}")||spatialHash==null||!spatialHash.matches("[a-f0-9]{64}")||regionHash==null||!regionHash.matches("[a-f0-9]{64}")||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.abs(x)>29_999_000||Math.abs(z)>29_999_000||!Float.isFinite(yaw)||!Float.isFinite(pitch)||Math.abs(pitch)>90||regions.isEmpty()||regions.size()>64||regions.stream().anyMatch(s->!s.matches("[A-Za-z0-9_-]{1,64}")))throw invalid();}
        public StoryRuntimeSettings.Run run(UUID physical,String contentHash){return new StoryRuntimeSettings.Run(new StoryBinding(run,key,physical,version,spatialHash,contentHash),x,y,z,yaw,pitch,regions);}
    }
    public StoryPreparationSettings{
        jobs=Map.copyOf(jobs);
        if(jobs.isEmpty()||jobs.size()>64||jobs.entrySet().stream().anyMatch(e->!e.getKey().equals(e.getValue().id()))||new HashSet<>(jobs.values().stream().map(Job::run).toList()).size()!=jobs.size()||new HashSet<>(jobs.values().stream().map(Job::key).toList()).size()!=jobs.size()
                ||maxFiles<1||maxFiles>100000||maxBytes<1||maxBytes>12L*1024*1024*1024||reserveBytes<1||reserveBytes>64L*1024*1024*1024
                ||contentFile==null||!contentFile.matches("[a-zA-Z0-9_-]+\\.yml")||contentHash==null||!contentHash.matches("[a-f0-9]{64}")||dbHost==null||!dbHost.matches("[A-Za-z0-9.-]{1,253}")||dbPort<1||dbPort>65535||dbName==null||!dbName.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")||dbUser==null||!dbUser.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")||passwordEnvironment==null||!passwordEnvironment.matches("[A-Z][A-Z0-9_]{0,63}"))throw invalid();
    }
    public static StoryPreparationSettings parse(Map<String,?> root){
        if(!(root.get("jobs") instanceof List<?> rows))throw invalid();var jobs=new LinkedHashMap<String,Job>();
        for(Object value:rows){var row=map(value);if(!(row.get("required-regions") instanceof List<?> regions)||regions.stream().anyMatch(x->!(x instanceof String)))throw invalid();String id=text(row,"id");var job=new Job(id,UUID.fromString(text(row,"run")),text(row,"world-key"),text(row,"template"),text(row,"template-version"),text(row,"template-sha256"),text(row,"regions-sha256"),number(row,"entry-x"),number(row,"entry-y"),number(row,"entry-z"),(float)number(row,"entry-yaw"),(float)number(row,"entry-pitch"),new HashSet<>(regions.stream().map(String.class::cast).toList()));if(jobs.putIfAbsent(id,job)!=null)throw invalid();}
        return new StoryPreparationSettings(jobs,text(root,"content-file"),text(root,"content-sha256"),Math.toIntExact(whole(root,"max-template-files")),whole(root,"max-template-bytes"),whole(root,"reserve-bytes"),text(root,"db-host"),Math.toIntExact(whole(root,"db-port")),text(root,"db-name"),text(root,"db-user"),text(root,"password-environment"));
    }
    @SuppressWarnings("unchecked") private static Map<String,?> map(Object value){if(!(value instanceof Map<?,?> m)||m.keySet().stream().anyMatch(k->!(k instanceof String)))throw invalid();return (Map<String,?>)m;}
    private static String text(Map<String,?> p,String key){if(!(p.get(key) instanceof String value)||value.isBlank())throw invalid();return value;}
    private static double number(Map<String,?> p,String key){if(!(p.get(key) instanceof Number n)||!Double.isFinite(n.doubleValue()))throw invalid();return n.doubleValue();}
    private static long whole(Map<String,?> p,String key){double n=number(p,key);if(n!=Math.rint(n)||n<Long.MIN_VALUE||n>Long.MAX_VALUE)throw invalid();return (long)n;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("STORY_PREPARATION_CONFIG_INVALID");}
}
