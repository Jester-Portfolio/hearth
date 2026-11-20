package io.github.jesterportfolio.hearth.survival.platform;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.security.*;
import java.util.*;
import java.io.*;

/** 후보와 원본은 불변 증거다. 의도를 먼저 저장하고 PG/파일 확정 사이의 중단을 보류한다. */
public final class ConfigurationChanges {
    public record Plan(UUID id,String name,String before,String after,boolean restart,Path directory){}
    public record Scan(List<Plan> plans,List<String> issues) {public Scan{plans=List.copyOf(plans);issues=List.copyOf(issues);}}
    public record Detail(Plan plan,String state,String current,String position,List<String> preview){}
    @FunctionalInterface public interface Validator {void validate(String name,byte[] before,byte[] after)throws Exception;}
    private static final Set<String> STATES=Set.of("STAGED","PREPARING","APPLYING","APPLIED","COMMITTED","ROLLBACK_PREPARING","ROLLING_BACK","ROLLBACK_APPLIED","ROLLED_BACK");
    private final Path root,changes;
    public ConfigurationChanges(Path root)throws Exception{this.root=root.toRealPath();changes=this.root.resolve("platform-changes");Files.createDirectories(changes);if(!changes.equals(changes.toRealPath()))throw new IOException("CONFIG_PATH");}
    public static String hash(byte[] data){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}catch(Exception e){throw new IllegalStateException(e);}}
    private Path file(String name)throws Exception{if(name==null||!name.matches("(platform\\.properties|rpg-policy\\.properties|rpg-content\\.properties|enchants/[a-z][a-z0-9_]{0,48}\\.yml)"))throw new IOException("CONFIG_SCOPE");Path file=root.resolve(name);read(file);return file;}
    private byte[] read(Path path)throws Exception{if(!path.equals(path.toRealPath())||!path.getParent().equals(path.getParent().toRealPath())||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>131072)throw new IOException("CONFIG_PATH");return Files.readAllBytes(path);}
    public Plan stage(String name,String candidate,Validator validator)throws Exception{
        Path source=file(name);if(!candidate.matches("[a-zA-Z0-9_-]{1,64}\\.candidate"))throw new IOException("CONFIG_CANDIDATE_NAME");
        byte[] before=read(source),after=read(root.resolve(candidate));validator.validate(name,before,after);if(Arrays.equals(before,after))throw new IOException("CONFIG_UNCHANGED");
        var scan=scan();if(!scan.issues().isEmpty())throw new IOException("CONFIG_EVIDENCE_REVIEW");if(scan.plans().size()>=4096)throw new IOException("CONFIG_CAPACITY");
        UUID id=UUID.randomUUID();Path dir=Files.createDirectory(changes.resolve(id.toString()),java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        writeNew(dir.resolve("before"),before);writeNew(dir.resolve("after"),after);
        var plan=new Plan(id,name,hash(before),hash(after),name.startsWith("rpg-"),dir);var p=new Properties();p.setProperty("version","1");p.setProperty("id",id.toString());p.setProperty("name",name);p.setProperty("before",plan.before);p.setProperty("after",plan.after);p.setProperty("restart",Boolean.toString(plan.restart));p.setProperty("state","STAGED");save(dir.resolve("manifest.properties"),p,false);sync(changes);return plan;
    }
    private Properties manifest(Plan plan)throws Exception{var p=new Properties();try(var in=new ByteArrayInputStream(read(plan.directory.resolve("manifest.properties")))){p.load(in);}return p;}
    public Plan plan(UUID id)throws Exception{
        Path dir=changes.resolve(id.toString());if(!dir.equals(dir.toRealPath()))throw new IOException("CONFIG_PATH");
        var p=manifest(new Plan(id,null,null,null,false,dir));String name=p.getProperty("name");
        if(!"1".equals(p.getProperty("version"))||!id.toString().equals(p.getProperty("id"))||!STATES.contains(p.getProperty("state",""))||!Boolean.toString(name!=null&&name.startsWith("rpg-")).equals(p.getProperty("restart")))throw new IOException("CONFIG_VERSION");
        var plan=new Plan(id,name,p.getProperty("before"),p.getProperty("after"),name.startsWith("rpg-"),dir);file(plan.name);
        if(!Objects.equals(plan.before,hash(read(dir.resolve("before"))))||!Objects.equals(plan.after,hash(read(dir.resolve("after")))))throw new IOException("CONFIG_EVIDENCE_CHANGED");return plan;
    }
    public String state(Plan plan)throws Exception{return manifest(plan).getProperty("state");}
    public void mark(Plan plan,String state)throws Exception{if(!STATES.contains(state))throw new IOException("CONFIG_STATE");if(!plan.equals(plan(plan.id)))throw new IOException("CONFIG_PLAN_CHANGED");var p=manifest(plan);p.setProperty("state",state);save(plan.directory.resolve("manifest.properties"),p,true);}
    public static boolean pendingState(String state){return Set.of("PREPARING","APPLYING","APPLIED","ROLLBACK_PREPARING","ROLLING_BACK","ROLLBACK_APPLIED").contains(state);}
    public Detail inspect(UUID id)throws Exception{
        var plan=plan(id);String actual=hash(read(file(plan.name)));String position=actual.equals(plan.before)?"BEFORE":actual.equals(plan.after)?"AFTER":"EXTERNAL";
        var old=new String(read(plan.directory.resolve("before")),java.nio.charset.StandardCharsets.UTF_8).lines().toList();var next=new String(read(plan.directory.resolve("after")),java.nio.charset.StandardCharsets.UTF_8).lines().toList();
        var preview=new ArrayList<String>();for(int i=0;i<Math.max(old.size(),next.size())&&preview.size()<12;i++){String a=i<old.size()?old.get(i):null,b=i<next.size()?next.get(i):null;if(!Objects.equals(a,b)){if(a!=null)preview.add("- "+clip(a));if(b!=null)preview.add("+ "+clip(b));}}
        return new Detail(plan,state(plan),actual,position,List.copyOf(preview));
    }
    private static String clip(String s){return s.replaceAll("[\\p{Cntrl}§]"," ").substring(0,Math.min(180,s.length()));}
    /** 실행 전 의도부터 영속화한다. DB 실패도 원본을 덮어쓰지 않고 재개 대상이 된다. */
    public void begin(Plan plan,boolean rollback,Validator validator)throws Exception{check(plan,rollback,validator);mark(plan,rollback?"ROLLBACK_PREPARING":"PREPARING");}
    private void check(Plan plan,boolean rollback,Validator validator)throws Exception{if(!plan.equals(plan(plan.id)))throw new IOException("CONFIG_PLAN_CHANGED");var detail=inspect(plan.id);if(detail.position.equals("EXTERNAL"))throw new IOException("CONFIG_EXTERNAL_CHANGE");validator.validate(plan.name,read(file(plan.name)),read(plan.directory.resolve(rollback?"before":"after")));}
    public void publish(Plan plan,boolean rollback,Validator validator)throws Exception{
        check(plan,rollback,validator);Path target=file(plan.name);byte[] wanted=read(plan.directory.resolve(rollback?"before":"after"));String desired=rollback?plan.before:plan.after;
        mark(plan,rollback?"ROLLING_BACK":"APPLYING");
        if(!hash(read(target)).equals(desired)){
            Path temp=target.resolveSibling(target.getFileName()+".platform-writing");
            if(Files.exists(temp,LinkOption.NOFOLLOW_LINKS)){if(!hash(read(temp)).equals(desired))throw new IOException("CONFIG_TEMP_CONFLICT");}else writeNew(temp,wanted);
            Files.setPosixFilePermissions(temp,Files.getPosixFilePermissions(target));Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);sync(target.getParent());
        }
        mark(plan,rollback?"ROLLBACK_APPLIED":"APPLIED");
    }
    public Scan scan()throws Exception{
        var out=new ArrayList<Plan>();var issues=new ArrayList<String>();try(var paths=Files.list(changes)){var all=paths.sorted().limit(4097).toList();if(all.size()>4096)throw new IOException("CONFIG_CAPACITY");for(var path:all){String key=path.getFileName().toString();try{UUID id=UUID.fromString(key);if(!id.toString().equals(key))throw new IOException("CONFIG_DIRECTORY");out.add(plan(id));}catch(Exception e){issues.add("CONFIG_EVIDENCE "+clip(key)+" "+e.getClass().getSimpleName());}}}return new Scan(out,issues);
    }
    public List<Plan> pending()throws Exception{var scan=scan();if(!scan.issues.isEmpty())throw new IOException("CONFIG_EVIDENCE_REVIEW");var out=new ArrayList<Plan>();for(var plan:scan.plans)if(pendingState(state(plan)))out.add(plan);return List.copyOf(out);}
    private static void sync(Path dir)throws Exception{try(var channel=FileChannel.open(dir,StandardOpenOption.READ)){channel.force(true);}}
    private static void writeNew(Path path,byte[] bytes)throws Exception{Files.write(path,bytes,StandardOpenOption.CREATE_NEW);try(var channel=FileChannel.open(path,StandardOpenOption.WRITE)){channel.force(true);}}
    private static void save(Path path,Properties p,boolean replace)throws Exception{var bytes=new ByteArrayOutputStream();p.store(bytes,"Hearth configuration change v1");if(!replace){writeNew(path,bytes.toByteArray());sync(path.getParent());return;}Path temp=path.resolveSibling("manifest-"+UUID.randomUUID()+".writing");writeNew(temp,bytes.toByteArray());Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);sync(path.getParent());}
}
