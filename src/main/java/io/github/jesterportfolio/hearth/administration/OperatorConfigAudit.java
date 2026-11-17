package io.github.jesterportfolio.hearth.administration;

import io.github.jesterportfolio.hearth.story.*;
import io.github.jesterportfolio.hearth.economy.*;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.security.MessageDigest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** 오프라인 읽기 전용 검토. SQL/HTTP/월드 로드/환경 변수 조회·설정 변경을 실행하지 않습니다. */
public final class OperatorConfigAudit {
    private final Path root;private final SortedMap<String,String> hashes=new TreeMap<>();
    private OperatorConfigAudit(Path root)throws Exception{this.root=root.toAbsolutePath().normalize();safe(this.root);if(!Files.isDirectory(this.root))throw invalid();}
    private static void safe(Path path)throws Exception{for(var part:path)if(part.toString().equals("hearth-story-private"))throw invalid();if(!path.equals(path.toRealPath()))throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("OPERATOR_CONFIG_REFUSED");}
    private Path file(String relative,long max)throws Exception{if(!relative.matches("[A-Za-z0-9_./-]+")||relative.startsWith("/")||Arrays.asList(relative.split("/")).contains(".."))throw invalid();var path=root.resolve(relative).normalize();if(!path.startsWith(root))throw invalid();safe(path);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>max)throw invalid();return path;}
    private Path immutable(String relative,long max)throws Exception{var path=file(relative,max);var digest=MessageDigest.getInstance("SHA-256");try(var input=new java.security.DigestInputStream(Files.newInputStream(path),digest)){input.transferTo(java.io.OutputStream.nullOutputStream());}hashes.put(relative,HexFormat.of().formatHex(digest.digest()));return path;}
    private static Map<String,Object> map(ConfigurationSection section){var result=new LinkedHashMap<String,Object>();section.getValues(false).forEach((k,v)->result.put(k,v instanceof ConfigurationSection c?map(c):v));return result;}
    private YamlConfiguration yaml(Path file)throws Exception{var config=new YamlConfiguration();config.load(file.toFile());return config;}
    private Properties properties(String relative)throws Exception{var p=new Properties();try(var input=Files.newBufferedReader(file(relative,1024*1024))){p.load(input);}return p;}
    private void hashMatches(String name,String expected)throws Exception{immutable("plugins/Hearth/"+name,1024*1024);if(!expected.equals(hashes.get("plugins/Hearth/"+name)))throw invalid();}
    private void world(String key,String identity,String extra,double x,double z)throws Exception{String relative="world/dimensions/"+key.replace(':','/');var marker=file(relative+"/"+(extra==null?".hearth-economy-world":".hearth-story-world"),4096);String expected=identity+(extra==null?"":"\n"+extra);if(!Files.readString(marker).trim().equals(expected))throw invalid();immutable(root.relativize(marker).toString(),4096);file(relative+"/data/paper/metadata.dat",1024*1024);file(relative+"/region/r."+(((int)Math.floor(x))>>9)+"."+(((int)Math.floor(z))>>9)+".mca",1024L*1024*1024);}
    public record Result(String mode,int immutableFiles,int worlds,String passwordEnvironment,String manifestSha256){}
    public static Result audit(Path root,Path output,String serverJar,List<String> reviewedIncludes)throws Exception{return new OperatorConfigAudit(root).run(output,serverJar,reviewedIncludes);}
    private Result run(Path output,String serverJar,List<String> includes)throws Exception{
        for(String marker:List.of("story-runtime.active","economy-runtime.active"))if(Files.exists(root.resolve("plugins/Hearth/"+marker),LinkOption.NOFOLLOW_LINKS))throw invalid();
        var server=properties("server.properties");if(!"true".equals(server.getProperty("online-mode"))||!"true".equals(server.getProperty("white-list"))||!"true".equals(server.getProperty("enforce-whitelist"))||!"127.0.0.1".equals(server.getProperty("server-ip"))||!"world".equals(server.getProperty("level-name","world"))||!"false".equals(server.getProperty("enable-rcon","false"))||!"false".equals(server.getProperty("enable-query","false")))throw invalid();
        int port=Integer.parseInt(server.getProperty("server-port"));if(port<1024||port>65535||!"true".equals(properties("eula.txt").getProperty("eula")))throw invalid();
        try(var input=Files.newBufferedReader(file("whitelist.json",1024*1024))){if(!JsonParser.parseReader(input).isJsonArray())throw invalid();}
        var config=yaml(immutable("plugins/Hearth/config.yml",1024*1024));String mode=EconomyRuntimeSettings.mode(config.getString("runtime-mode"),config.getBoolean("verification-enabled"));if(!Set.of("story","economy").contains(mode)||RuntimeObservation.interval(config.get("runtime-observation-seconds",0))<1)throw invalid();
        String passwordEnv;int worlds;
        if(mode.equals("story")){
            if(!config.getBoolean("phone-dialog-enabled")||!"ollama".equals(config.getString("phone-ai-mode")))throw invalid();
            var endpoint=java.net.URI.create(config.getString("phone-ai-endpoint",""));if(!"http".equals(endpoint.getScheme())||!Set.of("127.0.0.1","localhost","[::1]").contains(endpoint.getHost())||endpoint.getUserInfo()!=null||endpoint.getQuery()!=null||endpoint.getFragment()!=null||!"/api/generate".equals(endpoint.getPath()))throw invalid();
            var settings=StoryRuntimeSettings.parse(map(Objects.requireNonNull(config.getConfigurationSection("story-runtime"))));if(!"127.0.0.1".equals(settings.dbHost()))throw invalid();hashMatches(settings.contentFile(),settings.contentHash());StoryContentBundle.parse(map(yaml(file("plugins/Hearth/"+settings.contentFile(),1024*1024))));
            for(var run:settings.runs().values()){var b=run.binding();world(b.worldKey(),b.run()+"/"+b.worldKey()+"/"+b.worldId(),b.templateVersion()+"/"+b.templateHash(),run.x(),run.z());}passwordEnv=settings.passwordEnvironment();worlds=settings.runs().size();
        }else{
            var settings=EconomyRuntimeSettings.parse(map(Objects.requireNonNull(config.getConfigurationSection("economy-runtime"))));if(!"127.0.0.1".equals(settings.dbHost()))throw invalid();var contentPath=immutable("plugins/Hearth/"+settings.contentFile(),1024*1024);var p=new Properties();try(var input=Files.newBufferedReader(contentPath)){p.load(input);}var content=EconomyContent.parse(p);if(!content.offline().equals("PAUSE")||content.fixtureBalance()!=0)throw invalid();var gather=EconomyGatheringContent.file(p);if(gather.isPresent()){var f=gather.orElseThrow();hashMatches(f.name(),f.sha256());EconomyGatheringContent.parse(map(yaml(file("plugins/Hearth/"+f.name(),1024*1024))));}world(settings.worldKey(),settings.realm()+"/"+settings.worldKey()+"/"+settings.worldId(),null,settings.x(),settings.z());passwordEnv=settings.passwordEnvironment();worlds=1;
        }
        immutable(serverJar,128L*1024*1024);var plugins=root.resolve("plugins");safe(plugins);var names=new HashSet<String>();try(var entries=Files.list(plugins)){var jars=entries.filter(p->p.getFileName().toString().endsWith(".jar")).sorted().limit(65).toList();if(jars.size()>64)throw invalid();for(var jar:jars){immutable(root.relativize(jar).toString(),128L*1024*1024);try(var zip=new java.util.zip.ZipFile(jar.toFile())){var entry=zip.getEntry("plugin.yml");if(entry==null||entry.getSize()>64*1024||entry.getSize()<0)throw invalid();var descriptor=new YamlConfiguration();try(var reader=new java.io.InputStreamReader(zip.getInputStream(entry),java.nio.charset.StandardCharsets.UTF_8)){descriptor.load(reader);}if(!names.add(descriptor.getString("name","")))throw invalid();}}}if(!hashes.containsKey("plugins/Hearth.jar")||!names.containsAll(Set.of("Hearth","WorldGuard","WorldEdit")))throw invalid();if(includes.size()>128)throw invalid();for(String include:includes)immutable(include,16L*1024*1024);
        output=output.toAbsolutePath().normalize();for(var part:output)if(part.toString().equals("hearth-story-private"))throw invalid();safe(Objects.requireNonNull(output.getParent()));if(Files.exists(output,LinkOption.NOFOLLOW_LINKS)||output.startsWith(root))throw invalid();
        String checksums=hashes.entrySet().stream().map(e->e.getValue()+"  "+e.getKey()+"\n").collect(java.util.stream.Collectors.joining());String checksumsHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(checksums.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String manifest="format=1\nmode="+mode+"\nserver_jar="+serverJar+"\npassword_environment="+passwordEnv+"\nchecksums_sha256="+checksumsHash+"\n";
        Files.createDirectory(output,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));Files.writeString(output.resolve("deployment.sha256"),checksums,StandardOpenOption.CREATE_NEW);Files.writeString(output.resolve("deployment.properties"),manifest,StandardOpenOption.CREATE_NEW);
        return new Result(mode,hashes.size(),worlds,passwordEnv,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
    public static void main(String[] args){try{if(args.length<3)throw invalid();var result=audit(Path.of(args[0]),Path.of(args[1]),args[2],Arrays.asList(args).subList(3,args.length));System.out.println("OPERATOR_CONFIG_AUDIT PASS mode="+result.mode()+" immutable_files="+result.immutableFiles()+" worlds="+result.worlds()+" manifest_sha256="+result.manifestSha256()+" live_dependencies=UNVERIFIED");}catch(Exception error){System.err.println("OPERATOR_CONFIG_AUDIT REFUSED");System.exit(2);}}
}
